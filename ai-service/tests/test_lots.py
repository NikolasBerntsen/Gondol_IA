from datetime import datetime, timedelta, timezone

import numpy as np
import pytest

from app.analysis.lots import (
    FEFO,
    FIFO,
    RISK_EXPIRED,
    RISK_HIGH,
    RISK_NONE,
    SimLot,
    assess_lots,
    promote,
    rotation_order,
    simulate,
)
from app.schemas import LotInput
from tests.synthetic import AS_OF


def received(days_ago: int) -> datetime:
    return datetime.combine(AS_OF - timedelta(days=days_ago), datetime.min.time(), tzinfo=timezone.utc).replace(hour=13)


def make_lot(lot_id, quantity, expires_in, received_days_ago, **extra) -> LotInput:
    expiry = AS_OF + timedelta(days=expires_in) if expires_in is not None else None
    return LotInput(lot_id=lot_id, quantity=quantity, expiry_date=expiry, received_at=received(received_days_ago), **extra)


def assess(lots, demand_per_day, rotation, elasticity=2.0, max_discount=40, stock=None):
    demand = np.full(730, float(demand_per_day))
    if stock is None:
        stock = sum(lot.quantity for lot in lots if lot.status == "ACTIVE" and (lot.expiry_date is None or lot.expiry_date >= AS_OF))
    return assess_lots(lots, stock, demand, AS_OF, rotation, 15, 5, elasticity, max_discount)


def by_id(assessment):
    return {risk.lot_id: risk for risk in assessment.risks}


def test_fifo_orders_by_received_at_then_id_and_fefo_by_expiry_nulls_last():
    lots = [
        SimLot(3, 5, 10, received(1)),
        SimLot(1, 5, None, received(20)),
        SimLot(2, 5, 30, received(20)),
        SimLot(4, 5, 2, None),
        SimLot(None, 5, None, None),
    ]
    assert [lot.key for lot in rotation_order(lots, FIFO)] == [4, 1, 2, 3, None]
    assert [lot.key for lot in rotation_order(lots, FEFO)] == [4, 3, 2, 1, None]


def test_expired_lots_are_never_consumed():
    lots = [make_lot(1, 10, -3, 30, lot_number="VIEJO"), make_lot(2, 6, 20, 5)]
    assessment = assess(lots, 3, FIFO)
    risks = by_id(assessment)
    assert risks[1].risk_level == RISK_EXPIRED
    assert risks[1].units_at_risk == 10 and risks[1].expected_sales_before_expiry == 0
    assert 1 not in assessment.baseline.sold_by_lot
    assert assessment.baseline.stock_start[0] == pytest.approx(6)
    assert assessment.baseline.stockout_day == 2
    assert risks[2].risk_level == RISK_NONE


def test_lots_expire_during_the_simulation():
    simulation = simulate([SimLot(1, 20, 1), SimLot(2, 10, None)], np.full(10, 4.0), FEFO)
    assert simulation.sold_by_lot[1] == pytest.approx(8)
    assert simulation.expired[2] == pytest.approx(12)
    assert simulation.expired_by_lot[1] == pytest.approx(12)
    assert simulation.stockout_day == 4
    assert simulation.coverage == pytest.approx(4.5)


def test_fifo_flags_a_newer_lot_that_expires_before_older_stock():
    older = make_lot(1, 60, 40, 20, lot_number="L2408B")
    newer = make_lot(2, 20, 6, 2, lot_number="L2409A")
    fifo = by_id(assess([older, newer], 5, FIFO))
    fefo = by_id(assess([older, newer], 5, FEFO))

    risk = fifo[2]
    assert risk.rotation_rank == 2
    assert risk.expected_sales_before_expiry == pytest.approx(0)
    assert risk.units_at_risk == 20
    assert risk.risk_level == RISK_HIGH
    assert risk.rotation_blocked
    assert risk.blocking_units == pytest.approx(60)
    assert risk.blocking_expiry == AS_OF + timedelta(days=40)
    assert risk.first_sale_day is None
    assert risk.units_at_risk_fefo == 0
    assert risk.recommended_discount_pct == 10
    assert risk.discount_clears_lot

    assert fefo[2].units_at_risk == 0
    assert not fefo[2].rotation_blocked
    assert fefo[2].recommended_discount_pct is None
    assert fifo[1].units_at_risk == 0


