import type { LucideIcon } from 'lucide-react';
import { forwardRef, type HTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type CardPadding = 'none' | 'sm' | 'md' | 'lg';

const PADDING_CLASSES: Record<CardPadding, string> = {
  none: '',
  sm: 'p-3.5',
  md: 'p-4 sm:p-5',
  lg: 'p-5 sm:p-6',
};

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  padding?: CardPadding;
}

/**
 * Panel: superficie `card` con borde y radio de panel (12 px). **Sin sombra**: en Góndola UI
 * la sombra significa "esto flota por encima" (diálogos, popovers).
 * Para listas y tablas usá `padding="none"` y dejá que la tabla ocupe todo el panel.
 */
export const Card = forwardRef<HTMLDivElement, CardProps>(function Card({ padding = 'md', className, ...props }, ref) {
  return (
    <div
      ref={ref}
      className={cn('min-w-0 rounded-panel border border-border bg-card text-foreground', PADDING_CLASSES[padding], className)}
      {...props}
    />
  );
});

export interface CardHeaderProps {
  title: ReactNode;
  description?: ReactNode;
  icon?: LucideIcon;
  actions?: ReactNode;
  /** Id del título (para `aria-labelledby` del panel). */
  titleId?: string;
  className?: string;
}

/** Encabezado de panel: título de 16 px, bajada y acciones a la derecha. */
export function CardHeader({ title, description, icon: Icon, actions, titleId, className }: CardHeaderProps) {
  return (
    <div className={cn('flex flex-wrap items-start justify-between gap-x-4 gap-y-2', className)}>
      <div className="flex min-w-0 items-start gap-2.5">
        {Icon && (
          <span className="mt-0.5 shrink-0 text-muted-foreground">
            <Icon className="h-[18px] w-[18px]" aria-hidden="true" />
          </span>
        )}
        <div className="min-w-0">
          <h2 id={titleId} className="text-md font-semibold leading-6 text-foreground">
            {title}
          </h2>
          {description && <p className="mt-0.5 text-sm text-muted-foreground">{description}</p>}
        </div>
      </div>
      {actions && <div className="flex min-w-0 max-w-full flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function CardFooter({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn('flex flex-col-reverse gap-2 border-t border-border pt-4 sm:flex-row sm:justify-end', className)}
      {...props}
    />
  );
}

/** Alias: en `docs/design-system.md` y en el prototipo este componente se llama `Panel`. */
export const Panel = Card;
export const PanelHeader = CardHeader;
