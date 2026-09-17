"""Elasticidad precio por categoría, aprendida a partir del resultado real de los descuentos aceptados."""

import math
from collections import defaultdict
from dataclasses import dataclass, field

from ..schemas import FeedbackItem

DEFAULT_ELASTICITY = 2.0
"""+2% de ventas por cada 1% de descuento cuando todavía no hay evidencia propia."""
PRIOR_WEIGHT = 20.0
"""Peso de la elasticidad previa, en unidades vendidas equivalentes (evita sobre-reaccionar a un caso)."""
GLOBAL_PRIOR_WEIGHT = 60.0
"""La evidencia de otras categorías se transfiere con más cautela que la de la propia categoría."""
MIN_ELASTICITY = 0.0
MAX_ELASTICITY = 8.0
MAX_OBSERVATION_WEIGHT = 150.0
NO_CATEGORY = "Sin categoría"


def category_key(category: str | None) -> str:
    return category.strip() if category and category.strip() else NO_CATEGORY


@dataclass
class Observation:
    category: str
    elasticity: float
    weight: float


def observation_from_feedback(item: FeedbackItem) -> Observation | None:
    """Elasticidad observada = (lift − 1) / (descuento / 100), ponderada por las unidades medidas."""
    if item.type != "DISCOUNT" or item.status != "ACCEPTED" or item.outcome is None:
        return None
    pct = item.discount_pct or 0.0
    if pct <= 0:
        return None
    outcome = item.outcome
    lift = outcome.lift
    if lift is None and outcome.units_before7d and outcome.units_after7d is not None and outcome.units_before7d > 0:
        lift = outcome.units_after7d / outcome.units_before7d
    if lift is None or not math.isfinite(lift) or lift <= 0:
        return None
    elasticity = min(MAX_ELASTICITY, max(MIN_ELASTICITY, (lift - 1.0) / (pct / 100.0)))
    units = (outcome.units_before7d or 0.0) + (outcome.units_after7d or 0.0)
    weight = min(MAX_OBSERVATION_WEIGHT, max(1.0, units))
    return Observation(category_key(item.category), elasticity, weight)


def _shrink(prior: float, observations: list[Observation], prior_weight: float = PRIOR_WEIGHT) -> float:
    total = sum(o.weight for o in observations)
    if total <= 0:
        return prior
    weighted = sum(o.weight * o.elasticity for o in observations)
    return (prior_weight * prior + weighted) / (prior_weight + total)


@dataclass
class ElasticityModel:
    observations: list[Observation] = field(default_factory=list)
    acceptance: dict[str, tuple[int, int]] = field(default_factory=dict)
    """Por tipo de recomendación: (aceptadas, descartadas)."""

    @classmethod
    def from_feedback(cls, feedback: list[FeedbackItem]) -> "ElasticityModel":
        observations = [obs for obs in (observation_from_feedback(item) for item in feedback) if obs is not None]
        counts: dict[str, list[int]] = defaultdict(lambda: [0, 0])
        for item in feedback:
            if item.status == "ACCEPTED":
                counts[item.type][0] += 1
            elif item.status == "DISCARDED":
                counts[item.type][1] += 1
        return cls(observations, {k: (v[0], v[1]) for k, v in counts.items()})

    def global_elasticity(self, exclude_category: str | None = None) -> float:
        pool = [o for o in self.observations if o.category != exclude_category]
        return _shrink(DEFAULT_ELASTICITY, pool, GLOBAL_PRIOR_WEIGHT)

    def for_category(self, category: str | None) -> float:
        """Promedio ponderado jerárquico: categoría ← resto de las categorías ← valor por defecto."""
        key = category_key(category)
        own = [o for o in self.observations if o.category == key]
        return _shrink(self.global_elasticity(exclude_category=key), own)

    def observations_for(self, category: str | None) -> int:
        key = category_key(category)
        return sum(1 for o in self.observations if o.category == key)

    def confidence_factor(self, category: str | None) -> float:
        """Más resultados medidos en la categoría → más confianza en la respuesta al descuento."""
        return 0.75 + 0.25 * (1.0 - math.exp(-self.observations_for(category) / 2.0))

    def acceptance_factor(self, recommendation_type: str) -> float:
        """Ajuste suave de confianza según la tasa de aceptación histórica del tipo (≥ 3 decisiones)."""
        accepted, discarded = self.acceptance.get(recommendation_type, (0, 0))
        if accepted + discarded < 3:
            return 1.0
        rate = (accepted + 1) / (accepted + discarded + 2)
        return 0.85 + 0.3 * rate

    def by_category(self, categories: set[str]) -> dict[str, float]:
        keys = {category_key(c) for c in categories} | {o.category for o in self.observations}
        return {key: round(self.for_category(key), 2) for key in sorted(keys)}
