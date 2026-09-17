"""Política de reposición: stock de seguridad, punto de pedido, cobertura, quiebre previsto y cantidad sugerida."""

import math
import statistics
from dataclasses import dataclass
from statistics import NormalDist
from zoneinfo import ZoneInfo

import numpy as np

from ..schemas import LotInput
from .forecasting import ForecastResult
from .lots import ConsumptionSimulation

MAX_COVER_DAYS = 9999.0
MAX_ORDER_UNITS = 2_147_483_647
"""Las cantidades se informan como enteros de 32 bits (int de Java)."""
BUSINESS_TIMEZONE = ZoneInfo("America/Argentina/Buenos_Aires")
DEMAND_EPSILON = 1e-3


@dataclass
class InventoryPlan:
    lead_time_days: int
    coverage_target_days: int
    effective_coverage_days: int
    """Cobertura usada para la cantidad sugerida: el objetivo, acotado por la vida útil en perecederos."""
    shelf_life_days: int | None
    demand_rate: float
    """Demanda diaria esperada promedio para las próximas 4 semanas."""
    lead_time_demand: float
    safety_stock: int
    reorder_point: int
    order_up_to: float
    stock: float
    usable_stock: float
    """Stock que se espera vender: descuenta lo que vencería sin venderse dentro de la ventana."""
    suggested_order_qty: int
    days_of_cover: float | None
    """Días de venta que representa el stock según el pronóstico (sin considerar vencimientos)."""
    stockout_day: int | None
    """Día (hoy = 0) del primer faltante según la simulación por lotes (sí considera vencimientos)."""
    reorder_day: int | None
    """Primer día en que el stock simulado queda en el punto de pedido o por debajo."""
    unmet_units: float
    """Ventas que se perderían entre la llegada de un pedido hecho hoy y el fin de la cobertura."""

    @property
    def has_demand(self) -> bool:
        return self.demand_rate > DEMAND_EPSILON


def service_z(service_level: float) -> float:
    return NormalDist().inv_cdf(min(max(service_level, 0.5), 0.999))


def demand_path(fc: ForecastResult, sold_today: float) -> np.ndarray:
    """Demanda esperada desde hoy; a la de hoy se le resta lo que ya se vendió."""
    path = np.array(fc.path, dtype=float)
    if len(path):
        path[0] = max(0.0, path[0] - max(sold_today, 0.0))
    return path


def nominal_cover(stock: float, demand: np.ndarray) -> float | None:
    """Días (con fracción) que cubre el stock con la demanda pronosticada; `None` si no hay demanda."""
    if stock <= 0:
        return 0.0 if demand.sum() > 0 else None
    cumulative = np.cumsum(demand)
    reached = np.flatnonzero(cumulative >= stock - 1e-9)
    if len(reached):
        day = int(reached[0])
        before = cumulative[day - 1] if day > 0 else 0.0
        return float(day + (stock - before) / demand[day])
    tail_rate = float(demand[-28:].mean()) if len(demand) else 0.0
    if tail_rate <= 1e-6:
        return None
    return float(min(MAX_COVER_DAYS, len(demand) + (stock - cumulative[-1]) / tail_rate))


def estimate_shelf_life(lots: list[LotInput]) -> int | None:
    """Vida útil habitual del producto: mediana de días entre el ingreso y el vencimiento de sus lotes."""
    spans = [
        (lot.expiry_date - lot.received_at.astimezone(BUSINESS_TIMEZONE).date()).days
        for lot in lots
        if lot.expiry_date is not None and lot.received_at is not None
    ]
    spans = [span for span in spans if span > 0]
    return int(statistics.median(spans)) if spans else None


def plan_inventory(
    fc: ForecastResult,
    demand: np.ndarray,
    stock: float,
    min_stock: float,
    lead_time_days: int,
    coverage_target_days: int,
    service_level: float,
    simulation: ConsumptionSimulation,
    shelf_life_days: int | None = None,
) -> InventoryPlan:
    """Punto de pedido = demanda durante el lead time + z(nivel de servicio)·σ·√LT (nunca menor al stock mínimo).

    La cantidad sugerida lleva el stock a la demanda de `leadTimeDays + targetCoverageDays` más el stock
    de seguridad, descontando el stock que se espera que venza sin venderse en ese período.
    """
    lead = max(0, int(lead_time_days))
    target = max(1, int(coverage_target_days))
    effective_target = min(target, shelf_life_days) if shelf_life_days else target
    effective_target = max(1, effective_target)
    stock = max(0.0, float(stock))
    demand_rate = float(demand[:28].mean()) if len(demand) else 0.0
    has_demand = demand_rate > DEMAND_EPSILON
    planned = has_demand or min_stock > 0

    lead_time_demand = float(demand[:lead].sum())
    safety = max(0, math.ceil(service_z(service_level) * fc.sigma * math.sqrt(lead) - 1e-9)) if has_demand else 0
    statistical_rop = math.ceil(lead_time_demand + safety - 1e-9) if has_demand else 0
    reorder_point = min(MAX_ORDER_UNITS, max(statistical_rop, math.ceil(min_stock))) if planned else 0

    window = lead + effective_target
    horizon_demand = float(demand[:window].sum())
    expiring_unsold = float(simulation.expired[: window + 1].sum())
    usable_stock = max(0.0, stock - expiring_unsold)
    order_up_to = max(horizon_demand + safety, min_stock + lead_time_demand) if planned else 0.0
    suggested = min(MAX_ORDER_UNITS, max(0, math.ceil(order_up_to - usable_stock - 1e-9)))

    reorder_day: int | None = None
    if planned and simulation.days:
        below = np.flatnonzero(simulation.stock_start <= reorder_point + 1e-9)
        reorder_day = int(below[0]) if len(below) else None

    return InventoryPlan(
        lead_time_days=lead,
        coverage_target_days=target,
        effective_coverage_days=effective_target,
        shelf_life_days=shelf_life_days,
        demand_rate=demand_rate,
        lead_time_demand=lead_time_demand,
        safety_stock=safety,
        reorder_point=reorder_point,
        order_up_to=order_up_to,
        stock=stock,
        usable_stock=usable_stock,
        suggested_order_qty=suggested,
        days_of_cover=nominal_cover(stock, demand) if has_demand or stock <= 0 else None,
        stockout_day=simulation.stockout_day if has_demand else None,
        reorder_day=reorder_day,
        unmet_units=float(simulation.unmet[lead:window].sum()),
    )
