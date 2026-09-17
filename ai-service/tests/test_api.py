import io
import json
import time
from datetime import date

import numpy as np
import pytest
import zxingcpp
from PIL import Image, ImageDraw, ImageFont

from app.ocr.engine import tesseract_available
from tests import synthetic

PRODUCT_KEYS = {
    "productId", "pattern", "patternDescription", "abcClass", "xyzClass", "avgDailySales", "trendPct", "weekdayProfile",
    "forecastMethod", "forecast", "daysOfCover", "predictedStockoutDate", "reorderPoint", "safetyStock",
    "suggestedOrderQty", "anomalies", "lotRisks",
}  # fmt: skip
LOT_RISK_KEYS = {
    "lotId", "daysToExpiry", "quantity", "expectedSalesBeforeExpiry", "unitsAtRisk", "riskLevel",
    "recommendedDiscountPct", "expectedUnitsSoldWithDiscount",
}  # fmt: skip
RECOMMENDATION_KEYS = {
    "type", "productId", "lotId", "priority", "confidence", "title", "explanation", "suggestedQuantity",
    "suggestedDiscountPct", "suggestedDate", "expectedImpact", "dedupeKey",
}  # fmt: skip
SUMMARY_KEYS = {"productsAnalyzed", "atRiskValue", "predictedStockouts7d", "anomalies30d", "patternCounts", "elasticityByCategory", "modelNotes"}
OCR_KEYS = {"text", "lines", "expiryDates", "lotNumbers", "manufactureDates", "barcodes", "productNameCandidates", "processingMs"}
VALID_EAN = "7791234500017"


def strict_json(response) -> dict:
    """Falla si el JSON trae NaN o Infinity (Jackson los rechaza)."""

    def reject(constant):
        raise AssertionError(f"JSON inválido: {constant}")

    return json.loads(response.text, parse_constant=reject)


