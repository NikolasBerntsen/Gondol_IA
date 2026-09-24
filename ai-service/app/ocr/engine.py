"""Reconocimiento de texto con Tesseract (spa+eng): elige la rotación y la variante con mayor confianza."""

import functools
import logging
import math
import os
import statistics
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

import numpy as np
import pytesseract
from pytesseract import Output

from .parse import TextLine
from .preprocess import PROBE_SIDE, ROTATIONS, ensure_dark_text, ocr_variants, resize_long_side, rotate, scale_for_ocr, to_gray

log = logging.getLogger(__name__)

LANGUAGES = "spa+eng"
CALL_TIMEOUT_SECONDS = 15
GOOD_WORD_CONFIDENCE = 60.0
PARALLEL_CALLS = 4
AUTO_SEGMENTATION = 3
"""Segmentación automática de página."""
SPARSE_TEXT = 11
"""Texto disperso: típico de etiquetas con datos sueltos, pero muy sensible al ruido."""
SPARSE_VARIANTS = 2

# Cada pasada es un proceso de Tesseract y corren PARALLEL_CALLS a la vez. El Tesseract de Debian/Ubuntu usa OpenMP:
# sin límite, cada proceso abre un hilo por núcleo, compiten entre ellos y una lectura de 1 s supera CALL_TIMEOUT_SECONDS.
# La imagen Docker ya fija OMP_THREAD_LIMIT=1; esto cubre pytest y uvicorn fuera de ella (CI, desarrollo local).
os.environ.setdefault("OMP_THREAD_LIMIT", "1")


class OcrUnavailableError(RuntimeError):
    """Tesseract no está instalado o no responde."""


@functools.lru_cache(maxsize=1)
def tesseract_available() -> bool:
    try:
        pytesseract.get_tesseract_version()
        languages = set(pytesseract.get_languages(config=""))
    except Exception:  # binario ausente o roto
        return False
    return {"spa", "eng"} <= languages


@dataclass
class OcrLine:
    text: str
    confidence: float
    height: float
    top: int
    left: int


@dataclass
class OcrPass:
    variant: str
    rotation: int
    psm: int
    lines: list[OcrLine]
    score: float
    mean_confidence: float

    @property
    def text(self) -> str:
        return "\n".join(line.text for line in self.lines)

    def text_lines(self) -> list[TextLine]:
        return [TextLine(line.text, line.confidence, line.height) for line in self.lines]


def _to_float(value: object) -> float:
    try:
        return float(value)
    except (TypeError, ValueError):
        return -1.0


def run_pass(image: np.ndarray, variant: str, rotation: int, psm: int) -> OcrPass:
    """Una pasada de Tesseract.

    Puntaje = caracteres de palabras confiables (confianza ≥ 60, ponderados por confianza) menos los caracteres
    dudosos (ponderados por su desconfianza). Así una lectura limpia supera a una llena de basura por ruido.
    """
    try:
        data = pytesseract.image_to_data(
            image,
            lang=LANGUAGES,
            config=f"--oem 1 --psm {psm}",
            output_type=Output.DICT,
            timeout=CALL_TIMEOUT_SECONDS,
        )
    except RuntimeError as exc:  # pytesseract corta el proceso al superar el timeout
        log.warning(
            "Tesseract superó el tiempo máximo",
            extra={"variant": variant, "rotation": rotation, "psm": psm, "error": str(exc)},
        )
        return OcrPass(variant, rotation, psm, [], -math.inf, 0.0)
    grouped: dict[tuple[int, int, int], list[int]] = {}
    for i, word in enumerate(data["text"]):
        if not str(word).strip() or _to_float(data["conf"][i]) < 0:
            continue
        key = (int(data["block_num"][i]), int(data["par_num"][i]), int(data["line_num"][i]))
        grouped.setdefault(key, []).append(i)

    lines: list[OcrLine] = []
    score = 0.0
    confidences: list[float] = []
    for indexes in grouped.values():
        words = [str(data["text"][i]).strip() for i in indexes]
        word_conf = [max(0.0, _to_float(data["conf"][i])) for i in indexes]
        for word, conf in zip(words, word_conf):
            alnum = sum(ch.isalnum() for ch in word)
            if conf >= GOOD_WORD_CONFIDENCE and alnum >= 2:
                score += conf / 100.0 * alnum
            score -= (1.0 - conf / 100.0) * max(alnum, 1)
            confidences.append(conf)
        lines.append(
            OcrLine(
                text=" ".join(words),
                confidence=round(sum(word_conf) / len(word_conf) / 100.0, 3),
                height=float(statistics.median(int(data["height"][i]) for i in indexes)),
                top=min(int(data["top"][i]) for i in indexes),
                left=min(int(data["left"][i]) for i in indexes),
            )
        )
    lines.sort(key=lambda line: (round(line.top / max(line.height, 1.0) / 1.5), line.left))
    mean_confidence = sum(confidences) / len(confidences) / 100.0 if confidences else 0.0
    return OcrPass(variant, rotation, psm, lines, score, mean_confidence)


@dataclass
class OcrOutcome:
    best: OcrPass
    passes: list[OcrPass]
    """Todas las pasadas sobre la rotación elegida, de mayor a menor puntaje."""


def recognize(image_bgr: np.ndarray) -> OcrOutcome:
    """Elige la rotación (0/90/180/270) con una imagen reducida y después la mejor variante de preprocesado.

    Todas las variantes se leen con segmentación automática; el modo de texto disperso (más lento y sensible
    al ruido) solo se aplica a las dos variantes con mejor puntaje.
    """
    if not tesseract_available():
        raise OcrUnavailableError("Tesseract no está disponible")
    gray = scale_for_ocr(to_gray(image_bgr))
    probe = ensure_dark_text(resize_long_side(gray, PROBE_SIDE) if max(gray.shape[:2]) > PROBE_SIDE else gray)

    with ThreadPoolExecutor(max_workers=PARALLEL_CALLS) as pool:
        probes = list(pool.map(lambda degrees: run_pass(rotate(probe, degrees), "probe", degrees, SPARSE_TEXT), ROTATIONS))
        rotation = max(probes, key=lambda p: p.score).rotation
        variants = ocr_variants(rotate(gray, rotation))
        passes = list(pool.map(lambda item: run_pass(item[1], item[0], rotation, AUTO_SEGMENTATION), variants.items()))
        ranked = sorted(passes, key=lambda p: p.score, reverse=True)[:SPARSE_VARIANTS]
        passes += list(pool.map(lambda p: run_pass(variants[p.variant], p.variant, rotation, SPARSE_TEXT), ranked))

    passes.sort(key=lambda p: p.score, reverse=True)
    best = passes[0]
    log.debug(
        "OCR completado",
        extra={"rotation": rotation, "variant": best.variant, "psm": best.psm, "meanConfidence": round(best.mean_confidence, 3)},
    )
    return OcrOutcome(best, passes)
