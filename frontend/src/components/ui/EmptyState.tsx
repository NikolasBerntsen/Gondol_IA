import { Inbox, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface EmptyStateProps {
  icon?: LucideIcon;
  title: ReactNode;
  description?: ReactNode;
  /** Botón o link con la próxima acción (p. ej. "Cargar producto"). */
  action?: ReactNode;
  size?: 'sm' | 'md';
  /** Dibuja un contenedor con borde punteado (para usarlo fuera de un panel). */
  bordered?: boolean;
  className?: string;
}

/**
 * Estado vacío (docs/design-system.md §8): ícono en cuadro punteado, título que dice qué falta,
 * texto con la próxima acción y botón. Alineado a la izquierda, como todo el sistema.
 */
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
        'flex flex-col items-start gap-3',
        size === 'sm' ? 'px-4 py-6' : 'px-5 py-8',
        bordered && 'rounded-panel border border-dashed border-input bg-card/60',
        className,
      )}
    >
      <span className="grid h-11 w-11 shrink-0 place-items-center rounded-control border border-dashed border-input bg-muted text-muted-foreground">
        <Icon className="h-5 w-5" aria-hidden="true" />
      </span>
      <div className="max-w-[52ch]">
        <h3 className={cn('font-semibold text-foreground', size === 'sm' ? 'text-base' : 'text-md')}>{title}</h3>
        {description && <p className="mt-1 text-base text-muted-foreground">{description}</p>}
      </div>
      {action && <div className="flex flex-wrap gap-2">{action}</div>}
    </div>
  );
}
