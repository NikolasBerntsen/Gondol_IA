"""Generadores de datos sintéticos para los tests: series por patrón de venta y pedidos de análisis."""

from datetime import date, datetime, time, timedelta, timezone

import numpy as np

from app.analysis.series import DailySeries, SaleRecord, build_daily_series

AS_OF = date(2026, 9, 17)
WEEKEND_PROFILE = np.array([0.75, 0.8, 0.85, 0.9, 1.1, 1.75, 1.85])


def _dates(days: int, as_of: date = AS_OF) -> list[date]:
    start = as_of - timedelta(days=days)
    return [start + timedelta(days=i) for i in range(days)]


def stable(rng: np.random.Generator, days: int = 180, level: float = 8.0) -> np.ndarray:
    return rng.poisson(level, days)


def weekly_seasonal(rng: np.random.Generator, days: int = 180, level: float = 6.0, as_of: date = AS_OF) -> np.ndarray:
    weekdays = np.array([d.weekday() for d in _dates(days, as_of)])
    return rng.poisson(level * WEEKEND_PROFILE[weekdays])


def intermittent(rng: np.random.Generator, days: int = 180, probability: float = 0.15, size: float = 4.0) -> np.ndarray:
    sells = rng.random(days) < probability
    return np.where(sells, 2 + rng.poisson(size, days), 0)


def growing(rng: np.random.Generator, days: int = 180, start: float = 2.0, end: float = 9.0) -> np.ndarray:
    return rng.poisson(np.linspace(start, end, days))


def declining(rng: np.random.Generator, days: int = 180, start: float = 9.0, end: float = 2.0) -> np.ndarray:
    return rng.poisson(np.linspace(start, end, days))


def slow(rng: np.random.Generator, days: int = 180, rate: float = 0.3) -> np.ndarray:
    return np.minimum(rng.poisson(rate, days), 2)


def dormant(rng: np.random.Generator, days: int = 180, silent_days: int = 45) -> np.ndarray:
    values = rng.poisson(2.0, days)
    values[-silent_days:] = 0
    return values


def short_history(rng: np.random.Generator, days: int = 10) -> np.ndarray:
    return rng.poisson(5.0, days)


def to_series(values: np.ndarray, as_of: date = AS_OF, discounts: np.ndarray | None = None) -> DailySeries:
    """Serie diaria cuyo último valor corresponde a ayer (el día `as_of` está en curso)."""
    dates = _dates(len(values), as_of)
    sales = [
        SaleRecord(d, float(q), float(discounts[i]) if discounts is not None else 0.0)
        for i, (d, q) in enumerate(zip(dates, values))
        if q > 0
    ]
    return build_daily_series(sales, as_of, dates[0] if dates else as_of)


def daily_sales(values: np.ndarray, as_of: date = AS_OF, discounts: np.ndarray | None = None) -> list[dict]:
    """Formato del pedido: solo los días con ventas (el servicio rellena con ceros)."""
    return [
        {"date": d.isoformat(), "quantity": int(q), "discountPct": float(discounts[i]) if discounts is not None else 0}
        for i, (d, q) in enumerate(zip(_dates(len(values), as_of), values))
        if q > 0
    ]


def received(day: date, hour: int = 13) -> str:
    return datetime.combine(day, time(hour, 10), tzinfo=timezone.utc).isoformat().replace("+00:00", "Z")


def lot(lot_id: int, quantity: int, expiry: date | None, received_day: date, number: str | None = None, **extra) -> dict:
    return {
        "lotId": lot_id,
        "lotNumber": number,
        "expiryDate": expiry.isoformat() if expiry else None,
        "receivedAt": received(received_day),
        "quantity": quantity,
        "discountPct": extra.get("discount_pct"),
        "status": extra.get("status", "ACTIVE"),
    }


def product(
    product_id: int,
    values: np.ndarray,
    name: str | None = None,
    category: str = "Almacén",
    sale_price: float = 1000.0,
    cost_price: float = 650.0,
    min_stock: int = 5,
    stock: int | None = None,
    perishable: bool = True,
    lots: list[dict] | None = None,
    lead_time_days: int | None = None,
    as_of: date = AS_OF,
    discounts: np.ndarray | None = None,
) -> dict:
    return {
        "productId": product_id,
        "name": name or f"Producto {product_id}",
        "category": category,
        "salePrice": sale_price,
        "costPrice": cost_price,
        "minStock": min_stock,
        "sellableStock": stock if stock is not None else sum(item["quantity"] for item in lots or [] if item["status"] == "ACTIVE"),
        "perishable": perishable,
        "leadTimeDays": lead_time_days,
        "createdAt": (as_of - timedelta(days=len(values))).isoformat(),
        "dailySales": daily_sales(values, as_of, discounts),
        "lots": lots or [],
    }


def analyze_request(products: list[dict], feedback: list[dict] | None = None, as_of: date = AS_OF, **settings) -> dict:
    base_settings = {
        "stockRotation": "FIFO",
        "expiryWarningDays": 15,
        "expiryCriticalDays": 5,
        "leadTimeDays": 3,
        "targetCoverageDays": 14,
        "serviceLevel": 0.95,
        "maxDiscountPct": 40,
        "horizonDays": 14,
    }
    base_settings.update(settings)
    return {
        "tenantId": 2,
        "branchId": 3,
        "branchName": "Sucursal Centro",
        "asOfDate": as_of.isoformat(),
        "settings": base_settings,
        "products": products,
        "feedback": feedback or [],
    }


def mixed_catalog(rng: np.random.Generator, count: int, days: int = 180, as_of: date = AS_OF) -> list[dict]:
    """Catálogo variado (todos los patrones, lotes con vencimientos) para pruebas de punta a punta."""
    generators = [stable, weekly_seasonal, intermittent, growing, declining, slow, dormant]
    categories = ["Lácteos", "Bebidas", "Almacén", "Snacks", "Limpieza", "Panificados"]
    products = []
    for i in range(count):
        values = generators[i % len(generators)](rng, days)
        daily_mean = max(float(values[-28:].mean()), 0.2)
        lots = [
            lot(1000 + i * 10, int(daily_mean * 6) + 2, as_of + timedelta(days=int(rng.integers(20, 60))), as_of - timedelta(days=12), f"L{i}A"),
            lot(1001 + i * 10, int(daily_mean * 4) + 1, as_of + timedelta(days=int(rng.integers(3, 25))), as_of - timedelta(days=3), f"L{i}B"),
        ]
        if i % 9 == 0:
            lots.append(lot(1002 + i * 10, 3, as_of - timedelta(days=2), as_of - timedelta(days=40), f"L{i}V"))
        products.append(
            product(
                100 + i,
                values,
                name=f"Producto de prueba {i}",
                category=categories[i % len(categories)],
                sale_price=float(rng.integers(500, 5000)),
                cost_price=float(rng.integers(300, 450)),
                min_stock=int(rng.integers(0, 12)),
                lots=lots,
                stock=sum(item["quantity"] for item in lots[:2]),
                as_of=as_of,
            )
        )
    return products
