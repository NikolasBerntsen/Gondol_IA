"""Construcción de recomendaciones accionables con prioridad, confianza, impacto y explicación en español."""

import math
import re
from dataclasses import dataclass
from datetime import date, timedelta

from ..schemas import AnalyzeSettings
from .context import ProductAnalysis
from .discounts import ElasticityModel, category_key
from .forecasting import CROSTON_SBA
from .formatting import fmt_date, fmt_day_month, fmt_days, fmt_money, fmt_num, fmt_pct, fmt_units, truncate
from .lots import FIFO, RISK_EXPIRED, LotRisk
from .patterns import DATOS_INSUFICIENTES

REORDER = "REORDER"
DISCOUNT = "DISCOUNT"
REMOVE_EXPIRED = "REMOVE_EXPIRED"
REVIEW_ANOMALY = "REVIEW_ANOMALY"
REDUCE_PURCHASE = "REDUCE_PURCHASE"

ANOMALY_REVIEW_DAYS = 7
REORDER_MARGIN_DAYS = 2
OVERSTOCK_FACTOR = 3.0
TITLE_MAX = 200
ABC_IMPORTANCE = {"A": 1.0, "B": 0.65, "C": 0.35}
TYPE_ORDER = {REMOVE_EXPIRED: 0, DISCOUNT: 1, REORDER: 2, REVIEW_ANOMALY: 3, REDUCE_PURCHASE: 4}
NO_DEMAND_RATE = 0.05
"""Por debajo de este ritmo (u/día) no se puede estimar la respuesta de las ventas a un descuento."""


@dataclass
class Recommendation:
    type: str
    product_id: int | None
    lot_id: int | None
    title: str
    explanation: str
    suggested_quantity: int | None
    suggested_discount_pct: int | None
    suggested_date: date | None
    expected_impact: float
    confidence: float
    base_priority: float
    """Prioridad por urgencia e importancia (sin el componente económico)."""
    value_weight: float
    """Puntos extra máximos según el impacto económico relativo dentro del mismo tipo."""
    dedupe_key: str
    priority: int = 0


def dedupe_key(kind: str, product_id: int | None = None, lot_id: int | None = None, day: date | None = None) -> str:
    """Claves de SPEC §8.2: el backend no duplica una recomendación PENDING con la misma clave en la sucursal."""
    if kind in (DISCOUNT, REMOVE_EXPIRED):
        return f"{kind}:{lot_id}"
    if kind == REVIEW_ANOMALY:
        return f"{kind}:{product_id}:{day.isoformat() if day else ''}"
    return f"{kind}:{product_id}"


def tidy(text: str) -> str:
    """Evita la doble puntuación que surge de abreviaturas al final de una oración ("12 u.." → "12 u.")."""
    return re.sub(r"\.\.(?!\.)", ".", re.sub(r"\s+", " ", text)).strip()


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _lot_label(lot_number: str | None) -> str:
    return f"el lote {lot_number}" if lot_number else "el lote sin número"


def _capitalize(text: str) -> str:
    return text[:1].upper() + text[1:]


def forecast_confidence(a: ProductAnalysis) -> float:
    """Confianza del pronóstico: historia disponible, error relativo y claridad del patrón."""
    history = min(1.0, a.features.n / 90.0)
    mean = max(a.plan.demand_rate, a.features.mean_recent, 0.05)
    fit = 1.0 / (1.0 + 0.5 * a.forecast.sigma / mean)
    score = 0.25 + 0.7 * (0.4 * history + 0.4 * fit + 0.2 * a.pattern.strength)
    if a.degraded:
        score *= 0.6
    return _clamp(score, 0.2, 0.95)


