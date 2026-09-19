"""Simulación del consumo pronosticado lote por lote (rotación FIFO/FEFO) y riesgo de vencimiento."""

import math
from dataclasses import dataclass, field
from datetime import date, datetime

import numpy as np

from ..schemas import LotInput

FIFO = "FIFO"
FEFO = "FEFO"
EPSILON = 1e-6
DISCOUNT_STEPS = (10, 15, 20, 25, 30, 40)

RISK_NONE = "NONE"
RISK_LOW = "LOW"
RISK_MEDIUM = "MEDIUM"
RISK_HIGH = "HIGH"
RISK_EXPIRED = "EXPIRED"


@dataclass
class SimLot:
    key: int | None
    """Id del lote; `None` para stock vendible que no llegó asociado a ningún lote."""
    quantity: float
    last_sellable_day: int | None
    """Último día (hoy = 0) en que se puede vender; `None` si no vence."""
    received_at: datetime | None = None
    on_sale: bool = False
    """Lote en liquidación (tiene `discountPct` > 0): se vende antes que el resto (SPEC §4.2)."""


def rotation_key(lot: SimLot, rotation: str) -> tuple:
    """Mismo orden que el backend (SPEC §4.2): `(discount_pct IS NULL) ASC, <orden FIFO|FEFO>`.

    Primero los lotes en liquidación (con descuento vigente) y, dentro de cada grupo, FIFO
    (`received_at ASC, id ASC`) o FEFO (`expiry_date ASC NULLS LAST, received_at ASC, id ASC`).
    Un lote sin fecha de ingreso se considera el más antiguo; el stock sin lote va al final.
    """
    unassigned = lot.key is None
    regular = not lot.on_sale
    received = lot.received_at.timestamp() if lot.received_at is not None else -math.inf
    lot_id = lot.key if lot.key is not None else math.inf
    if rotation == FEFO:
        no_expiry = lot.last_sellable_day is None
        return (unassigned, regular, no_expiry, lot.last_sellable_day or 0, received, lot_id)
    return (unassigned, regular, received, lot_id)


def rotation_order(lots: list[SimLot], rotation: str) -> list[SimLot]:
    return sorted(lots, key=lambda lot: rotation_key(lot, rotation))


def _expires_after(lot: SimLot, day: int) -> bool:
    return lot.last_sellable_day is None or lot.last_sellable_day > day


@dataclass
class ConsumptionSimulation:
    days: int
    sold_by_lot: dict[int | None, float]
    first_sale_day: dict[int | None, int | None]
    expired_by_lot: dict[int | None, float]
    stock_start: np.ndarray
    """Stock vendible al inicio de cada día (ya descontado lo que venció)."""
    expired: np.ndarray
    """Unidades que dejan de ser vendibles al inicio de cada día por vencimiento."""
    unmet: np.ndarray
    """Demanda que no se puede atender por falta de stock."""
    stockout_day: int | None
    coverage: float | None
    """Días (con fracción) hasta el quiebre de stock, considerando vencimientos."""


