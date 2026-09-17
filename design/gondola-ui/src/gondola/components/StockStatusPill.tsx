import type { StockStatus } from "../data"
import { StatusPill, type Tone } from "./StatusPill"

export const STOCK_LABEL: Record<StockStatus, string> = {
  OUT: "Sin stock",
  CRITICAL: "Crítico",
  LOW: "Bajo",
  OK: "OK",
}
const TONE: Record<StockStatus, Tone> = { OUT: "crit", CRITICAL: "crit", LOW: "warn", OK: "ok" }

/** Estado de stock por sucursal: Sin stock (sólido) / Crítico / Bajo / OK */
export function StockStatusPill({ status, className }: { status: StockStatus; className?: string }) {
  return (
    <StatusPill tone={TONE[status]} solid={status === "OUT"} className={className}>
      {STOCK_LABEL[status]}
    </StatusPill>
  )
}

export const stockSeverity = (s: StockStatus) => (s === "OUT" || s === "CRITICAL" ? "crit" : s === "LOW" ? "warn" : "ok")