def _reorder(a: ProductAnalysis, as_of: date, model: ElasticityModel) -> Recommendation | None:
    plan = a.plan
    product = a.product
    if not plan.has_demand or plan.suggested_order_qty <= 0:
        return None
    stock = plan.stock
    lead = plan.lead_time_days
    stockout_soon = plan.stockout_day is not None and plan.stockout_day < lead + REORDER_MARGIN_DAYS
    below_rop = stock <= plan.reorder_point
    if not (below_rop or stockout_soon):
        return None

    if stock <= 0:
        urgency = 1.0
    elif plan.stockout_day is not None and plan.stockout_day <= lead:
        urgency = 0.9
    elif stockout_soon:
        urgency = 0.75
    else:
        urgency = 0.5 + 0.25 * (1.0 - stock / max(plan.reorder_point, 1))
    importance = ABC_IMPORTANCE.get(a.abc_class, 0.35)

    qty = plan.suggested_order_qty
    order_day = plan.reorder_day if plan.reorder_day is not None else 0
    if plan.stockout_day is not None:
        order_day = min(order_day, max(0, plan.stockout_day - lead))
    suggested = as_of + timedelta(days=order_day)
    when = "hoy" if order_day == 0 else f"antes del {fmt_day_month(suggested, as_of)}"

    rate = f"{fmt_num(plan.demand_rate)} u/día"
    if abs(a.trend_pct) >= 10:
        rate += f", tendencia {fmt_pct(a.trend_pct, signed=True)}"
    lead_text = "el proveedor entrega en el día" if lead == 0 else f"el proveedor tarda {fmt_days(lead)}"
    cover = plan.days_of_cover
    since = a.censoring.since
    if stock <= 0 and since is not None:
        text = (
            f"No hay stock vendible desde el {fmt_day_month(since, as_of)} y la demanda es de {rate} "
            f"(los días sin stock no se toman como una caída de la venta). Sugerimos pedir {fmt_units(qty)} {when} ({lead_text})."
        )
    elif stock <= 0:
        text = f"No queda stock vendible y se venden {rate}. Sugerimos pedir {fmt_units(qty)} {when} ({lead_text})."
    elif cover is not None:
        text = (
            f"Al ritmo actual ({rate}) el stock de {fmt_units(stock)} alcanza para {fmt_days(cover)} y {lead_text}. "
            f"Sugerimos pedir {fmt_units(qty)} {when}."
        )
    else:
        text = (
            f"El stock ({fmt_units(stock)}) llegó al punto de pedido ({fmt_units(plan.reorder_point)}) con una venta de {rate} "
            f"y {lead_text}. Sugerimos pedir {fmt_units(qty)} {when}."
        )
    wasted = stock - plan.usable_stock
    if wasted >= 0.5:
        text += f" Se descontaron {fmt_units(wasted)} que vencerían antes de venderse"
        if plan.stockout_day is not None and cover is not None and plan.stockout_day + 1 < cover:
            text += f", por eso el faltante se espera el {fmt_day_month(as_of + timedelta(days=plan.stockout_day), as_of)}"
        text += "."
    if plan.effective_coverage_days < plan.coverage_target_days and plan.shelf_life_days:
        text += (
            f" El pedido cubre {fmt_days(plan.effective_coverage_days)} en lugar de {fmt_days(plan.coverage_target_days)} "
            f"porque la vida útil habitual del producto es de {fmt_days(plan.shelf_life_days)}."
        )
    if product.min_stock > 0 and plan.reorder_point <= math.ceil(product.min_stock) and stock <= product.min_stock:
        text += f" El stock mínimo configurado es de {fmt_units(product.min_stock)}."

    confidence = forecast_confidence(a) * model.acceptance_factor(REORDER)
    return Recommendation(
        type=REORDER,
        product_id=product.product_id,
        lot_id=None,
        title=truncate(f"Reponer {product.name}", TITLE_MAX),
        explanation=text,
        suggested_quantity=qty,
        suggested_discount_pct=None,
        suggested_date=suggested,
        expected_impact=plan.unmet_units * product.sale_price,
        confidence=confidence,
        base_priority=50 + 30 * urgency + 6 * importance,
        value_weight=4,
        dedupe_key=dedupe_key(REORDER, product.product_id),
    )


