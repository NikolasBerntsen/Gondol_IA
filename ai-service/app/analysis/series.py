"""Construcción de la serie diaria de ventas (relleno de días sin venta) y agregaciones."""

from collections import defaultdict
from collections.abc import Iterable
from dataclasses import dataclass, field
from datetime import date, timedelta

import numpy as np

HISTORY_WINDOW_DAYS = 180
MAX_HISTORY_DAYS = 366
STOCKOUT_REFERENCE_DAYS = 28
"""Días previos a un faltante con los que se estima la demanda que no se pudo vender."""
STOCKOUT_MIN_REFERENCE_DAYS = 7
STOCKOUT_PROFILE_DAYS = 56
STOCKOUT_MAX_RUN_DAYS = 56
"""Un faltante más largo que esto no se completa: la venta de hace más de 8 semanas ya no dice cuánto se vendería hoy."""
WEEKDAY_SHRINK = 4.0
"""Observaciones "virtuales" del promedio general con las que se suaviza el factor de cada día de la semana."""


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


@dataclass(frozen=True)
class Censoring:
    """Días sin stock (demanda censurada) que se completaron con la demanda esperada."""

    days: int = 0
    runs: int = 0
    since: date | None = None
    """Primer día del faltante que llega hasta ayer (el producto sigue o seguía sin stock al final de la serie)."""


def _runs(mask: np.ndarray) -> list[tuple[int, int]]:
    """Tramos consecutivos `[inicio, fin)` donde `mask` es verdadero."""
    runs: list[tuple[int, int]] = []
    start: int | None = None
    for i, flag in enumerate(mask):
        if flag and start is None:
            start = i
        elif not flag and start is not None:
            runs.append((start, i))
            start = None
    if start is not None:
        runs.append((start, len(mask)))
    return runs


def impute_stockouts(series: DailySeries, stockout_days: Iterable[date]) -> tuple[DailySeries, Censoring]:
    """Completa los días sin stock con la demanda esperada: no vender por falta de stock no es una caída de la venta.

    El backend informa los días en que el producto no tuvo stock vendible y no registró ventas (`stockoutDays`).
    Esas ventas en 0 son demanda **censurada**: tomarlas como datos haría ver una caída que no existió (tendencia de
    -90%, pronóstico a la baja) justo cuando hay que reponer. Cada tramo se completa con el promedio de los 28 días
    previos con stock, ajustado por día de la semana (8 semanas, suavizado hacia 1). No se completan tramos sin
    historia previa suficiente ni más largos que 8 semanas.
    """
    mask = np.zeros(series.n, dtype=bool)
    for day in stockout_days:
        index = series.index_of(day)
        if 0 <= index < series.n and series.values[index] <= 0:
            mask[index] = True
    if not mask.any():
        return series, Censoring()

    values = series.values.copy()
    imputed = runs = 0
    since: date | None = None
    for start, end in _runs(mask):
        if end - start > STOCKOUT_MAX_RUN_DAYS:
            continue
        reference = [i for i in range(max(0, start - STOCKOUT_REFERENCE_DAYS), start) if not mask[i]]
        if len(reference) < STOCKOUT_MIN_REFERENCE_DAYS:
            continue
        level = float(series.values[reference].mean())
        if level <= 0:
            continue
        observed = [i for i in range(max(0, start - STOCKOUT_PROFILE_DAYS), start) if not mask[i]]
        observed_values = series.values[observed]
        observed_weekdays = series.weekdays[observed]
        overall = float(observed_values.mean())
        factors = np.ones(7)
        for weekday in range(7):
            same = observed_values[observed_weekdays == weekday]
            factors[weekday] = (float(same.sum()) + WEEKDAY_SHRINK * overall) / ((len(same) + WEEKDAY_SHRINK) * overall)
        values[start:end] = level * factors[series.weekdays[start:end]]
        imputed += end - start
        runs += 1
        if end == series.n:
            since = series.date_at(start)
    if not imputed:
        return series, Censoring()
    completed = DailySeries(series.start, series.as_of, values, series.discount, series.sold_today)
    return completed, Censoring(imputed, runs, since)
