import { Check, Minus, Plus } from 'lucide-react';
import { useRef, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

// ---------------------------------------------------------------------------
// Segmented: opciones excluyentes con contadores (filtros de listado)
// ---------------------------------------------------------------------------

export interface SegmentedOption<T extends string> {
  value: T;
  label: ReactNode;
  /** Contador tonal a la derecha de la opción. */
  count?: ReactNode;
  tone?: 'ok' | 'warn' | 'crit';
  icon?: ReactNode;
  title?: string;
}

export interface SegmentedProps<T extends string> {
  value: T;
  onChange: (value: T) => void;
  options: ReadonlyArray<SegmentedOption<T>>;
  /** Nombre accesible del grupo. */
  label: string;
  id?: string;
  size?: 'sm' | 'md';
  className?: string;
}

/**
 * Control segmentado (`radiogroup` con flechas): filtros de estado con contadores vivos
 * ("Válidas 1.194 · Advertencias 45 · Errores 9").
 */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
  label,
  id,
  size = 'md',
  className,
}: SegmentedProps<T>) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const index = options.findIndex((option) => option.value === value);

  const move = (direction: number) => {
    const next = (index + direction + options.length) % options.length;
    onChange(options[next].value);
    refs.current[next]?.focus();
  };

  return (
    <div
      id={id}
      role="radiogroup"
      aria-label={label}
      className={cn(
        'gd-scroll inline-flex max-w-full items-stretch gap-0.5 overflow-x-auto rounded-control border border-border bg-muted p-0.5',
        className,
      )}
      onKeyDown={(event) => {
        if (event.key === 'ArrowRight' || event.key === 'ArrowDown') {
          event.preventDefault();
          move(1);
        } else if (event.key === 'ArrowLeft' || event.key === 'ArrowUp') {
          event.preventDefault();
          move(-1);
        }
      }}
    >
      {options.map((option, i) => {
        const active = option.value === value;
        return (
          <button
            key={option.value}
            ref={(element) => {
              refs.current[i] = element;
            }}
            type="button"
            role="radio"
            aria-checked={active}
            title={option.title}
            tabIndex={active ? 0 : -1}
            onClick={() => onChange(option.value)}
            className={cn(
              'inline-flex min-w-0 shrink-0 items-center justify-center gap-1.5 whitespace-nowrap rounded-[6px] font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring [&_svg]:size-4',
              size === 'sm' ? 'h-7 px-2 text-xs' : 'h-8 px-3 text-base',
              active
                ? 'bg-card text-foreground shadow-[0_0_0_1px_hsl(var(--border))]'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {option.icon}
            {option.label}
            {option.count !== undefined && (
              <span
                className={cn(
                  'rounded-tag px-1 font-mono text-[11px] tabular-nums',
                  option.tone === 'crit' && 'bg-crit-soft text-crit-ink',
                  option.tone === 'warn' && 'bg-warn-soft text-warn-ink',
                  option.tone === 'ok' && 'bg-ok-soft text-ok-ink',
                  !option.tone && 'bg-background text-muted-foreground',
                )}
              >
                {option.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}

// ---------------------------------------------------------------------------
// QtyStepper: − [n] +
// ---------------------------------------------------------------------------

export interface QtyStepperProps {
  value: number;
  onChange: (value: number) => void;
  /** Nombre accesible ("Cantidad de Leche entera 1 L"). */
  label: string;
  id?: string;
  min?: number;
  max?: number;
  /** `sm` 32 · `md` 36 · `lg` 44 (carrito del POS y carga móvil). */
  size?: 'sm' | 'md' | 'lg';
  disabled?: boolean;
  className?: string;
}

/** Contador de unidades con input editable (carrito del POS, carga de mercadería). */
export function QtyStepper({
  value,
  onChange,
  label,
  id,
  min = 0,
  max = 9999,
  size = 'md',
  disabled,
  className,
}: QtyStepperProps) {
  const height = size === 'lg' ? 'h-11' : size === 'sm' ? 'h-8' : 'h-9';
  const width = size === 'lg' ? 'w-11' : size === 'sm' ? 'w-8' : 'w-9';
  const clamp = (n: number) => Math.max(min, Math.min(max, n));
  return (
    <div
      className={cn('inline-flex items-stretch overflow-hidden rounded-control border border-input bg-card', height, className)}
      role="group"
      aria-label={label}
    >
      <button
        type="button"
        className={cn('grid place-items-center text-foreground transition-colors hover:bg-muted disabled:opacity-40', width)}
        onClick={() => onChange(clamp(value - 1))}
        disabled={disabled || value <= min}
        aria-label={`Restar uno a ${label.toLowerCase()}`}
      >
        <Minus className="h-4 w-4" aria-hidden="true" />
      </button>
      <input
        id={id}
        inputMode="numeric"
        disabled={disabled}
        className={cn(
          'w-12 border-x border-input bg-transparent text-center font-semibold tabular-nums text-foreground focus-visible:bg-primary/[0.06] focus-visible:outline-none',
          size === 'lg' ? 'text-md' : 'text-base',
        )}
        value={value}
        aria-label={label}
        onChange={(event) => {
          const parsed = Number(event.target.value.replace(/\D/g, ''));
          onChange(clamp(Number.isFinite(parsed) ? parsed : min));
        }}
      />
      <button
        type="button"
        className={cn('grid place-items-center text-foreground transition-colors hover:bg-muted disabled:opacity-40', width)}
        onClick={() => onChange(clamp(value + 1))}
        disabled={disabled || value >= max}
        aria-label={`Sumar uno a ${label.toLowerCase()}`}
      >
        <Plus className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
}

// ---------------------------------------------------------------------------
// WizardSteps: pasos reales de un proceso (importación)
// ---------------------------------------------------------------------------

export interface WizardStepsProps {
  steps: readonly string[];
  /** Índice del paso actual (0-based). */
  current: number;
  ariaLabel?: string;
  className?: string;
}

/** Pasos numerados. Solo para procesos secuenciales de verdad (nunca como decoración). */
export function WizardSteps({ steps, current, ariaLabel = 'Pasos', className }: WizardStepsProps) {
  return (
    <ol className={cn('flex flex-wrap items-center gap-x-2 gap-y-2', className)} aria-label={ariaLabel}>
      {steps.map((step, index) => {
        const done = index < current;
        const active = index === current;
        return (
          <li key={step} className="flex items-center gap-2" aria-current={active ? 'step' : undefined}>
            <span
              className={cn(
                'grid h-6 w-6 shrink-0 place-items-center rounded-full border text-xs font-bold tabular-nums',
                done && 'border-primary bg-primary text-primary-foreground',
                active && 'border-primary bg-card text-primary ring-2 ring-primary/25',
                !done && !active && 'border-input bg-card text-muted-foreground',
              )}
            >
              {done ? <Check className="h-3.5 w-3.5" strokeWidth={3} aria-hidden="true" /> : index + 1}
            </span>
            <span
              className={cn(
                'text-base',
                active ? 'font-semibold text-foreground' : done ? 'font-medium text-foreground' : 'text-muted-foreground',
              )}
            >
              {step}
              {done && <span className="sr-only"> (completo)</span>}
            </span>
            {index < steps.length - 1 && (
              <span aria-hidden="true" className={cn('mx-1 hidden h-px w-8 sm:block', done ? 'bg-primary' : 'bg-border')} />
            )}
          </li>
        );
      })}
    </ol>
  );
}

// ---------------------------------------------------------------------------
// Kbd
// ---------------------------------------------------------------------------

/** Tecla de atajo (F2, F4, F8, Esc, Enter). Se oculta en pantallas táctiles. */
export function Kbd({ children, className }: { children: ReactNode; className?: string }) {
  return <kbd className={cn('gd-kbd hidden sm:inline-flex', className)}>{children}</kbd>;
}
