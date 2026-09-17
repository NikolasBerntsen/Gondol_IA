import { Loader2 } from 'lucide-react';
import { cn } from '@/lib/cn';

const SIZE_CLASSES = {
  xs: 'h-3.5 w-3.5',
  sm: 'h-4 w-4',
  md: 'h-6 w-6',
  lg: 'h-8 w-8',
} as const;

export interface SpinnerProps {
  size?: keyof typeof SIZE_CLASSES;
  /** Texto para lectores de pantalla. */
  label?: string;
  className?: string;
}

export function Spinner({ size = 'md', label = 'Cargando…', className }: SpinnerProps) {
  return (
    <span role="status" className="inline-flex items-center">
      <Loader2 className={cn('animate-spin text-brand-600', SIZE_CLASSES[size], className)} aria-hidden="true" />
      <span className="sr-only">{label}</span>
    </span>
  );
}

export interface PageSpinnerProps {
  label?: string;
  className?: string;
}

/** Spinner centrado para secciones o páginas completas. */
export function PageSpinner({ label = 'Cargando…', className }: PageSpinnerProps) {
  return (
    <div
      role="status"
      className={cn('flex min-h-[40vh] flex-col items-center justify-center gap-3 text-sm text-slate-500', className)}
    >
      <Loader2 className="h-8 w-8 animate-spin text-brand-600" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}
