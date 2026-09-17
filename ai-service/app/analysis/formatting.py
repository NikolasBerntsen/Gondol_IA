"""Formato de números, dinero y fechas en español rioplatense (es-AR)."""

import math
from datetime import date

WEEKDAY_NAMES = ["lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo"]


def _round_half_up(value: float, decimals: int) -> float:
    factor = 10**decimals
    return math.copysign(math.floor(abs(value) * factor + 0.5) / factor, value)


def fmt_num(value: float | None, decimals: int = 1, trim: bool = True) -> str:
    """3.25 -> "3,3"; 6300 -> "6.300"; 8.0 -> "8" (con `trim`, se omiten los decimales nulos)."""
    if value is None or not math.isfinite(value):
        return "-"
    rounded = _round_half_up(float(value), decimals)
    if rounded == 0:
        rounded = 0.0
    if trim and float(rounded).is_integer():
        decimals = 0
    text = f"{abs(rounded):,.{decimals}f}".replace(",", "_").replace(".", ",").replace("_", ".")
    return f"-{text}" if rounded < 0 else text


def fmt_int(value: float) -> str:
    return fmt_num(value, 0)


def fmt_money(value: float | None) -> str:
    """Pesos con separador de miles: 6300 -> "$ 6.300"; 45.5 -> "$ 45,50"."""
    if value is None or not math.isfinite(value):
        return "$ -"
    if abs(value) >= 100 or float(_round_half_up(value, 2)).is_integer():
        return f"$ {fmt_num(value, 0)}"
    return f"$ {fmt_num(value, 2, trim=False)}"


def fmt_pct(value: float, signed: bool = False) -> str:
    """12.4 -> "12%"; con `signed`: "+12%" / "-8%"."""
    rounded = _round_half_up(value, 0)
    text = fmt_num(abs(rounded), 0)
    if rounded < 0:
        return f"-{text}%"
    if signed and rounded > 0:
        return f"+{text}%"
    return f"{text}%"


def fmt_units(value: float, decimals: int = 0) -> str:
    return f"{fmt_num(value, decimals)} u."


def fmt_days(count: float) -> str:
    return f"{fmt_num(count)} {'día' if _round_half_up(count, 1) == 1 else 'días'}"


def fmt_day_month(day: date, reference: date | None = None) -> str:
    """"21/09"; si la fecha cae en otro año que `reference`, se agrega el año ("13/02/2027")."""
    if reference is not None and day.year != reference.year:
        return fmt_date(day)
    return day.strftime("%d/%m")


def fmt_date(day: date) -> str:
    return day.strftime("%d/%m/%Y")


def truncate(text: str, limit: int) -> str:
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"
