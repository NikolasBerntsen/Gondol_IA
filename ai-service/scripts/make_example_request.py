"""Genera docs/examples/analyze-request.json: un pedido realista de /v1/analyze para una sucursal.

Uso (desde la raíz del repo, sin dependencias externas):  python ai-service/scripts/make_example_request.py
Los datos son ficticios y reproducibles (semilla fija). Incluye todos los escenarios que la IA sabe explicar:
reposición, lote nuevo que vence antes que stock viejo con FIFO, lote vencido, sobrestock de perecedero,
pico anómalo de ventas, demanda intermitente, productos en alza/baja, sin movimiento y recién dados de alta.
"""

import json
import math
import random
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path

AS_OF = date(2026, 9, 17)
HISTORY_DAYS = 180
OUTPUT = Path(__file__).resolve().parents[2] / "docs" / "examples" / "analyze-request.json"
WEEKEND = [0.8, 0.85, 0.9, 0.95, 1.1, 1.6, 1.8]

rng = random.Random(20260917)


def poisson(lam: float) -> int:
    if lam <= 0:
        return 0
    if lam > 30:
        return max(0, round(rng.gauss(lam, math.sqrt(lam))))
    limit, k, p = math.exp(-lam), 0, 1.0
    while True:
        p *= rng.random()
        if p <= limit:
            return k
        k += 1


def history(level, days=HISTORY_DAYS, weekly=None, trend=(1.0, 1.0), sparse=None, silent_last=0):
    """Ventas diarias hasta ayer; devuelve solo los días con ventas (como las manda el backend)."""
    start = AS_OF - timedelta(days=days)
    sales = []
    for i in range(days):
        day = start + timedelta(days=i)
        factor = trend[0] + (trend[1] - trend[0]) * i / max(days - 1, 1)
        rate = level * factor * (weekly[day.weekday()] if weekly else 1.0)
        if sparse:
            quantity = 2 + poisson(sparse[1]) if rng.random() < sparse[0] else 0
        else:
            quantity = poisson(rate)
        if i >= days - silent_last:
            quantity = 0
        if quantity > 0:
            sales.append({"date": day.isoformat(), "quantity": quantity, "discountPct": 0})
    return sales


def received(days_ago: int, hour: int = 13, minute: int = 10) -> str:
    moment = datetime.combine(AS_OF - timedelta(days=days_ago), time(hour, minute), tzinfo=timezone.utc)
    return moment.isoformat().replace("+00:00", "Z")


def lot(lot_id, number, expiry_in, received_days_ago, quantity, discount=None, status="ACTIVE"):
    return {
        "lotId": lot_id,
        "lotNumber": number,
        "expiryDate": (AS_OF + timedelta(days=expiry_in)).isoformat() if expiry_in is not None else None,
        "receivedAt": received(received_days_ago),
        "quantity": quantity,
        "discountPct": discount,
        "status": status,
    }


def product(product_id, name, category, sale, cost, min_stock, sales, lots, perishable=True, lead=None, created_days_ago=400):
    sellable = sum(
        item["quantity"]
        for item in lots
        if item["status"] == "ACTIVE" and (item["expiryDate"] is None or item["expiryDate"] >= AS_OF.isoformat())
    )
    return {
        "productId": product_id,
        "name": name,
        "category": category,
        "salePrice": sale,
        "costPrice": cost,
        "minStock": min_stock,
        "sellableStock": sellable,
        "perishable": perishable,
        "leadTimeDays": lead,
        "createdAt": (AS_OF - timedelta(days=created_days_ago)).isoformat(),
        "dailySales": sales,
        "lots": lots,
    }


