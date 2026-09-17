import { cn } from "@/lib/utils"

/** Isotipo: etiqueta de góndola con agujero + marca "GondolIA". */
export function LogoMark({ size = 28, className }: { size?: number; className?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true" className={cn("shrink-0", className)}>
      <path d="M4 7.5 A3.5 3.5 0 0 1 7.5 4 H24.5 A3.5 3.5 0 0 1 28 7.5 V24.5 A3.5 3.5 0 0 1 24.5 28 H7.5 A3.5 3.5 0 0 1 4 24.5 Z" fill="hsl(var(--accent))" />
      <circle cx="9.5" cy="16" r="2.4" fill="hsl(var(--rail))" />
      <path d="M15 11.5 h9 M15 16 h7 M15 20.5 h9" stroke="hsl(var(--accent-foreground))" strokeWidth="2.2" strokeLinecap="round" />
    </svg>
  )
}

export function Logo({ collapsed, className }: { collapsed?: boolean; className?: string }) {
  return (
    <div className={cn("flex items-center gap-2.5", className)}>
      <LogoMark />
      {collapsed ? null : (
        <div className="min-w-0 leading-none">
          <div className="font-display text-[19px] font-bold tracking-[-0.02em] text-rail-strong">
            Gondol<span className="text-accent">IA</span>
          </div>
          <div className="mt-1 text-[11px] font-medium text-rail-muted">Tu negocio siempre a tiempo</div>
        </div>
      )}
    </div>
  )
}
