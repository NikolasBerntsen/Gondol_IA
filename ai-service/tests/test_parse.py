from datetime import date

import pytest

from app.ocr.parse import TextLine, ean_checksum_ok, fix_date_digits, normalize, normalize_lot, parse_label

TODAY = date(2026, 9, 17)


def parse(*lines):
    return parse_label(list(lines), TODAY)


def first_value(items):
    assert items, "no se encontró ningún candidato"
    return items[0].value


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("VTO: 12/10/26", date(2026, 10, 12)),
        ("VTO 25/09/2026", date(2026, 9, 25)),
        ("VENCE 03-2027", date(2027, 3, 31)),
        ("CONSUMIR ANTES DE 25 SEP 2026", date(2026, 9, 25)),
        ("Consumir preferentemente antes del 3 de octubre de 2026", date(2026, 10, 3)),
        ("Vence: 05.11.2026", date(2026, 11, 5)),
        ("FECHA DE VENCIMIENTO: 30/04/2027", date(2027, 4, 30)),
        ("F. VTO 07-12-26", date(2026, 12, 7)),
        ("EXP 2027-01-15", date(2027, 1, 15)),
        ("CAD 15.01.27", date(2027, 1, 15)),
        ("BB 01/2027", date(2027, 1, 31)),
        ("VTO SEPT/26", date(2026, 9, 30)),
        ("VTO. 12 DIC 2026", date(2026, 12, 12)),
        ("Best before: 10 MAR 2027", date(2027, 3, 10)),
        ("EXPIRA ENE 2027", date(2027, 1, 31)),
        ("VENC 1O/O3/2O27", date(2027, 3, 10)),
        ("VTO 121026", date(2026, 10, 12)),
        ("VTO 10/26", date(2026, 10, 31)),
        ("Vto: 29/02/28", date(2028, 2, 29)),
        ("vencimiento 1/6/2027", date(2027, 6, 1)),
    ],
)
def test_expiry_dates_from_real_world_labels(label, expected):
    result = parse(label)
    assert first_value(result.expiry_dates) == expected
    assert result.expiry_dates[0].confidence >= 0.7
    assert not result.manufacture_dates


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("ELAB 25/03/26", date(2026, 3, 25)),
        ("F.ELAB 10/09/2026", date(2026, 9, 10)),
        ("FAB: 01/08/2026", date(2026, 8, 1)),
        ("Envasado el 02/09/2026", date(2026, 9, 2)),
        ("Fecha de elaboración: 14-SEP-2026", date(2026, 9, 14)),
    ],
)
def test_manufacture_dates(label, expected):
    result = parse(label)
    assert first_value(result.manufacture_dates) == expected
    assert not result.expiry_dates


def test_full_label_line_with_lot_manufacture_and_expiry():
    result = parse("LOTE 24091B F.ELAB 10/09/2026 VTO 10/03/2027")
    assert first_value(result.lot_numbers) == "24091B"
    assert result.lot_numbers[0].raw == "LOTE 24091B"
    assert first_value(result.manufacture_dates) == date(2026, 9, 10)
    assert result.manufacture_dates[0].raw == "F.ELAB 10/09/2026"
    assert first_value(result.expiry_dates) == date(2027, 3, 10)
    assert result.expiry_dates[0].raw == "VTO 10/03/2027"
    assert len(result.expiry_dates) == 1 and len(result.manufacture_dates) == 1


def test_combined_header_assigns_dates_in_order():
    result = parse("ELAB/VTO: 10/09/26 10/03/27")
    assert first_value(result.manufacture_dates) == date(2026, 9, 10)
    assert first_value(result.expiry_dates) == date(2027, 3, 10)


def test_keyword_on_previous_line():
    result = parse("VENCIMIENTO:", "25/09/2026")
    assert first_value(result.expiry_dates) == date(2026, 9, 25)
    assert result.expiry_dates[0].confidence >= 0.7


def test_unprefixed_dates_farthest_is_expiry_candidate():
    result = parse("12/05/2026", "18/11/2026")
    assert first_value(result.expiry_dates) == date(2026, 11, 18)
    assert first_value(result.manufacture_dates) == date(2026, 5, 12)
    assert result.expiry_dates[0].confidence < 0.7


def test_labeled_expiry_outranks_unprefixed_dates():
    result = parse("20/12/2027", "VTO 25/09/2026")
    assert first_value(result.expiry_dates) == date(2026, 9, 25)
    assert [d.confidence for d in result.expiry_dates] == sorted((d.confidence for d in result.expiry_dates), reverse=True)


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("L.2409A", "L2409A"),
        ("LOTE: L2409A", "L2409A"),
        ("L-2409/A", "L2409A"),
        ("L: 24091B", "L24091B"),
        ("Lote N° 45821", "45821"),
        ("Nro. de lote 7781A", "7781A"),
        ("LOT 2409-A", "2409A"),
        ("BATCH B2409X7", "B2409X7"),
        ("PARTIDA 0915", "0915"),
        ("L2409A", "L2409A"),
        ("LOTE:L2409A-VTO:25/09/26", "L2409A"),
    ],
)
def test_lot_numbers(label, expected):
    result = parse(label)
    assert first_value(result.lot_numbers) == expected
    assert 0 < result.lot_numbers[0].confidence <= 0.99


