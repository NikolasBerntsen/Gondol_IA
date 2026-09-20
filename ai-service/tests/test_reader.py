"""Lectura completa de una etiqueta (OCR + parseo + códigos), con el motor de OCR simulado."""

from datetime import date

import numpy as np
import pytest

from app.ocr import reader
from app.ocr.engine import OcrLine, OcrOutcome, OcrPass
from app.ocr.parse import DateFound, LotFound
from app.ocr.reader import _merge, _scaled, read_label

TODAY = date(2026, 9, 20)


def ocr_pass(variant: str, lines: list[str]) -> OcrPass:
    return OcrPass(
        variant=variant,
        rotation=0,
        psm=3,
        lines=[OcrLine(text=text, confidence=0.9, height=14.0, top=i * 20, left=0) for i, text in enumerate(lines)],
        score=10.0,
        mean_confidence=0.9,
    )


@pytest.fixture
def label(monkeypatch):
    """Etiqueta mínima: evita decodificar una imagen real y devuelve las pasadas que pida cada prueba."""

    def install(passes: list[OcrPass], barcodes: list | None = None) -> None:
        monkeypatch.setattr(reader, "load_image", lambda data: np.zeros((40, 40, 3), dtype=np.uint8))
        monkeypatch.setattr(reader, "recognize", lambda image: OcrOutcome(passes[0], passes))
        monkeypatch.setattr(reader, "decode_barcodes", lambda image: barcodes or [])

    return install


def test_reads_expiry_lot_and_text_from_the_best_pass(label):
    label([ocr_pass("otsu", ["YERBA SERRANA 1 KG", "VENC 25/12/2026", "LOTE L2026A"])])

    result = read_label(b"imagen", TODAY)

    assert result.lines == ["YERBA SERRANA 1 KG", "VENC 25/12/2026", "LOTE L2026A"]
    assert result.expiry_dates[0].value == date(2026, 12, 25)
    assert [lot.value for lot in result.lot_numbers] == ["L2026A"]
    assert result.processing_ms >= 0


def test_data_read_only_in_another_variant_is_added_with_less_confidence(label):
    label(
        [
            ocr_pass("otsu", ["VENC 25/12/2026"]),
            ocr_pass("adaptativa", ["VENC 25/12/2026", "LOTE L2026A"]),
        ]
    )

    result = read_label(b"imagen", TODAY)

    valores = [lot.value for lot in result.lot_numbers]
    assert "L2026A" in valores
    # Viene de una variante secundaria: su confianza está penalizada.
    assert result.lot_numbers[0].confidence < 1.0


def test_only_the_first_alternative_passes_are_considered(label):
    extra = ocr_pass("quinta", ["LOTE ZZZZ99"])
    label([ocr_pass("otsu", ["VENC 25/12/2026"])] + [ocr_pass(f"v{i}", []) for i in range(3)] + [extra])

    result = read_label(b"imagen", TODAY)

    assert "ZZZZ99" not in [lot.value for lot in result.lot_numbers]


def test_barcodes_of_the_image_and_of_the_text_are_merged_without_repeating(label):
    class Decoded:
        def __init__(self, value: str, fmt: str) -> None:
            self.value = value
            self.format = fmt

    label([ocr_pass("otsu", ["7790123456789"])], barcodes=[Decoded("7790123456789", "EAN_13")])

    result = read_label(b"imagen", TODAY)

    assert [b.value for b in result.barcodes] == ["7790123456789"]
    assert result.barcodes[0].format == "EAN_13"


def test_an_unreadable_label_returns_empty_lists(label):
    label([ocr_pass("otsu", [])])

    result = read_label(b"imagen", TODAY)

    assert result.text == ""
    assert result.expiry_dates == []
    assert result.lot_numbers == []
    assert result.barcodes == []
    assert result.product_name_candidates == []


def test_merge_keeps_the_highest_confidence_for_each_value():
    primary = [DateFound(date(2026, 12, 25), "25/12/2026", 0.6)]
    extra = [DateFound(date(2026, 12, 25), "25-12-2026", 0.9), DateFound(date(2027, 1, 1), "01/01/2027", 0.4)]

    merged = _merge(primary, extra)

    assert [item.confidence for item in merged] == [0.9, 0.4]
    assert merged[0].raw == "25-12-2026"


def test_scaled_lowers_the_confidence_of_the_alternative_passes():
    scaled = _scaled([LotFound("L2026A", "lote l2026a", 0.8)], 0.9)

    assert scaled[0].confidence == 0.72
    assert scaled[0].value == "L2026A"
