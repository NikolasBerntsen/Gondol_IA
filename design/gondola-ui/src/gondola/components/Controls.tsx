import * as React from "react"
import { Minus, Plus, AlertCircle, Check } from "lucide-react"
import { cn } from "@/lib/utils"
import { Label } from "@/components/ui/label"

// ---------------------------------------------------------------------------
// Field: etiqueta + control + ayuda + error, con aria-describedby cableado
// ---------------------------------------------------------------------------
export function fieldDescribedBy(id: string, opts: { help?: React.ReactNode; error?: React.ReactNode }) {
  return [opts.error ? `${id}-error` : null, opts.help ? `${id}-help` : null].filter(Boolean).join(" ") || undefined
}

export function Field({
  id,
  label,
  help,
  error,
  optional,
  children,
  className,
}: {
  id: string
  label: React.ReactNode
  help?: React.ReactNode
  error?: React.ReactNode
  optional?: boolean
  children: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn("flex min-w-0 flex-col gap-1.5", className)}>
      <Label htmlFor={id} className="flex items-baseline gap-1.5 text-sm font-semibold text-foreground">
        {label}
        {optional ? <span className="text-xs font-normal text-muted-foreground">(opcional)</span> : null}
      </Label>
      {children}
      {error ? (
        <p id={`${id}-error`} className="flex items-start gap-1.5 text-sm text-crit-ink">
          <AlertCircle className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
          {error}
        </p>
      ) : null}
      {help ? (
        <p id={`${id}-help`} className="text-sm text-muted-foreground">
          {help}
        </p>
      ) : null}
    </div>
  )
}

// ---------------------------------------------------------------------------
// QtyStepper: − [n] +   (md 36px · lg 44px para uso táctil)
// ---------------------------------------------------------------------------
export function QtyStepper({
  id,
  value,
  onChange,
  min = 0,
  max = 9999,
  size = "md",
  label,
}: {
  id: string
  value: number
  onChange: (n: number) => void
  min?: number
  max?: number
  size?: "sm" | "md" | "lg"
  label: string
}) {
  const h = size === "lg" ? "h-11" : size === "sm" ? "h-8" : "h-9"
  const w = size === "lg" ? "w-11" : size === "sm" ? "w-8" : "w-9"
  const clamp = (n: number) => Math.max(min, Math.min(max, n))
  return (
    <div className={cn("inline-flex items-stretch overflow-hidden rounded-control border border-input bg-card", h)} role="group" aria-label={label}>
      <button
        type="button"
        className={cn("grid place-items-center text-foreground hover:bg-muted disabled:opacity-40", w)}
        onClick={() => onChange(clamp(value - 1))}
        disabled={value <= min}
        aria-label={`Restar uno a ${label.toLowerCase()}`}
      >
        <Minus className="h-4 w-4" />
      </button>
      <input
        id={id}
        inputMode="numeric"
        className={cn(
          "w-12 border-x border-input bg-transparent text-center font-semibold tabular-nums text-foreground focus-visible:outline-none focus-visible:bg-primary/[0.06]",
          size === "lg" ? "text-md" : "text-base"
        )}
        value={value}
        aria-label={label}
        onChange={(e) => {
          const n = Number(e.target.value.replace(/\D/g, ""))
          onChange(clamp(Number.isFinite(n) ? n : min))
        }}
      />
      <button
        type="button"
        className={cn("grid place-items-center text-foreground hover:bg-muted disabled:opacity-40", w)}
        onClick={() => onChange(clamp(value + 1))}
        disabled={value >= max}
        aria-label={`Sumar uno a ${label.toLowerCase()}`}
      >
        <Plus className="h-4 w-4" />
      </button>
    </div>
  )
}

