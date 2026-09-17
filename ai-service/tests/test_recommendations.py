from datetime import date, timedelta

import numpy as np
import pytest

from app.analysis.formatting import fmt_date, fmt_days, fmt_money, fmt_num, fmt_pct, fmt_units
from app.analysis.pipeline import analyze
from app.analysis.recommendations import (
    DISCOUNT,
    REDUCE_PURCHASE,
    REMOVE_EXPIRED,
    REORDER,
    REVIEW_ANOMALY,
    Recommendation,
    dedupe_key,
    finalize,
    tidy,
)
from app.schemas import AnalyzeRequest
from tests import synthetic
from tests.synthetic import AS_OF, lot, product


def scenario_products() -> list[dict]:
    rng = np.random.default_rng(2026)
    spike = synthetic.stable(rng, level=8)
    spike[-1] = 60
    return [
        product(1, synthetic.stable(rng, level=6), name="Leche entera 1L", category="Lácteos", perishable=False, stock=8, min_stock=5),
        product(
            2,
            synthetic.stable(rng, level=5),
            name="Yogur bebible frutilla 1L",
            category="Lácteos",
            sale_price=2100,
            cost_price=1400,
            lots=[
                lot(21, 60, AS_OF + timedelta(days=40), AS_OF - timedelta(days=20), "L2408B"),
                lot(22, 20, AS_OF + timedelta(days=6), AS_OF - timedelta(days=2), "L2409A"),
            ],
        ),
        product(
            3,
            synthetic.stable(rng, level=3),
            name="Queso cremoso",
            category="Lácteos",
            lots=[
                lot(31, 7, AS_OF - timedelta(days=2), AS_OF - timedelta(days=40), "Q0801"),
                lot(32, 40, AS_OF + timedelta(days=60), AS_OF - timedelta(days=5), "Q0902"),
            ],
            stock=40,
        ),
        product(4, synthetic.stable(rng, level=2), name="Dulce de leche 400 g", lots=[lot(41, 200, AS_OF + timedelta(days=150), AS_OF - timedelta(days=4))]),
        product(5, spike, name="Gaseosa cola 2,25L", category="Bebidas", perishable=False, stock=150),
    ]


def run(products, feedback=None, **settings):
    request = AnalyzeRequest.model_validate(synthetic.analyze_request(products, feedback, **settings))
    return analyze(request)


@pytest.fixture(scope="module")
def result():
    return run(scenario_products())


def recs_by_type(response, kind):
    return [r for r in response.recommendations if r.type == kind]


def test_dedupe_key_formats():
    assert dedupe_key(REORDER, product_id=10) == "REORDER:10"
    assert dedupe_key(DISCOUNT, product_id=10, lot_id=55) == "DISCOUNT:55"
    assert dedupe_key(REMOVE_EXPIRED, product_id=10, lot_id=56) == "REMOVE_EXPIRED:56"
    assert dedupe_key(REVIEW_ANOMALY, product_id=10, day=date(2026, 9, 1)) == "REVIEW_ANOMALY:10:2026-09-01"
    assert dedupe_key(REDUCE_PURCHASE, product_id=10) == "REDUCE_PURCHASE:10"


def make_rec(key, base_priority, impact=0.0, kind=REORDER):
    return Recommendation(kind, 1, None, "t", "Texto con 3 u..", None, None, None, impact, 0.7, base_priority, 8, key)


def test_finalize_dedupes_keeping_the_highest_priority_and_sorts():
    recs = finalize([make_rec("REORDER:1", 50), make_rec("REORDER:1", 70), make_rec("REORDER:2", 60, impact=100)])
    assert [r.dedupe_key for r in recs] == ["REORDER:1", "REORDER:2"]
    assert recs[0].priority == 70
    assert recs[1].priority == 68
    assert recs[0].explanation == "Texto con 3 u."


def test_every_recommendation_type_is_generated(result):
    types = {r.type for r in result.recommendations}
    assert {REORDER, DISCOUNT, REMOVE_EXPIRED, REVIEW_ANOMALY, REDUCE_PURCHASE} <= types