def _rotation_context(risk: LotRisk) -> str:
    """Explica por qué, con FIFO, un lote más nuevo que vence antes queda sin vender."""
    older = f"hay {fmt_units(risk.blocking_units)} de lotes que ingresaron antes y vencen después"
    if risk.blocking_expiry is not None:
        older += f" (hasta el {fmt_date(risk.blocking_expiry)})"
    if risk.first_sale_day is None:
        consequence = "así que este lote no llegaría a venderse antes de vencer"
    elif risk.first_sale_day <= 0:
        consequence = "así que este lote se vendería en paralelo con ellos"
    else:
        consequence = f"así que este lote empezaría a venderse recién en {fmt_days(risk.first_sale_day)}"
    return f"Con rotación FIFO primero sale la mercadería que entró antes: {older}, {consequence}. "


def _liquidation_context(risk: LotRisk) -> str:
    """Explica que los lotes en liquidación (con descuento aceptado) se venden antes que este (SPEC §4.2)."""
    return (
        f"Los lotes en liquidación salen primero: hay {fmt_units(risk.liquidation_units)} con descuento "
        "que se venden antes que este lote. "
    )


def _discounts(a: ProductAnalysis, as_of: date, settings: AnalyzeSettings, model: ElasticityModel) -> list[Recommendation]:
    product = a.product
    recs: list[Recommendation] = []
    observations = model.observations_for(product.category)
    importance = ABC_IMPORTANCE.get(a.abc_class, 0.35)
    for risk in a.lots.risks:
        if risk.risk_level == RISK_EXPIRED or risk.units_at_risk <= 0 or risk.days_to_expiry <= 0:
            continue
        if risk.recommended_discount_pct is None:
            continue
        pct = risk.recommended_discount_pct
        sold_with = risk.expected_units_sold_with_discount or 0.0
        base = risk.expected_sales_before_expiry
        helps = sold_with > base + 0.5
        if not helps and risk.days_to_expiry > settings.expiry_warning_days:
            # Sin demanda que reaccione al precio y con vencimiento lejano no hay descuento útil que sugerir.
            continue
        price = product.sale_price
        current_factor = 1 - (risk.current_discount_pct or 0.0) / 100.0
        if helps:
            impact = max(0.0, sold_with * price * (1 - pct / 100.0) - base * price * current_factor)
        else:
            # El modelo no puede estimar ventas extra: el impacto es lo que está en juego a costo.
            impact = risk.units_at_risk * product.cost_price
        rotation_issue = a.lots.rotation == FIFO and risk.rotation_issue
        no_demand = a.plan.demand_rate < NO_DEMAND_RATE
        sells_out = sold_with >= risk.quantity - 0.5

        days = risk.days_to_expiry
        if days <= settings.expiry_critical_days:
            urgency = 1.0
        elif days <= settings.expiry_warning_days:
            urgency = 0.7
        else:
            urgency = _clamp(0.4 * settings.expiry_warning_days / days, 0.1, 0.4)
        share = risk.units_at_risk / max(risk.quantity, 1)

        text = (
            f"{_capitalize(_lot_label(risk.lot_number))} vence el {fmt_date(risk.expiry_date)} "
            f"(en {fmt_days(days)}) y tiene {fmt_units(risk.quantity)}. "
        )
        if risk.liquidation_units > 0.5:
            text += _liquidation_context(risk)
        if rotation_issue:
            text += _rotation_context(risk)
        text += (
            f"Al ritmo actual se venderían {fmt_units(base, 1)} antes del vencimiento y quedan {fmt_units(risk.units_at_risk)} en riesgo "
            f"({fmt_money(risk.units_at_risk * product.cost_price)} a costo). "
        )
        # Al aceptar el descuento el lote pasa a estar en liquidación y se vende antes que el resto (SPEC §4.2).
        jumps_queue = not risk.current_discount_pct and (risk.rotation_rank or 1) > 1
        display = ", que lo pone primero en la fila de venta," if jumps_queue else ""
        if risk.discount_clears_lot:
            text += f"Con {pct}% de descuento{display} se estima vender {fmt_units(sold_with, 1)}, suficiente para liquidar el lote."
        elif sells_out:
            text += (
                f"Con {pct}% de descuento{display} se liquidaría el lote, pero se demoraría la venta de otros lotes y parte "
                "de ellos vencería igual: hay más stock del que se puede vender a tiempo, así que conviene además achicar "
                "los próximos pedidos."
            )
        elif helps:
            text += (
                f"Con el descuento máximo permitido ({pct}%){display} se venderían {fmt_units(sold_with, 1)}: "
                "conviene además exhibirlo en un lugar visible u ofrecerlo en combos."
            )
        elif no_demand:
            text += (
                "Como casi no registra ventas, no se puede estimar cuánto ayudaría un descuento: "
                f"aplicá el máximo permitido ({pct}%), ofrecelo en combos o evaluá donarlo antes del vencimiento."
            )
        else:
            text += (
                f"Con el ritmo de venta actual ni siquiera un {pct}% de descuento alcanzaría: "
                "probá armar combos o donarlo antes del vencimiento."
            )
        if rotation_issue:
            text += (
                f" Con rotación FEFO (primero lo que vence antes) quedarían {fmt_units(risk.units_at_risk_fefo)} en riesgo "
                f"en lugar de {fmt_units(risk.units_at_risk)}: evaluá cambiarla en Configuración."
            )
        if not no_demand:
            learned = f", ajustada con {observations} {'resultado medido' if observations == 1 else 'resultados medidos'} de descuentos" if observations else ""
            text += f" Elasticidad usada para {category_key(product.category)}: {fmt_num(a.elasticity, 1, trim=False)}{learned}."
        if risk.current_discount_pct:
            text += f" Hoy el lote tiene {fmt_num(risk.current_discount_pct)}% de descuento."

        confidence = forecast_confidence(a) * model.confidence_factor(product.category) * model.acceptance_factor(DISCOUNT)
        if not risk.discount_clears_lot:
            confidence *= 0.8 if helps else 0.6
        label = f"lote {risk.lot_number}" if risk.lot_number else f"vence {fmt_day_month(risk.expiry_date, as_of)}"
        if helps:
            title = f"Aplicar {pct}% de descuento a {product.name} ({label})"
        else:
            title = f"Liquidar {product.name} antes del vencimiento ({label})"
        recs.append(
            Recommendation(
                type=DISCOUNT,
                product_id=product.product_id,
                lot_id=risk.lot_id,
                title=truncate(title, TITLE_MAX),
                explanation=text,
                suggested_quantity=None,
                suggested_discount_pct=pct,
                suggested_date=as_of,
                expected_impact=impact,
                confidence=confidence,
                base_priority=45 + 28 * urgency + 6 * share + 5 * importance - (0 if helps else 8),
                value_weight=6,
                dedupe_key=dedupe_key(DISCOUNT, lot_id=risk.lot_id),
            )
        )
    return recs


