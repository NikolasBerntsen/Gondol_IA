import * as React from "react"
import { cn } from "@/lib/utils"
import { TableRow } from "@/components/ui/table"
import type { Severity } from "../data"

export type StripeSeverity = Severity | "none"

/** Clase de franja (4px a la izquierda) para filas de tabla o ítems de lista con esquinas rectas. */
export const stripeClass = (s: StripeSeverity) => `gd-stripe-${s}`

/**
 * Fila de tabla con franja de severidad en la primera celda.
 * Solo para TABLAS y LISTAS: nunca como barra de acento sobre una tarjeta redondeada.
 */
export const SeverityRow = React.forwardRef<
  HTMLTableRowElement,
  React.HTMLAttributes<HTMLTableRowElement> & { severity: StripeSeverity }
>(({ severity, className, ...props }, ref) => (
  <TableRow
    ref={ref}
    data-severity={severity}
    className={cn(
      severity === "crit" && "[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--crit))]",
      severity === "warn" && "[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--warn))]",
      severity === "info" && "[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--info))]",
      severity === "ok" && "[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--ok))]",
      "[&>td:first-child]:pl-4",
      className
    )}
    {...props}
  />
))
SeverityRow.displayName = "SeverityRow"

/** Ítem de lista con franja de severidad (esquinas rectas). */
export function SeverityItem({
  severity,
  className,
  as: Comp = "li",
  ...props
}: React.HTMLAttributes<HTMLElement> & { severity: StripeSeverity; as?: "li" | "div" }) {
  return <Comp data-severity={severity} className={cn(stripeClass(severity), "pl-4", className)} {...props} />
}
