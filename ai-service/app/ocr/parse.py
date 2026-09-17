"""Extracción de vencimientos, fechas de elaboración, lotes, nombres de producto y códigos desde texto OCR.

Convenciones argentinas: las fechas numéricas se leen día/mes/año y los años de dos dígitos son 20xx.
Todas las funciones trabajan sobre texto normalizado con la misma longitud que el original, así el campo
`raw` de cada candidato es un fragmento de lo que leyó el OCR (con las confusiones de dígitos ya corregidas).
"""

import calendar
import re
import unicodedata
from dataclasses import dataclass, field
from datetime import date

EXPIRY = "EXPIRY"
MANUFACTURE = "MANUFACTURE"

MONTHS = {
    "ENERO": 1, "ENE": 1, "JANUARY": 1, "JAN": 1,
    "FEBRERO": 2, "FEB": 2, "FEBRUARY": 2,
    "MARZO": 3, "MAR": 3, "MARCH": 3,
    "ABRIL": 4, "ABR": 4, "APRIL": 4, "APR": 4,
    "MAYO": 5, "MAY": 5,
    "JUNIO": 6, "JUN": 6, "JUNE": 6,
    "JULIO": 7, "JUL": 7, "JULY": 7,
    "AGOSTO": 8, "AGO": 8, "AUGUST": 8, "AUG": 8,
    "SEPTIEMBRE": 9, "SETIEMBRE": 9, "SEPTEMBER": 9, "SEPT": 9, "SEP": 9, "SET": 9,
    "OCTUBRE": 10, "OCT": 10, "OCTOBER": 10,
    "NOVIEMBRE": 11, "NOV": 11, "NOVEMBER": 11,
    "DICIEMBRE": 12, "DIC": 12, "DECEMBER": 12, "DEC": 12,
}  # fmt: skip
_MONTH_RE = "|".join(sorted(MONTHS, key=len, reverse=True))
_SEP = r"\s?[/\-.]\s?"
_YEAR = r"(?P<y>\d{4}|\d{2})"

# (tipo, regex, confianza base, requiere palabra clave inmediatamente antes)
_DATE_PATTERNS: list[tuple[str, re.Pattern[str], float, bool]] = [
    ("ISO", re.compile(rf"(?<!\d)(?P<y>(?:19|20)\d{{2}}){_SEP}(?P<m>\d{{1,2}}){_SEP}(?P<d>\d{{1,2}})(?!\d)"), 0.9, False),
    (
        "D_MON_Y",
        re.compile(
            rf"(?<!\d)(?P<d>\d{{1,2}})\s?(?:DE\s)?[/\-.]?\s?(?P<mon>{_MONTH_RE})(?![A-Z])\.?\s?(?:DEL?\s)?[/\-.]?\s?{_YEAR}(?!\d)"
        ),
        0.88,
        False,
    ),
    ("DMY", re.compile(rf"(?<!\d)(?P<d>\d{{1,2}}){_SEP}(?P<m>\d{{1,2}}){_SEP}{_YEAR}(?!\d)"), 0.9, False),
    ("MON_Y", re.compile(rf"(?<![A-Z])(?P<mon>{_MONTH_RE})(?![A-Z])\.?\s?(?:DEL?\s)?[/\-.]?\s?{_YEAR}(?!\d)"), 0.75, False),
    ("MY4", re.compile(rf"(?<![\d/.\-])(?P<m>\d{{1,2}}){_SEP}(?P<y>(?:19|20)\d{{2}})(?![/.\-]?\d)"), 0.75, False),
    ("YM4", re.compile(rf"(?<![\d/.\-])(?P<y>20\d{{2}}){_SEP}(?P<m>\d{{1,2}})(?![/.\-]?\d)"), 0.72, False),
    ("MY2", re.compile(rf"(?<![\d/.\-])(?P<m>\d{{1,2}}){_SEP}(?P<y>\d{{2}})(?![/.\-]?\d)"), 0.7, True),
    # Impresiones de fechador sin separadores o con espacios ("VTO 10 03 27", "EXP 20271012"): solo tras una palabra clave.
    ("DMY_SPACED", re.compile(r"(?<![\d/.\-])(?P<d>\d{1,2})\s(?P<m>\d{1,2})\s(?P<y>\d{4}|\d{2})(?![/.\-]?\d)"), 0.8, True),
    ("MY_SPACED", re.compile(r"(?<![\d/.\-])(?P<m>\d{1,2})\s(?P<y>20\d{2})(?![/.\-]?\d)"), 0.72, True),
    ("COMPACT_ISO", re.compile(r"(?<!\d)(?P<y>20\d{2})(?P<m>\d{2})(?P<d>\d{2})(?!\d)"), 0.68, True),
    ("COMPACT", re.compile(r"(?<!\d)(?P<d>\d{2})(?P<m>\d{2})(?P<y>\d{4}|\d{2})(?!\d)"), 0.68, True),
]
MONTH_FIRST_PENALTY = 0.15
"""Fechas que solo son válidas leídas mes/día (etiquetas importadas, "12/31/2026"): se aceptan con menos confianza."""