def _remove_expired(a: ProductAnalysis, as_of: date) -> list[Recommendation]:
    product = a.product
    recs: list[Recommendation] = []
    for risk in a.lots.risks:
        if risk.risk_level != RISK_EXPIRED or risk.quantity <= 0:
            continue
        days_ago = -risk.days_to_expiry
        cost_value = risk.quantity * product.cost_price
        text = (
            f"{_capitalize(_lot_label(risk.lot_number))} venció el {fmt_date(risk.expiry_date)} "
            f"(hace {fmt_days(days_ago)}) y todavía figuran {fmt_units(risk.quantity)} ({fmt_money(cost_value)} a costo). "
            "Los lotes vencidos no se venden: retiralo de la góndola y registrá la merma para que el stock refleje la realidad."
        )
        recs.append(
            Recommendation(
                type=REMOVE_EXPIRED,
                product_id=product.product_id,
                lot_id=risk.lot_id,
                title=truncate(f"Retirar lote vencido de {product.name}", TITLE_MAX),
                explanation=text,
                suggested_quantity=risk.quantity,
                suggested_discount_pct=None,
                suggested_date=as_of,
                expected_impact=cost_value,
                confidence=0.99,
                base_priority=92 + min(8, days_ago),
                value_weight=0,
                dedupe_key=dedupe_key(REMOVE_EXPIRED, lot_id=risk.lot_id),
            )
        )
    return recs


