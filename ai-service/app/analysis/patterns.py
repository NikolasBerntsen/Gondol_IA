"""Features de la serie, clasificación del patrón de ventas por reglas y refinamiento con KMeans."""

import math
import warnings
from collections import Counter
from dataclasses import dataclass

import numpy as np
from scipy import stats
from sklearn.cluster import KMeans
from sklearn.preprocessing import StandardScaler

from .formatting import WEEKDAY_NAMES, fmt_num, fmt_pct
from .series import DailySeries, coefficient_of_variation, weekday_means, weekly_totals

MIN_HISTORY_DAYS = 14
TREND_WINDOW_DAYS = 56
LONG_TREND_WINDOW_DAYS = 112
PROFILE_LOOKBACK_DAYS = 84
AVG_SALES_WINDOW_DAYS = 56
NO_MOVEMENT_DAYS = 30
SPORADIC_ADI = 1.32
"""Umbral de Syntetos-Boylan: intervalo medio entre ventas a partir del cual la demanda es intermitente."""
INTERMITTENT_ADI = 2.0
"""Para la etiqueta de negocio "intermitente" se exige vender como mucho la mitad de los días."""
HIGH_ROTATION_MIN_DAILY = 1.0
LUMPY_MIN_SIZE = 2.0
TREND_MIN_LEVEL = 0.7
TREND_SIGNIFICANCE = 0.01

ALTA_ROTACION_ESTABLE = "ALTA_ROTACION_ESTABLE"
ESTACIONAL_SEMANAL = "ESTACIONAL_SEMANAL"
INTERMITENTE = "INTERMITENTE"
EN_CRECIMIENTO = "EN_CRECIMIENTO"
EN_DECLIVE = "EN_DECLIVE"
BAJA_ROTACION = "BAJA_ROTACION"
SIN_MOVIMIENTO = "SIN_MOVIMIENTO"
DATOS_INSUFICIENTES = "DATOS_INSUFICIENTES"

ALL_PATTERNS = [
    ALTA_ROTACION_ESTABLE,
    ESTACIONAL_SEMANAL,
    INTERMITENTE,
    EN_CRECIMIENTO,
    EN_DECLIVE,
    BAJA_ROTACION,
    SIN_MOVIMIENTO,
    DATOS_INSUFICIENTES,
]
_REASSIGNABLE = {ALTA_ROTACION_ESTABLE, BAJA_ROTACION, INTERMITENTE, ESTACIONAL_SEMANAL}


@dataclass
class SeriesFeatures:
    n: int
    total: float
    sale_days: int
    mean_recent: float
    mean_all: float
    cv_daily: float
    cv_weekly: float
    zero_share: float
    adi: float
    avg_size: float
    cv2_size: float
    trend_pct: float
    trend_pvalue: float
    long_trend_pct: float
    long_trend_pvalue: float
    level_before: float
    level_after: float
    profile: np.ndarray
    weekend_ratio: float
    seasonal_pvalue: float
    seasonal_amplitude: float
    max_weekday_sell_rate: float
    days_since_last_sale: int | None

    @property
    def seasonal(self) -> bool:
        return (
            self.n >= 21
            and self.seasonal_pvalue < 0.01
            and (self.seasonal_amplitude >= 1.3 or abs(self.weekend_ratio - 1.0) >= 0.2)
        )

    @property
    def zeros_explained_by_weekday(self) -> bool:
        """Días sin venta concentrados en ciertos días de la semana (p. ej. solo vende los sábados)."""
        return self.seasonal and self.max_weekday_sell_rate >= 0.8

    @property
    def sporadic(self) -> bool:
        return self.sale_days > 0 and self.adi >= SPORADIC_ADI and not self.zeros_explained_by_weekday

    def vector(self) -> list[float]:
        """Features para KMeans: media, CV, tendencia, ratio de fin de semana y % de días sin venta."""
        return [
            math.log1p(self.mean_recent),
            min(self.cv_weekly if self.n >= 28 else self.cv_daily, 3.0),
            max(-1.0, min(1.0, self.trend_pct / 100.0)),
            math.log(max(0.2, min(5.0, self.weekend_ratio))),
            self.zero_share,
        ]


@dataclass
class PatternResult:
    pattern: str
    description: str
    strength: float
    """Qué tan clara es la evidencia (0..1). Los casos débiles pueden reasignarse con KMeans."""


def _linear_trend(values: np.ndarray) -> tuple[float, float, float]:
    """(pendiente, p-valor, media) de una regresión lineal de las ventas contra el tiempo."""
    if len(values) < 7:
        return 0.0, 1.0, float(values.mean()) if len(values) else 0.0
    mean = float(values.mean())
    if np.allclose(values, values[0]):
        return 0.0, 1.0, mean
    result = stats.linregress(np.arange(len(values), dtype=float), values)
    pvalue = float(result.pvalue) if math.isfinite(result.pvalue) else 1.0
    return float(result.slope), pvalue, mean


