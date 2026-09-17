import { ArrowLeft, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';

export interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  icon?: LucideIcon;
  /** Botones de la página (a la derecha en desktop, debajo del título en mobile). */
  actions?: ReactNode;
  /** Link "volver" encima del título. */
  back?: { to: string; label?: string };
  /** Contenido extra debajo (p. ej. filtros o `Tabs`). */
  children?: ReactNode;
  className?: string;
}

/** Encabezado de página: título, subtítulo, acciones y "volver". */
export function PageHeader({ title, description, icon: Icon, actions, back, children, className }: PageHeaderProps) {
  return (
    <header className={cn('mb-6 space-y-4', className)}>
      {back && (
        <Link
          to={back.to}
          className="inline-flex items-center gap-1.5 rounded-lg text-sm font-medium text-slate-500 transition hover:text-brand-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          {back.label ?? 'Volver'}
        </Link>
      )}
      <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex min-w-0 items-start gap-3">
          {Icon && (
            <span className="mt-0.5 hidden h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-brand-100 text-brand-700 sm:flex">
              <Icon className="h-6 w-6" aria-hidden="true" />
            </span>
          )}
          <div className="min-w-0">
            <h1 className="text-2xl font-bold tracking-tight text-slate-900 sm:text-[1.75rem]">{title}</h1>
            {description && <p className="mt-1 text-sm text-slate-500 sm:text-base">{description}</p>}
          </div>
        </div>
        {actions && <div className="flex flex-wrap items-center gap-2 sm:shrink-0 sm:justify-end">{actions}</div>}
      </div>
      {children}
    </header>
  );
}