def png_bytes(image: Image.Image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def barcode_image(value: str = VALID_EAN) -> Image.Image:
    bitmap = zxingcpp.write_barcode(zxingcpp.BarcodeFormat.EAN13, value, width=520, height=160, quiet_zone=30)
    return Image.fromarray(np.array(bitmap))


def label_image() -> Image.Image:
    image = Image.new("RGB", (1100, 900), "white")
    draw = ImageDraw.Draw(image)
    title = ImageFont.load_default(size=64)
    body = ImageFont.load_default(size=44)
    draw.text((60, 50), "SOPA DE TOMATE", fill="black", font=title)
    draw.text((60, 130), "LA HUERTA", fill="black", font=title)
    draw.text((60, 250), "CONT. NETO 340 g", fill="black", font=body)
    draw.text((60, 330), "LOTE: L2409A", fill="black", font=body)
    draw.text((60, 400), "ELAB 25/03/26", fill="black", font=body)
    draw.text((60, 470), "VTO 25/09/2027", fill="black", font=body)
    image.paste(barcode_image(), (60, 600))
    return image


def test_health(client):
    response = client.get("/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["version"] == "1.0.0"
    assert body["modelVersion"] == "gondolia-ai-1.0"
    assert isinstance(body["tesseract"], bool)


def test_analyze_80_products_180_days_is_fast_and_matches_the_contract(client):
    request = synthetic.analyze_request(synthetic.mixed_catalog(np.random.default_rng(42), 80, days=180))
    started = time.perf_counter()
    response = client.post("/v1/analyze", json=request)
    elapsed = time.perf_counter() - started

    assert response.status_code == 200, response.text
    assert elapsed < 20, f"el análisis tardó {elapsed:.1f} s"
    body = strict_json(response)
    assert set(body) == {"modelVersion", "generatedAt", "products", "recommendations", "summary"}
    assert body["modelVersion"] == "gondolia-ai-1.0"
    assert body["generatedAt"].endswith("Z")
    assert len(body["products"]) == 80
    for item in body["products"]:
        assert set(item) == PRODUCT_KEYS
        assert len(item["weekdayProfile"]) == 7
        assert len(item["forecast"]) == 14
        assert set(item["forecast"][0]) == {"date", "yhat", "lo", "hi"}
        assert item["forecast"][0]["date"] == "2026-09-18"
        assert item["abcClass"] in "ABC" and item["xyzClass"] in "XYZ"
        for risk in item["lotRisks"]:
            assert set(risk) == LOT_RISK_KEYS
        for anomaly in item["anomalies"]:
            assert set(anomaly) == {"date", "quantity", "expected", "score", "kind"}
    assert body["recommendations"]
    for rec in body["recommendations"]:
        assert set(rec) == RECOMMENDATION_KEYS
    assert set(body["summary"]) == SUMMARY_KEYS
    assert body["summary"]["productsAnalyzed"] == 80


def test_analyze_accepts_sparse_and_nullable_fields(client):
    request = {
        "tenantId": 1,
        "branchId": 7,
        "branchName": "Sucursal Norte",
        "asOfDate": "2026-09-17",
        "settings": {"stockRotation": None, "horizonDays": 7},
        "products": [
            {
                "productId": 1,
                "name": "Producto nuevo",
                "category": None,
                "salePrice": 100,
                "costPrice": 60,
                "minStock": 0,
                "sellableStock": 0,
                "perishable": True,
                "leadTimeDays": None,
                "createdAt": "2026-09-15T10:00:00Z",
                "dailySales": [{"date": "2026-09-16", "quantity": 2, "discountPct": None}],
                "lots": [{"lotId": 5, "lotNumber": None, "expiryDate": None, "receivedAt": "2026-09-16", "quantity": 0, "discountPct": None, "status": "DEPLETED"}],
            }
        ],
    }
    response = client.post("/v1/analyze", json=request)
    assert response.status_code == 200, response.text
    body = strict_json(response)
    product = body["products"][0]
    assert product["pattern"] == "DATOS_INSUFICIENTES"
    assert len(product["forecast"]) == 7
    assert body["summary"]["elasticityByCategory"] == {"Sin categoría": 2.0}
    assert "rotación FIFO" in body["summary"]["modelNotes"][0]


def test_analyze_with_no_products(client):
    response = client.post("/v1/analyze", json={"tenantId": 1, "branchId": 2, "asOfDate": "2026-09-17", "products": []})
    assert response.status_code == 200
    body = strict_json(response)
    assert body["products"] == [] and body["recommendations"] == []
    assert body["summary"]["productsAnalyzed"] == 0


def test_analyze_validation_errors_are_in_spanish(client):
    response = client.post("/v1/analyze", json={"tenantId": 1, "settings": {"stockRotation": "LIFO"}})
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == "VALIDATION_ERROR"
    assert body["message"] == "Revisá los datos enviados al servicio de IA"
    errors = {error["field"]: error["message"] for error in body["fieldErrors"]}
    assert errors["asOfDate"] == "es obligatorio"
    assert errors["settings.stockRotation"] == "debe ser uno de: FIFO, FEFO"


def test_analyze_range_errors_are_in_spanish(client):
    request = {"tenantId": "uno", "asOfDate": "17/09/2026", "settings": {"serviceLevel": 1.5, "horizonDays": 0}}
    response = client.post("/v1/analyze", json=request)
    errors = {error["field"]: error["message"] for error in response.json()["fieldErrors"]}
    assert errors == {
        "tenantId": "debe ser un número entero",
        "asOfDate": "debe ser una fecha AAAA-MM-DD",
        "settings.serviceLevel": "debe ser menor o igual a 1",
        "settings.horizonDays": "debe ser mayor o igual a 1",
    }


def test_analyze_rejects_duplicated_products(client):
    item = {"productId": 1, "name": "A"}
    response = client.post("/v1/analyze", json={"tenantId": 1, "asOfDate": "2026-09-17", "products": [item, item]})
    assert response.status_code == 422
    assert "repetido" in response.json()["fieldErrors"][0]["message"]


def test_barcode_endpoint_reads_a_generated_ean13(client):
    response = client.post("/v1/barcode", files={"file": ("codigo.png", png_bytes(barcode_image()), "image/png")})
    assert response.status_code == 200
    assert response.json() == {"barcodes": [{"value": VALID_EAN, "format": "EAN_13"}]}


def test_barcode_endpoint_handles_rotated_photos_with_margins(client):
    canvas = Image.new("RGB", (1400, 1000), (235, 232, 225))
    canvas.paste(barcode_image(), (500, 380))
    rotated = canvas.rotate(90, expand=True)
    buffer = io.BytesIO()
    rotated.save(buffer, format="JPEG", quality=85)
    response = client.post("/v1/barcode", files={"file": ("foto.jpg", buffer.getvalue(), "image/jpeg")})
    assert response.status_code == 200
    assert {"value": VALID_EAN, "format": "EAN_13"} in response.json()["barcodes"]


def test_barcode_endpoint_reads_light_bars_on_dark_background(client):
    inverted = Image.eval(barcode_image(), lambda value: 255 - value)
    response = client.post("/v1/barcode", files={"file": ("invertido.png", png_bytes(inverted), "image/png")})
    assert response.json()["barcodes"] == [{"value": VALID_EAN, "format": "EAN_13"}]


def test_barcode_endpoint_without_codes(client):
    response = client.post("/v1/barcode", files={"file": ("vacia.png", png_bytes(Image.new("RGB", (300, 200), "white")), "image/png")})
    assert response.status_code == 200
    assert response.json() == {"barcodes": []}


@pytest.mark.parametrize("path", ["/v1/barcode", "/v1/ocr"])
def test_invalid_files_are_rejected(client, path):
    response = client.post(path, files={"file": ("nota.txt", b"esto no es una imagen", "text/plain")})
    assert response.status_code == 400
    assert response.json() == {"code": "INVALID_FILE", "message": "El archivo no es una imagen válida"}


def test_missing_file_is_a_validation_error(client):
    response = client.post("/v1/ocr")
    assert response.status_code == 422
    assert response.json()["fieldErrors"][0]["field"] == "file"


def noisy_photo(image: Image.Image) -> bytes:
    """Simula una foto de celular: menos contraste, ruido, girada y comprimida en JPEG."""
    pixels = np.asarray(image.convert("L")).astype(float)
    pixels = pixels * 0.6 + 60 + np.random.default_rng(3).normal(0, 25, pixels.shape)
    photo = Image.fromarray(np.clip(pixels, 0, 255).astype("uint8")).rotate(270, expand=True)
    buffer = io.BytesIO()
    photo.save(buffer, format="JPEG", quality=70)
    return buffer.getvalue()


LABEL_UPLOADS = {
    "limpia": lambda: ("etiqueta.png", png_bytes(label_image()), "image/png"),
    "girada_90": lambda: ("etiqueta.png", png_bytes(label_image().rotate(90, expand=True)), "image/png"),
    "foto_con_ruido": lambda: ("foto.jpg", noisy_photo(label_image()), "image/jpeg"),
}


@pytest.mark.skipif(not tesseract_available(), reason="Tesseract con spa+eng no está instalado")
@pytest.mark.parametrize("variant", list(LABEL_UPLOADS))
def test_ocr_reads_a_generated_label(client, variant):
    response = client.post("/v1/ocr", files={"file": LABEL_UPLOADS[variant]()})
    assert response.status_code == 200, response.text
    body = strict_json(response)
    assert set(body) == OCR_KEYS
    assert body["expiryDates"][0]["value"] == "2027-09-25"
    assert "VTO" in body["expiryDates"][0]["raw"]
    assert body["manufactureDates"][0]["value"] == "2026-03-25"
    assert body["lotNumbers"][0]["value"] == "L2409A"
    assert {"value": VALID_EAN, "format": "EAN_13"} in body["barcodes"]
    assert any("SOPA DE TOMATE" in name.upper() for name in body["productNameCandidates"])
    assert any("L2409A" in line for line in body["lines"])
    assert 0 < body["expiryDates"][0]["confidence"] <= 1
    assert body["processingMs"] > 0
    assert date.fromisoformat(body["expiryDates"][0]["value"])


def test_unknown_route_uses_the_error_format(client):
    response = client.get("/v1/nada")
    assert response.status_code == 404
    assert response.json() == {"code": "NOT_FOUND", "message": "El recurso no existe"}


def test_analyze_tolerates_nulls_and_wide_settings(client):
    request = {
        "tenantId": 1,
        "asOfDate": "2026-09-17",
        "settings": None,
        "feedback": None,
        "products": [{"productId": 1, "name": "A", "dailySales": None, "lots": None}],
    }
    assert client.post("/v1/analyze", json=request).status_code == 200
    request["settings"] = {"maxDiscountPct": 100, "targetCoverageDays": 0, "leadTimeDays": 200}
    assert client.post("/v1/analyze", json=request).status_code == 200


def test_absurd_quantities_stay_within_java_int_and_numeric_columns(client):
    sales = [{"date": f"2026-09-{day:02d}", "quantity": 2_000_000_000, "discountPct": 0} for day in range(1, 17)]
    item = {
        "productId": 1, "name": "Carga errónea", "salePrice": 9_999_999_999.99, "costPrice": 9_999_999_999.99, "minStock": 0,
        "sellableStock": 1, "createdAt": "2026-09-01", "dailySales": sales, "lots": [],
    }  # fmt: skip
    response = client.post("/v1/analyze", json={"tenantId": 1, "asOfDate": "2026-09-17", "products": [item]})
    assert response.status_code == 200
    body = strict_json(response)
    insight = body["products"][0]
    for key in ("reorderPoint", "safetyStock", "suggestedOrderQty"):
        assert insight[key] <= 2_147_483_647
    assert insight["avgDailySales"] < 10_000_000
    for rec in body["recommendations"]:
        assert rec["expectedImpact"] < 1e12
        assert rec["suggestedQuantity"] is None or rec["suggestedQuantity"] <= 2_147_483_647


def test_malformed_json_is_reported_on_the_body(client):
    response = client.post("/v1/analyze", content=b"{mal json", headers={"Content-Type": "application/json"})
    assert response.status_code == 422
    assert response.json()["fieldErrors"] == [{"field": "body", "message": "el JSON es inválido"}]