def _clip_spikes(values: np.ndarray) -> np.ndarray:
    """Acota picos aislados (mediana + 5 desvíos robustos) para que una venta extraordinaria no fabrique una tendencia."""
    if len(values) < MIN_HISTORY_DAYS:
        return values
    median = float(np.median(values))
    scale = max(1.4826 * float(np.median(np.abs(values - median))), math.sqrt(max(median, 1.0)))
    return np.minimum(values, median + 5.0 * scale)


def _trend_pct(slope: float, mean: float, window: int) -> float:
    """Variación de la recta de regresión a lo largo de la ventana, relativa al promedio de la ventana."""
    if mean <= 0:
        return 0.0
    return float(max(-100.0, min(300.0, slope * (window - 1) / mean * 100.0)))


def compute_features(series: DailySeries) -> SeriesFeatures:
    y = series.values
    n = series.n
    total = float(y.sum()) if n else 0.0
    sale_mask = y > 0
    sale_days = int(sale_mask.sum())

    mean_all = total / n if n else 0.0
    mean_recent = float(series.tail(AVG_SALES_WINDOW_DAYS).mean()) if n else 0.0
    cv_daily = coefficient_of_variation(y[-PROFILE_LOOKBACK_DAYS:]) if n else 0.0
    weeks = weekly_totals(y, max_weeks=12)
    cv_weekly = coefficient_of_variation(weeks) if len(weeks) >= 4 else cv_daily
    zero_share = 1.0 - sale_days / n if n else 1.0
    adi = n / sale_days if sale_days else float(max(n, 1))
    sizes = y[sale_mask]
    avg_size = float(sizes.mean()) if sale_days else 0.0
    cv2_size = float((sizes.std() / sizes.mean()) ** 2) if sale_days >= 2 and sizes.mean() > 0 else 0.0

    recent = y[-PROFILE_LOOKBACK_DAYS:]
    recent_weekdays = series.weekdays[-PROFILE_LOOKBACK_DAYS:]
    recent_mean = float(recent.mean()) if len(recent) else 0.0
    if n >= MIN_HISTORY_DAYS and recent_mean > 0:
        profile = weekday_means(recent, recent_weekdays) / recent_mean
        profile = profile * 7.0 / profile.sum()
        weekday_mean = profile[:5].mean()
        weekend_ratio = float(profile[5:].mean() / weekday_mean) if weekday_mean > 0 else 5.0
        seasonal_amplitude = float(profile.max() / max(profile.min(), 0.1))
        groups = [recent[recent_weekdays == d] for d in range(7)]
        groups = [g for g in groups if len(g) >= 2]
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("ignore")
                seasonal_p = float(stats.kruskal(*groups).pvalue) if len(groups) == 7 else 1.0
        except ValueError:
            seasonal_p = 1.0
        if not math.isfinite(seasonal_p):
            seasonal_p = 1.0
        sell_rates = [float((recent[recent_weekdays == d] > 0).mean()) for d in range(7) if (recent_weekdays == d).any()]
        max_sell_rate = max(sell_rates) if sell_rates else 0.0
    else:
        profile = np.ones(7)
        weekend_ratio, seasonal_amplitude, seasonal_p, max_sell_rate = 1.0, 1.0, 1.0, 0.0

    # La tendencia se mide sobre la serie desestacionalizada: el perfil semanal agrega varianza que
    # esconde pendientes reales (p. ej. un snack que crece y además vende el doble los sábados).
    trend_values = _clip_spikes(y / np.maximum(profile[series.weekdays], 0.3) if n and seasonal_p < 0.05 else y)
    window = min(TREND_WINDOW_DAYS, (n // 7) * 7)
    if window >= 28:
        slope, trend_p, window_mean = _linear_trend(trend_values[-window:])
        raw_trend_pct = _trend_pct(slope, window_mean, window)
    else:
        raw_trend_pct, trend_p = 0.0, 1.0

    long_window = min(LONG_TREND_WINDOW_DAYS, (n // 7) * 7)
    if long_window >= 42:
        slope_l, long_p, long_mean = _linear_trend(trend_values[-long_window:])
        long_trend_pct = _trend_pct(slope_l, long_mean, long_window)
        block = y[-long_window:]
        level_before = float(block[:28].mean())
        level_after = float(block[-28:].mean())
    else:
        long_trend_pct, long_p = raw_trend_pct, trend_p
        half = n // 2
        level_before = float(y[:half].mean()) if half else 0.0
        level_after = float(y[half:].mean()) if n - half else 0.0

    # Con ruido diario la pendiente de 8 semanas varía mucho: se informa si es significativa por sí misma
    # o si la regresión de 16 semanas (más robusta) confirma la misma dirección.
    confirmed = long_p < 0.01 and raw_trend_pct * long_trend_pct > 0
    trend_pct = raw_trend_pct if trend_p < TREND_SIGNIFICANCE or confirmed else 0.0

    days_since_last_sale = n - int(np.flatnonzero(sale_mask)[-1]) if sale_days else None

    return SeriesFeatures(
        n=n,
        total=total,
        sale_days=sale_days,
        mean_recent=mean_recent,
        mean_all=mean_all,
        cv_daily=cv_daily,
        cv_weekly=cv_weekly,
        zero_share=zero_share,
        adi=adi,
        avg_size=avg_size,
        cv2_size=cv2_size,
        trend_pct=trend_pct,
        trend_pvalue=trend_p,
        long_trend_pct=long_trend_pct,
        long_trend_pvalue=long_p,
        level_before=level_before,
        level_after=level_after,
        profile=profile,
        weekend_ratio=weekend_ratio,
        seasonal_pvalue=seasonal_p,
        seasonal_amplitude=seasonal_amplitude,
        max_weekday_sell_rate=max_sell_rate,
        days_since_last_sale=days_since_last_sale,
    )


# ---------------------------------------------------------------------------
# Reglas
# ---------------------------------------------------------------------------
def trend_direction(f: SeriesFeatures) -> int:
    """+1 crecimiento, -1 declive, 0 sin tendencia clara.

    Exige tres evidencias a la vez: pendiente significativa (p < 0,01), variación relevante de la
    recta y cambio real de nivel entre el primer y el último mes de la ventana.
    """
    if f.n < 28 or f.mean_all <= 0 or max(f.level_before, f.level_after) < TREND_MIN_LEVEL:
        return 0
    ratio = f.level_after / f.level_before if f.level_before > 0 else 5.0
    # En demanda esporádica las ventas se agrupan al azar: se pide evidencia más fuerte.
    max_p, up_ratio, down_ratio = (0.001, 1.5, 0.67) if f.sporadic else (0.01, 1.25, 0.8)
    if f.n >= 42:
        if f.long_trend_pvalue < max_p and f.long_trend_pct >= 25 and ratio >= up_ratio and f.trend_pct >= -10:
            return 1
        if f.long_trend_pvalue < max_p and f.long_trend_pct <= -20 and ratio <= down_ratio and f.trend_pct <= 10:
            return -1
        return 0
    if f.trend_pvalue < max_p and f.trend_pct >= 30 and ratio >= up_ratio:
        return 1
    if f.trend_pvalue < max_p and f.trend_pct <= -25 and ratio <= down_ratio:
        return -1
    return 0


def describe(pattern: str, f: SeriesFeatures) -> str:
    if pattern == DATOS_INSUFICIENTES:
        if f.n == 0:
            return "Todavía no hay historial de ventas para analizar"
        return (
            f"Solo hay {f.n} {'día' if f.n == 1 else 'días'} de historial: "
            f"se necesitan al menos {MIN_HISTORY_DAYS} para detectar patrones"
        )
    if pattern == SIN_MOVIMIENTO:
        if f.days_since_last_sale is None:
            return f"Sin ventas registradas en los últimos {f.n} días"
        return f"Sin ventas en los últimos {f.days_since_last_sale} días"
    if pattern in (EN_CRECIMIENTO, EN_DECLIVE):
        before, after = f.level_before, f.level_after
        verb = "en alza" if pattern == EN_CRECIMIENTO else "en baja"
        text = f"Ventas {verb}: pasó de {fmt_num(before)} a {fmt_num(after)} u/día"
        if before < 0.05:
            return text
        return f"{text} ({fmt_pct((after / before - 1.0) * 100.0, signed=True)})"
    if pattern == INTERMITENTE:
        share = (1.0 - f.zero_share) * 100.0
        return f"Ventas esporádicas: vende en el {fmt_pct(share)} de los días, {fmt_num(f.avg_size)} u. por vez"
    if pattern == BAJA_ROTACION:
        return f"Baja rotación: {fmt_num(f.mean_recent * 7)} u. por semana en promedio"
    if pattern == ESTACIONAL_SEMANAL:
        if f.weekend_ratio >= 1.2:
            return f"Vende {fmt_pct((f.weekend_ratio - 1.0) * 100.0)} más los fines de semana"
        if f.weekend_ratio <= 0.8:
            return f"Vende {fmt_pct((1.0 - f.weekend_ratio) * 100.0)} menos los fines de semana"
        best = int(np.argmax(f.profile))
        return f"Su mejor día es el {WEEKDAY_NAMES[best]} ({fmt_pct((f.profile[best] - 1.0) * 100.0, signed=True)} sobre el promedio)"
    if f.cv_weekly < 0.5:
        return f"Venta estable de {fmt_num(f.mean_recent)} u/día"
    return f"Rotación alta con variaciones irregulares ({fmt_num(f.mean_recent)} u/día)"


def classify(f: SeriesFeatures) -> PatternResult:
    """Clasificación interpretable por reglas, en orden de prioridad."""

    def result(pattern: str, strength: float) -> PatternResult:
        return PatternResult(pattern, describe(pattern, f), strength)

    if f.n < MIN_HISTORY_DAYS:
        return result(DATOS_INSUFICIENTES, 1.0)
    if f.days_since_last_sale is None or f.days_since_last_sale > NO_MOVEMENT_DAYS:
        return result(SIN_MOVIMIENTO, 1.0)

    direction = trend_direction(f)
    if direction != 0:
        strength = max(0.6, min(1.0, abs(f.long_trend_pct) / 50.0))
        return result(EN_CRECIMIENTO if direction > 0 else EN_DECLIVE, strength)

    if f.sporadic:
        if f.mean_recent < HIGH_ROTATION_MIN_DAILY and f.avg_size < LUMPY_MIN_SIZE:
            return result(BAJA_ROTACION, 0.9 if f.adi >= INTERMITTENT_ADI else 0.6)
        if f.adi >= INTERMITTENT_ADI:
            return result(INTERMITENTE, 0.9 if f.adi >= 3 else 0.7)

    if f.seasonal:
        strong = f.seasonal_pvalue < 0.001 and f.seasonal_amplitude >= 1.5
        return result(ESTACIONAL_SEMANAL, 0.9 if strong else 0.65)

    if f.mean_recent >= HIGH_ROTATION_MIN_DAILY:
        if f.cv_weekly < 0.5:
            return result(ALTA_ROTACION_ESTABLE, 0.85 if f.cv_weekly < 0.35 else 0.6)
        return result(ALTA_ROTACION_ESTABLE, 0.35)
    return result(BAJA_ROTACION, 0.5)


def _consistent(pattern: str, f: SeriesFeatures) -> bool:
    """Evita reasignaciones que contradigan los datos del producto."""
    if pattern == INTERMITENTE:
        return f.zero_share >= 0.3
    if pattern == ESTACIONAL_SEMANAL:
        return f.seasonal_amplitude >= 1.2 and f.seasonal_pvalue < 0.1
    if pattern == ALTA_ROTACION_ESTABLE:
        return f.mean_recent >= 0.7
    if pattern == BAJA_ROTACION:
        return f.mean_recent < 1.5
    return False


@dataclass
class ClusterRefinement:
    clusters: int
    reassigned: int


def refine_with_clusters(features: list[SeriesFeatures], results: list[PatternResult]) -> ClusterRefinement:
    """Agrupa los productos con KMeans y reclasifica los casos dudosos según su grupo.

    Las reglas deciden los casos claros. Un producto con evidencia débil adopta el patrón dominante
    entre los productos "claros" de su mismo grupo, siempre que sea consistente con sus propias features.
    """
    candidates = [i for i, r in enumerate(results) if r.pattern not in (DATOS_INSUFICIENTES, SIN_MOVIMIENTO)]
    if len(candidates) < 8:
        return ClusterRefinement(clusters=0, reassigned=0)

    matrix = np.nan_to_num(np.array([features[i].vector() for i in candidates], dtype=float), nan=0.0, posinf=0.0, neginf=0.0)
    scaled = StandardScaler().fit_transform(matrix)
    k = int(max(2, min(6, round(math.sqrt(len(candidates) / 2)))))
    k = min(k, len({tuple(row) for row in np.round(scaled, 6)}))
    if k < 2:
        return ClusterRefinement(clusters=0, reassigned=0)
    labels = KMeans(n_clusters=k, n_init=5, random_state=42).fit_predict(scaled)

    reassigned = 0
    for cluster in range(k):
        members = [candidates[j] for j in range(len(candidates)) if labels[j] == cluster]
        strong = [results[i].pattern for i in members if results[i].strength >= 0.6]
        if len(strong) < 3:
            continue
        dominant, count = Counter(strong).most_common(1)[0]
        if count / len(strong) < 0.6 or dominant not in _REASSIGNABLE:
            continue
        for i in members:
            current = results[i]
            if current.strength >= 0.5 or current.pattern == dominant or current.pattern not in _REASSIGNABLE:
                continue
            if not _consistent(dominant, features[i]):
                continue
            results[i] = PatternResult(dominant, describe(dominant, features[i]), 0.55)
            reassigned += 1
    return ClusterRefinement(clusters=k, reassigned=reassigned)