_EXPIRY_KW = (
    r"FECHA\sDE\sVENCIMIENTO|FECHA\sDE\sVTO|F\.?\s?VTO|VENCIMIENTO|VENCE(?:\sEL)?|VENC|VTOS?|V\.T\.O|EXPIRA|EXPIRY|"
    r"EXP(?:\sDATE)?|CADUCIDAD|CAD|BEST\sBEFORE\sEND|BEST\sBEFORE|BBE|BB|"
    r"CONSUMIR\s(?:PREFERENTEMENTE\s)?ANTES\sDEL?|USE\sBY|VALIDO\sHASTA|F\.?V\.?|V(?=\s?:)"
)
_MANUFACTURE_KW = (
    r"FECHA\sDE\sELABORACION|FECHA\sDE\sELAB|F\.?\s?ELAB|ELABORACION|ELABORADO(?:\sEL)?|ELAB|"
    r"FECHA\sDE\sFABRICACION|F\.?\s?FAB|FABRICACION|FABRICADO(?:\sEL)?|FAB|MFG|MFD|ENVASADO(?:\sEL)?|ENV|"
    r"FECHA\sDE\sPRODUCCION|PRODUCCION|PROD|F\.?E\.?|F\.?P\.?"
)
_KEYWORD_RE = re.compile(rf"(?<![A-Z])(?:(?P<exp>{_EXPIRY_KW})|(?P<man>{_MANUFACTURE_KW}))(?![A-Z])\.?")
_KEYWORD_MAX_GAP = 30

_LOT_KEYWORD_RE = re.compile(
    r"(?<![A-Z])(?P<kw>N(?:RO|°|O)?\.?\s?(?:DE\s)?LOTE|LOTE|LOT|BATCH|PARTIDA|LT)(?![A-Z])"
    r"\s?(?:N(?:RO|°|O)?\.?\s?)?[:#.\-]?\s?(?P<code>[A-Z0-9](?:[A-Z0-9\-/.]{0,24}[A-Z0-9])?)"
)
_LOT_SHORT_RE = re.compile(r"(?<![A-Z0-9])L\s?[.:\-]\s?(?P<code>[A-Z0-9](?:[A-Z0-9\-/]{0,20}[A-Z0-9])?)")
_LOT_SPACED_RE = re.compile(r"(?<![A-Z0-9])(?<![\d.,]\s)L\s(?P<code>\d[A-Z0-9]{2,11})(?![A-Z0-9])")
""""L 2409A" (sin separador): se descarta "1 L 500" porque la L sigue a una cantidad (litros)."""
_LOT_BARE_RE = re.compile(r"(?<![A-Z0-9])(?P<code>L\d{3,}[A-Z0-9]{0,6})(?![A-Z0-9])")
_LOT_GENERIC_RE = re.compile(r"(?<![A-Z0-9])(?P<code>(?=[A-Z0-9]*\d)(?=[A-Z0-9]*[A-Z])[A-Z0-9]{5,12})(?![A-Z0-9])")
_LOT_CODE_CUT_RE = re.compile(r"[\-/.](?=(?:F\.?)?(?:VTO|VENC|ELAB|FAB|EXP|CAD)(?![A-Z0-9]))")
_MEASURE_RE = re.compile(r"^(?:X?\d+(?:[.,]\d+)?(?:G|GR|GRS|KG|ML|L|LT|LTS|CC|MG|OZ|U|UN|UNID|KCAL|KJ|CM|MM)?|\d+X\d+(?:G|GR|ML)?)$")
_GENERIC_LOT_NOISE = ("RNPA", "RNE", "INAL", "CUIT", "TEL", "SENASA", "EAN", "WWW", "@", "HTTP")

