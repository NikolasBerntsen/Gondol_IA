import { useId } from 'react';
import type { Tone } from './StatusPill';

const COLOR: Record<Tone, string> = {
  ok: 'var(--ok)',
  warn: 'var(--warn)',
  crit: 'var(--crit)',
  info: 'var(--info)',
  neutral: 'var(--primary)',
};

export interface SparklineProps {
  /** Serie de valores (al menos 2). */
  data: number[];
  tone?: Tone;
  width?: number;
  height?: number;
}

/** Minigráfico decorativo (área + línea + punto final) para los KPI. No reemplaza un gráfico con ejes. */
export function Sparkline({ data, tone = 'neutral', width = 96, height = 32 }: SparklineProps) {
  const id = useId().replace(/:/g, '');
  if (data.length < 2) return null;
  const min = Math.min(...data);
  const max = Math.max(...data);
  const pad = 3;
  const x = (i: number) => pad + (i * (width - pad * 2)) / (data.length - 1);
  const y = (v: number) => pad + (height - pad * 2) * (1 - (v - min) / (max - min || 1));
  const line = data.map((v, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  const area = `${line} L${x(data.length - 1).toFixed(1)},${height} L${x(0).toFixed(1)},${height} Z`;
  const color = COLOR[tone];
  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      aria-hidden="true"
      className="block overflow-visible"
    >
      <defs>
        <linearGradient id={`sp-${id}`} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={`hsl(${color})`} stopOpacity={0.22} />
          <stop offset="100%" stopColor={`hsl(${color})`} stopOpacity={0} />
        </linearGradient>
      </defs>
      <path d={area} fill={`url(#sp-${id})`} />
      <path d={line} fill="none" stroke={`hsl(${color})`} strokeWidth={1.75} strokeLinejoin="round" strokeLinecap="round" />
      <circle
        cx={x(data.length - 1)}
        cy={y(data[data.length - 1])}
        r={2.75}
        fill={`hsl(${color})`}
        stroke="hsl(var(--card))"
        strokeWidth={1.5}
      />
    </svg>
  );
}