def test_recommendations_are_unique_sorted_and_bounded(result):
    keys = [r.dedupe_key for r in result.recommendations]
    assert len(keys) == len(set(keys))
    priorities = [r.priority for r in result.recommendations]
    assert priorities == sorted(priorities, reverse=True)
    for r in result.recommendations:
        assert 1 <= r.priority <= 100
        assert 0 < r.confidence <= 0.99
        assert r.expected_impact >= 0
        assert r.title and r.explanation and ".." not in r.explanation


def test_reorder_for_low_stock(result):
    (rec,) = [r for r in recs_by_type(result, REORDER) if r.product_id == 1]
    insight = next(p for p in result.products if p.product_id == 1)
    assert rec.dedupe_key == "REORDER:1"
    assert rec.suggested_quantity == insight.suggested_order_qty > 0
    assert rec.suggested_date is not None and rec.suggested_date >= AS_OF
    assert rec.title == "Reponer Leche entera 1L"
    assert "u/día" in rec.explanation and "Sugerimos pedir" in rec.explanation
    assert insight.reorder_point >= 5


def test_fifo_discount_explains_the_rotation(result):
    (rec,) = [r for r in recs_by_type(result, DISCOUNT) if r.lot_id == 22]
    assert rec.dedupe_key == "DISCOUNT:22"
    assert rec.suggested_discount_pct in (10, 15, 20, 25, 30, 40)
    assert rec.suggested_date == AS_OF
    assert "FIFO" in rec.explanation
    assert "entró antes" in rec.explanation
    assert "FEFO" in rec.explanation
    assert "L2409A" in rec.title
    risks = {r.lot_id: r for r in next(p for p in result.products if p.product_id == 2).lot_risks}
    assert risks[22].units_at_risk > 0
    assert risks[22].recommended_discount_pct == rec.suggested_discount_pct
    assert risks[21].units_at_risk == 0


def test_fefo_rotation_does_not_leave_the_newer_lot_unsold():
    response = run(scenario_products(), stockRotation="FEFO")
    assert not [r for r in response.recommendations if r.lot_id == 22]
    risks = {r.lot_id: r for r in next(p for p in response.products if p.product_id == 2).lot_risks}
    assert risks[22].units_at_risk == 0


def test_remove_expired_lot(result):
    (rec,) = recs_by_type(result, REMOVE_EXPIRED)
    assert rec.dedupe_key == "REMOVE_EXPIRED:31"
    assert rec.suggested_quantity == 7
    assert rec.priority >= 92
    assert "venció el 15/09/2026" in rec.explanation


def test_reduce_purchase_for_perishable_overstock(result):
    (rec,) = recs_by_type(result, REDUCE_PURCHASE)
    assert rec.product_id == 4
    assert rec.dedupe_key == "REDUCE_PURCHASE:4"
    assert rec.suggested_quantity > 100
    assert "vencimiento" in rec.explanation


def test_review_anomaly_for_yesterdays_spike(result):
    (rec,) = [r for r in recs_by_type(result, REVIEW_ANOMALY) if r.product_id == 5]
    yesterday = AS_OF - timedelta(days=1)
    assert rec.dedupe_key == f"REVIEW_ANOMALY:5:{yesterday.isoformat()}"
    assert rec.suggested_date == yesterday
    assert rec.explanation.startswith("Ayer se vendieron 60 u.")
    anomalies = next(p for p in result.products if p.product_id == 5).anomalies
    assert anomalies[0].kind == "SPIKE" and anomalies[0].date == yesterday


def test_feedback_changes_the_suggested_discount():
    rng = np.random.default_rng(7)
    values = synthetic.stable(rng, level=5)
    products = [product(6, values, name="Postre de vainilla", category="Lácteos", lots=[lot(61, 32, AS_OF + timedelta(days=4), AS_OF - timedelta(days=3), "P1")])]
    weak = {
        "recommendationId": 9, "type": "DISCOUNT", "productId": 6, "category": "Lácteos", "status": "ACCEPTED",
        "discountPct": 20, "outcome": {"unitsBefore7d": 80, "unitsAfter7d": 82, "lift": 1.025},
    }  # fmt: skip

    default = run(products)
    learned = run(products, feedback=[weak, weak], stockRotation="FEFO")

    default_pct = recs_by_type(default, DISCOUNT)[0].suggested_discount_pct
    learned_rec = recs_by_type(learned, DISCOUNT)[0]
    assert default_pct < 40
    assert learned_rec.suggested_discount_pct == 40
    assert learned.summary.elasticity_by_category["Lácteos"] < default.summary.elasticity_by_category["Lácteos"]
    assert "2 resultados medidos" in learned_rec.explanation


