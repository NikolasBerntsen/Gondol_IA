"""Pronóstico de demanda diaria: Holt-Winters, Croston/SBA y media móvil ponderada, con intervalos."""

import logging
import math
import warnings
from dataclasses import dataclass

import numpy as np
import pandas as pd
from statsmodels.tsa.holtwinters import ExponentialSmoothing

from .patterns import MIN_HISTORY_DAYS, NO_MOVEMENT_DAYS, SeriesFeatures
from .series import DailySeries

log = logging.getLogger(__name__)

HOLT_WINTERS = "HOLT_WINTERS"
CROSTON_SBA = "CROSTON_SBA"
WMA = "WMA"
ZERO = "ZERO"

INTERVAL_Z = 1.2816
"""Intervalo de predicción del 80% (percentiles 10 y 90)."""
FORECAST_DAYS = 730
"""Largo del camino de demanda: alcanza para simular lotes que vencen dentro de dos años."""
HW_MIN_SALE_DAYS = 28
HW_MAX_HISTORY_DAYS = 182
HW_TOLERANCE = 1.1
"""Holt-Winters se descarta si su error reciente supera en más de 10% al de la media móvil."""
WMA_WINDOW_DAYS = 14
CROSTON_ALPHAS = (0.05, 0.1, 0.2, 0.3)
RESIDUAL_WINDOW_DAYS = 56
OUTLIER_SCALES = 6.0
"""Un día se acota antes de ajustar los modelos si supera su nivel local en más de 6 desvíos robustos."""
LOCAL_LEVEL_WINDOW = 15


@dataclass
class ForecastResult:
    method: str
    fitted: np.ndarray
    """Pronóstico a un paso dentro de la muestra (NaN donde no hay estimación)."""
    path: np.ndarray
    """Demanda esperada: path[0] corresponde a `as_of` (hoy), path[1] a mañana, etc."""
    sigma: float
    """Desvío estándar del error de pronóstico a un paso."""
    growth: float
    """Crecimiento relativo de la varianza del error por cada paso adicional del horizonte."""

    def interval(self, step_index: int) -> tuple[float, float]:
        """Intervalo del 80% para `path[step_index]`."""
        mean = float(self.path[step_index])
        spread = INTERVAL_Z * self.sigma * math.sqrt(1.0 + step_index * self.growth)
        return max(0.0, mean - spread), mean + spread


def recent_rmse(values: np.ndarray, fitted: np.ndarray, window: int = RESIDUAL_WINDOW_DAYS) -> float | None:
    """Error cuadrático medio de las últimas semanas, con los residuos extremos acotados (winsorizados).

    Un pico aislado (una venta mayorista) no debe inflar el stock de seguridad de todo el período: los
    residuos se recortan a la mediana ± 3,5 desvíos robustos (MAD) antes de promediar.
    """
    residuals = (values - fitted)[-window:]
    residuals = residuals[np.isfinite(residuals)]
    if len(residuals) < 3:
        return None
    median = float(np.median(residuals))
    spread = 1.4826 * float(np.median(np.abs(residuals - median)))
    if spread > 0:
        residuals = np.clip(residuals, median - 3.5 * spread, median + 3.5 * spread)
    return float(np.sqrt(np.mean(residuals**2)))


def _sigma(values: np.ndarray, fitted: np.ndarray, level: float) -> float:
    rmse = recent_rmse(values, fitted)
    if rmse is not None:
        return rmse
    if len(values) >= 2:
        return float(values.std(ddof=1))
    return math.sqrt(max(level, 0.0))


def _profile_for(series: DailySeries, features: SeriesFeatures, start_offset: int, length: int) -> np.ndarray:
    """Multiplicadores del perfil semanal para `length` días a partir de `series.start + start_offset`."""
    if not features.seasonal:
        return np.ones(length)
    weekdays = (np.arange(length) + start_offset + series.start.weekday()) % 7
    return features.profile[weekdays]


def zero_forecast(series: DailySeries) -> ForecastResult:
    return ForecastResult(ZERO, np.zeros(series.n), np.zeros(FORECAST_DAYS), 0.0, 0.0)


def _holt_winters(series: DailySeries) -> ForecastResult | None:
    y = series.values[-HW_MAX_HISTORY_DAYS:]
    offset = series.n - len(y)
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            model = ExponentialSmoothing(
                y,
                trend="add",
                damped_trend=True,
                seasonal="add",
                seasonal_periods=7,
                initialization_method="estimated",
            )
            fit = model.fit(optimized=True, use_brute=False)
            path = np.asarray(fit.forecast(FORECAST_DAYS), dtype=float)
            fitted_tail = np.asarray(fit.fittedvalues, dtype=float)
    except Exception as exc:  # statsmodels puede fallar con series degeneradas
        log.debug("Holt-Winters no pudo ajustar la serie: %s", exc)
        return None
    if not (np.all(np.isfinite(path)) and np.all(np.isfinite(fitted_tail))):
        return None
    recent_level = float(y[-28:].mean())
    guard = max(recent_level, 0.5)
    if path[:14].mean() > 4 * guard or path[:28].max() > 10 * guard:
        return None
    if recent_level >= 1.0 and path[:28].mean() < 0.25 * recent_level:
        return None
    fitted = np.full(series.n, np.nan)
    fitted[offset:] = np.maximum(fitted_tail, 0.0)
    alpha = float(fit.params.get("smoothing_level", 0.2) or 0.0)
    return ForecastResult(HOLT_WINTERS, fitted, np.maximum(path, 0.0), _sigma(series.values, fitted, recent_level), alpha**2)