_CONFUSABLE_DATE_RE = re.compile(r"(?<![A-Za-z0-9])[0-9OoIl|]{1,4}(?:\s?[/.\-]\s?[0-9OoIl|]{1,4}){1,2}(?![A-Za-z0-9])")
_CONFUSABLE_TABLE = str.maketrans({"O": "0", "o": "0", "I": "1", "l": "1", "|": "1"})
_BARCODE_DIGITS_RE = re.compile(r"(?<!\d)(\d(?: ?\d){11,12})(?!\d)")

_NAME_NOISE = (
    "INGREDIENTE", "INDUSTRIA", "ARGENTIN", "CONT.", "CONTENIDO", "NETO", "PESO", "RNPA", "RNE", "INAL", "SENASA",
    "CUIT", "TEL", "WWW", "HTTP", ".COM", "@", "CONSERV", "MANTENER", "REFRIGER", "LUGAR FRESCO", "SIN TACC",
    "ELABORADO", "ENVASADO", "DISTRIBUIDO", "IMPORTADO", "FABRICADO", "NUTRICIONAL", "VALOR ENERG", "PORCION",
    "KCAL", "GRASA", "CARBOHIDRATO", "PROTEINA", "SODIO", "CONTIENE", "PUEDE CONTENER", "LOTE", "VTO", "VENC",
    "ELAB", "CONSUMIR", "EXP", "CADUC", "HECHO EN", "ORIGEN", "AGITE", "AGITAR", "UNA VEZ ABIERTO", "DIRECCION",
    "C.A.B.A", "BUENOS AIRES", "CODIGO", "PRODUCTO DE", "FECHA",
)  # fmt: skip


@dataclass
class TextLine:
    text: str
    confidence: float = 1.0
    """Confianza del OCR para la línea (0..1)."""
    height: float = 0.0
    """Altura típica de las letras en píxeles (0 si no se conoce)."""


@dataclass
class DateFound:
    value: date
    raw: str
    confidence: float


@dataclass
class LotFound:
    value: str
    raw: str
    confidence: float


@dataclass
class ParsedLabel:
    expiry_dates: list[DateFound] = field(default_factory=list)
    manufacture_dates: list[DateFound] = field(default_factory=list)
    lot_numbers: list[LotFound] = field(default_factory=list)
    product_name_candidates: list[str] = field(default_factory=list)
    barcodes: list[tuple[str, str]] = field(default_factory=list)
    """Códigos EAN-13 / UPC-A leídos como texto (dígitos impresos debajo de las barras)."""


# ---------------------------------------------------------------------------
# Normalización
# ---------------------------------------------------------------------------
def fix_date_digits(text: str) -> str:
    """Corrige confusiones típicas del OCR dentro de fechas numéricas ("1O/O3/2O27" → "10/03/2027")."""

    def repair(match: re.Match[str]) -> str:
        chunk = match.group(0)
        if sum(ch.isdigit() for ch in chunk) < 2:
            return chunk
        return chunk.translate(_CONFUSABLE_TABLE)

    return _CONFUSABLE_DATE_RE.sub(repair, text)


