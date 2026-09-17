"""Orquestación del análisis de una sucursal: de las ventas y lotes a insights y recomendaciones."""

import logging
import math
import time
from collections import Counter
from datetime import date, datetime, timedelta, timezone

import numpy as np

from .. import MODEL_VERSION
from ..schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    AnalyzeSettings,
    AnalyzeSummary,
    AnomalyOut,
    ForecastPoint,
    LotRiskOut,
    ProductInput,
    ProductInsightOut,
    RecommendationOut,
)
from .abcxyz import REVENUE_WINDOW_DAYS, classify_abc, classify_xyz
from .anomalies import detect_anomalies
from .context import ProductAnalysis
from .discounts import DEFAULT_ELASTICITY, ElasticityModel
from .forecasting import CROSTON_SBA, FORECAST_DAYS, HOLT_WINTERS, WMA, ZERO, ForecastResult, clip_outliers, forecast
from .formatting import fmt_date, fmt_num
from .inventory import MAX_COVER_DAYS, demand_path, estimate_shelf_life, plan_inventory
from .lots import FIFO, RISK_EXPIRED, assess_lots
from .patterns import ALL_PATTERNS, classify, compute_features, refine_with_clusters
from .recommendations import Recommendation, build_recommendations
from .series import DailySeries, SaleRecord, build_daily_series

log = logging.getLogger(__name__)

STOCKOUT_SOON_DAYS = 7
ANOMALY_SUMMARY_DAYS = 30
METHOD_LABELS = {
    HOLT_WINTERS: "con Holt-Winters",
    CROSTON_SBA: "con Croston/SBA",
    WMA: "con media móvil ponderada",
    ZERO: "sin ventas para pronosticar",
}
INT_MAX = 2_147_483_647
"""Las cantidades viajan como `int` de Java y se guardan en columnas INT."""
MONEY_MAX = 999_999_999_999.99
"""Tope de NUMERIC(14,2) (impacto esperado y valores monetarios)."""
AVG_SALES_MAX = 9_999_999.99
"""Tope de NUMERIC(10,3) (`product_insights.avg_daily_sales`)."""


def _int(value: float | int) -> int:
    return int(max(-INT_MAX, min(INT_MAX, value)))


def _money(value: float) -> float:
    return round(max(-MONEY_MAX, min(MONEY_MAX, float(value))), 2) if math.isfinite(value) else 0.0


def _plural(count: int, singular: str, plural: str) -> str:
    return f"{count} {singular if count == 1 else plural}"


def _analyze_product(
    product: ProductInput,
    as_of: date,
    settings: AnalyzeSettings,
    model: ElasticityModel,
    simplified: bool = False,
) -> ProductAnalysis:
    sales = [SaleRecord(s.date, s.quantity, s.discount_pct or 0.0) for s in product.daily_sales]
    series = build_daily_series(sales, as_of, product.created_at)
    features = compute_features(series)
    # Patrón, promedio y pronóstico se calculan con los picos extremos acotados (una venta mayorista o un
    # error de carga no deben definir el comportamiento del producto); las anomalías usan la serie original.
    model_series = series
    clipped = clip_outliers(series, features)
    if not np.array_equal(clipped, series.values):
        model_series = DailySeries(series.start, series.as_of, clipped, series.discount, series.sold_today)
        features = compute_features(model_series)
    pattern = classify(features)
    if simplified:
        level = float(model_series.tail(28).mean()) if model_series.n else 0.0
        fc = ForecastResult(WMA, np.full(series.n, np.nan), np.full(FORECAST_DAYS, level), math.sqrt(level), 0.0)
    else:
        fc = forecast(model_series, features)
    demand = demand_path(fc, series.sold_today)
    elasticity = model.for_category(product.category)
    lots = assess_lots(
        product.lots,
        product.sellable_stock,
        demand,
        as_of,
        settings.stock_rotation,
        settings.expiry_warning_days,
        settings.expiry_critical_days,
        elasticity,
        settings.max_discount_pct,
    )
    lead_time = product.lead_time_days if product.lead_time_days is not None else settings.lead_time_days
    plan = plan_inventory(
        fc,
        demand,
        product.sellable_stock,
        product.min_stock,
        lead_time,
        settings.target_coverage_days,
        settings.service_level,
        lots.baseline,
        estimate_shelf_life(product.lots) if product.perishable else None,
    )
    return ProductAnalysis(
        product=product,
        series=series,
        features=features,
        forecast=fc,
        demand=demand,
        pattern=pattern,
        lots=lots,
        plan=plan,
        elasticity=elasticity,
        degraded=simplified,
    )


