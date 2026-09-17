"""Clasificación ABC (facturación 80/15/5) y XYZ (coeficiente de variación de la demanda)."""

from .patterns import SeriesFeatures

ABC_A_SHARE = 0.80
ABC_B_SHARE = 0.95
REVENUE_WINDOW_DAYS = 90


def classify_abc(revenues: list[float]) -> list[str]:
    """A: productos que acumulan el primer 80% de la facturación; B: hasta el 95%; C: el resto."""
    total = sum(r for r in revenues if r > 0)
    classes = ["C"] * len(revenues)
    if total <= 0:
        return classes
    order = sorted(range(len(revenues)), key=lambda i: revenues[i], reverse=True)
    cumulative = 0.0
    for i in order:
        if revenues[i] <= 0:
            break
        share_before = cumulative / total
        cumulative += revenues[i]
        if share_before < ABC_A_SHARE:
            classes[i] = "A"
        elif share_before < ABC_B_SHARE:
            classes[i] = "B"
        else:
            classes[i] = "C"
    return classes


def classify_xyz(features: SeriesFeatures) -> str:
    """X: CV < 0,5 (demanda predecible); Y: CV < 1; Z: CV ≥ 1 o sin ventas.

    Se usa el CV de los totales semanales (hasta 12 semanas): el CV diario de un comercio chico
    está dominado por el ruido del día a día y el perfil semanal, y no refleja qué tan
    predecible es la reposición.
    """
    if features.sale_days == 0:
        return "Z"
    cv = features.cv_weekly
    if cv < 0.5:
        return "X"
    if cv < 1.0:
        return "Y"
    return "Z"