def normalize(text: str) -> str:
    """Mayúsculas sin acentos, carácter por carácter (conserva la longitud y las posiciones)."""
    chars = []
    for ch in text:
        base = unicodedata.normalize("NFKD", ch)[:1] or ch
        upper = base.upper()
        chars.append(upper if len(upper) == 1 else base)
    return "".join(chars)


def normalize_lot(raw: str) -> str | None:
    """Mismo criterio que `LotNumbers.normalize` del backend: mayúsculas y solo [A-Z0-9]."""
    value = re.sub(r"[^A-Z0-9]", "", normalize(raw or ""))
    return value or None


def _to_date(year: int, month: int, day: int | None, today: date) -> date | None:
    if year < 100:
        year += 2000
    if not (today.year - 10 <= year <= today.year + 10) or not 1 <= month <= 12:
        return None
    last_day = calendar.monthrange(year, month)[1]
    if day is None:
        return date(year, month, last_day)
    if not 1 <= day <= last_day:
        return None
    return date(year, month, day)


# ---------------------------------------------------------------------------
# Fechas
# ---------------------------------------------------------------------------
@dataclass
class _DateMatch:
    line: int
    start: int
    end: int
    value: date
    base: float
    kind: str | None = None
    keyword_start: int | None = None


@dataclass
class _Keyword:
    kind: str
    start: int
    end: int


def _find_keywords(norm: str) -> list[_Keyword]:
    return [
        _Keyword(EXPIRY if m.group("exp") else MANUFACTURE, m.start(), m.end())
        for m in _KEYWORD_RE.finditer(norm)
    ]


def _find_dates(norm: str, line_index: int, keywords: list[_Keyword], today: date) -> list[_DateMatch]:
    work = norm
    found: list[_DateMatch] = []
    for name, pattern, base, needs_keyword in _DATE_PATTERNS:
        for m in pattern.finditer(work):
            if needs_keyword and not any(0 <= m.start() - k.end <= 3 and not work[k.end : m.start()].strip(" :.-") for k in keywords):
                continue
            groups = m.groupdict()
            year = int(groups["y"])
            if "mon" in groups and groups["mon"]:
                month = MONTHS[groups["mon"]]
            else:
                month = int(groups["m"])
            day = int(groups["d"]) if groups.get("d") else None
            value = _to_date(year, month, day, today)
            month_first = False
            if value is None and name == "DMY" and day is not None and day <= 12 < month:
                value = _to_date(year, day, month, today)
                month_first = value is not None
            if value is None:
                continue
            confidence = base - (0.05 if name == "DMY" and len(groups["y"]) == 2 else 0.0) - (MONTH_FIRST_PENALTY if month_first else 0.0)
            found.append(_DateMatch(line_index, m.start(), m.end(), value, confidence))
            work = work[: m.start()] + " " * (m.end() - m.start()) + work[m.end() :]
    found.sort(key=lambda d: d.start)
    return found


def _assign_keywords(norm: str, dates: list[_DateMatch], keywords: list[_Keyword]) -> None:
    """Asocia cada fecha a la palabra clave más cercana que la precede en la línea.

    Encabezados combinados como "ELAB/VTO: 10/09/26 10/03/27" asignan las fechas en el mismo orden.
    """
    combined_counts: dict[int, int] = {}
    for current in dates:
        preceding = [k for k in keywords if k.end <= current.start and current.start - k.end <= _KEYWORD_MAX_GAP]
        if not preceding:
            continue
        keyword = max(preceding, key=lambda k: k.end)
        partner = next(
            (
                k
                for k in keywords
                if k.end <= keyword.start and k.kind != keyword.kind and not norm[k.end : keyword.start].strip(" /-")
            ),
            None,
        )
        date_in_between = any(keyword.end <= d.start and d.end <= current.start for d in dates if d is not current)
        if partner is not None:
            index = combined_counts.get(keyword.start, 0)
            if index > 1:
                continue
            combined_counts[keyword.start] = index + 1
            current.kind = partner.kind if index == 0 else keyword.kind
            current.keyword_start = partner.start
        elif not date_in_between:
            current.kind = keyword.kind
            current.keyword_start = keyword.start