// ---------------------------------------------------------------------------
// Segmented: grupo de opciones excluyentes (radiogroup con flechas)
// ---------------------------------------------------------------------------
export function Segmented<T extends string>({
  id,
  value,
  onChange,
  options,
  label,
  size = "md",
  className,
}: {
  id: string
  value: T
  onChange: (v: T) => void
  options: { value: T; label: React.ReactNode; count?: React.ReactNode; tone?: "ok" | "warn" | "crit"; icon?: React.ReactNode; title?: string }[]
  label: string
  size?: "sm" | "md"
  className?: string
}) {
  const refs = React.useRef<(HTMLButtonElement | null)[]>([])
  const idx = options.findIndex((o) => o.value === value)
  const move = (dir: number) => {
    const next = (idx + dir + options.length) % options.length
    onChange(options[next].value)
    refs.current[next]?.focus()
  }
  return (
    <div
      id={id}
      role="radiogroup"
      aria-label={label}
      className={cn("inline-flex max-w-full items-stretch gap-0.5 rounded-control border border-border bg-muted p-0.5", className)}
      onKeyDown={(e) => {
        if (e.key === "ArrowRight" || e.key === "ArrowDown") {
          e.preventDefault()
          move(1)
        } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
          e.preventDefault()
          move(-1)
        }
      }}
    >
      {options.map((o, i) => {
        const active = o.value === value
        return (
          <button
            key={o.value}
            ref={(el) => {
              refs.current[i] = el
            }}
            type="button"
            role="radio"
            aria-checked={active}
            title={o.title}
            tabIndex={active ? 0 : -1}
            onClick={() => onChange(o.value)}
            className={cn(
              "inline-flex min-w-0 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-[6px] font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring [&_svg]:size-4",
              size === "sm" ? "h-7 px-2 text-xs" : "h-8 px-3 text-sm",
              active ? "bg-card text-foreground shadow-[0_0_0_1px_hsl(var(--border))]" : "text-muted-foreground hover:text-foreground"
            )}
          >
            {o.icon}
            {o.label}
            {o.count !== undefined ? (
              <span
                className={cn(
                  "rounded-[4px] px-1 font-mono text-[11px] tabular-nums",
                  o.tone === "crit" && "bg-crit-soft text-crit-ink",
                  o.tone === "warn" && "bg-warn-soft text-warn-ink",
                  o.tone === "ok" && "bg-ok-soft text-ok-ink",
                  !o.tone && "bg-background text-muted-foreground"
                )}
              >
                {o.count}
              </span>
            ) : null}
          </button>
        )
      })}
    </div>
  )
}

// ---------------------------------------------------------------------------
// WizardSteps: pasos reales de un proceso (acá sí corresponde numerar)
// ---------------------------------------------------------------------------
export function WizardSteps({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol className="flex flex-wrap items-center gap-x-2 gap-y-2" aria-label="Pasos de la importación">
      {steps.map((s, i) => {
        const done = i < current
        const active = i === current
        return (
          <li key={s} className="flex items-center gap-2" aria-current={active ? "step" : undefined}>
            <span
              className={cn(
                "grid h-6 w-6 shrink-0 place-items-center rounded-full border text-xs font-bold tabular-nums",
                done && "border-primary bg-primary text-primary-foreground",
                active && "border-primary bg-card text-primary ring-2 ring-primary/25",
                !done && !active && "border-input bg-card text-muted-foreground"
              )}
            >
              {done ? <Check className="h-3.5 w-3.5" strokeWidth={3} aria-hidden="true" /> : i + 1}
            </span>
            <span className={cn("text-sm", active ? "font-semibold text-foreground" : done ? "font-medium text-foreground" : "text-muted-foreground")}>
              {s}
              {done ? <span className="sr-only"> (completo)</span> : null}
            </span>
            {i < steps.length - 1 ? <span aria-hidden="true" className={cn("mx-1 hidden h-px w-8 sm:block", done ? "bg-primary" : "bg-border")} /> : null}
          </li>
        )
      })}
    </ol>
  )
}

// ---------------------------------------------------------------------------
// EmptyState
// ---------------------------------------------------------------------------
export function EmptyState({
  icon,
  title,
  description,
  action,
  className,
}: {
  icon: React.ReactNode
  title: string
  description: React.ReactNode
  action?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn("flex flex-col items-start gap-3 px-5 py-8", className)}>
      <div className="grid h-11 w-11 place-items-center rounded-control border border-dashed border-input bg-muted text-muted-foreground [&_svg]:size-5">{icon}</div>
      <div className="max-w-[46ch]">
        <h3 className="text-md font-semibold text-foreground">{title}</h3>
        <p className="mt-1 text-base text-muted-foreground">{description}</p>
      </div>
      {action}
    </div>
  )
}

/** Tecla de atajo */
export function Kbd({ children, className }: { children: React.ReactNode; className?: string }) {
  // En pantallas chicas (táctiles) los atajos no aplican: se ocultan.
  return <kbd className={cn("gd-kbd hidden sm:inline-flex", className)}>{children}</kbd>
}
