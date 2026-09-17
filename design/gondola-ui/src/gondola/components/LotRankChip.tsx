import { cn } from "@/lib/utils"

export interface LotRankChipProps {
  /** Posición en la cola de salida (1 = el próximo que se vende) */
  rank: number
  rotation?: "FIFO" | "FEFO"
  className?: string
}

/**
 * Orden de salida del lote según la rotación del comercio.
 * 1º se destaca en verde sólido ("1º sale"); el resto queda en contorno.
 */
export function LotRankChip({ rank, rotation = "FIFO", className }: LotRankChipProps) {
  const first = rank === 1
  return (
    <span
      className={cn(
        "inline-flex h-[22px] shrink-0 items-center gap-1 whitespace-nowrap rounded-tag border px-1.5 text-xs font-bold tabular-nums",
        first ? "border-primary bg-primary text-primary-foreground" : "border-input bg-card text-foreground",
        className
      )}
      title={`${rank}º en salir (${rotation === "FIFO" ? "primero sale lo que entró antes" : "primero sale lo que vence antes"})`}
    >
      {rank}º{first ? <span className="font-semibold">sale</span> : null}
    </span>
  )
}