def _date_confidence(match: _DateMatch, kind: str, line_confidence: float, today: date) -> float:
    confidence = min(0.98, match.base + 0.05) if match.kind else match.base * 0.65
    delta = (match.value - today).days
    if kind == EXPIRY:
        if delta < -365:
            confidence *= 0.5
        elif delta > 365 * 6:
            confidence *= 0.6
    else:
        if delta > 30:
            confidence *= 0.5
        elif delta < -365 * 3:
            confidence *= 0.7
    return confidence * (0.6 + 0.4 * max(0.0, min(1.0, line_confidence)))


def _merge_dates(items: list[DateFound]) -> list[DateFound]:
    best: dict[date, DateFound] = {}
    for item in items:
        current = best.get(item.value)
        if current is None or item.confidence > current.confidence:
            best[item.value] = item
    return sorted(
        (DateFound(i.value, i.raw.strip(), round(min(i.confidence, 0.99), 2)) for i in best.values()),
        key=lambda i: (-i.confidence, i.value),
    )


# ---------------------------------------------------------------------------
# Lotes
# ---------------------------------------------------------------------------
def _clean_lot_code(code: str) -> str:
    code = _LOT_CODE_CUT_RE.split(code, maxsplit=1)[0]
    return code.strip(".-/")


def _valid_lot(value: str | None) -> bool:
    if not value or not 2 <= len(value) <= 20 or not any(ch.isdigit() for ch in value):
        return False
    return not (value.isdigit() and len(value) >= 12)


def _find_lots(original: str, norm: str, line_confidence: float) -> list[LotFound]:
    factor = 0.6 + 0.4 * max(0.0, min(1.0, line_confidence))
    lots: list[LotFound] = []
    covered: list[tuple[int, int]] = []

    for m in _LOT_KEYWORD_RE.finditer(norm):
        code = _clean_lot_code(m.group("code"))
        value = normalize_lot(code)
        measure = bool(_MEASURE_RE.match(value or ""))
        with_unit = measure and not (value or "").isdigit()
        if not _valid_lot(value) or measure and m.group("kw") == "LT":
            continue
        end = m.start("code") + len(code)
        base = 0.75 if m.group("kw") == "LT" else 0.92
        if with_unit:
            # "LOTE 500G" suele ser el peso leído a continuación de la palabra LOTE, no el lote.
            base *= 0.4
        lots.append(LotFound(value, original[m.start() : end], base * factor))
        covered.append((m.start(), end))

    for m in _LOT_SHORT_RE.finditer(norm):
        if any(s <= m.start() < e for s, e in covered):
            continue
        code = _clean_lot_code(m.group("code"))
        value = normalize_lot(code)
        if not _valid_lot(value):
            continue
        end = m.start("code") + len(code)
        lots.append(LotFound(f"L{value}", original[m.start() : end], 0.85 * factor))
        lots.append(LotFound(value, original[m.start() : end], 0.5 * factor))
        covered.append((m.start(), end))

    for m in _LOT_SPACED_RE.finditer(norm):
        if any(s <= m.start() < e for s, e in covered):
            continue
        code = m.group("code")
        lots.append(LotFound(f"L{code}", original[m.start() : m.end()], 0.7 * factor))
        lots.append(LotFound(code, original[m.start() : m.end()], 0.45 * factor))
        covered.append((m.start(), m.end()))

    for m in _LOT_BARE_RE.finditer(norm):
        if any(s <= m.start() < e for s, e in covered):
            continue
        lots.append(LotFound(m.group("code"), original[m.start() : m.end()], 0.6 * factor))
        covered.append((m.start(), m.end()))

    if line_confidence >= 0.6 and not any(noise in norm for noise in _GENERIC_LOT_NOISE):
        for m in _LOT_GENERIC_RE.finditer(norm):
            code = m.group("code")
            if any(s <= m.start() < e for s, e in covered) or _MEASURE_RE.match(code) or _looks_like_month_code(code):
                continue
            lots.append(LotFound(code, original[m.start() : m.end()], 0.3 * factor))
    return lots


