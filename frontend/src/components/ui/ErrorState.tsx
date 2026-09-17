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

/** Estado de error para secciones que no pudieron cargar datos. */
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
      className={cn(
        'flex flex-col items-center justify-center text-center',
        size === 'sm' ? 'gap-2 px-4 py-8' : 'gap-3 px-6 py-14',
        className,
      )}
    >
      <span
        className={cn(
          'flex items-center justify-center rounded-2xl bg-red-50 text-red-600',
          size === 'sm' ? 'h-10 w-10' : 'h-14 w-14',
        )}
      >
        <Icon className={size === 'sm' ? 'h-5 w-5' : 'h-7 w-7'} aria-hidden="true" />
      </span>
      <div className="max-w-md space-y-1">
        <h3 className={cn('font-semibold text-slate-900', size === 'sm' ? 'text-sm' : 'text-base')}>{title}</h3>
        <p className="text-sm text-slate-500">{message ?? getErrorMessage(error)}</p>
      </div>
      {onRetry && (
        <Button
          variant="outline"
          size="sm"
          onClick={onRetry}
          loading={retrying}
          leftIcon={<RotateCw className="h-4 w-4" aria-hidden="true" />}
          className="mt-1"
        >
          Reintentar
        </Button>
      )}
    </div>
  );
}
