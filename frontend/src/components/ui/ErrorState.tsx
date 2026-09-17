import { AlertTriangle, RotateCw, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { ApiError, getErrorMessage } from '@/api/client';
import { cn } from '@/lib/cn';
import { Button } from './Button';

export interface ErrorStateProps {
  title?: ReactNode;
  /** Error de la query/mutación; se traduce con `getErrorMessage`. */
  error?: unknown;
  /** Mensaje explícito (tiene prioridad sobre `error`). */
  message?: ReactNode;
  onRetry?: () => void;
  retrying?: boolean;
  size?: 'sm' | 'md';
  className?: string;
}

/** Error de carga de una sección: qué pasó + "Reintentar". Nunca culpa al usuario (§8). */
export function ErrorState({
  title = 'No pudimos cargar la información',
  error,
  message,
  onRetry,
  retrying,
  size = 'md',
  className,
}: ErrorStateProps) {
  const offline = error instanceof ApiError && error.isNetworkError;
  const Icon = offline ? WifiOff : AlertTriangle;
  return (
    <div
      role="alert"
      className={cn('flex flex-col items-start gap-3', size === 'sm' ? 'px-4 py-6' : 'px-5 py-8', className)}
    >
      <span className="grid h-11 w-11 shrink-0 place-items-center rounded-control border border-dashed border-crit/40 bg-crit-soft text-crit-ink">
        <Icon className="h-5 w-5" aria-hidden="true" />
      </span>
      <div className="max-w-[52ch]">
        <h3 className={cn('font-semibold text-foreground', size === 'sm' ? 'text-base' : 'text-md')}>{title}</h3>
        <p className="mt-1 text-base text-muted-foreground">{message ?? getErrorMessage(error)}</p>
      </div>
      {onRetry && (
        <Button
          variant="outline"
          size="sm"
          onClick={onRetry}
          loading={retrying}
          leftIcon={<RotateCw aria-hidden="true" />}
        >
          Reintentar
        </Button>
      )}
    </div>
  );
}