def _looks_like_month_code(code: str) -> bool:
    letters = re.sub(r"\d", "", code)
    return letters in MONTHS


def _merge_lots(items: list[LotFound]) -> list[LotFound]:
    best: dict[str, LotFound] = {}
    for item in items:
        current = best.get(item.value)
        if current is None or item.confidence > current.confidence:
            best[item.value] = item
    return sorted(
        (LotFound(i.value, i.raw.strip(), round(min(i.confidence, 0.99), 2)) for i in best.values()),
        key=lambda i: (-i.confidence, i.value),
    )


# ---------------------------------------------------------------------------
# Códigos de barras impresos y nombre de producto
# ---------------------------------------------------------------------------
def ean_checksum_ok(digits: str) -> bool:
    if not digits.isdigit() or len(digits) not in (8, 12, 13):
        return False
    padded = digits.zfill(13) if len(digits) != 8 else digits
    body, check = padded[:-1], int(padded[-1])
    weights = (1, 3) if len(padded) == 13 else (3, 1)
    total = sum(int(d) * weights[i % 2] for i, d in enumerate(body))
    return (10 - total % 10) % 10 == check


def _find_printed_barcodes(norm: str) -> list[tuple[str, str]]:
    """Números de 13 dígitos se toman como EAN-13 aunque el dígito verificador no cierre (puede ser un
    error de lectura o un código cargado a mano); los de 12 dígitos solo si son UPC-A válidos."""
    found = []
    for m in _BARCODE_DIGITS_RE.finditer(norm):
        digits = m.group(1).replace(" ", "")
        if len(digits) == 13:
            found.append((digits, "EAN_13"))
        elif ean_checksum_ok(digits):
            found.append((digits, "UPC_A"))
    return found


def _name_candidates(lines: list[TextLine], excluded: set[int]) -> list[str]:
    max_height = max((line.height for line in lines), default=0.0)
    total = max(len(lines), 1)
    scored: list[tuple[float, int, str]] = []
    for index, line in enumerate(lines):
        if index in excluded:
            continue
        text = re.sub(r"\s+", " ", line.text).strip()
        text = re.sub(r"^[^\w]+|[^\w.)]+$", "", text)
        norm = normalize(text)
        letters = sum(ch.isalpha() for ch in text)
        visible = sum(not ch.isspace() for ch in text)
        if not 3 <= len(text) <= 60 or letters < 3 or letters / max(visible, 1) < 0.7:
            continue
        if not any(len(word) >= 3 and word.isalpha() for word in re.findall(r"[^\W\d_]+", text)):
            continue
        if line.confidence < 0.45 or any(noise in norm for noise in _NAME_NOISE):
            continue
        size = line.height / max_height if max_height > 0 else 1.0 - index / total
        score = 0.5 * size + 0.3 * line.confidence + 0.2 * (1.0 - index / total)
        scored.append((score, index, text))

    by_index = {index: (score, text) for score, index, text in scored}
    for score, index, text in list(scored):
        # La línea siguiente puede estar separada por basura del OCR de 1-2 caracteres ("Ll", "|").
        following_index = index + 1
        while following_index < len(lines) and following_index not in by_index and len(lines[following_index].text.strip()) <= 2:
            following_index += 1
        following = by_index.get(following_index)
        if following is None:
            continue
        height, next_height = lines[index].height, lines[following_index].height
        if height and next_height and abs(height - next_height) / max(height, next_height) > 0.3:
            continue
        joined = f"{text} {following[1]}"
        if len(joined) <= 60:
            scored.append((max(score, following[0]) + 0.02, index, joined))

    result: list[str] = []
    for _, _, text in sorted(scored, key=lambda item: (-item[0], item[1])):
        if text.upper() not in {r.upper() for r in result}:
            result.append(text)
        if len(result) == 3:
            break
    return result


