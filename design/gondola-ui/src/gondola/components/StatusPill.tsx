import * as React from "react"
import { cn } from "@/lib/utils"

export type Tone = "ok" | "warn" | "crit" | "info" | "neutral"

const SOFT: Record<Tone, string> = {
  ok: "bg-ok-soft text-ok-ink",
  warn: "bg-warn-soft text-warn-ink",
  crit: "bg-crit-soft text-crit-ink",
  info: "bg-info-soft text-info-ink",
  neutral: "bg-muted text-muted-foreground",
}
// ok y warn sólidos usan la tinta (no el color puro): el texto sobre #2F8A55 / #C96A12 no llega a AA.
const SOLID: Record<Tone, string> = {
  ok: "bg-ok-ink text-card",
  warn: "bg-warn-ink text-card",
  crit: "bg-crit text-crit-foreground",
  info: "bg-info text-info-foreground",
  neutral: "bg-foreground text-background",
}
const DOT: Record<Tone, string> = {
  ok: "bg-ok",
  warn: "bg-warn",
  crit: "bg-crit",
  info: "bg-info",
  neutral: "bg-muted-foreground",
}

export interface StatusPillProps extends React.HTMLAttributes<HTMLSpanElement> {
  tone: Tone
  /** solid = estado terminal o bloqueante (Vencido, Sin stock); soft = el resto */
  solid?: boolean
  dot?: boolean
  size?: "sm" | "md"
}

/**
 * Píldora de ESTADO. Es el único elemento totalmente redondeado del sistema:
 * si no comunica un estado, no es una píldora (usá un chip de datos o un tag).
 */
export function StatusPill({ tone, solid, dot = true, size = "sm", className, children, ...props }: StatusPillProps) {
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full font-semibold",
        size === "sm" ? "h-[22px] px-2 text-xs" : "h-7 px-2.5 text-sm",
        solid ? SOLID[tone] : SOFT[tone],
        className
      )}
      {...props}
    >
      {dot ? (
        <span aria-hidden="true" className={cn("h-1.5 w-1.5 rounded-full", solid ? "bg-current opacity-80" : DOT[tone])} />
      ) : null}
      {children}
    </span>
  )
}

export const toneText: Record<Tone, string> = {
  ok: "text-ok-ink",
  warn: "text-warn-ink",
  crit: "text-crit-ink",
  info: "text-info-ink",
  neutral: "text-muted-foreground",
}
export const toneDot = DOT
export const toneSoft = SOFT
