import { Inbox, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  icon?: LucideIcon;
  title: ReactNode;
  description?: ReactNode;
  /** Botón o link de acción (p. ej. "Cargar producto"). */
  action?: ReactNode;
  size?: 'sm' | 'md';
  /** Dibuja el borde punteado de contenedor. */
  bordered?: boolean;
  className?: string;
}

export function EmptyState({
  icon: Icon = Inbox,
  title,
  description,
  action,
  size = 'md',
  bordered = false,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        'flex flex-col items-center justify-center text-center',
        size === 'sm' ? 'gap-2 px-4 py-8' : 'gap-3 px-6 py-14',
        bordered && 'rounded-2xl border border-dashed border-slate-300 bg-white/60',
        className,
      )}
    >
      <span
        className={cn(
          'flex items-center justify-center rounded-2xl bg-brand-50 text-brand-600',
          size === 'sm' ? 'h-10 w-10' : 'h-14 w-14',
        )}
      >
        <Icon className={size === 'sm' ? 'h-5 w-5' : 'h-7 w-7'} aria-hidden="true" />
      </span>
      <div className="max-w-md space-y-1">
        <h3 className={cn('font-semibold text-slate-900', size === 'sm' ? 'text-sm' : 'text-base')}>{title}</h3>
        {description && <p className="text-sm text-slate-500">{description}</p>}
      </div>
      {action && <div className="mt-2 flex flex-wrap justify-center gap-2">{action}</div>}
    </div>
  );
}