def _croston_run(y: np.ndarray, alpha: float) -> tuple[np.ndarray, float]:
    fitted = np.full(len(y), np.nan)
    nonzero = np.flatnonzero(y > 0)
    first = int(nonzero[0])
    size = float(y[first])
    interval = float(nonzero[1] - nonzero[0]) if len(nonzero) >= 2 else float(first + 1)
    correction = 1.0 - alpha / 2.0
    periods_since = 1
    for t in range(first + 1, len(y)):
        fitted[t] = correction * size / interval
        if y[t] > 0:
            size = alpha * y[t] + (1 - alpha) * size
            interval = alpha * periods_since + (1 - alpha) * interval
            periods_since = 1
        else:
            periods_since += 1
    return fitted, correction * size / interval


def _croston_sba(series: DailySeries, features: SeriesFeatures) -> ForecastResult:
    """Croston con corrección de Syntetos-Boylan: estima por separado el tamaño y el intervalo entre ventas."""
    y = series.values
    best: tuple[float, np.ndarray, float, float] | None = None
    for alpha in CROSTON_ALPHAS:
        fitted, rate = _croston_run(y, alpha)
        mask = np.isfinite(fitted)
        mse = float(np.mean((y[mask] - fitted[mask]) ** 2)) if mask.any() else math.inf
        if best is None or mse < best[0]:
            best = (mse, fitted, rate, alpha)
    _, fitted, rate, alpha = best
    fitted = fitted * _profile_for(series, features, 0, series.n)
    path = rate * _profile_for(series, features, series.n, FORECAST_DAYS)
    return ForecastResult(CROSTON_SBA, fitted, path, _sigma(y, fitted, rate), alpha**2)


def _weighted_average(values: np.ndarray) -> float:
    weights = np.arange(1, len(values) + 1, dtype=float)
    return float(np.dot(values, weights) / weights.sum())


def _wma(series: DailySeries, features: SeriesFeatures, window: int) -> ForecastResult:
    """Media móvil ponderada (pesos lineales) sobre la serie desestacionalizada."""
    y = series.values
    n = series.n
    window = max(1, window)
    profile_hist = _profile_for(series, features, 0, n)
    deseasonalized = y / np.maximum(profile_hist, 0.1)
    fitted = np.full(n, np.nan)
    for t in range(1, n):
        lo = max(0, t - window)
        fitted[t] = _weighted_average(deseasonalized[lo:t]) * profile_hist[t]
    level = _weighted_average(deseasonalized[-window:]) if n else 0.0
    path = level * _profile_for(series, features, n, FORECAST_DAYS)
    return ForecastResult(WMA, fitted, np.maximum(path, 0.0), _sigma(y, fitted, level), 1.0 / window)


def _robust_spread(values: np.ndarray) -> float:
    return 1.4826 * float(np.median(np.abs(values - np.median(values))))


def clip_outliers(series: DailySeries, features: SeriesFeatures) -> np.ndarray:
    """Acota los picos extremos (una venta mayorista, un error de carga) antes de ajustar los modelos.

    Así un solo día con 1.000 u. en un producto que vende 3 por día no dispara el pronóstico ni el stock
    de seguridad (la anomalía se sigue detectando contra la serie original). En demanda esporádica se
    compara el tamaño de cada venta con el de las demás; en el resto, cada día con la mediana móvil de
    15 días (desestacionalizada), así un cambio de nivel sostenido no se recorta.
    """
    y = series.values
    if series.n < MIN_HISTORY_DAYS or features.sale_days < 5:
        return y
    if features.sporadic:
        sizes = y[y > 0]
        median = float(np.median(sizes))
        cap = median + OUTLIER_SCALES * max(_robust_spread(sizes), math.sqrt(max(median, 1.0)))
        return np.minimum(y, cap)
    multipliers = np.maximum(features.profile[series.weekdays], 0.3) if features.seasonal else np.ones(series.n)
    base = y / multipliers
    local = pd.Series(base).rolling(LOCAL_LEVEL_WINDOW, center=True, min_periods=1).median().to_numpy()
    spread = _robust_spread(base - local)
    cap = local + OUTLIER_SCALES * np.maximum(spread, np.sqrt(np.maximum(local, 1.0)))
    return np.minimum(y, cap * multipliers)


def forecast(series: DailySeries, features: SeriesFeatures) -> ForecastResult:
    """Elige el método según la historia disponible y la forma de la demanda (sobre la serie con picos acotados)."""
    if series.n == 0 or features.sale_days == 0:
        return zero_forecast(series)
    clipped = clip_outliers(series, features)
    if not np.array_equal(clipped, series.values):
        series = DailySeries(series.start, series.as_of, clipped, series.discount, series.sold_today)
    if features.days_since_last_sale is not None and features.days_since_last_sale > NO_MOVEMENT_DAYS:
        return _wma(series, features, window=28)
    if features.sporadic and features.sale_days >= 3:
        return _croston_sba(series, features)
    wma = _wma(series, features, window=min(WMA_WINDOW_DAYS, series.n))
    if features.sale_days >= HW_MIN_SALE_DAYS and series.n >= HW_MIN_SALE_DAYS:
        hw = _holt_winters(series)
        if hw is not None:
            hw_error = recent_rmse(series.values, hw.fitted)
            wma_error = recent_rmse(series.values, wma.fitted)
            if hw_error is None or wma_error is None or hw_error <= HW_TOLERANCE * wma_error:
                return hw
    return wma