def simulate(
    lots: list[SimLot],
    demand: np.ndarray,
    rotation: str,
    days: int | None = None,
    lifts: dict[int, float] | None = None,
    liquidate: int | None = None,
    until_no_expiring_stock: bool = False,
) -> ConsumptionSimulation:
    """Consume la demanda esperada día a día respetando la rotación del comercio.

    Los lotes vencidos nunca se venden y los lotes en liquidación salen primero (SPEC §4.2). `lifts`
    multiplica la demanda mientras se vende un lote con descuento; `liquidate` simula aceptar un descuento
    para ese lote: pasa a estar en liquidación y se vende antes que el resto, igual que en el backend. Con
    `until_no_expiring_stock` la simulación termina cuando ya no queda stock con vencimiento (alcanza para
    medir mermas y es mucho más rápido).
    """
    days = len(demand) if days is None else max(0, min(days, len(demand)))
    lifts = lifts or {}
    queue = [
        SimLot(
            lot.key,
            float(lot.quantity),
            lot.last_sellable_day,
            lot.received_at,
            lot.on_sale or (liquidate is not None and lot.key == liquidate),
        )
        for lot in lots
    ]
    queue = [lot for lot in rotation_order(queue, rotation) if lot.quantity > EPSILON]

    sold = {lot.key: 0.0 for lot in queue}
    first_sale: dict[int | None, int | None] = {lot.key: None for lot in queue}
    expired_by_lot = {lot.key: 0.0 for lot in queue}
    stock_start = np.zeros(days)
    expired = np.zeros(days)
    unmet = np.zeros(days)
    stockout_day: int | None = None
    coverage: float | None = None

    for t in range(days):
        if until_no_expiring_stock and not any(lot.quantity > EPSILON and lot.last_sellable_day is not None for lot in queue):
            break
        remaining = 0.0
        for lot in queue:
            if lot.quantity <= EPSILON:
                continue
            if lot.last_sellable_day is not None and lot.last_sellable_day < t:
                expired[t] += lot.quantity
                expired_by_lot[lot.key] += lot.quantity
                lot.quantity = 0.0
            else:
                remaining += lot.quantity
        stock_start[t] = remaining

        if remaining <= EPSILON:
            unmet[t:] = demand[t:days]
            pending = np.flatnonzero(unmet[t:] > EPSILON)
            if stockout_day is None and len(pending):
                stockout_day = t + int(pending[0])
                coverage = float(stockout_day)
            break

        need = float(demand[t])
        requested = need
        for lot in queue:
            if need <= EPSILON:
                break
            if lot.quantity <= EPSILON:
                continue
            factor = lifts.get(lot.key, 1.0) if lot.key is not None else 1.0
            take = min(lot.quantity, need * factor)
            if take > EPSILON and first_sale[lot.key] is None:
                first_sale[lot.key] = t
            lot.quantity -= take
            sold[lot.key] += take
            need -= take / factor
        if need > EPSILON:
            unmet[t] = need
            if stockout_day is None:
                stockout_day = t
                coverage = t + (requested - need) / requested
    return ConsumptionSimulation(days, sold, first_sale, expired_by_lot, stock_start, expired, unmet, stockout_day, coverage)


@dataclass
class LotRisk:
    lot_id: int
    lot_number: str | None
    expiry_date: date
    received_at: datetime | None
    days_to_expiry: int
    quantity: int
    expected_sales_before_expiry: float
    units_at_risk: int
    risk_level: str
    current_discount_pct: float | None = None
    rotation_rank: int | None = None
    """Posición en la fila de venta (1 = se vende primero); `None` si no es vendible."""
    first_sale_day: int | None = None
    blocking_units: float = 0.0
    """Unidades de lotes que ingresaron antes y vencen después: con FIFO se venden antes que este."""
    blocking_expiry: date | None = None
    liquidation_units: float = 0.0
    """Unidades de lotes en liquidación que vencen después y igual se venden antes (salen primero)."""
    units_at_risk_fefo: int | None = None
    recommended_discount_pct: int | None = None
    expected_units_sold_with_discount: float | None = None
    discount_clears_lot: bool = False

    @property
    def rotation_blocked(self) -> bool:
        """Con FIFO, mercadería que entró antes y vence después demora la venta de este lote."""
        return self.blocking_units > EPSILON and self.units_at_risk > 0

    @property
    def rotation_issue(self) -> bool:
        """El orden FIFO es la causa del riesgo: con FEFO quedarían menos unidades sin vender."""
        return self.rotation_blocked and self.units_at_risk_fefo is not None and self.units_at_risk_fefo < self.units_at_risk


@dataclass
class LotAssessment:
    rotation: str
    sim_lots: list[SimLot]
    baseline: ConsumptionSimulation
    risks: list[LotRisk] = field(default_factory=list)
    unassigned_units: float = 0.0

    @property
    def expired_units(self) -> int:
        return sum(r.quantity for r in self.risks if r.risk_level == RISK_EXPIRED)


def round_units(value: float) -> int:
    """Redondeo comercial (0,5 → 1), sin el redondeo bancario de `round`."""
    return int(math.floor(value + 0.5))