def _revenue(product: ProductInput, as_of: date) -> float:
    since = as_of - timedelta(days=REVENUE_WINDOW_DAYS)
    return sum(
        s.quantity * product.sale_price * (1.0 - (s.discount_pct or 0.0) / 100.0)
        for s in product.daily_sales
        if since <= s.date <= as_of
    )


def _round(value: float | None, decimals: int) -> float | None:
    if value is None or not math.isfinite(value):
        return None
    return round(float(value), decimals)


def _product_out(a: ProductAnalysis, as_of: date, horizon: int) -> ProductInsightOut:
    points = []
    for step in range(1, horizon + 1):
        lo, hi = a.forecast.interval(step)
        points.append(
            ForecastPoint(date=as_of + timedelta(days=step), yhat=round(float(a.forecast.path[step]), 2), lo=round(lo, 2), hi=round(hi, 2))
        )
    plan = a.plan
    cover = _round(min(plan.days_of_cover, MAX_COVER_DAYS), 1) if plan.days_of_cover is not None else None
    return ProductInsightOut(
        product_id=a.product_id,
        pattern=a.pattern.pattern,
        pattern_description=a.pattern.description,
        abc_class=a.abc_class,
        xyz_class=a.xyz_class,
        avg_daily_sales=round(min(a.features.mean_recent, AVG_SALES_MAX), 2),
        trend_pct=round(a.features.trend_pct, 1),
        weekday_profile=[round(float(v), 2) for v in a.features.profile],
        forecast_method=a.forecast.method,
        forecast=points,
        days_of_cover=cover,
        predicted_stockout_date=as_of + timedelta(days=plan.stockout_day) if plan.stockout_day is not None else None,
        reorder_point=_int(plan.reorder_point),
        safety_stock=_int(plan.safety_stock),
        suggested_order_qty=_int(plan.suggested_order_qty),
        anomalies=[
            AnomalyOut(
                date=anomaly.day,
                quantity=_int(round(anomaly.quantity)),
                expected=round(anomaly.expected, 2),
                score=round(anomaly.score, 2),
                kind=anomaly.kind,
            )
            for anomaly in a.anomalies
        ],
        lot_risks=[
            LotRiskOut(
                lot_id=risk.lot_id,
                days_to_expiry=_int(risk.days_to_expiry),
                quantity=_int(risk.quantity),
                expected_sales_before_expiry=round(risk.expected_sales_before_expiry, 1),
                units_at_risk=_int(risk.units_at_risk),
                risk_level=risk.risk_level,
                recommended_discount_pct=risk.recommended_discount_pct,
                expected_units_sold_with_discount=_round(risk.expected_units_sold_with_discount, 1),
            )
            for risk in a.lots.risks
        ],
    )


def _recommendation_out(r: Recommendation) -> RecommendationOut:
    return RecommendationOut(
        type=r.type,
        product_id=r.product_id,
        lot_id=r.lot_id,
        priority=r.priority,
        confidence=r.confidence,
        title=r.title,
        explanation=r.explanation,
        suggested_quantity=_int(r.suggested_quantity) if r.suggested_quantity is not None else None,
        suggested_discount_pct=r.suggested_discount_pct,
        suggested_date=r.suggested_date,
        expected_impact=_money(r.expected_impact),
        dedupe_key=r.dedupe_key,
    )


