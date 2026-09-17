import math
from datetime import datetime, timedelta, timezone

import numpy as np
import pytest

from app.analysis.forecasting import FORECAST_DAYS, WMA, ForecastResult
from app.analysis.inventory import demand_path, estimate_shelf_life, nominal_cover, plan_inventory, service_z
from app.analysis.lots import FIFO, SimLot, simulate
from app.schemas import LotInput
from tests.synthetic import AS_OF


def flat_forecast(level: float, sigma: float) -> ForecastResult:
    return ForecastResult(WMA, np.full(30, np.nan), np.full(FORECAST_DAYS, level), sigma, 0.0)


def plan_for(level, sigma, stock, lead=4, target=14, min_stock=0, lots=None, service=0.95, shelf_life=None):
    fc = flat_forecast(level, sigma)
    demand = demand_path(fc, 0.0)
    sim_lots = lots if lots is not None else [SimLot(None, stock, None)]
    simulation = simulate(sim_lots, demand, FIFO)
    return plan_inventory(fc, demand, stock, min_stock, lead, target, service, simulation, shelf_life)


def test_reorder_point_is_lead_time_demand_plus_safety_stock():
    plan = plan_for(level=5, sigma=2, stock=40, lead=4)
    z = service_z(0.95)
    assert z == pytest.approx(1.6449, abs=1e-3)
    assert plan.safety_stock == math.ceil(z * 2 * math.sqrt(4))  # 7
    assert plan.lead_time_demand == pytest.approx(20)
    assert plan.reorder_point == 20 + plan.safety_stock


def test_suggested_quantity_covers_target_plus_lead_time():
    plan = plan_for(level=5, sigma=2, stock=40, lead=4, target=14)
    assert plan.suggested_order_qty == math.ceil(5 * (4 + 14) + plan.safety_stock - 40)
    assert plan.days_of_cover == pytest.approx(8.0)
    assert plan.stockout_day == 8
    assert plan.reorder_day == 3  # el stock simulado baja a 25 u. (≤ 27) al inicio del día 3


def test_min_stock_acts_as_floor_for_the_reorder_point():
    plan = plan_for(level=0.5, sigma=0.5, stock=8, lead=2, min_stock=10)
    assert plan.reorder_point == 10
    assert plan.suggested_order_qty >= 10 + 1 - 8


def test_stock_that_expires_unsold_is_not_usable():
    fresh = plan_for(level=5, sigma=1, stock=50, lots=[SimLot(1, 50, None)])
    expiring = plan_for(level=5, sigma=1, stock=50, lots=[SimLot(1, 30, 1), SimLot(2, 20, None)])
    assert expiring.usable_stock == pytest.approx(50 - 20)
    assert expiring.suggested_order_qty == fresh.suggested_order_qty + 20
    assert expiring.stockout_day < fresh.stockout_day


def test_shelf_life_caps_the_coverage_of_perishables():
    received = datetime(2026, 9, 1, 12, tzinfo=timezone.utc)
    lots = [
        LotInput(lot_id=1, quantity=10, expiry_date=received.date() + timedelta(days=9), received_at=received),
        LotInput(lot_id=2, quantity=10, expiry_date=received.date() + timedelta(days=11), received_at=received),
        LotInput(lot_id=3, quantity=10, expiry_date=None, received_at=received),
    ]
    shelf_life = estimate_shelf_life(lots)
    assert shelf_life == 10
    capped = plan_for(level=5, sigma=1, stock=0, lead=2, target=14, shelf_life=shelf_life)
    assert capped.effective_coverage_days == 10
    assert capped.suggested_order_qty == math.ceil(5 * (2 + 10) + capped.safety_stock)


def test_products_without_demand_do_not_need_reorders():
    plan = plan_for(level=0, sigma=0, stock=12)
    assert not plan.has_demand
    assert plan.reorder_point == 0
    assert plan.suggested_order_qty == 0
    assert plan.days_of_cover is None
    assert plan.stockout_day is None


def test_todays_sales_are_discounted_from_todays_demand():
    fc = flat_forecast(6, 1)
    demand = demand_path(fc, sold_today=4)
    assert demand[0] == pytest.approx(2)
    assert demand[1] == pytest.approx(6)
    assert demand_path(fc, sold_today=10)[0] == 0


def test_nominal_cover_has_fractional_days():
    assert nominal_cover(12, np.full(30, 5.0)) == pytest.approx(2.4)
    assert nominal_cover(0, np.full(30, 5.0)) == 0.0
    assert nominal_cover(10, np.zeros(30)) is None
    assert AS_OF.year == 2026
