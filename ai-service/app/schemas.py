"""Contratos JSON del servicio (SPEC §8). Todo se serializa en camelCase."""

import math
from datetime import date, datetime, timezone
from typing import Annotated, Any, Literal

from pydantic import (
    AfterValidator,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    PlainSerializer,
    model_validator,
)


def _coerce_date(value: Any) -> Any:
    """Acepta fechas `YYYY-MM-DD` o instantes ISO-8601 (se toma la parte de fecha)."""
    if isinstance(value, datetime):
        return value.date()
    if isinstance(value, str) and len(value) > 10 and value[4] == "-" and value[10] in "T ":
        return value[:10]
    return value


def _as_utc(value: datetime | None) -> datetime | None:
    """Instantes sin zona horaria se interpretan como UTC para poder compararlos entre sí."""
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _upper_or_default(default: str):
    def normalize(value: Any) -> Any:
        if value is None or (isinstance(value, str) and not value.strip()):
            return default
        return value.strip().upper() if isinstance(value, str) else value

    return normalize


def _empty_if_null(value: Any) -> Any:
    """El backend puede serializar listas u objetos opcionales como `null`: se toman como vacíos."""
    return {} if value is None else value


def _empty_list_if_null(value: Any) -> Any:
    return [] if value is None else value


def _finite(value: float | None) -> float | None:
    if value is None:
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def _finite_or_zero(value: float) -> float:
    finite = _finite(value)
    return 0.0 if finite is None else finite


LenientDate = Annotated[date, BeforeValidator(_coerce_date)]
UtcDateTime = Annotated[datetime, AfterValidator(_as_utc)]
FiniteFloat = Annotated[float, PlainSerializer(_finite_or_zero, return_type=float)]
OptionalFiniteFloat = Annotated[float | None, PlainSerializer(_finite, return_type=float | None)]
StockRotation = Annotated[Literal["FIFO", "FEFO"], BeforeValidator(_upper_or_default("FIFO"))]
LotStatus = Annotated[str, BeforeValidator(_upper_or_default("ACTIVE"))]
NullableList = BeforeValidator(_empty_list_if_null)


def to_camel(name: str) -> str:
    """`units_before7d` -> `unitsBefore7d` (sin alterar las letras que siguen a dígitos)."""
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


class CamelModel(BaseModel):
    model_config = ConfigDict(
        alias_generator=to_camel,
        populate_by_name=True,
        serialize_by_alias=True,
        ser_json_inf_nan="null",
    )


# ---------------------------------------------------------------------------
# /v1/analyze - request
# ---------------------------------------------------------------------------
class AnalyzeSettings(CamelModel):
    stock_rotation: StockRotation = "FIFO"
    expiry_warning_days: int = Field(15, ge=0, le=365)
    expiry_critical_days: int = Field(5, ge=0, le=365)
    lead_time_days: int = Field(3, ge=0, le=365)
    target_coverage_days: int = Field(14, ge=0, le=365)
    """0 se interpreta como 1 día de cobertura."""
    service_level: float = Field(0.95, gt=0, le=1)
    max_discount_pct: float = Field(40, ge=0, le=100)
    horizon_days: int = Field(14, ge=1, le=90)


class DailySale(CamelModel):
    date: LenientDate
    quantity: float = Field(ge=0)
    discount_pct: float | None = Field(0, ge=0, le=100)


class LotInput(CamelModel):
    lot_id: int
    lot_number: str | None = None
    expiry_date: LenientDate | None = None
    received_at: UtcDateTime | None = None
    quantity: float = Field(ge=0)
    discount_pct: float | None = Field(None, ge=0, le=100)
    status: LotStatus = "ACTIVE"


class ProductInput(CamelModel):
    product_id: int
    name: str
    category: str | None = None
    sale_price: float = Field(0, ge=0)
    cost_price: float = Field(0, ge=0)
    min_stock: float = Field(0, ge=0)
    sellable_stock: float = 0
    perishable: bool = True
    lead_time_days: int | None = Field(None, ge=0, le=365)
    created_at: LenientDate | None = None
    daily_sales: Annotated[list[DailySale], NullableList] = Field(default_factory=list)
    lots: Annotated[list[LotInput], NullableList] = Field(default_factory=list)


class FeedbackOutcome(CamelModel):
    model_config = ConfigDict(extra="allow")

    units_before7d: float | None = None
    units_after7d: float | None = None
    lift: float | None = None
    lot_units_sold: float | None = None
    lot_units_remaining: float | None = None


