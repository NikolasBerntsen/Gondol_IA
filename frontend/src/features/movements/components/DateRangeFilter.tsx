import { Input } from '@/components/ui';

export interface DateRange {
  from: string;
  to: string;
}

export interface DateRangeFilterProps {
  value: DateRange;
  onChange: (value: DateRange) => void;
  /** Rótulo accesible del grupo ("Rango de fechas de las ventas"). */
  label: string;
  className?: string;
}

/**
 * Rango "desde / hasta" con dos `<input type="date">`. Los filtros de fecha de todas las pantallas del
 * módulo usan este control para que el rango se vea y se limpie igual en todas.
 */
export function DateRangeFilter({ value, onChange, label, className }: DateRangeFilterProps) {
  return (
    <div role="group" aria-label={label} className={className}>
      <div className="flex items-center gap-2">
        <Input
          type="date"
          inputSize="sm"
          aria-label="Desde"
          value={value.from}
          max={value.to || undefined}
          onChange={(event) => onChange({ ...value, from: event.target.value })}
          className="w-[9.5rem]"
        />
        <span className="text-sm text-muted-foreground" aria-hidden="true">
          a
        </span>
        <Input
          type="date"
          inputSize="sm"
          aria-label="Hasta"
          value={value.to}
          min={value.from || undefined}
          onChange={(event) => onChange({ ...value, to: event.target.value })}
          className="w-[9.5rem]"
        />
      </div>
    </div>
  );
}

/** Rango vacío (sin filtro de fechas). */
export const EMPTY_RANGE: DateRange = { from: '', to: '' };
