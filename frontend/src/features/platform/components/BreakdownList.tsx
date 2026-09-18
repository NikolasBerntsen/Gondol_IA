import { formatNumber, formatRatio } from '@/lib/format';

export interface BreakdownRow {
  key: string;
  label: string;
  value: number;
  /** Detalle a la derecha del rótulo (p. ej. el precio del plan). */
  hint?: string;
}

/** Reparto de clientes por plan o por rubro: barra + cantidad + porcentaje. */
export function BreakdownList({ rows, emptyLabel }: { rows: BreakdownRow[]; emptyLabel: string }) {
  const total = rows.reduce((sum, row) => sum + row.value, 0);
  if (total === 0) {
    return <p className="text-base text-muted-foreground">{emptyLabel}</p>;
  }

  return (
    <ul className="space-y-3">
      {rows.map((row) => {
        const ratio = row.value / total;
        return (
          <li key={row.key} className="space-y-1">
            <div className="flex items-baseline justify-between gap-3 text-base">
              <span className="min-w-0 truncate text-foreground">
                {row.label}
                {row.hint ? <span className="text-muted-foreground"> · {row.hint}</span> : null}
              </span>
              <span className="shrink-0 tabular-nums text-muted-foreground">
                <span className="font-semibold text-foreground">{formatNumber(row.value)}</span> ·{' '}
                {formatRatio(ratio)}
              </span>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-muted">
              <div className="h-full rounded-full bg-primary" style={{ width: `${Math.round(ratio * 100)}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
