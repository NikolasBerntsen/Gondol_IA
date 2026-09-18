import { PLAN_LABELS, type TenantPlan, type TenantStatus } from '@/api/types';
import { StatusPill } from '@/components/gondola';
import { cn } from '@/lib/cn';

/** Plan del cliente: escalera visual (punteado → borde → sólido) además del texto. */
export function PlanBadge({ plan, className }: { plan: TenantPlan; className?: string }) {
  return (
    <span
      className={cn(
        'inline-flex h-[22px] items-center rounded-tag border px-1.5 font-mono text-xs font-semibold uppercase tracking-[0.04em]',
        plan === 'PROFESIONAL' && 'border-foreground bg-foreground text-background',
        plan === 'BASICO' && 'border-input bg-card text-foreground',
        plan === 'FREEMIUM' && 'border-dashed border-input bg-transparent text-muted-foreground',
        className,
      )}
    >
      {PLAN_LABELS[plan]}
    </span>
  );
}

const STATUS_TONE: Record<TenantStatus, 'ok' | 'crit' | 'neutral'> = {
  ACTIVE: 'ok',
  DISABLED: 'crit',
  CANCELLED: 'neutral',
};

const STATUS_LABEL: Record<TenantStatus, string> = {
  ACTIVE: 'Activo',
  DISABLED: 'Deshabilitado',
  CANCELLED: 'Baja',
};

/** Estado del cliente como píldora (palabra + forma + color). */
export function TenantStatusPill({ status }: { status: TenantStatus }) {
  return (
    <StatusPill tone={STATUS_TONE[status]} solid={status === 'DISABLED'}>
      {STATUS_LABEL[status]}
    </StatusPill>
  );
}

/** Franja de severidad de la fila de un cliente. */
export function tenantRowSeverity(status: TenantStatus): 'crit' | 'none' {
  return status === 'DISABLED' ? 'crit' : 'none';
}
