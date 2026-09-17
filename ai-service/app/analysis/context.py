"""Resultado intermedio del análisis de un producto, compartido entre las etapas del pipeline."""

from dataclasses import dataclass, field

import numpy as np

from ..schemas import ProductInput
from .anomalies import Anomaly
from .forecasting import ForecastResult
from .inventory import InventoryPlan
from .lots import LotAssessment
from .patterns import PatternResult, SeriesFeatures
from .series import DailySeries


@dataclass
class ProductAnalysis:
    product: ProductInput
    series: DailySeries
    features: SeriesFeatures
    forecast: ForecastResult
    demand: np.ndarray
    """Demanda esperada desde hoy (hoy = índice 0), ya descontadas las ventas de hoy."""
    pattern: PatternResult
    lots: LotAssessment
    plan: InventoryPlan
    elasticity: float
    abc_class: str = "C"
    xyz_class: str = "Z"
    anomalies: list[Anomaly] = field(default_factory=list)
    degraded: bool = False
    """True si hubo que usar un cálculo simplificado por un error inesperado con los datos del producto."""

    @property
    def product_id(self) -> int:
        return self.product.product_id
