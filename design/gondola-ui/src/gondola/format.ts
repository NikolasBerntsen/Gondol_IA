// Góndola UI · formato es-AR (independiente del locale del navegador)

const group = (intPart: string) => intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ".")

/** 8450000 → "8.450.000"; 1234.5 con 2 decimales → "1.234,50" */
export function formatNumber(n: number, decimals = 0): string {
  const neg = n < 0
  const fixed = Math.abs(n).toFixed(decimals)
  const [i, d] = fixed.split(".")
  return (neg ? "-" : "") + group(i) + (d ? "," + d : "")
}

/** "$ 8.450.000" · con decimales: "$ 1.720,00" */
export function formatMoney(n: number, decimals = 0): string {
  const neg = n < 0
  return (neg ? "-$ " : "$ ") + formatNumber(Math.abs(n), decimals)
}

/** Separa entero y centavos para la etiqueta de precio: 16270 → { int: "16.270", cents: "00" } */
export function splitMoney(n: number): { int: string; cents: string } {
  const [i, d] = Math.abs(n).toFixed(2).split(".")
  return { int: group(i), cents: d }
}

/** "2026-09-25" → "25/09/2026" (o "25/09/26" con short) */
export function formatDate(iso: string, short = false): string {
  const [y, m, d] = iso.split("-")
  return `${d}/${m}/${short ? y.slice(2) : y}`
}

const DAYS = ["domingo", "lunes", "martes", "miércoles", "jueves", "viernes", "sábado"]
const MONTHS = [
  "enero",
  "febrero",
  "marzo",
  "abril",
  "mayo",
  "junio",
  "julio",
  "agosto",
  "septiembre",
  "octubre",
  "noviembre",
  "diciembre",
]

/** "jueves, 17 de septiembre de 2026" */
export function formatLongDate(iso: string): string {
  const [y, m, d] = iso.split("-").map(Number)
  const dt = new Date(Date.UTC(y, m - 1, d))
  return `${DAYS[dt.getUTCDay()]}, ${d} de ${MONTHS[m - 1]} de ${y}`
}

/** Días entre dos fechas ISO (b - a) */
export function daysBetween(a: string, b: string): number {
  const pa = a.split("-").map(Number)
  const pb = b.split("-").map(Number)
  return Math.round((Date.UTC(pb[0], pb[1] - 1, pb[2]) - Date.UTC(pa[0], pa[1] - 1, pa[2])) / 86400000)
}

export function pluralize(n: number, one: string, many: string) {
  return `${formatNumber(n)} ${n === 1 ? one : many}`
}

/** Dígito verificador EAN-13 */
export function eanCheckDigit(first12: string): number {
  const sum = first12
    .split("")
    .map(Number)
    .reduce((acc, d, i) => acc + d * (i % 2 === 0 ? 1 : 3), 0)
  return (10 - (sum % 10)) % 10
}

export function isValidEan13(code: string): boolean {
  if (!/^\d{13}$/.test(code)) return false
  return eanCheckDigit(code.slice(0, 12)) === Number(code[12])
}

/** Fecha dd/mm/aaaa válida → ISO; inválida → null */
export function parseDmy(value: string): string | null {
  const m = value.trim().match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/)
  if (!m) return null
  const d = Number(m[1])
  const mo = Number(m[2])
  const y = Number(m[3])
  if (mo < 1 || mo > 12 || d < 1) return null
  const dim = new Date(Date.UTC(y, mo, 0)).getUTCDate()
  if (d > dim) return null
  return `${y}-${String(mo).padStart(2, "0")}-${String(d).padStart(2, "0")}`
}

/** "$ 1.234,50" / "1234.5" / "1.234" → número; inválido → null */
export function parseArs(value: string): number | null {
  let v = value.trim().replace(/\$/g, "").replace(/\s/g, "")
  if (!v) return null
  if (/,/.test(v)) v = v.replace(/\./g, "").replace(",", ".")
  else if (/^\d{1,3}(\.\d{3})+$/.test(v)) v = v.replace(/\./g, "")
  if (!/^-?\d+(\.\d+)?$/.test(v)) return null
  return Number(v)
}