# ---------------------------------------------------------------------------
# API pública
# ---------------------------------------------------------------------------
def parse_label(lines: list[TextLine | str], today: date) -> ParsedLabel:
    text_lines = [line if isinstance(line, TextLine) else TextLine(line) for line in lines]
    text_lines = [line for line in text_lines if line.text and line.text.strip()]

    expiry: list[DateFound] = []
    manufacture: list[DateFound] = []
    unprefixed: list[tuple[_DateMatch, str, float]] = []
    lots: list[LotFound] = []
    barcodes: list[tuple[str, str]] = []
    informative_lines: set[int] = set()
    previous_trailing_keyword: _Keyword | None = None

    for index, line in enumerate(text_lines):
        original = fix_date_digits(line.text)
        norm = normalize(original)
        keywords = _find_keywords(norm)
        dates = _find_dates(norm, index, keywords, today)
        _assign_keywords(norm, dates, keywords)

        if (
            previous_trailing_keyword is not None
            and dates
            and dates[0].kind is None
            and not any(k.end <= dates[0].start for k in keywords)
        ):
            dates[0].kind = previous_trailing_keyword.kind
        for match in dates:
            if match.kind is None:
                unprefixed.append((match, original[match.start : match.end], line.confidence))
                continue
            raw_start = match.keyword_start if match.keyword_start is not None and match.keyword_start <= match.start else match.start
            found = DateFound(match.value, original[raw_start : match.end], _date_confidence(match, match.kind, line.confidence, today))
            (expiry if match.kind == EXPIRY else manufacture).append(found)

        masked = norm
        for match in dates:
            masked = masked[: match.start] + " " * (match.end - match.start) + masked[match.end :]
        line_lots = _find_lots(original, masked, line.confidence)
        lots.extend(line_lots)
        line_barcodes = _find_printed_barcodes(masked)
        barcodes.extend(line_barcodes)
        if dates or line_lots or keywords or line_barcodes:
            informative_lines.add(index)

        trailing = [k for k in keywords if not norm[k.end :].strip(" :.-")]
        previous_trailing_keyword = trailing[-1] if trailing and not dates else None

    _infer_unprefixed(unprefixed, expiry, manufacture, today)

    unique_barcodes: list[tuple[str, str]] = []
    for code in barcodes:
        if code not in unique_barcodes:
            unique_barcodes.append(code)
    return ParsedLabel(
        expiry_dates=_merge_dates(expiry),
        manufacture_dates=_merge_dates(manufacture),
        lot_numbers=_merge_lots(lots),
        product_name_candidates=_name_candidates(text_lines, informative_lines),
        barcodes=unique_barcodes,
    )


def _infer_unprefixed(
    unprefixed: list[tuple[_DateMatch, str, float]],
    expiry: list[DateFound],
    manufacture: list[DateFound],
    today: date,
) -> None:
    """Fechas sin prefijo: la más lejana es candidata a vencimiento y la más antigua, a elaboración."""
    if not unprefixed:
        return
    has_labeled_expiry = bool(expiry)
    latest = max(unprefixed, key=lambda item: item[0].value)
    earliest = min(unprefixed, key=lambda item: item[0].value)
    for match, raw, line_confidence in unprefixed:
        confidence = _date_confidence(match, EXPIRY, line_confidence, today)
        if has_labeled_expiry:
            confidence *= 0.6
        if match is latest[0]:
            expiry.append(DateFound(match.value, raw, confidence))
            continue
        if match.value == earliest[0].value and match.value < latest[0].value and (match.value - today).days <= 30:
            manufacture.append(DateFound(match.value, raw, _date_confidence(match, MANUFACTURE, line_confidence, today) * 0.8))
        expiry.append(DateFound(match.value, raw, confidence * 0.6))