def _model_notes(
    request: AnalyzeRequest,
    analyses: list[ProductAnalysis],
    model: ElasticityModel,
    clusters: int,
    reassigned: int,
    anomalies_recent: int,
) -> list[str]:
    settings = request.settings
    rotation_text = "primero sale lo que entró antes" if settings.stock_rotation == FIFO else "primero sale lo que vence antes"
    branch = request.branch_name or (f"Sucursal {request.branch_id}" if request.branch_id is not None else "Sucursal")
    analyzed = _plural(len(analyses), "producto analizado", "productos analizados")
    notes = [f"{branch}: {analyzed} al {fmt_date(request.as_of_date)} con rotación {settings.stock_rotation} ({rotation_text})."]
    if analyses:
        methods = Counter(a.forecast.method for a in analyses)
        parts = [f"{count} {METHOD_LABELS.get(method, method)}" for method, count in methods.most_common()]
        notes.append("Pronósticos: " + ", ".join(parts) + ".")
    if clusters:
        doubtful = _plural(reassigned, "caso dudoso reclasificado", "casos dudosos reclasificados")
        notes.append(f"KMeans agrupó los productos en {clusters} perfiles de venta ({doubtful} según su grupo).")
    notes.append(
        f"Anomalías de los últimos {ANOMALY_SUMMARY_DAYS} días: {anomalies_recent} "
        "(z-score robusto sobre los residuos del pronóstico, con Isolation Forest como segunda opinión)."
    )
    if model.observations:
        notes.append(
            f"La elasticidad por descuento se ajustó con {len(model.observations)} "
            f"{'resultado medido' if len(model.observations) == 1 else 'resultados medidos'} de descuentos aceptados."
        )
    else:
        notes.append(f"Todavía no hay resultados medidos de descuentos: se usa la elasticidad por defecto ({fmt_num(DEFAULT_ELASTICITY, 1, trim=False)}).")
    blocked = sum(1 for a in analyses for r in a.lots.risks if r.rotation_issue)
    if settings.stock_rotation == FIFO and blocked:
        notes.append(
            f"Con FIFO, {blocked} {'lote vence' if blocked == 1 else 'lotes vencen'} antes que mercadería que ingresó antes y "
            "quedaría sin vender: conviene exhibirlos adelante o evaluar la rotación FEFO."
        )
    expired = sum(1 for a in analyses for r in a.lots.risks if r.risk_level == RISK_EXPIRED)
    if expired:
        notes.append(f"Hay {expired} {'lote vencido' if expired == 1 else 'lotes vencidos'} con stock para retirar y registrar como merma.")
    degraded = sum(1 for a in analyses if a.degraded)
    if degraded:
        notes.append(f"{degraded} {'producto se analizó' if degraded == 1 else 'productos se analizaron'} con un método simplificado por datos inconsistentes.")
    return notes


def analyze(request: AnalyzeRequest, now: datetime | None = None) -> AnalyzeResponse:
    started = time.perf_counter()
    settings = request.settings
    as_of = request.as_of_date
    model = ElasticityModel.from_feedback(request.feedback)

    analyses: list[ProductAnalysis] = []
    for product in request.products:
        try:
            analyses.append(_analyze_product(product, as_of, settings, model))
        except Exception:
            log.exception(
                "Falló el análisis de un producto; se usa el método simplificado",
                extra={"tenantId": request.tenant_id, "branchId": request.branch_id, "productId": product.product_id},
            )
            analyses.append(_analyze_product(product, as_of, settings, model, simplified=True))

    for a, abc in zip(analyses, classify_abc([_revenue(a.product, as_of) for a in analyses])):
        a.abc_class = abc
        a.xyz_class = classify_xyz(a.features)

    patterns = [a.pattern for a in analyses]
    refinement = refine_with_clusters([a.features for a in analyses], patterns)
    for a, pattern in zip(analyses, patterns):
        a.pattern = pattern

    for a, anomalies in zip(analyses, detect_anomalies([(a.series, a.forecast) for a in analyses])):
        a.anomalies = anomalies

    recommendations = build_recommendations(analyses, as_of, settings, model)

    anomaly_cutoff = as_of - timedelta(days=ANOMALY_SUMMARY_DAYS)
    anomalies_recent = sum(1 for a in analyses for anomaly in a.anomalies if anomaly.day >= anomaly_cutoff)
    at_risk_value = sum(risk.units_at_risk * a.product.cost_price for a in analyses for risk in a.lots.risks)
    stockouts = sum(1 for a in analyses if a.plan.stockout_day is not None and a.plan.stockout_day < STOCKOUT_SOON_DAYS)
    pattern_counts = {pattern: 0 for pattern in ALL_PATTERNS}
    for a in analyses:
        pattern_counts[a.pattern.pattern] += 1

    generated_at = (now or datetime.now(timezone.utc)).astimezone(timezone.utc).replace(microsecond=0)
    response = AnalyzeResponse(
        model_version=MODEL_VERSION,
        generated_at=generated_at,
        products=[_product_out(a, as_of, settings.horizon_days) for a in analyses],
        recommendations=[_recommendation_out(r) for r in recommendations],
        summary=AnalyzeSummary(
            products_analyzed=len(analyses),
            at_risk_value=_money(at_risk_value),
            predicted_stockouts7d=stockouts,
            anomalies30d=anomalies_recent,
            pattern_counts=pattern_counts,
            elasticity_by_category=model.by_category({a.product.category for a in analyses}),
            model_notes=_model_notes(request, analyses, model, refinement.clusters, refinement.reassigned, anomalies_recent),
        ),
    )
    log.info(
        "Análisis completado",
        extra={
            "tenantId": request.tenant_id,
            "branchId": request.branch_id,
            "rotation": settings.stock_rotation,
            "products": len(analyses),
            "recommendations": len(recommendations),
            "durationMs": int((time.perf_counter() - started) * 1000),
        },
    )
    return response