class FeedbackItem(CamelModel):
    recommendation_id: int | None = None
    type: str
    product_id: int | None = None
    category: str | None = None
    status: str
    discount_pct: float | None = None
    outcome: FeedbackOutcome | None = None


class AnalyzeRequest(CamelModel):
    tenant_id: int
    branch_id: int | None = None
    branch_name: str | None = None
    as_of_date: LenientDate
    settings: Annotated[AnalyzeSettings, BeforeValidator(_empty_if_null)] = Field(default_factory=AnalyzeSettings)
    products: Annotated[list[ProductInput], NullableList] = Field(default_factory=list, max_length=20000)
    feedback: Annotated[list[FeedbackItem], NullableList] = Field(default_factory=list)

    @model_validator(mode="after")
    def _unique_products(self) -> "AnalyzeRequest":
        seen: set[int] = set()
        for product in self.products:
            if product.product_id in seen:
                raise ValueError(f"El producto {product.product_id} está repetido en el pedido")
            seen.add(product.product_id)
        return self


# ---------------------------------------------------------------------------
# /v1/analyze - response
# ---------------------------------------------------------------------------
class ForecastPoint(CamelModel):
    date: date
    yhat: FiniteFloat
    lo: FiniteFloat
    hi: FiniteFloat


class AnomalyOut(CamelModel):
    date: date
    quantity: int
    expected: FiniteFloat
    score: FiniteFloat
    kind: str


class LotRiskOut(CamelModel):
    lot_id: int
    days_to_expiry: int
    quantity: int
    expected_sales_before_expiry: FiniteFloat
    units_at_risk: int
    risk_level: str
    recommended_discount_pct: int | None = None
    expected_units_sold_with_discount: OptionalFiniteFloat = None


class ProductInsightOut(CamelModel):
    product_id: int
    pattern: str
    pattern_description: str
    abc_class: str
    xyz_class: str
    avg_daily_sales: FiniteFloat
    trend_pct: FiniteFloat
    weekday_profile: list[FiniteFloat]
    forecast_method: str
    forecast: list[ForecastPoint]
    days_of_cover: OptionalFiniteFloat = None
    predicted_stockout_date: date | None = None
    reorder_point: int
    safety_stock: int
    suggested_order_qty: int
    anomalies: list[AnomalyOut]
    lot_risks: list[LotRiskOut]


class RecommendationOut(CamelModel):
    type: str
    product_id: int | None = None
    lot_id: int | None = None
    priority: int
    confidence: FiniteFloat
    title: str
    explanation: str
    suggested_quantity: int | None = None
    suggested_discount_pct: int | None = None
    suggested_date: date | None = None
    expected_impact: FiniteFloat
    dedupe_key: str


class AnalyzeSummary(CamelModel):
    products_analyzed: int
    at_risk_value: FiniteFloat
    predicted_stockouts7d: int
    anomalies30d: int
    pattern_counts: dict[str, int]
    elasticity_by_category: dict[str, FiniteFloat]
    model_notes: list[str]


class AnalyzeResponse(CamelModel):
    model_version: str
    generated_at: datetime
    products: list[ProductInsightOut]
    recommendations: list[RecommendationOut]
    summary: AnalyzeSummary


# ---------------------------------------------------------------------------
# OCR y códigos de barras
# ---------------------------------------------------------------------------
class DateCandidate(CamelModel):
    value: date
    raw: str
    confidence: FiniteFloat


class LotCandidate(CamelModel):
    value: str
    raw: str
    confidence: FiniteFloat


class BarcodeOut(CamelModel):
    value: str
    format: str


class OcrResponse(CamelModel):
    text: str
    lines: list[str]
    expiry_dates: list[DateCandidate]
    lot_numbers: list[LotCandidate]
    manufacture_dates: list[DateCandidate]
    barcodes: list[BarcodeOut]
    product_name_candidates: list[str]
    processing_ms: int


class BarcodeResponse(CamelModel):
    barcodes: list[BarcodeOut]


class HealthResponse(CamelModel):
    status: str
    version: str
    model_version: str
    tesseract: bool


class FieldError(CamelModel):
    field: str
    message: str


class ErrorResponse(CamelModel):
    code: str
    message: str
    field_errors: list[FieldError] | None = None
