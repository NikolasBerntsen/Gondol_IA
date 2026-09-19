import { ArrowLeft, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';
import { useDocumentTitle } from '@/lib/documentTitle';

export interface PageHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  /** Rótulo chico en mayúsculas encima del título. */
  eyebrow?: ReactNode;
  icon?: LucideIcon;
  /** Botones de la página (a la derecha en desktop, debajo del título en mobile). */
  actions?: ReactNode;
  /** Link "volver" encima del título. */
  back?: { to: string; label?: string };
  /** Contenido extra debajo (filtros, `Tabs`, `Segmented`). */
  children?: ReactNode;
  /**
   * Título de la pestaña ("… · GondolIA"). Por defecto, `title` cuando es texto; si `title` es un nodo, pasalo acá.
   * `null` deja el título de la ruta.
   */
  documentTitle?: string | null;
  className?: string;
}

/**
 * Encabezado de página: título en Bricolage (26 px en mobile, 34 px desde `sm`), bajada y acciones.
 * Alineado a la izquierda y sin héroes: el contenido empieza enseguida.
 */
export function PageHeader({
  title,
  description,
  eyebrow,
  icon: Icon,
  actions,
  back,
  children,
  documentTitle,
  className,
}: PageHeaderProps) {
  useDocumentTitle(documentTitle === undefined ? (typeof title === 'string' ? title : null) : documentTitle);

  return (
    <header className={cn('mb-5 space-y-4', className)}>
      {back && (
        <Link
          to={back.to}
          className="inline-flex items-center gap-1.5 rounded-control text-sm font-semibold text-muted-foreground transition-colors hover:text-primary"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          {back.label ?? 'Volver'}
        </Link>
      )}
      <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="flex min-w-0 items-start gap-3">
          {Icon && (
            <span className="mt-1 hidden h-10 w-10 shrink-0 place-items-center rounded-control bg-primary/10 text-primary sm:grid">
              <Icon className="h-5 w-5" aria-hidden="true" />
            </span>
          )}
          <div className="min-w-0 max-w-[68ch]">
            {eyebrow && <div className="gd-eyebrow mb-1">{eyebrow}</div>}
            <h1 className="font-display text-xl font-semibold leading-8 tracking-[-0.015em] text-foreground sm:text-2xl sm:leading-10">
              {title}
            </h1>
            {description && <p className="mt-1 text-read text-muted-foreground">{description}</p>}
          </div>
        </div>
        {actions && <div className="flex min-w-0 max-w-full flex-wrap items-center gap-2">{actions}</div>}
      </div>
      {children}
    </header>
  );
}