def _review_anomalies(a: ProductAnalysis, as_of: date) -> list[Recommendation]:
    product = a.product
    importance = ABC_IMPORTANCE.get(a.abc_class, 0.35)
    recs: list[Recommendation] = []
    cutoff = as_of - timedelta(days=ANOMALY_REVIEW_DAYS)
    for anomaly in a.anomalies:
        if anomaly.day < cutoff:
            continue
        strength = _clamp((anomaly.score - 3.5) / 6.5, 0.0, 1.0)
        days_ago = (as_of - anomaly.day).days
        when = "Ayer" if days_ago == 1 else f"El {fmt_day_month(anomaly.day, as_of)}"
        forest = " Isolation Forest también lo marcó como atípico." if anomaly.confirmed_by_forest else ""
        if anomaly.kind == "SPIKE":
            ratio = anomaly.quantity / anomaly.expected if anomaly.expected >= 0.1 else None
            times = f" ({fmt_num(ratio)} veces lo normal)" if ratio else ""
            title = f"Revisar pico de ventas de {product.name}"
            reference = (
                f"una venta típica de este producto es de {fmt_units(anomaly.expected, 1)}"
                if a.forecast.method == CROSTON_SBA
                else f"lo esperable era {fmt_units(anomaly.expected, 1)}"
            )
            text = (
                f"{when} se vendieron {fmt_units(anomaly.quantity)} cuando {reference}{times}. "
                f"Verificá si fue una venta mayorista, una promoción o un error de carga.{forest}"
            )
        else:
            title = f"Revisar caída de ventas de {product.name}"
            text = (
                f"{when} se vendieron {fmt_units(anomaly.quantity)} cuando lo esperable era {fmt_units(anomaly.expected, 1)}. "
                f"Revisá si hubo faltante en góndola, un problema con el punto de venta o un error de carga.{forest}"
            )
        recs.append(
            Recommendation(
                type=REVIEW_ANOMALY,
                product_id=product.product_id,
                lot_id=None,
                title=truncate(title, TITLE_MAX),
                explanation=text,
                suggested_quantity=None,
                suggested_discount_pct=None,
                suggested_date=anomaly.day,
                expected_impact=abs(anomaly.quantity - anomaly.expected) * product.sale_price,
                confidence=_clamp(0.55 + 0.25 * strength + (0.1 if anomaly.confirmed_by_forest else 0.0), 0.3, 0.9),
                base_priority=30 + 18 * strength + 8 * importance + (3 if anomaly.confirmed_by_forest else 0) + (3 if days_ago <= 2 else 0),
                value_weight=0,
                dedupe_key=dedupe_key(REVIEW_ANOMALY, product.product_id, day=anomaly.day),
            )
        )
    return recs


