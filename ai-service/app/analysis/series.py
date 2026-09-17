"""Construcción de la serie diaria de ventas (relleno de días sin venta) y agregaciones."""

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np

HISTORY_WINDOW_DAYS = 180
MAX_HISTORY_DAYS = 366


@dataclass(frozen=True)
class SaleRecord:
    day: date
    quantity: float
    discount_pct: float = 0.0


@dataclass
class DailySeries:
    """Ventas diarias completas desde `start` hasta el día anterior a `as_of` (inclusive).

    El día `as_of` se considera en curso: sus ventas no forman parte de la historia porque
    están incompletas, pero se guardan en `sold_today` para descontarlas de la demanda de hoy.
    """

    start: date
    as_of: date
    values: np.ndarray
    discount: np.ndarray
    sold_today: float = 0.0
    weekdays: np.ndarray = field(init=False)

    def __post_init__(self) -> None:
        first_weekday = self.start.weekday()
        self.weekdays = (np.arange(len(self.values)) + first_weekday) % 7

    @property
    def n(self) -> int:
        return int(len(self.values))

    @property
    def end(self) -> date:
        return self.as_of - timedelta(days=1)

    def date_at(self, index: int) -> date:
        return self.start + timedelta(days=int(index))

    def index_of(self, day: date) -> int:
        return (day - self.start).days

    def tail(self, days: int) -> np.ndarray:
        return self.values[-days:] if days > 0 else self.values[:0]


def build_daily_series(sales: Iterable[SaleRecord], as_of: date, created_at: date | None) -> DailySeries:
    """Arma la serie diaria rellenando con 0 los días sin ventas.

    Inicio: la fecha de alta del producto (acotada a la ventana de 180 días que envía el
    backend) o la primera venta si es anterior (datos históricos cargados después del alta).
    """
    quantities: dict[date, float] = defaultdict(float)
    discounted_units: dict[date, float] = defaultdict(float)
    first_record: date | None = None
    sold_today = 0.0

    for sale in sales:
        if sale.day > as_of:
            continue
        qty = max(0.0, float(sale.quantity))
        if sale.day == as_of:
            sold_today += qty
            continue
        quantities[sale.day] += qty
        discounted_units[sale.day] += qty * max(0.0, float(sale.discount_pct or 0.0))
        if first_record is None or sale.day < first_record:
            first_record = sale.day

    end = as_of - timedelta(days=1)
    oldest_allowed = as_of - timedelta(days=MAX_HISTORY_DAYS)
    if created_at is not None:
        start = max(created_at, as_of - timedelta(days=HISTORY_WINDOW_DAYS))
        if first_record is not None and first_record < start:
            start = first_record
    elif first_record is not None:
        start = first_record
    else:
        start = as_of
    start = max(start, oldest_allowed)

    n = max(0, (end - start).days + 1)
    values = np.zeros(n, dtype=float)
    discount = np.zeros(n, dtype=float)
    for day, qty in quantities.items():
        idx = (day - start).days
        if 0 <= idx < n:
            values[idx] = qty
            if qty > 0:
                discount[idx] = discounted_units[day] / qty
    return DailySeries(start=start if n else as_of, as_of=as_of, values=values, discount=discount, sold_today=sold_today)


def weekly_totals(values: np.ndarray, max_weeks: int = 12) -> np.ndarray:
    """Totales por bloques completos de 7 días, alineados al final de la serie."""
    weeks = min(len(values) // 7, max_weeks)
    if weeks == 0:
        return np.zeros(0)
    block = values[len(values) - weeks * 7:]
    return block.reshape(weeks, 7).sum(axis=1)


def weekday_means(values: np.ndarray, weekdays: np.ndarray) -> np.ndarray:
    means = np.zeros(7)
    for d in range(7):
        mask = weekdays == d
        if mask.any():
            means[d] = values[mask].mean()
    return means


def coefficient_of_variation(values: np.ndarray) -> float:
    if len(values) < 2:
        return 0.0
    mean = float(values.mean())
    if mean <= 0:
        return 0.0
    return float(values.std(ddof=1) / mean)
