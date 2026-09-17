import { cn } from "@/lib/utils"

/** Góndola UI · Skeleton — bloque neutro con brillo suave (se desactiva con prefers-reduced-motion). */
function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return <div aria-hidden="true" className={cn("gd-skeleton rounded-[6px] bg-muted", className)} {...props} />
}

export { Skeleton }
