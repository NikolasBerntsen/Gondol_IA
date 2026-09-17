"""Lectura completa de una etiqueta: OCR + parseo + códigos de barras (SPEC §8.3)."""

import time
from datetime import date

from ..barcode import decode_barcodes
from ..schemas import BarcodeOut, DateCandidate, LotCandidate, OcrResponse
from .engine import recognize
from .parse import DateFound, LotFound, ParsedLabel, parse_label
from .preprocess import load_image

ALTERNATIVE_PASS_FACTOR = 0.9
MAX_ALTERNATIVE_PASSES = 3


def _merge(primary: list, extra: list) -> list:
    best: dict = {item.value: item for item in primary}
    for item in extra:
        current = best.get(item.value)
        if current is None or item.confidence > current.confidence:
            best[item.value] = item
    return sorted(best.values(), key=lambda i: (-i.confidence, str(i.value)))


def _scaled(items: list, factor: float) -> list:
    return [type(item)(item.value, item.raw, round(item.confidence * factor, 2)) for item in items]


def read_label(data: bytes, today: date) -> OcrResponse:
    started = time.perf_counter()
    image = load_image(data)
    outcome = recognize(image)

    parsed: ParsedLabel = parse_label(outcome.best.text_lines(), today)
    expiry: list[DateFound] = parsed.expiry_dates
    manufacture: list[DateFound] = parsed.manufacture_dates
    lots: list[LotFound] = parsed.lot_numbers
    printed_codes = list(parsed.barcodes)
    names = list(parsed.product_name_candidates)
    # Un dato puede leerse bien solo en otra variante: se suma con una confianza algo menor.
    for alternative in outcome.passes[1 : 1 + MAX_ALTERNATIVE_PASSES]:
        other = parse_label(alternative.text_lines(), today)
        expiry = _merge(expiry, _scaled(other.expiry_dates, ALTERNATIVE_PASS_FACTOR))
        manufacture = _merge(manufacture, _scaled(other.manufacture_dates, ALTERNATIVE_PASS_FACTOR))
        lots = _merge(lots, _scaled(other.lot_numbers, ALTERNATIVE_PASS_FACTOR))
        printed_codes.extend(code for code in other.barcodes if code not in printed_codes)
        if not names:
            names = list(other.product_name_candidates)

    barcodes = [BarcodeOut(value=b.value, format=b.format) for b in decode_barcodes(image)]
    known = {b.value for b in barcodes}
    barcodes.extend(BarcodeOut(value=value, format=fmt) for value, fmt in printed_codes if value not in known)

    return OcrResponse(
        text=outcome.best.text,
        lines=[line.text for line in outcome.best.lines],
        expiry_dates=[DateCandidate(value=d.value, raw=d.raw, confidence=d.confidence) for d in expiry],
        lot_numbers=[LotCandidate(value=lot.value, raw=lot.raw, confidence=lot.confidence) for lot in lots],
        manufacture_dates=[DateCandidate(value=d.value, raw=d.raw, confidence=d.confidence) for d in manufacture],
        barcodes=barcodes,
        product_name_candidates=names,
        processing_ms=int((time.perf_counter() - started) * 1000),
    )
