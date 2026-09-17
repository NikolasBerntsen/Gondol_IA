"""Lectura de códigos de barras con zxing-cpp probando variantes de preprocesado."""

import logging
from dataclasses import dataclass

import numpy as np
import zxingcpp

from .ocr.preprocess import barcode_variants, to_gray

log = logging.getLogger(__name__)

# Nombres de formato iguales a los de ZXing (Java / @zxing/library) que usa el frontend.
FORMAT_NAMES = {
    "Aztec": "AZTEC",
    "Codabar": "CODABAR",
    "Code39": "CODE_39",
    "Code93": "CODE_93",
    "Code128": "CODE_128",
    "DataBar": "RSS_14",
    "DataBarExpanded": "RSS_EXPANDED",
    "DataBarLimited": "RSS_LIMITED",
    "DataMatrix": "DATA_MATRIX",
    "DXFilmEdge": "DX_FILM_EDGE",
    "EAN8": "EAN_8",
    "EAN13": "EAN_13",
    "ITF": "ITF",
    "MaxiCode": "MAXICODE",
    "MicroQRCode": "MICRO_QR_CODE",
    "PDF417": "PDF_417",
    "QRCode": "QR_CODE",
    "RMQRCode": "RMQR_CODE",
    "UPCA": "UPC_A",
    "UPCE": "UPC_E",
}


_F = zxingcpp.BarcodeFormat
SEARCHED_FORMATS = _F.EAN13 | _F.EAN8 | _F.UPCA | _F.UPCE | _F.Code128 | _F.Code39 | _F.Code93 | _F.QRCode | _F.DataMatrix
"""Formatos de productos y etiquetas de comercio. Se excluyen ITF, Codabar y DataBar: en fotos con ruido
generan lecturas falsas y no identifican productos de góndola."""


@dataclass(frozen=True)
class DetectedBarcode:
    value: str
    format: str


def format_name(barcode_format: object) -> str:
    name = getattr(barcode_format, "name", None) or str(barcode_format).split(".")[-1]
    return FORMAT_NAMES.get(name, name.upper())


def _read(image: np.ndarray, binarizer: "zxingcpp.Binarizer") -> list[DetectedBarcode]:
    results = zxingcpp.read_barcodes(
        np.ascontiguousarray(image), formats=SEARCHED_FORMATS, try_rotate=True, try_downscale=True, binarizer=binarizer
    )
    found = []
    for result in results:
        if getattr(result, "valid", True) and result.text:
            found.append(DetectedBarcode(result.text.strip(), format_name(result.format)))
    return found


def decode_barcodes(image_bgr: np.ndarray) -> list[DetectedBarcode]:
    """Devuelve los códigos encontrados en la primera variante que logre leer alguno (sin duplicados)."""
    gray = to_gray(image_bgr)
    for name, variant in barcode_variants(gray):
        found: list[DetectedBarcode] = []
        for binarizer in (zxingcpp.Binarizer.LocalAverage, zxingcpp.Binarizer.GlobalHistogram):
            try:
                found.extend(_read(variant, binarizer))
            except Exception as exc:  # una variante defectuosa no debe cortar la búsqueda
                log.debug("zxing-cpp falló con la variante %s: %s", name, exc)
            if found:
                break
        if found:
            unique = list(dict.fromkeys(found))
            log.debug("Códigos de barras detectados", extra={"variant": name, "count": len(unique)})
            return unique
    return []
