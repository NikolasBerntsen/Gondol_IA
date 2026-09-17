import { useId } from "react"
import type { Tone } from "./StatusPill"

const COLOR: Record<Tone, string> = {
  ok: "var(--ok)",
  warn: "var(--warn)",
  crit: "var(--crit)",
  info: "var(--info)",
  neutral: "var(--primary)",
}

/** Minigráfico: área suave + línea + punto final destacado. Decorativo (aria-hidden). */
export function Sparkline({ data, tone = "neutral", width = 96, height = 32 }: { data: number[]; tone?: Tone; width?: number; height?: number }) {
  const id = useId().replace(/:/g, "")
  const min = Math.min(...data)
  const max = Math.max(...data)
  const pad = 3
  const x = (i: number) => pad + (i * (width - pad * 2)) / (data.length - 1)
  const y = (v: number) => pad + (height - pad * 2) * (1 - (v - min) / (max - min || 1))
  const line = data.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(" ")
  const area = `${line} L${x(data.length - 1).toFixed(1)},${height} L${x(0).toFixed(1)},${height} Z`
  const c = COLOR[tone]
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} aria-hidden="true" className="block overflow-visible">
      <defs>
        <linearGradient id={`sp-${id}`} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={`hsl(${c})`} stopOpacity={0.22} />
          <stop offset="100%" stopColor={`hsl(${c})`} stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#sp-${id})`} />
      <path d={line} fill="none" stroke={`hsl(${c})`} strokeWidth={1.75} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={x(data.length - 1)} cy={y(data[data.length - 1])} r={2.75} fill={`hsl(${c})`} stroke="hsl(var(--card))" strokeWidth={1.5} />
    </svg>
  )
}
