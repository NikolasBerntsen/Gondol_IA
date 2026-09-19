import { ArrowDownWideNarrow, CalendarClock, Check } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import type { StockRotation } from '@/api/types';
import { cn } from '@/lib/cn';

interface RotationOption {
  value: StockRotation;
  title: string;
  claim: string;
  icon: LucideIcon;
  /** Qué hace el sistema con esta rotación. */
  consequence: string;
  /** A quién le conviene. */
  fit: string;
  example: string;
}

const OPTIONS: RotationOption[] = [
  {
    value: 'FIFO',
    title: 'FIFO · primero sale lo que entró antes',
    claim: 'Orden de llegada',
    icon: ArrowDownWideNarrow,
    consequence:
      'Cada venta descuenta del lote más viejo por fecha de ingreso. Es el orden natural de la góndola: lo que llegó primero se va primero.',
    fit: 'Almacenes y kioscos con mercadería de vida larga y reposición pareja.',
    example: 'Si cargás un lote nuevo que vence antes que uno viejo, GondolIA te avisa para que lo revises.',
  },
  {
    value: 'FEFO',
    title: 'FEFO · primero sale lo que vence antes',
    claim: 'Orden de vencimiento',
    icon: CalendarClock,
    consequence:
      'Cada venta descuenta del lote con el vencimiento más cercano, sin importar cuándo entró. Baja la merma por productos vencidos.',
    fit: 'Dietéticas, fiambrerías y farmacias con mucho producto perecedero.',
    example: 'Un lote que ingresó ayer pero vence el viernes se vende antes que uno de hace un mes.',
  },
];

interface RotationChoiceProps {
  value: StockRotation;
  onChange: (value: StockRotation) => void;
  disabled?: boolean;
}

/**
 * Elección de la rotación de stock (SPEC §4.2). Cada tarjeta explica la consecuencia real de la opción, porque
 * cambia el orden en el que se descuenta el stock en cada venta.
 */
export function RotationChoice({ value, onChange, disabled }: RotationChoiceProps) {
  return (
    <div role="radiogroup" aria-label="Rotación de stock" className="grid gap-3 md:grid-cols-2">
      {OPTIONS.map((option) => {
        const selected = option.value === value;
        const Icon = option.icon;
        return (
          <label
            key={option.value}
            className={cn(
              'relative flex cursor-pointer flex-col gap-2 rounded-panel border p-4 transition-colors',
              selected ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted',
              disabled && 'cursor-not-allowed opacity-60',
            )}
          >
            <input
              type="radio"
              name="stockRotation"
              className="sr-only"
              value={option.value}
              checked={selected}
              disabled={disabled}
              onChange={() => onChange(option.value)}
            />
            <div className="flex items-start gap-3">
              <span
                className={cn(
                  'flex size-9 shrink-0 items-center justify-center rounded-control',
                  selected ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
                )}
              >
                <Icon className="size-5" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <p className="gd-eyebrow text-muted-foreground">{option.claim}</p>
                <p className="font-display text-md font-semibold text-foreground">{option.title}</p>
              </div>
              <span
                className={cn(
                  'flex size-5 shrink-0 items-center justify-center rounded-full border',
                  selected ? 'border-primary bg-primary text-primary-foreground' : 'border-input',
                )}
                aria-hidden="true"
              >
                {selected && <Check className="size-3.5" />}
              </span>
            </div>
            <p className="text-read text-foreground">{option.consequence}</p>
            <p className="text-sm text-muted-foreground">
              <span className="font-medium text-foreground">Para quién:</span> {option.fit}
            </p>
            <p className="rounded-control bg-muted px-3 py-2 text-sm text-muted-foreground">{option.example}</p>
          </label>
        );
      })}
    </div>
  );
}
