from datetime import timedelta

import numpy as np
import pytest

from app.analysis.abcxyz import classify_abc, classify_xyz
from app.analysis.patterns import (
    ALTA_ROTACION_ESTABLE,
    BAJA_ROTACION,
    DATOS_INSUFICIENTES,
    EN_CRECIMIENTO,
    EN_DECLIVE,
    ESTACIONAL_SEMANAL,
    INTERMITENTE,
    SIN_MOVIMIENTO,
    PatternResult,
    classify,
    compute_features,
    refine_with_clusters,
)
from app.analysis.series import build_daily_series
from tests import synthetic

PATTERN_GENERATORS = [
    (ALTA_ROTACION_ESTABLE, synthetic.stable),
    (ESTACIONAL_SEMANAL, synthetic.weekly_seasonal),
    (INTERMITENTE, synthetic.intermittent),
    (EN_CRECIMIENTO, synthetic.growing),
    (EN_DECLIVE, synthetic.declining),
    (BAJA_ROTACION, synthetic.slow),
    (SIN_MOVIMIENTO, synthetic.dormant),
    (DATOS_INSUFICIENTES, synthetic.short_history),
]


def features_for(values):
    return compute_features(synthetic.to_series(values))


@pytest.mark.parametrize(("expected", "generator"), PATTERN_GENERATORS, ids=[p for p, _ in PATTERN_GENERATORS])
def test_classifier_recognizes_each_sales_pattern(expected, generator):
    seeds = range(20)
    hits = sum(classify(features_for(generator(np.random.default_rng(seed)))).pattern == expected for seed in seeds)
    assert hits >= 18, f"{expected}: reconocido en {hits}/20 series"


def test_weekly_profile_and_description_for_weekend_seller(rng):
    features = features_for(synthetic.weekly_seasonal(rng))
    result = classify(features)
    assert result.pattern == ESTACIONAL_SEMANAL
    assert features.profile.shape == (7,)
    assert features.profile.sum() == pytest.approx(7.0)
    assert features.profile[5] > 1.5 * features.profile[0]
    assert features.weekend_ratio > 1.6
    assert "más los fines de semana" in result.description


def test_trend_sign_matches_direction(rng):
    assert features_for(synthetic.growing(rng)).trend_pct > 10
    assert features_for(synthetic.declining(rng)).trend_pct < -10


def test_non_significant_trend_is_reported_as_zero():
    trends = [features_for(synthetic.stable(np.random.default_rng(seed))).trend_pct for seed in range(20)]
    assert sum(trend == 0 for trend in trends) >= 17
    assert all(abs(trend) < 60 for trend in trends)


def test_growth_is_detected_even_with_a_strong_weekly_profile():
    hits = 0
    for seed in range(10):
        rng = np.random.default_rng(seed)
        weekdays = np.array([d.weekday() for d in synthetic._dates(180)])
        values = rng.poisson(np.linspace(2.5, 6.5, 180) * synthetic.WEEKEND_PROFILE[weekdays])
        features = features_for(values)
        hits += classify(features).pattern == EN_CRECIMIENTO and features.trend_pct > 0
    assert hits >= 8


def test_product_selling_most_days_is_not_labeled_intermittent():
    for seed in range(10):
        result = classify(features_for(np.random.default_rng(seed).poisson(1.3, 180)))
        assert result.pattern in (ALTA_ROTACION_ESTABLE, BAJA_ROTACION)


def test_growth_description_uses_es_ar_numbers(rng):
    result = classify(features_for(synthetic.growing(rng)))
    assert result.pattern == EN_CRECIMIENTO
    assert result.description.startswith("Ventas en alza: pasó de ")
    assert "," in result.description and "u/día" in result.description


def test_no_history_and_no_sales():
    empty = compute_features(build_daily_series([], synthetic.AS_OF, None))
    assert classify(empty).pattern == DATOS_INSUFICIENTES
    assert classify(empty).description == "Todavía no hay historial de ventas para analizar"

    silent = compute_features(build_daily_series([], synthetic.AS_OF, synthetic.AS_OF - timedelta(days=90)))
    result = classify(silent)
    assert result.pattern == SIN_MOVIMIENTO
    assert result.description == "Sin ventas registradas en los últimos 90 días"


def test_kmeans_reassigns_weak_cases_to_the_dominant_pattern_of_their_cluster():
    features, results = [], []
    for seed in range(10):
        f = features_for(synthetic.weekly_seasonal(np.random.default_rng(seed)))
        features.append(f)
        results.append(classify(f))
    for seed in range(10, 20):
        f = features_for(synthetic.intermittent(np.random.default_rng(seed)))
        features.append(f)
        results.append(classify(f))
    doubtful = features_for(synthetic.weekly_seasonal(np.random.default_rng(99)))
    features.append(doubtful)
    results.append(PatternResult(ALTA_ROTACION_ESTABLE, "dudoso", 0.35))

    refinement = refine_with_clusters(features, results)

    assert refinement.clusters >= 2
    assert refinement.reassigned >= 1
    assert results[-1].pattern == ESTACIONAL_SEMANAL
    assert all(r.pattern == ESTACIONAL_SEMANAL for r in results[:10])


def test_kmeans_needs_enough_products():
    features = [features_for(synthetic.stable(np.random.default_rng(seed))) for seed in range(5)]
    results = [classify(f) for f in features]
    assert refine_with_clusters(features, results).clusters == 0


def test_abc_by_revenue_share():
    assert classify_abc([1000, 500, 300, 100, 50, 50]) == ["A", "A", "A", "B", "C", "C"]
    assert classify_abc([0, 0]) == ["C", "C"]
    assert classify_abc([0, 900, 100]) == ["C", "A", "B"]


def test_xyz_by_coefficient_of_variation(rng):
    assert classify_xyz(features_for(synthetic.stable(rng))) == "X"
    assert classify_xyz(features_for(synthetic.intermittent(rng, probability=0.05, size=6))) == "Z"
    assert classify_xyz(features_for(np.zeros(60))) == "Z"