def build() -> dict:
    cola = history(9, weekly=WEEKEND)
    cola[-1] = {"date": (AS_OF - timedelta(days=1)).isoformat(), "quantity": 64, "discountPct": 0}

    products = [
        product(10, "Yogur bebible frutilla 1L", "Lácteos", 2100.0, 1400.0, 10, history(3.4, weekly=WEEKEND),
                [lot(551, "L2408B", 21, 15, 40), lot(555, "L2409A", 7, 2, 18)]),
        product(11, "Leche entera sachet 1L", "Lácteos", 1350.0, 980.0, 24, history(14),
                [lot(560, "LE0911", 5, 6, 9), lot(561, "LE0914", 8, 3, 12)], lead=2),
        product(12, "Queso cremoso Don Anselmo x kg", "Lácteos", 9800.0, 6900.0, 3, history(1.6),
                [lot(570, "QC0812", -2, 36, 4), lot(571, "QC0905", 38, 12, 9)]),
        product(13, "Dulce de leche La Quinta 400 g", "Lácteos", 2650.0, 1750.0, 6, history(1.2),
                [lot(580, "DL2607", 160, 20, 140)]),
        product(20, "Pan lactal blanco 550 g", "Panificados", 2900.0, 1900.0, 8, history(6.5, weekly=[1, 1, 1, 1, 1.1, 1.3, 0.9]),
                [lot(590, "PL0916", 5, 1, 22)], lead=1),
        product(30, "Gaseosa cola 2,25L Burbuja", "Bebidas", 3200.0, 2150.0, 18, cola,
                [lot(600, "GC2608", 150, 25, 70), lot(601, "GC2609", 175, 5, 60)], perishable=False),
        product(31, "Cerveza rubia lata 473 ml Patagonia Sur", "Bebidas", 1900.0, 1200.0, 36, history(11, weekly=WEEKEND),
                [lot(610, "CR2606", 220, 30, 96)], perishable=False),
        product(32, "Fernet 750 ml Tres Sierras", "Bebidas", 11900.0, 8300.0, 6, history(2.2, trend=(0.45, 1.6)),
                [lot(620, "F2605", 700, 18, 14)], perishable=False, lead=5),
        product(40, "Papas fritas clásicas 150 g Crocantitas", "Snacks", 2300.0, 1450.0, 12, history(4.5, weekly=WEEKEND, trend=(0.4, 1.6)),
                [lot(630, "PF2611", 60, 9, 35)]),
        product(41, "Pimentón dulce 50 g Sabores del Valle", "Almacén", 1150.0, 690.0, 2, history(0, sparse=(0.12, 2.5)),
                [lot(640, "PD2702", 150, 40, 11)]),
        product(42, "Aceite de girasol 900 ml Campo Dorado", "Almacén", 3400.0, 2400.0, 10, history(5.5, trend=(1.4, 0.55)),
                [lot(650, "AG2703", 190, 22, 48)], perishable=False),
        product(43, "Yerba mate 1 kg Tranquera", "Almacén", 5200.0, 3600.0, 15, history(7.5),
                [lot(660, "YM2710", 380, 10, 16)], perishable=False, lead=4),
        product(44, "Sopa de tomate en lata La Huerta 340 g", "Almacén", 1850.0, 1100.0, 4, history(0.35),
                [lot(670, "L2409A", 420, 50, 21)]),
        product(45, "Café molido 500 g Estación Sur", "Almacén", 8900.0, 6100.0, 3, history(1.1, silent_last=46),
                [lot(680, "CM2701", 110, 70, 12)]),
        product(50, "Detergente limón 750 ml Brillo", "Limpieza", 1650.0, 980.0, 8, history(3.1),
                [lot(690, None, None, 14, 30)], perishable=False),
        product(60, "Kéfir natural 500 ml Pampa Verde", "Lácteos", 2750.0, 1800.0, 4, history(2.5, days=9),
                [lot(700, "KF0920", 12, 4, 10)], created_days_ago=9),
    ]  # fmt: skip

    feedback = [
        {"recommendationId": 90, "type": "DISCOUNT", "productId": 10, "category": "Lácteos", "status": "ACCEPTED", "discountPct": 20,
         "outcome": {"unitsBefore7d": 10, "unitsAfter7d": 18, "lift": 1.8, "lotUnitsSold": 11, "lotUnitsRemaining": 1}},
        {"recommendationId": 97, "type": "DISCOUNT", "productId": 40, "category": "Snacks", "status": "ACCEPTED", "discountPct": 25,
         "outcome": {"unitsBefore7d": 30, "unitsAfter7d": 36, "lift": 1.2, "lotUnitsSold": 20, "lotUnitsRemaining": 6}},
        {"recommendationId": 101, "type": "REORDER", "productId": 11, "category": "Lácteos", "status": "ACCEPTED", "discountPct": None, "outcome": None},
        {"recommendationId": 104, "type": "REDUCE_PURCHASE", "productId": 13, "category": "Lácteos", "status": "DISCARDED", "discountPct": None, "outcome": None},
    ]  # fmt: skip

    return {
        "tenantId": 2,
        "branchId": 3,
        "branchName": "Sucursal Centro",
        "asOfDate": AS_OF.isoformat(),
        "settings": {
            "stockRotation": "FIFO",
            "expiryWarningDays": 15,
            "expiryCriticalDays": 5,
            "leadTimeDays": 3,
            "targetCoverageDays": 14,
            "serviceLevel": 0.95,
            "maxDiscountPct": 40,
            "horizonDays": 14,
        },
        "products": products,
        "feedback": feedback,
    }


def dumps(payload: dict) -> str:
    """JSON indentado, pero con cada venta diaria en una sola línea para que el archivo sea legible."""
    compact: dict[str, str] = {}
    for item in payload["products"]:
        for index, sale in enumerate(item["dailySales"]):
            marker = f"@@sale-{item['productId']}-{index}@@"
            compact[marker] = json.dumps(sale, ensure_ascii=False)
            item["dailySales"][index] = marker
    text = json.dumps(payload, ensure_ascii=False, indent=2)
    for marker, value in compact.items():
        text = text.replace(f'"{marker}"', value)
    return text + "\n"


if __name__ == "__main__":
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(dumps(build()), encoding="utf-8")
    print(f"Escrito {OUTPUT}")
