import { cn } from '@/lib/cn';

export interface LogoMarkProps {
  className?: string;
  size?: number;
  /** Con texto, el isotipo deja de ser decorativo y se anuncia. */
  title?: string;
}

/**
 * Isotipo: etiqueta de góndola amarilla con el agujero perforado y las líneas del precio
 * (mismo lenguaje que `PriceTag`).
 */
export function LogoMark({ className, size = 28, title }: LogoMarkProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      className={cn('shrink-0', className)}
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
    >
      <path
        d="M4 7.5 A3.5 3.5 0 0 1 7.5 4 H24.5 A3.5 3.5 0 0 1 28 7.5 V24.5 A3.5 3.5 0 0 1 24.5 28 H7.5 A3.5 3.5 0 0 1 4 24.5 Z"
        fill="hsl(var(--accent))"
      />
      <circle cx="9.5" cy="16" r="2.4" fill="hsl(var(--rail))" />
      <path
        d="M15 11.5 h9 M15 16 h7 M15 20.5 h9"
        stroke="hsl(var(--accent-foreground))"
        strokeWidth="2.2"
        strokeLinecap="round"
      />
    </svg>
  );
}

export interface LogoProps {
  /** `light`: texto claro para el riel. `dark`: texto oscuro para fondos claros (login, barra superior). */
  variant?: 'light' | 'dark';
  size?: 'sm' | 'md' | 'lg';
  /** Muestra "Tu negocio siempre a tiempo" debajo del nombre. */
  showTagline?: boolean;
  className?: string;
}

const MARK_SIZES = { sm: 24, md: 28, lg: 36 } as const;
const TEXT_SIZES = { sm: 'text-[17px]', md: 'text-[19px]', lg: 'text-[24px]' } as const;

export function Logo({ variant = 'light', size = 'md', showTagline = false, className }: LogoProps) {
  const light = variant === 'light';
  return (
    <div className={cn('flex items-center gap-2.5', className)}>
      <LogoMark size={MARK_SIZES[size]} />
      <div className="min-w-0 leading-none">
        <div
          className={cn(
            'font-display font-bold tracking-[-0.02em]',
            TEXT_SIZES[size],
            light ? 'text-rail-strong' : 'text-foreground',
          )}
        >
          Gondol<span className={light ? 'text-accent' : 'text-primary'}>IA</span>
        </div>
        {showTagline && (
          <div className={cn('mt-1 truncate text-[11px] font-medium', light ? 'text-rail-muted' : 'text-muted-foreground')}>
            Tu negocio siempre a tiempo
          </div>
        )}
      </div>
    </div>
  );
}
