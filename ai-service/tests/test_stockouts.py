"""Días sin stock (demanda censurada) y coherencia de las cifras de una misma ficha de producto."""

from datetime import timedelta

import numpy as np
import pytest

from app.analysis.patterns import (
    ALTA_ROTACION_ESTABLE,
    EN_CRECIMIENTO,
    classify,
    compute_features,
    reported_trend_pct,
)
from app.analysis.pipeline import analyze
from app.analysis.recommendations import REORDER
from app.analysis.series import STOCKOUT_MAX_RUN_DAYS, impute_stockouts
from app.schemas import AnalyzeRequest
from tests import synthetic
from tests.synthetic import AS_OF


def days_before(first: int, last: int) -> list:
    """Días `AS_OF - first` .. `AS_OF - last` (first >= last >= 1)."""
    return [AS_OF - timedelta(days=offset) for offset in range(last, first + 1)]


def run(products):
    return analyze(AnalyzeRequest.model_validate(synthetic.analyze_request(products)))


def out_of_stock_milk(seed: int, stockout: bool, days: int = 6) -> dict:
    """Leche que vendía ~7 u/día y lleva `days` días sin stock (el proveedor dejó de entregar)."""
    values = np.random.default_rng(seed).poisson(7.0, 180)
    values[-days:] = 0
    item = synthetic.product(122, values, name="Leche descremada 1 L", category="Lácteos", stock=0, min_stock=8, lead_time_days=2)
    if stockout:
        item["stockoutDays"] = [day.isoformat() for day in days_before(days, 1)]
    return item


# ---------------------------------------------------------------------------
# Completar los días sin stock
# ---------------------------------------------------------------------------
def test_trailing_stockout_days_are_filled_with_the_previous_demand():
    values = np.full(120, 7.0)
    values[-6:] = 0
    series, censoring = impute_stockouts(synthetic.to_series(values), days_before(6, 1))
    assert censoring.days == 6 and censoring.runs == 1
    assert censoring.since == AS_OF - timedelta(days=6)
    assert series.values[-6:] == pytest.approx([7.0] * 6)
    assert series.n == 120


def test_interior_stockout_uses_the_weekday_profile_before_it():
    weekday_profile = np.array([1, 1, 1, 1, 1, 2, 2], dtype=float) * 4
    weekdays = (np.arange(140) + (AS_OF - timedelta(days=140)).weekday()) % 7
    values = weekday_profile[weekdays]
    values[-20:-10] = 0
    series, censoring = impute_stockouts(synthetic.to_series(values), days_before(20, 11))
    assert censoring.days == 10 and censoring.since is None
    filled = series.values[-20:-10]
    weekend = weekdays[-20:-10] >= 5
    # Los sábados y domingos se completan con más venta que los días hábiles (perfil suavizado hacia 1).
    assert filled[weekend].min() > filled[~weekend].max()
    assert filled.mean() == pytest.approx(values[-48:-20].mean(), rel=0.15)


def test_only_zero_sale_days_with_history_before_are_filled():
    values = np.full(60, 5.0)
    values[-3:] = 0
    values[2] = 0
    days = days_before(3, 1) + [AS_OF - timedelta(days=58)] + [AS_OF - timedelta(days=10)]
    series, censoring = impute_stockouts(synthetic.to_series(values), days)
    # Día 2 (sin historia previa) y el día 10 (tuvo ventas) quedan igual; solo se completan los 3 últimos.
    assert censoring.days == 3
    assert series.values[2] == 0
    assert series.values[-10] == 5


def test_very_long_stockouts_are_not_filled():
    days = STOCKOUT_MAX_RUN_DAYS + 5
    values = np.full(180, 4.0)
    values[-days:] = 0
    series, censoring = impute_stockouts(synthetic.to_series(values), days_before(days, 1))
    assert censoring.days == 0
    assert series.values[-1] == 0


def test_no_stockout_days_leaves_the_series_untouched():
    original = synthetic.to_series(np.full(30, 3.0))
    series, censoring = impute_stockouts(original, [])
    assert series is original and censoring.days == 0


# ---------------------------------------------------------------------------
# Ficha coherente: patrón, venta diaria, tendencia y recomendación
# ---------------------------------------------------------------------------
@pytest.mark.parametrize("seed", [1, 3])
def test_out_of_stock_product_keeps_its_real_demand_in_every_figure(seed):
    censored = run([out_of_stock_milk(seed, stockout=True)])
    insight = censored.products[0]
    assert insight.pattern == ALTA_ROTACION_ESTABLE
    assert insight.pattern_description.startswith("Venta estable de")
    assert insight.trend_pct == pytest.approx(0.0, abs=10)
    assert insight.avg_daily_sales == pytest.approx(7.0, abs=0.8)
    assert not insight.anomalies

    reorder = next(r for r in censored.recommendations if r.type == REORDER)
    assert reorder.explanation.startswith("No hay stock vendible desde el 11/09")
    assert "tendencia" not in reorder.explanation
    rate = float(reorder.explanation.split("la demanda es de ")[1].split(" u/día")[0].replace(",", "."))
    assert rate == pytest.approx(insight.avg_daily_sales, abs=1.0)
    assert any("sin stock algunos días" in note for note in censored.summary.model_notes)


def test_without_stockout_days_a_trend_never_sits_next_to_venta_estable():
    """Sin el dato del backend los ceros cuentan como venta, pero la ficha no se contradice ("estable" y -80%)."""
    insight = run([out_of_stock_milk(1, stockout=False)]).products[0]
    assert insight.trend_pct < -10
    assert "estable" not in insight.pattern_description
    assert "en baja" in insight.pattern_description


def test_restocked_product_is_not_classified_as_declining():
    values = np.random.default_rng(7).poisson(7.0, 180)
    values[-13:-3] = 0
    plain = synthetic.product(9, values, stock=40)
    censored = dict(plain, stockoutDays=[day.isoformat() for day in days_before(13, 4)])
    response = run([plain, dict(censored, productId=10)])
    before, after = response.products
    assert before.trend_pct < -10
    assert after.pattern == ALTA_ROTACION_ESTABLE
    assert after.trend_pct == pytest.approx(0.0, abs=10)


def test_growth_pattern_and_trend_report_the_same_change():
    """Un alza que ya se estabilizó: la tendencia de 8 semanas no es significativa, pero la ficha informa la del patrón."""
    level = np.concatenate([np.full(64, 2.4), np.linspace(2.4, 3.9, 60), np.full(56, 3.8)])
    checked = 0
    for seed in range(60):
        features = compute_features(synthetic.to_series(np.random.default_rng(seed).poisson(level)))
        pattern = classify(features)
        if pattern.pattern != EN_CRECIMIENTO or features.trend_pct != 0:
            continue
        trend = reported_trend_pct(pattern.pattern, features)
        assert trend > 0
        assert f"({round(trend)}%)" in pattern.description.replace("+", "")
        checked += 1
    assert checked >= 2