def risk_level(units_at_risk: int, quantity: float, days_to_expiry: int, warning_days: int, critical_days: int) -> str:
    if days_to_expiry < 0:
        return RISK_EXPIRED
    if units_at_risk <= 0:
        return RISK_NONE
    share = units_at_risk / max(quantity, 1.0)
    if share >= 0.5 or days_to_expiry <= critical_days:
        return RISK_HIGH
    if share >= 0.2 or days_to_expiry <= warning_days:
        return RISK_MEDIUM
    return RISK_LOW


def discount_lift(elasticity: float, discount_pct: float) -> float:
    """Multiplicador de ventas por descuento: elasticidad 2 y 20% de descuento → ×1,4."""
    return 1.0 + max(elasticity, 0.0) * max(discount_pct, 0.0) / 100.0


def best_discount(
    assessment: LotAssessment,
    demand: np.ndarray,
    target: SimLot,
    base_lifts: dict[int, float],
    elasticity: float,
    max_discount_pct: float,
    current_discount_pct: float | None,
) -> tuple[int, float, bool] | None:
    """Menor escalón de descuento con el que se estima vender todo el lote antes de su vencimiento.

    Cada escalón se simula con toda la fila de lotes: al aceptar el descuento el lote pasa a estar en
    liquidación y se vende antes que el resto (SPEC §4.2), y un escalón no "alcanza" si liquidar este lote
    hace vencer mercadería de otros lotes.
    Devuelve (descuento, unidades del lote vendidas, ¿alcanza para liquidarlo?). Si ningún escalón
    alcanza, propone el mayor permitido.
    """
    floor = current_discount_pct or 0.0
    steps = [step for step in DISCOUNT_STEPS if floor + EPSILON < step <= max_discount_pct + EPSILON]
    if not steps or target.key is None or target.last_sellable_day is None:
        return None
    waste_elsewhere = sum(units for key, units in assessment.baseline.expired_by_lot.items() if key != target.key)
    best: tuple[int, float, bool] | None = None
    for step in steps:
        lifts = dict(base_lifts)
        lifts[target.key] = discount_lift(elasticity, step)
        simulation = simulate(
            assessment.sim_lots, demand, assessment.rotation, lifts=lifts, liquidate=target.key, until_no_expiring_stock=True
        )
        sold = simulation.sold_by_lot.get(target.key, 0.0)
        best = (step, sold, False)
        if sold >= target.quantity - 0.5:
            # Un descuento mayor no mejora nada: si ya se liquida pero hace vencer otros lotes, no alcanza.
            extra_waste = sum(units for key, units in simulation.expired_by_lot.items() if key != target.key) - waste_elsewhere
            return step, sold, extra_waste < 0.5
    return best


