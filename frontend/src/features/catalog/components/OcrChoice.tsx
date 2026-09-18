import { CheckCircle2 } from 'lucide-react';
import { cn } from '@/lib/cn';

export interface OcrChoiceOption {
  /** Valor que se aplica al campo (fecha ISO o número de lote normalizado). */
  value: string;
  /** Texto del chip ("VTO 25/10/2026"). */
  display: string;
  /** Confianza de la lectura, 0..100. */
  confidence: number;
}

export interface OcrChoiceProps {
  name: string;
  label: string;
  options: readonly OcrChoiceOption[];
  value: string;
  onChange: (value: string) => void;
}

function confidenceTone(confidence: number): string {
  if (confidence >= 80) return 'text-ok-ink';
  if (confidence >= 50) return 'text-warn-ink';
  return 'text-crit-ink';
}

/**
 * Lecturas propuestas por el OCR con su confianza: la cámara sugiere, la persona elige (docs/design-system.md §7.6).
 * Tocar un chip aplica la lectura al campo correspondiente.
 */
export function OcrChoice({ name, label, options, value, onChange }: OcrChoiceProps) {
  if (options.length === 0) return null;
  return (
    <div role="radiogroup" aria-label={label} className="flex flex-col gap-1.5">
      <span className="gd-eyebrow">{label}</span>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => {
          const active = option.value === value;
          return (
            <button
              key={`${name}-${option.value}`}
              type="button"
              role="radio"
              aria-checked={active}
              onClick={() => onChange(option.value)}
              className={cn(
                'inline-flex min-h-11 items-center gap-2 rounded-control border px-3 font-mono text-sm font-medium tabular-nums transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                active
                  ? 'border-primary bg-primary/[0.08] text-foreground'
                  : 'border-input bg-card text-muted-foreground hover:bg-muted',
              )}
            >
              {active ? <CheckCircle2 className="h-4 w-4 text-primary" aria-hidden="true" /> : null}
              <span className={active ? 'text-foreground' : ''}>{option.display}</span>
              <span className={cn('font-semibold', confidenceTone(option.confidence))}>
                · {Math.round(option.confidence)}%
              </span>
              <span className="sr-only">de confianza</span>
            </button>
          );
        })}
      </div>
    </div>
  );
}
