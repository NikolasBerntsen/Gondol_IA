import numpy as np

from app.analysis.anomalies import detect_anomalies
from app.analysis.forecasting import forecast
from app.analysis.patterns import compute_features
from tests import synthetic


def scan(series_values: list[np.ndarray]):
    entries = []
    for values in series_values:
        series = synthetic.to_series(values)
        entries.append((series, forecast(series, compute_features(series))))
    return entries, detect_anomalies(entries)


def false_positives(generator, count: int = 60) -> int:
    rng = np.random.default_rng(21)
    _, found = scan([generator(rng) for _ in range(count)])
    return sum(len(items) for items in found)


def test_clean_series_rarely_raise_anomalies():
    # 60 productos x 90 días revisados por familia: el ruido normal no debe generar alertas de revisión.
    assert false_positives(lambda r: synthetic.stable(r, level=8)) <= 2
    assert false_positives(lambda r: synthetic.stable(r, level=2)) <= 2
    assert false_positives(lambda r: synthetic.weekly_seasonal(r, level=1.5)) <= 3


def test_intermittent_sales_are_not_spikes_by_themselves():
    assert false_positives(synthetic.intermittent) <= 2


def test_a_real_spike_is_detected_in_stable_and_intermittent_demand():
    rng = np.random.default_rng(3)
    stable = synthetic.stable(rng, level=8)
    stable[-2] = 40
    sporadic = synthetic.intermittent(rng)
    sporadic[-5] = 60
    entries, found = scan([stable, sporadic])
    for (series, _), anomalies, offset in zip(entries, found, (2, 5), strict=True):
        day = series.date_at(series.n - offset)
        assert any(a.day == day and a.kind == "SPIKE" for a in anomalies)
    sporadic_spike = next(a for a in found[1] if a.kind == "SPIKE")
    assert sporadic_spike.expected >= 2  # se compara con el tamaño típico de una venta, no con el promedio diario


def test_the_day_after_a_huge_spike_is_not_a_drop():
    rng = np.random.default_rng(8)
    values = synthetic.stable(rng, level=3)
    values[-4] = 1_000_000
    _, found = scan([values])
    assert [a.kind for a in found[0]] == ["SPIKE"]
