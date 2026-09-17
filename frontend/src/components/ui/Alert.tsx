import { AlertTriangle, CheckCircle2, Info, XCircle, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type AlertTone = 'info' | 'success' | 'warning' | 'danger' | 'brand';

const TONES: Record<AlertTone, { container: string; icon: string; Icon: LucideIcon }> = {
  info: { container: 'border-sky-200 bg-sky-50 text-sky-900', icon: 'text-sky-600', Icon: Info },
  success: { container: 'border-emerald-200 bg-emerald-50 text-emerald-900', icon: 'text-emerald-600', Icon: CheckCircle2 },
  warning: { container: 'border-amber-200 bg-amber-50 text-amber-900', icon: 'text-amber-600', Icon: AlertTriangle },
  danger: { container: 'border-red-200 bg-red-50 text-red-900', icon: 'text-red-600', Icon: XCircle },
  brand: { container: 'border-brand-200 bg-brand-50 text-brand-900', icon: 'text-brand-600', Icon: Info },
};

export interface AlertProps {
  tone?: AlertTone;
  title?: ReactNode;
  children?: ReactNode;
  /** Reemplaza el ícono por defecto del tono (`null` = sin ícono). */
  icon?: LucideIcon | null;
  /** Botones o links a la derecha (abajo en mobile). */
  action?: ReactNode;
  className?: string;
}

/** Mensaje destacado dentro de una página o formulario. */
export function Alert({ tone = 'info', title, children, icon, action, className }: AlertProps) {
  const style = TONES[tone];
  const Icon = icon === null ? null : (icon ?? style.Icon);
  return (
    <div
      role={tone === 'danger' || tone === 'warning' ? 'alert' : 'status'}
      className={cn('flex flex-col gap-3 rounded-2xl border p-4 text-sm sm:flex-row sm:items-start', style.container, className)}
    >
      <div className="flex min-w-0 flex-1 gap-3">
        {Icon && <Icon className={cn('mt-0.5 h-5 w-5 shrink-0', style.icon)} aria-hidden="true" />}
        <div className="min-w-0 flex-1 space-y-1">
          {title && <p className="font-semibold">{title}</p>}
          {children && <div className="opacity-90">{children}</div>}
        </div>
      </div>
      {action && <div className="flex shrink-0 flex-wrap gap-2 sm:ml-2">{action}</div>}
    </div>
  );
}
