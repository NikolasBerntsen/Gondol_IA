import { cn } from "@/lib/utils"
import { expiryBucket, TODAY, type ExpiryBucket } from "../data"
import { daysBetween, formatDate } from "../format"

export const EXPIRY_LABEL: Record<ExpiryBucket, string> = {
  EXPIRED: "Vencido",
  CRITICAL: "Crítico",
  WARNING: "Por vencer",
  UPCOMING: "Próximo",
  OK: "OK",
}

const STYLE: Record<ExpiryBucket, string> = {
  EXPIRED: "bg-crit text-crit-foreground border-crit",
  CRITICAL: "bg-crit-soft text-crit-ink border-crit/35",
  WARNING: "bg-warn-soft text-warn-ink border-warn/35",
  UPCOMING: "bg-info-soft text-info-ink border-info/30",
  OK: "bg-card text-muted-foreground border-border",
}

export interface ExpiryChipProps {
  /** Fecha ISO aaaa-mm-dd */
  expiry: string
  lot?: string
  bucket?: ExpiryBucket
  /** Muestra año con 4 dígitos */
  longYear?: boolean
  showDays?: boolean
  className?: string
}

/**
 * Chip de vencimiento: dato en mono ("VTO 25/09/26") con color por bucket.
 * Radio de tag (4px): es un dato rotulado, no una píldora de estado.
 */
export function ExpiryChip({ expiry, lot, bucket, longYear, showDays, className }: ExpiryChipProps) {
  const b = bucket ?? expiryBucket(expiry)
  const days = daysBetween(TODAY, expiry)
  const daysText = days < 0 ? `hace ${-days} d` : days === 0 ? "hoy" : `en ${days} d`
  return (
    <span
      className={cn(
        "inline-flex h-[22px] shrink-0 items-center gap-1.5 whitespace-nowrap rounded-tag border px-1.5 font-mono text-xs font-medium tabular-nums",
        STYLE[b],
        className
      )}
      title={`${EXPIRY_LABEL[b]} · vence el ${formatDate(expiry)}`}
    >
      {lot ? (
        <>
          <span>{lot}</span>
          <span aria-hidden="true" className="opacity-50">
            ·
          </span>
        </>
      ) : null}
      <span>
        <span className="opacity-70">VTO</span> {formatDate(expiry, !longYear)}
      </span>
      {showDays ? <span className="opacity-75">({daysText})</span> : null}
      <span className="sr-only">, {EXPIRY_LABEL[b]}</span>
    </span>
  )
}
