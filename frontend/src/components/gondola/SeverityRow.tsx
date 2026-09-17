import { forwardRef, type HTMLAttributes } from 'react';
import { TableRow } from '@/components/ui/TableParts';
import { cn } from '@/lib/cn';

export type StripeSeverity = 'crit' | 'warn' | 'info' | 'ok' | 'none';

/** Clase de franja (4 px a la izquierda) para filas de tabla o ítems de lista con esquinas rectas. */
export const stripeClass = (severity: StripeSeverity) => `gd-stripe-${severity}`;

/** Severidad de la fila a partir de la severidad del backend (`Severity`). */
export function severityStripe(severity: 'INFO' | 'WARNING' | 'CRITICAL'): StripeSeverity {
  return severity === 'CRITICAL' ? 'crit' : severity === 'WARNING' ? 'warn' : 'info';
}

const CELL_STRIPE: Record<StripeSeverity, string> = {
  crit: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--crit))]',
  warn: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--warn))]',
  info: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--info))]',
  ok: '[&>td:first-child]:shadow-[inset_4px_0_0_hsl(var(--ok))]',
  none: '',
};

export interface SeverityRowProps extends HTMLAttributes<HTMLTableRowElement> {
  severity: StripeSeverity;
}

/**
 * Fila de tabla con franja de severidad en la primera celda (para tablas armadas con `TableRoot`).
 * Con el componente `Table` usá su prop `rowSeverity`.
 *
 * Solo para tablas y listas de esquinas rectas: **nunca** como barra de acento sobre una tarjeta.
 */
export const SeverityRow = forwardRef<HTMLTableRowElement, SeverityRowProps>(function SeverityRow(
  { severity, className, ...props },
  ref,
) {
  return (
    <TableRow
      ref={ref}
      data-severity={severity}
      className={cn(CELL_STRIPE[severity], '[&>td:first-child]:pl-4', className)}
      {...props}
    />
  );
});

export interface SeverityItemProps extends HTMLAttributes<HTMLElement> {
  severity: StripeSeverity;
  as?: 'li' | 'div';
}

/** Ítem de lista con franja de severidad ("Para hoy", notificaciones). */
export function SeverityItem({ severity, className, as: Comp = 'li', ...props }: SeverityItemProps) {
  return <Comp data-severity={severity} className={cn(stripeClass(severity), 'pl-4', className)} {...props} />;
}

/** Franja suelta para envolver cualquier bloque de esquinas rectas. */
export function SeverityStripe({ severity, className, ...props }: HTMLAttributes<HTMLDivElement> & { severity: StripeSeverity }) {
  return <div data-severity={severity} className={cn(stripeClass(severity), 'pl-4', className)} {...props} />;
}