def test_short_lot_prefix_also_offers_the_code_without_the_letter():
    values = [lot.value for lot in parse("L.2409A").lot_numbers]
    assert values == ["L2409A", "2409A"]


def test_lot_and_expiry_on_the_same_compact_line():
    result = parse("LOTE:L2409A-VTO:25/09/26")
    assert first_value(result.expiry_dates) == date(2026, 9, 25)


@pytest.mark.parametrize(
    "label",
    ["CONT. NETO 500 G", "RNPA 02-591234", "INDUSTRIA ARGENTINA", "1 LT", "LOTE/VTO 12/10/26", "Precio $ 1.250,50", "TEL 0800-555-1234"],
)
def test_texts_without_lots(label):
    assert parse(label).lot_numbers == []


def test_invalid_dates_are_ignored():
    result = parse("VTO 31/02/2027", "VTO 13/13/2026", "VTO 01/01/1990")
    assert result.expiry_dates == []


def test_month_first_dates_are_accepted_with_less_confidence():
    imported = parse("EXP 12/31/2026").expiry_dates
    assert first_value(imported) == date(2026, 12, 31)
    assert imported[0].confidence < parse("EXP 31/12/2026").expiry_dates[0].confidence


@pytest.mark.parametrize(
    ("label", "expected"),
    [
        ("EXP 20271012", date(2027, 10, 12)),
        ("EXP. 2027/03", date(2027, 3, 31)),
        ("VTO 10 03 27", date(2027, 3, 10)),
        ("VTO 10 03 2027", date(2027, 3, 10)),
        ("BEST BEFORE END 03 2027", date(2027, 3, 31)),
        ("FV 10/03/27", date(2027, 3, 10)),
        ("F.V. 10/03/27", date(2027, 3, 10)),
        ("V: 15/03/27", date(2027, 3, 15)),
    ],
)
def test_printer_formats_and_abbreviations(label, expected):
    result = parse(label)
    assert first_value(result.expiry_dates) == expected
    assert result.expiry_dates[0].confidence >= 0.7


def test_short_manufacture_and_expiry_abbreviations():
    result = parse("FE 10/09/26 FV 10/03/27")
    assert first_value(result.manufacture_dates) == date(2026, 9, 10)
    assert first_value(result.expiry_dates) == date(2027, 3, 10)
    assert len(result.expiry_dates) == 1


def test_spaced_lot_prefix():
    values = [lot.value for lot in parse("L 2409A").lot_numbers]
    assert values[0] == "L2409A"
    assert parse("1 L 2409").lot_numbers == []


def test_weights_after_the_lot_keyword_are_unlikely_lots():
    assert parse("LOTE 500G").lot_numbers[0].confidence < 0.5
    assert parse("LOTE 2409").lot_numbers[0].confidence > 0.8


def test_product_name_candidates_prefer_big_text():
    lines = [
        TextLine("SOPA DE TOMATE", 0.93, 60),
        TextLine("LA HUERTA", 0.9, 58),
        TextLine("CONT. NETO 340 g", 0.9, 20),
        TextLine("VTO 25/09/2026", 0.91, 22),
        TextLine("LOTE L2409A", 0.88, 22),
        TextLine("INDUSTRIA ARGENTINA", 0.9, 18),
    ]
    result = parse_label(lines, TODAY)
    assert result.product_name_candidates[0] == "SOPA DE TOMATE LA HUERTA"
    assert set(result.product_name_candidates[1:]) == {"SOPA DE TOMATE", "LA HUERTA"}
    assert first_value(result.expiry_dates) == date(2026, 9, 25)
    assert result.expiry_dates[0].raw == "VTO 25/09/2026"
    assert first_value(result.lot_numbers) == "L2409A"


def test_ocr_confidence_lowers_candidate_confidence():
    sure = parse_label([TextLine("VTO 25/09/2026", 0.95)], TODAY).expiry_dates[0].confidence
    unsure = parse_label([TextLine("VTO 25/09/2026", 0.3)], TODAY).expiry_dates[0].confidence
    assert unsure < sure


def test_printed_barcode_digits():
    assert parse("7 791234 500017").barcodes == [("7791234500017", "EAN_13")]
    assert parse("7791234500012").barcodes == [("7791234500012", "EAN_13")]
    assert parse("036000291452").barcodes == [("036000291452", "UPC_A")]
    assert parse("CUIT 30712345678").barcodes == []


def test_helpers():
    assert ean_checksum_ok("7791234500017")
    assert not ean_checksum_ok("7791234500012")
    assert ean_checksum_ok("96385074")
    assert normalize_lot("l-2409/a ") == "L2409A"
    assert normalize_lot("  ") is None
    assert normalize("Elaboración") == "ELABORACION"
    assert len(normalize("Nº lote ñandú")) == len("Nº lote ñandú")
    assert fix_date_digits("Vto 1O/O3/2O27 LOTE lO1") == "Vto 10/03/2027 LOTE lO1"


def test_name_candidates_join_lines_separated_by_ocr_noise():
    lines = [TextLine("YOGUR BEBIBLE", 0.93, 60), TextLine("Ll", 0.4, 30), TextLine("FRUTILLA", 0.92, 60), TextLine("PESO NETO 1L", 0.9, 20)]
    assert parse_label(lines, TODAY).product_name_candidates[0] == "YOGUR BEBIBLE FRUTILLA"
