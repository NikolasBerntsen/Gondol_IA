import * as React from "react"
import { cn } from "@/lib/utils"

/** Panel: superficie blanca con borde y radio de panel (12px). Sin sombra. */
export const Panel = React.forwardRef<HTMLElement, React.HTMLAttributes<HTMLElement> & { as?: "section" | "div" | "article" }>(
  ({ className, as: Comp = "section", ...props }, ref) => (
    <Comp ref={ref as React.Ref<HTMLElement & HTMLDivElement>} className={cn("min-w-0 rounded-panel border bg-card text-card-foreground", className)} {...props} />
  )
)
Panel.displayName = "Panel"

export function PanelHeader({
  title,
  description,
  actions,
  icon,
  id,
  className,
}: {
  title: React.ReactNode
  description?: React.ReactNode
  actions?: React.ReactNode
  icon?: React.ReactNode
  id?: string
  className?: string
}) {
  return (
    <header className={cn("flex flex-wrap items-start justify-between gap-x-4 gap-y-2 px-4 pb-3 pt-4 sm:px-5", className)}>
      <div className="flex min-w-0 items-start gap-2.5">
        {icon ? <span className="mt-0.5 text-muted-foreground [&_svg]:size-[18px]">{icon}</span> : null}
        <div className="min-w-0">
          <h2 id={id} className="text-md font-semibold leading-6 text-foreground">
            {title}
          </h2>
          {description ? <p className="text-sm text-muted-foreground">{description}</p> : null}
        </div>
      </div>
      {actions ? <div className="flex min-w-0 max-w-full flex-wrap items-center gap-2">{actions}</div> : null}
    </header>
  )
}

/** Encabezado de página: título en Bricolage, bajada y acciones a la derecha. Alineado a la izquierda. */
export function PageHeader({
  eyebrow,
  title,
  description,
  actions,
  className,
}: {
  eyebrow?: React.ReactNode
  title: React.ReactNode
  description?: React.ReactNode
  actions?: React.ReactNode
  className?: string
}) {
  return (
    <div className={cn("flex flex-wrap items-end justify-between gap-x-6 gap-y-3", className)}>
      <div className="min-w-0 max-w-[68ch]">
        {eyebrow ? <div className="gd-eyebrow mb-1">{eyebrow}</div> : null}
        <h1 className="font-display text-xl font-semibold leading-8 tracking-[-0.015em] text-foreground sm:text-2xl sm:leading-10">{title}</h1>
        {description ? <p className="mt-1 text-read text-muted-foreground">{description}</p> : null}
      </div>
      {actions ? <div className="flex min-w-0 max-w-full flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}
