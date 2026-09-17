"""Detección de anomalías: z-score robusto (MAD) sobre residuos + IsolationForest como segunda opinión."""

import math
from dataclasses import dataclass
from datetime import date

import numpy as np
from scipy.stats import poisson
from sklearn.ensemble import IsolationForest

from .forecasting import CROSTON_SBA, ForecastResult
from .series import DailySeries

LOOKBACK_DAYS = 90
Z_THRESHOLD = 3.5  # criterio de Iglewicz y Hoaglin para el z-score modificado
STRONG_Z = 6.0
MIN_ROWS_FOR_FOREST = 64
MAX_PER_PRODUCT = 10
SPIKE_TAIL_PROBABILITY = 3e-4
"""Un pico tiene que ser improbable incluso como conteo de Poisson con la media esperada: evita marcar
un 7 en un producto que vende 2 por día, que es ruido normal en volúmenes bajos."""
MIN_SPORADIC_SALES = 5


@dataclass
class Anomaly:
    day: date
    quantity: float
    expected: float
    score: float
    kind: str  # SPIKE | DROP
    confirmed_by_forest: bool


@dataclass
class _Candidate:
    product: int
    index: int
    row: int
    quantity: float
    expected: float
    z: float
    kind: str


def _robust_scale(residuals: np.ndarray, level: float) -> tuple[float, float]:
    median = float(np.median(residuals))
    deviations = np.abs(residuals - median)
    scale = 1.4826 * float(np.median(deviations))
    floor = max(0.5, 0.3 * math.sqrt(max(level, 0.0)))
    if scale < floor:
        scale = max(1.2533 * float(deviations.mean()), floor)
    return median, scale


def _poisson_tail(quantity: float, expected: float) -> float:
    """P(X >= quantity) para un conteo de Poisson con media `expected`."""
    return float(poisson.sf(math.ceil(quantity) - 1, max(expected, 0.05)))


def _scan_product(product: int, series: DailySeries, fc: ForecastResult, rows: list[list[float]]) -> list[_Candidate]:
    y = series.values
    valid = np.isfinite(fc.fitted)
    if series.n < 21 or valid.sum() < 14 or y.sum() == 0:
        return []
    residuals = y[valid] - fc.fitted[valid]
    level = float(y[-56:].mean())
    median, scale = _robust_scale(residuals, level)
    p95 = float(np.percentile(y, 95))

    # En demanda esporádica los días sin venta son normales y cada venta es "grande" frente al promedio
    # diario: un pico se evalúa comparando el tamaño de la venta con el de las demás, y no se buscan caídas.
    sporadic = fc.method == CROSTON_SBA
    sizes = y[y > 0]
    size_median = float(np.median(sizes)) if len(sizes) else 0.0
    size_scale = max(1.4826 * float(np.median(np.abs(sizes - size_median))), math.sqrt(size_median), 1.0) if len(sizes) else 1.0

    candidates: list[_Candidate] = []
    previous_drop = -2
    start = max(0, series.n - LOOKBACK_DAYS)
    for t in range(start, series.n):
        expected = fc.fitted[t]
        if not math.isfinite(expected):
            continue
        quantity = float(y[t])
        residual = quantity - expected
        z = (residual - median) / scale
        row_id = len(rows)
        rows.append([max(-20.0, min(20.0, z)), residual / (expected + 1.0), math.log1p(quantity) - math.log1p(max(expected, 0.0))])

        if sporadic:
            if len(sizes) < MIN_SPORADIC_SALES or quantity <= 0 or series.discount[t] > 0:
                continue
            size_z = (quantity - size_median) / size_scale
            if size_z >= Z_THRESHOLD and quantity >= 2.0 * size_median:
                candidates.append(_Candidate(product, t, row_id, quantity, size_median, size_z, "SPIKE"))
            continue

        if (
            z >= Z_THRESHOLD
            and residual >= max(3.0, expected)
            and quantity >= 1.3 * p95
            and series.discount[t] <= 0
            and _poisson_tail(quantity, expected) < SPIKE_TAIL_PROBABILITY
        ):
            candidates.append(_Candidate(product, t, row_id, quantity, expected, z, "SPIKE"))
        elif z <= -Z_THRESHOLD and expected >= 3.0 and quantity <= 0.25 * expected:
            if t != previous_drop + 1:
                candidates.append(_Candidate(product, t, row_id, quantity, expected, z, "DROP"))
            previous_drop = t
    return candidates


def detect_anomalies(entries: list[tuple[DailySeries, ForecastResult]]) -> list[list[Anomaly]]:
    """Devuelve, para cada producto, las anomalías de los últimos 90 días (más recientes primero)."""
    rows: list[list[float]] = []
    candidates: list[_Candidate] = []
    for product, (series, fc) in enumerate(entries):
        candidates.extend(_scan_product(product, series, fc, rows))

    outliers: np.ndarray | None = None
    if candidates and len(rows) >= MIN_ROWS_FOR_FOREST:
        matrix = np.nan_to_num(np.asarray(rows, dtype=float), nan=0.0, posinf=20.0, neginf=-20.0)
        forest = IsolationForest(n_estimators=100, max_samples=min(256, len(matrix)), contamination="auto", random_state=42)
        forest.fit(matrix)
        outliers = forest.decision_function(matrix) < 0

    result: list[list[Anomaly]] = [[] for _ in entries]
    for c in candidates:
        by_forest = bool(outliers[c.row]) if outliers is not None else False
        strong = abs(c.z) >= (STRONG_Z if outliers is not None else 4.5)
        if not (by_forest or strong):
            continue
        series = entries[c.product][0]
        result[c.product].append(
            Anomaly(
                day=series.date_at(c.index),
                quantity=c.quantity,
                expected=max(0.0, c.expected),
                score=min(99.0, abs(c.z)),
                kind=c.kind,
                confirmed_by_forest=by_forest,
            )
        )
    for items in result:
        items.sort(key=lambda a: a.day, reverse=True)
        del items[MAX_PER_PRODUCT:]
    return result