def assess_lots(
    lots: list[LotInput],
    sellable_stock: float,
    demand: np.ndarray,
    as_of: date,
    rotation: str,
    warning_days: int,
    critical_days: int,
    elasticity: float,
    max_discount_pct: float,
) -> LotAssessment:
    """Simula la venta de los lotes vendibles en el orden de rotación y calcula el riesgo de cada uno.

    El orden es el mismo que usa el backend al registrar ventas (SPEC §4.2): primero los lotes en liquidación
    (con `discountPct` > 0) y, dentro de cada grupo, FIFO o FEFO.
    """
    sim_lots: list[SimLot] = []
    expired_inputs: list[LotInput] = []
    by_id: dict[int, LotInput] = {}
    for lot in lots:
        if lot.status != "ACTIVE" or lot.quantity <= EPSILON or lot.lot_id in by_id:
            continue
        by_id[lot.lot_id] = lot
        if lot.expiry_date is not None and lot.expiry_date < as_of:
            expired_inputs.append(lot)
            continue
        last_day = (lot.expiry_date - as_of).days if lot.expiry_date is not None else None
        on_sale = lot.discount_pct is not None and lot.discount_pct > 0
        sim_lots.append(SimLot(lot.lot_id, float(lot.quantity), last_day, lot.received_at, on_sale))

    unassigned = max(0.0, float(sellable_stock) - sum(lot.quantity for lot in sim_lots))
    if unassigned >= 0.5:
        sim_lots.append(SimLot(None, unassigned, None, None))

    base_lifts = {
        lot.lot_id: discount_lift(elasticity, lot.discount_pct)
        for lot in by_id.values()
        if lot.discount_pct and lot.discount_pct > 0
    }
    baseline = simulate(sim_lots, demand, rotation, lifts=base_lifts)
    assessment = LotAssessment(rotation, sim_lots, baseline, unassigned_units=unassigned if unassigned >= 0.5 else 0.0)

    for lot in expired_inputs:
        assessment.risks.append(
            LotRisk(
                lot_id=lot.lot_id,
                lot_number=lot.lot_number,
                expiry_date=lot.expiry_date,
                received_at=lot.received_at,
                days_to_expiry=(lot.expiry_date - as_of).days,
                quantity=round_units(lot.quantity),
                expected_sales_before_expiry=0.0,
                units_at_risk=round_units(lot.quantity),
                risk_level=RISK_EXPIRED,
                current_discount_pct=lot.discount_pct,
            )
        )

    ordered = rotation_order(sim_lots, rotation)
    fefo_baseline: ConsumptionSimulation | None = None
    for rank, sim_lot in enumerate(ordered, start=1):
        if sim_lot.key is None or sim_lot.last_sellable_day is None:
            continue
        lot = by_id[sim_lot.key]
        days_to_expiry = sim_lot.last_sellable_day
        sold = baseline.sold_by_lot.get(sim_lot.key, 0.0)
        beyond_horizon = days_to_expiry >= baseline.days and baseline.stock_start[-1:].sum() > EPSILON if baseline.days else False
        at_risk = 0 if beyond_horizon else max(0, round_units(sim_lot.quantity - sold))

        ahead = [a for a in ordered[: rank - 1] if _expires_after(a, days_to_expiry)]
        # Dentro del mismo grupo (en liquidación o no) solo FIFO deja atrás a un lote que vence antes; un lote
        # en liquidación sale primero con cualquier rotación.
        blocking = [a for a in ahead if a.on_sale == sim_lot.on_sale] if rotation == FIFO else []
        liquidation = [a for a in ahead if a.on_sale and not sim_lot.on_sale]
        blocking_units = sum(a.quantity for a in blocking)
        dated = [a.last_sellable_day for a in blocking if a.last_sellable_day is not None]
        blocking_expiry = date.fromordinal(as_of.toordinal() + max(dated)) if dated else None

        risk = LotRisk(
            lot_id=sim_lot.key,
            lot_number=lot.lot_number,
            expiry_date=lot.expiry_date,
            received_at=lot.received_at,
            days_to_expiry=days_to_expiry,
            quantity=round_units(sim_lot.quantity),
            expected_sales_before_expiry=sold,
            units_at_risk=at_risk,
            risk_level=risk_level(at_risk, sim_lot.quantity, days_to_expiry, warning_days, critical_days),
            current_discount_pct=lot.discount_pct,
            rotation_rank=rank,
            first_sale_day=baseline.first_sale_day.get(sim_lot.key),
            blocking_units=blocking_units,
            blocking_expiry=blocking_expiry,
            liquidation_units=sum(a.quantity for a in liquidation),
        )
        if risk.rotation_blocked:
            if fefo_baseline is None:
                fefo_baseline = simulate(sim_lots, demand, FEFO, lifts=base_lifts)
            fefo_sold = fefo_baseline.sold_by_lot.get(sim_lot.key, 0.0)
            risk.units_at_risk_fefo = max(0, round_units(sim_lot.quantity - fefo_sold))

        if at_risk > 0 and days_to_expiry > 0:
            proposal = best_discount(assessment, demand, sim_lot, base_lifts, elasticity, max_discount_pct, lot.discount_pct)
            if proposal is not None:
                risk.recommended_discount_pct, risk.expected_units_sold_with_discount, risk.discount_clears_lot = proposal
        assessment.risks.append(risk)

    assessment.risks.sort(key=lambda r: (r.days_to_expiry, r.lot_id))
    return assessment
