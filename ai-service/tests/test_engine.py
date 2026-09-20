"""Pasadas de Tesseract y elección de la mejor lectura, sin necesidad del binario instalado."""

import math

import numpy as np
import pytest

from app.ocr import engine
from app.ocr.engine import OcrPass, OcrUnavailableError, recognize, run_pass, tesseract_available


def tsv(words: list[tuple[str, float, int, int, int]]) -> dict:
    """Respuesta de `image_to_data` con una palabra por tupla (texto, confianza, línea, top, alto)."""
    data: dict[str, list] = {
        "text": [], "conf": [], "block_num": [], "par_num": [], "line_num": [],
        "top": [], "left": [], "height": [],
    }
    for index, (text, conf, line, top, height) in enumerate(words):
        data["text"].append(text)
        data["conf"].append(conf)
        data["block_num"].append(1)
        data["par_num"].append(1)
        data["line_num"].append(line)
        data["top"].append(top)
        data["left"].append(index * 10)
        data["height"].append(height)
    return data


@pytest.fixture
def image() -> np.ndarray:
    return np.full((60, 120), 255, dtype=np.uint8)


def test_words_are_grouped_into_lines_with_their_confidence(monkeypatch, image):
    monkeypatch.setattr(
        engine.pytesseract,
        "image_to_data",
        lambda *a, **k: tsv([("VENC", 90, 1, 10, 12), ("25/12/2026", 80, 1, 10, 12), ("LOTE", 70, 2, 40, 12)]),
    )

    result = run_pass(image, "base", 0, 3)

    assert [line.text for line in result.lines] == ["VENC 25/12/2026", "LOTE"]
    assert result.lines[0].confidence == pytest.approx(0.85)
    assert result.mean_confidence == pytest.approx(0.80)
    assert result.score > 0


def test_words_without_text_or_confidence_are_ignored(monkeypatch, image):
    monkeypatch.setattr(
        engine.pytesseract,
        "image_to_data",
        lambda *a, **k: tsv([("", 90, 1, 10, 12), ("   ", 90, 1, 10, 12), ("LOTE", -1, 1, 10, 12)]),
    )

    result = run_pass(image, "base", 0, 3)

    assert result.lines == []
    assert result.mean_confidence == 0.0


def test_a_noisy_reading_scores_worse_than_a_clean_one(monkeypatch, image):
    clean = tsv([("YERBA", 95, 1, 10, 12), ("SERRANA", 92, 1, 10, 12)])
    noisy = tsv([("¥#@a", 12, 1, 10, 12), ("l1|", 8, 1, 10, 12)])
    monkeypatch.setattr(engine.pytesseract, "image_to_data", lambda *a, **k: clean)
    clean_pass = run_pass(image, "base", 0, 3)
    monkeypatch.setattr(engine.pytesseract, "image_to_data", lambda *a, **k: noisy)
    noisy_pass = run_pass(image, "base", 0, 3)

    assert clean_pass.score > noisy_pass.score
    assert noisy_pass.score < 0


def test_a_timeout_returns_an_empty_pass_instead_of_failing(monkeypatch, image):
    def timeout(*_args, **_kwargs):
        raise RuntimeError("Tesseract process timeout")

    monkeypatch.setattr(engine.pytesseract, "image_to_data", timeout)

    result = run_pass(image, "base", 90, 11)

    assert result.lines == []
    assert result.score == -math.inf
    assert (result.variant, result.rotation, result.psm) == ("base", 90, 11)


def test_lines_are_sorted_by_position_on_the_label(monkeypatch, image):
    monkeypatch.setattr(
        engine.pytesseract,
        "image_to_data",
        lambda *a, **k: tsv([("ABAJO", 90, 2, 200, 20), ("ARRIBA", 90, 1, 10, 20)]),
    )

    assert [line.text for line in run_pass(image, "base", 0, 3).lines] == ["ARRIBA", "ABAJO"]


def test_text_lines_keep_the_confidence_for_the_parser(monkeypatch, image):
    monkeypatch.setattr(
        engine.pytesseract, "image_to_data", lambda *a, **k: tsv([("VENC", 90, 1, 10, 14)])
    )

    text_lines = run_pass(image, "base", 0, 3).text_lines()

    assert len(text_lines) == 1
    assert text_lines[0].text == "VENC"


def test_without_tesseract_recognize_says_it_clearly(monkeypatch, image):
    monkeypatch.setattr(engine, "tesseract_available", lambda: False)

    with pytest.raises(OcrUnavailableError):
        recognize(np.dstack([image] * 3))


def test_tesseract_available_is_false_when_the_binary_is_missing(monkeypatch):
    tesseract_available.cache_clear()
    monkeypatch.setattr(
        engine.pytesseract, "get_tesseract_version", lambda: (_ for _ in ()).throw(OSError("no está"))
    )
    try:
        assert tesseract_available() is False
    finally:
        tesseract_available.cache_clear()


def test_tesseract_available_needs_spanish_and_english(monkeypatch):
    tesseract_available.cache_clear()
    monkeypatch.setattr(engine.pytesseract, "get_tesseract_version", lambda: "5.3.0")
    monkeypatch.setattr(engine.pytesseract, "get_languages", lambda config="": ["eng", "osd"])
    try:
        assert tesseract_available() is False
    finally:
        tesseract_available.cache_clear()

    tesseract_available.cache_clear()
    monkeypatch.setattr(engine.pytesseract, "get_languages", lambda config="": ["eng", "spa", "osd"])
    try:
        assert tesseract_available() is True
    finally:
        tesseract_available.cache_clear()


def test_the_best_pass_is_the_one_with_the_highest_score():
    best = OcrPass("otsu", 90, 3, [], 12.5, 0.9)
    worst = OcrPass("base", 90, 11, [], -3.0, 0.2)

    assert max([worst, best], key=lambda p: p.score) is best
    assert best.text == ""