def _reduce_purchase(a: ProductAnalysis, as_of: date, settings: AnalyzeSettings, model: ElasticityModel) -> Recommendation | None:
    product = a.product
    plan = a.plan
    stock = plan.stock
    if not product.perishable or stock <= 0 or a.pattern.pattern == DATOS_INSUFICIENTES:
        return None
    target = plan.coverage_target_days
    cover = plan.days_of_cover
    if not plan.has_demand:
        if a.features.n < 28:
            return None
    elif cover is None or cover <= OVERSTOCK_FACTOR * target:
        return None

    needed = math.ceil(plan.order_up_to) if plan.has_demand else 0
    excess = max(1, math.ceil(stock - needed))
    capital = excess * product.cost_price
    if not plan.has_demand:
        days_without = a.features.days_since_last_sale or a.features.n
        text = (
            f"No registra ventas en los últimos {fmt_days(days_without)} y tiene {fmt_units(stock)} en stock "
            f"({fmt_money(stock * product.cost_price)} a costo). Como tiene vencimiento, suspendé las compras hasta que vuelva a venderse."
        )
        suggested_date = None
        overstock = 1.0
    else:
        next_order = as_of + timedelta(days=plan.reorder_day) if plan.reorder_day is not None else None
        until = f" hasta el {fmt_day_month(next_order, as_of)}" if next_order else ""
        text = (
            f"Con {fmt_units(stock)} en stock y una venta de {fmt_num(plan.demand_rate)} u/día hay mercadería para {fmt_days(cover)} "
            f"(el objetivo es {fmt_days(target)}). Como tiene vencimiento, conviene no volver a pedir{until} y achicar los próximos pedidos: "
            f"sobran unas {fmt_units(excess)} ({fmt_money(capital)} inmovilizados a costo)."
        )
        expiring = stock - plan.usable_stock
        if expiring >= 0.5:
            text += f" Además, {fmt_units(expiring)} vencerían antes de venderse."
        suggested_date = next_order
        overstock = _clamp(cover / (OVERSTOCK_FACTOR * target) - 1.0, 0.0, 1.0)
    return Recommendation(
        type=REDUCE_PURCHASE,
        product_id=product.product_id,
        lot_id=None,
        title=truncate(f"Reducir compras de {product.name}", TITLE_MAX),
        explanation=text,
        suggested_quantity=excess,
        suggested_discount_pct=None,
        suggested_date=suggested_date,
        expected_impact=capital,
        confidence=forecast_confidence(a) * 0.9 * model.acceptance_factor(REDUCE_PURCHASE),
        base_priority=20 + 15 * overstock,
        value_weight=10,
        dedupe_key=dedupe_key(REDUCE_PURCHASE, product.product_id),
    )


def build_recommendations(
    analyses: list[ProductAnalysis], as_of: date, settings: AnalyzeSettings, model: ElasticityModel
) -> list[Recommendation]:
    recs: list[Recommendation] = []
    for a in analyses:
        recs.extend(_remove_expired(a, as_of))
        recs.extend(_discounts(a, as_of, settings, model))
        reorder = _reorder(a, as_of, model)
        if reorder is not None:
            recs.append(reorder)
        else:
            reduce = _reduce_purchase(a, as_of, settings, model)
            if reduce is not None:
                recs.append(reduce)
        recs.extend(_review_anomalies(a, as_of))
    return finalize(recs)


def finalize(recs: list[Recommendation]) -> list[Recommendation]:
    """Suma el componente económico a la prioridad, deduplica por `dedupeKey` y ordena."""
    max_impact: dict[str, float] = {}
    for r in recs:
        r.expected_impact = round(max(0.0, r.expected_impact if math.isfinite(r.expected_impact) else 0.0), 2)
        r.explanation = tidy(r.explanation)
        max_impact[r.type] = max(max_impact.get(r.type, 0.0), r.expected_impact)
    unique: dict[str, Recommendation] = {}
    for r in recs:
        top = max_impact.get(r.type, 0.0)
        value_score = math.log1p(r.expected_impact) / math.log1p(top) if top > 0 else 0.0
        r.priority = int(round(_clamp(r.base_priority + r.value_weight * value_score, 1, 100)))
        r.confidence = round(_clamp(r.confidence if math.isfinite(r.confidence) else 0.5, 0.05, 0.99), 3)
        current = unique.get(r.dedupe_key)
        if current is None or r.priority > current.priority:
            unique[r.dedupe_key] = r
    return sorted(unique.values(), key=lambda r: (-r.priority, TYPE_ORDER.get(r.type, 9), r.product_id or 0, r.lot_id or 0))