def test_summary_counts(result):
    summary = result.summary
    assert summary.products_analyzed == 5
    assert sum(summary.pattern_counts.values()) == 5
    assert summary.at_risk_value > 0
    assert summary.anomalies30d >= 1
    assert any("FIFO" in note for note in summary.model_notes)
    assert summary.model_notes[0].startswith("Sucursal Centro: 5 productos analizados al 17/09/2026")


def test_es_ar_number_formatting():
    assert fmt_num(3.25) == "3,3"
    assert fmt_num(6300) == "6.300"
    assert fmt_num(1234567.891, 2) == "1.234.567,89"
    assert fmt_money(8450000) == "$ 8.450.000"
    assert fmt_money(45.5) == "$ 45,50"
    assert fmt_pct(12.5, signed=True) == "+13%"
    assert fmt_pct(-8.2, signed=True) == "-8%"
    assert fmt_days(1) == "1 día" and fmt_days(7.8) == "7,8 días"
    assert fmt_units(30) == "30 u."
    assert fmt_date(date(2026, 9, 5)) == "05/09/2026"
    assert tidy("Quedan 3 u.. Listo") == "Quedan 3 u. Listo"


def test_priorities_put_expired_lots_first_then_stockouts_then_anomalies():
    rng = np.random.default_rng(99)
    spike = synthetic.stable(rng, level=6)
    spike[-1] = 45
    products = [
        product(1, synthetic.stable(rng, level=20), name="Leche", stock=0, sale_price=5000),
        product(2, synthetic.stable(rng, level=2), name="Queso", stock=0, lots=[lot(21, 4, AS_OF - timedelta(days=1), AS_OF - timedelta(days=30))]),
        product(3, spike, name="Gaseosa", perishable=False, stock=500),
    ]
    response = run(products)
    by_type = {r.type: r for r in response.recommendations}
    assert by_type[REMOVE_EXPIRED].priority > by_type[REORDER].priority > by_type[REVIEW_ANOMALY].priority
    for rec in response.recommendations:
        if rec.type != REMOVE_EXPIRED:
            assert rec.priority <= 90


def test_lot_at_risk_without_sales_is_a_liquidation_not_a_promise():
    at_risk = lot(71, 40, AS_OF + timedelta(days=10), AS_OF - timedelta(days=60), "M1")
    products = [product(7, np.zeros(120, dtype=int), name="Mermelada light", stock=40, lots=[at_risk])]
    (rec,) = recs_by_type(run(products), DISCOUNT)
    assert rec.title.startswith("Liquidar Mermelada light")
    assert rec.suggested_discount_pct == 40
    assert "no se puede estimar" in rec.explanation
    assert rec.expected_impact == pytest.approx(40 * 650)


def test_model_notes_use_singular_and_readable_method_names():
    response = run([product(1, np.zeros(40, dtype=int), stock=0)])
    notes = response.summary.model_notes
    assert "1 producto analizado al 17/09/2026" in notes[0]
    assert notes[1] == "Pronósticos: 1 sin ventas para pronosticar."


def test_dates_in_another_year_include_the_year():
    rng = np.random.default_rng(4)
    overstock = lot(81, 400, date(2028, 1, 1), AS_OF - timedelta(days=3))
    products = [product(8, synthetic.stable(rng, level=1), name="Dulce de membrillo", stock=400, lots=[overstock])]
    (rec,) = recs_by_type(run(products), REDUCE_PURCHASE)
    assert rec.suggested_date.year == 2027
    assert f"hasta el {fmt_date(rec.suggested_date)}" in rec.explanation


def test_spike_in_sporadic_demand_is_compared_with_a_typical_sale():
    rng = np.random.default_rng(12)
    values = synthetic.intermittent(rng)
    values[-1] = 70
    (rec,) = recs_by_type(run([product(9, values, name="Pimentón dulce", perishable=False, stock=100)]), REVIEW_ANOMALY)
    assert rec.explanation.startswith("Ayer se vendieron 70 u. cuando una venta típica de este producto es de")
