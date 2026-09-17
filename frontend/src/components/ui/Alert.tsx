import { AlertTriangle, CheckCircle2, Info, XCircle, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

/** Tonos preferidos: `info` · `ok` · `warn` · `crit` · `brand`. Los nombres viejos siguen andando. */
export type AlertTone = 'info' | 'ok' | 'success' | 'warn' | 'warning' | 'crit' | 'danger' | 'brand';

const TONES: Record<AlertTone, { container: string; icon: string; Icon: LucideIcon }> = {
  info: { container: 'border-info/30 bg-info-soft text-info-ink', icon: 'text-info', Icon: Info },
  ok: { container: 'border-ok/30 bg-ok-soft text-ok-ink', icon: 'text-ok', Icon: CheckCircle2 },
  success: { container: 'border-ok/30 bg-ok-soft text-ok-ink', icon: 'text-ok', Icon: CheckCircle2 },
  warn: { container: 'border-warn/35 bg-warn-soft text-warn-ink', icon: 'text-warn', Icon: AlertTriangle },
  warning: { container: 'border-warn/35 bg-warn-soft text-warn-ink', icon: 'text-warn', Icon: AlertTriangle },
  crit: { container: 'border-crit/35 bg-crit-soft text-crit-ink', icon: 'text-crit', Icon: XCircle },
  danger: { container: 'border-crit/35 bg-crit-soft text-crit-ink', icon: 'text-crit', Icon: XCircle },
  brand: { container: 'border-primary/25 bg-primary/[0.08] text-foreground', icon: 'text-primary', Icon: Info },
};

export interface AlertProps {
  tone?: AlertTone;
  title?: ReactNode;
  children?: ReactNode;
  /** Reemplaza el ícono del tono (`null` = sin ícono). */
  icon?: LucideIcon | null;
  /** Botones o links a la derecha (abajo en mobile). */
  action?: ReactNode;
  className?: string;
}

/** Aviso en línea (radio de control). `crit`/`warn` se anuncian con `role="alert"`. */
export function Alert({ tone = 'info', title, children, icon, action, className }: AlertProps) {
  const style = TONES[tone];
  const Icon = icon === null ? null : (icon ?? style.Icon);
  const isAlert = tone === 'crit' || tone === 'danger' || tone === 'warn' || tone === 'warning';
  return (
    <div
      role={isAlert ? 'alert' : 'status'}
      className={cn(
        'flex flex-col gap-3 rounded-control border p-3.5 text-base sm:flex-row sm:items-start',
        style.container,
        className,
      )}
    >
      <div className="flex min-w-0 flex-1 gap-2.5">
        {Icon && <Icon className={cn('mt-0.5 h-[18px] w-[18px] shrink-0', style.icon)} aria-hidden="true" />}
        <div className="min-w-0 flex-1 space-y-1">
          {title && <p className="font-semibold">{title}</p>}
          {children && <div className="text-[inherit] opacity-90">{children}</div>}
        </div>
      </div>
      {action && <div className="flex shrink-0 flex-wrap gap-2 sm:ml-2">{action}</div>}
    </div>
  );
}