def test_minimum_discount_step_depends_on_elasticity():
    lot = [make_lot(1, 30, 4, 3)]  # 5 días de venta (hoy..día 4) a 5 u/día = 25 u.: faltan vender 5
    assert by_id(assess(lot, 5, FEFO, elasticity=2.0))[1].recommended_discount_pct == 10
    assert by_id(assess(lot, 5, FEFO, elasticity=1.0))[1].recommended_discount_pct == 20
    assert by_id(assess(lot, 5, FEFO, elasticity=4.0))[1].recommended_discount_pct == 10


def test_discount_is_capped_by_max_discount_pct():
    lot = [make_lot(1, 60, 4, 3)]
    risk = by_id(assess(lot, 5, FEFO, elasticity=1.0, max_discount=15))[1]
    assert risk.recommended_discount_pct == 15
    assert not risk.discount_clears_lot
    assert risk.expected_units_sold_with_discount == pytest.approx(25 * 1.15)
    assert by_id(assess(lot, 5, FEFO, max_discount=5))[1].recommended_discount_pct is None


def test_lot_already_discounted_only_gets_a_bigger_step():
    lot = [make_lot(1, 60, 4, 3, discount_pct=20)]
    risk = by_id(assess(lot, 5, FEFO, elasticity=1.0))[1]
    assert risk.expected_sales_before_expiry == pytest.approx(25 * 1.2)
    assert risk.recommended_discount_pct in (25, 30, 40)


def test_recalled_and_depleted_lots_are_ignored_and_orphan_stock_is_kept():
    lots = [make_lot(1, 10, 10, 5, status="RECALLED"), make_lot(2, 0, 10, 5), make_lot(3, 8, 10, 5)]
    assessment = assess(lots, 1, FIFO, stock=20)
    assert [risk.lot_id for risk in assessment.risks] == [3]
    assert assessment.unassigned_units == pytest.approx(12)
    assert assessment.baseline.stock_start[0] == pytest.approx(20)


def test_lot_without_expiry_date_has_no_risk_entry():
    assessment = assess([make_lot(1, 10, None, 5)], 1, FIFO)
    assert assessment.risks == []


def test_promoted_lot_only_jumps_lots_that_expire_after_it():
    queue = [SimLot(1, 10, 3), SimLot(2, 10, 30), SimLot(3, 10, 5), SimLot(None, 5, None)]
    assert [lot.key for lot in promote(queue, 3)] == [1, 3, 2, None]
    assert [lot.key for lot in promote(queue, 1)] == [1, 2, 3, None]


def test_discount_does_not_count_sales_taken_from_a_lot_that_expires_earlier():
    earlier = make_lot(1, 10, 3, 5)
    later = make_lot(2, 30, 5, 5)
    risk = by_id(assess([earlier, later], 4, FEFO))[2]
    assert risk.expected_sales_before_expiry == pytest.approx(14)
    assert risk.units_at_risk == 16
    # Solo se vende con descuento desde que se termina el lote que vence antes: 14 u. x 1,8 = 25,2 < 30.
    assert risk.recommended_discount_pct == 40
    assert risk.expected_units_sold_with_discount == pytest.approx(14 * 1.8)
    assert not risk.discount_clears_lot


def test_fifo_block_without_demand_is_not_blamed_on_the_rotation():
    lots = [make_lot(1, 30, 40, 20), make_lot(2, 20, 5, 5)]
    risk = by_id(assess(lots, 0, FIFO))[2]
    assert risk.rotation_blocked
    assert not risk.rotation_issue


def test_duplicated_lot_ids_are_counted_once():
    lots = [make_lot(1, 10, 10, 5), make_lot(1, 10, 10, 5)]
    assessment = assess(lots, 1, FIFO, stock=10)
    assert len(assessment.risks) == 1
    assert assessment.baseline.stock_start[0] == pytest.approx(10)


def test_discount_that_moves_the_waste_to_another_lot_does_not_clear_it():
    older = make_lot(1, 20, 6, 10)
    newer = make_lot(2, 12, 3, 1)
    risk = by_id(assess([older, newer], 4, FIFO))[2]
    assert risk.units_at_risk == 12
    # Con 10% el lote nuevo se vende entero, pero el viejo empieza a venderse más tarde y vencen 6 u.
    assert risk.recommended_discount_pct == 10
    assert risk.expected_units_sold_with_discount == pytest.approx(12)
    assert not risk.discount_clears_lot
