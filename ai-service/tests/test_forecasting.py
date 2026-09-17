import math

import numpy as np
import pytest

from app.analysis.forecasting import CROSTON_SBA, HOLT_WINTERS, WMA, ZERO, clip_outliers, forecast
from app.analysis.patterns import compute_features
from tests import synthetic


def run(values):
    series = synthetic.to_series(values)
    return series, forecast(series, compute_features(series))


def test_stable_series_forecast_stays_near_the_level(rng):
    _, fc = run(synthetic.stable(rng, level=8))
    assert fc.method in (HOLT_WINTERS, WMA)
    assert np.mean(fc.path[1:29]) == pytest.approx(8.0, rel=0.15)
    assert 1.5 < fc.sigma < 4.5


def test_holt_winters_follows_the_weekly_shape(rng):
    series, fc = run(synthetic.weekly_seasonal(rng, level=6))
    assert fc.method == HOLT_WINTERS
    weekdays = (np.arange(1, 29) + series.as_of.weekday()) % 7
    path = fc.path[1:29]
    assert path[weekdays >= 5].mean() > 1.5 * path[weekdays <= 1].mean()


def test_intermittent_demand_uses_croston_sba(rng):
    values = synthetic.intermittent(rng)
    _, fc = run(values)
    assert fc.method == CROSTON_SBA
    assert fc.path[1] == pytest.approx(values.mean(), rel=0.4)


def test_short_history_uses_weighted_moving_average(rng):
    _, fc = run(synthetic.stable(rng, days=20, level=4))
    assert fc.method == WMA
    assert fc.path[1] == pytest.approx(4.0, rel=0.35)


def test_weighted_moving_average_reacts_to_recent_days():
    values = np.array([2] * 10 + [8] * 4)
    _, fc = run(values)
    assert fc.method == WMA
    assert fc.path[1] > values.mean()


def test_no_sales_gives_zero_forecast():
    _, fc = run(np.zeros(60, dtype=int))
    assert fc.method == ZERO
    assert not fc.path.any()
    assert fc.interval(5) == (0.0, 0.0)


@pytest.mark.parametrize("generator", [synthetic.declining, synthetic.growing, synthetic.dormant, synthetic.slow])
def test_forecast_is_finite_and_non_negative(generator, rng):
    _, fc = run(generator(rng))
    assert np.all(np.isfinite(fc.path)) and np.all(fc.path >= 0)
    assert math.isfinite(fc.sigma) and fc.sigma >= 0


def test_prediction_interval_contains_the_forecast_and_widens(rng):
    _, fc = run(synthetic.stable(rng, level=6))
    lo1, hi1 = fc.interval(1)
    lo14, hi14 = fc.interval(14)
    assert lo1 <= fc.path[1] <= hi1
    assert lo14 <= fc.path[14] <= hi14
    assert hi14 - lo14 >= hi1 - lo1
    assert lo1 >= 0


def test_an_extreme_spike_does_not_inflate_the_forecast(rng):
    values = synthetic.stable(rng, level=3)
    values[-3] = 5000
    _, fc = run(values)
    assert np.mean(fc.path[1:15]) == pytest.approx(3.0, rel=0.35)
    assert fc.sigma < 5


def test_a_sustained_level_change_is_not_clipped(rng):
    values = np.concatenate([synthetic.stable(rng, days=150, level=4), synthetic.stable(rng, days=30, level=40)])
    _, fc = run(values)
    assert np.mean(fc.path[1:8]) > 25


def test_intermittent_sale_sizes_are_kept(rng):
    values = synthetic.intermittent(rng)
    series = synthetic.to_series(values)
    assert np.array_equal(clip_outliers(series, compute_features(series)), series.values)
