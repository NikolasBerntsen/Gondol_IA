import { useId } from 'react';
import { cn } from '@/lib/cn';

export interface LogoMarkProps {
  className?: string;
  title?: string;
}

/** Isotipo: carrito de compras con el destello de la IA (mismo diseño que el favicon). */
export function LogoMark({ className, title }: LogoMarkProps) {
  const gradientId = `logo-gradient-${useId().replace(/:/g, '')}`;
  return (
    <svg
      viewBox="0 0 64 64"
      className={cn('h-10 w-10 shrink-0', className)}
      role={title ? 'img' : undefined}
      aria-hidden={title ? undefined : true}
      aria-label={title}
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#2f8753" />
          <stop offset="1" stopColor="#17452c" />
        </linearGradient>
      </defs>
      <rect width="64" height="64" rx="15" fill={`url(#${gradientId})`} />
      <path
        d="M10 18h6l5.2 22H45l5-16H17.9"
        fill="none"
        stroke="#fff"
        strokeWidth="4.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="24" cy="48" r="3.6" fill="#fff" />
      <circle cx="42" cy="48" r="3.6" fill="#fff" />
      <path d="M47 4c1 5 2 6 7 7-5 1-6 2-7 7-1-5-2-6-7-7 5-1 6-2 7-7z" fill="#bef264" />
    </svg>
  );
}

export interface LogoProps {
  /** `light`: texto claro para fondos oscuros (sidebar). `dark`: texto oscuro para fondos claros. */
  variant?: 'light' | 'dark';
  size?: 'sm' | 'md' | 'lg';
  /** Muestra "Tu negocio siempre a tiempo" debajo del nombre. */
  showTagline?: boolean;
  className?: string;
}

const MARK_SIZES = { sm: 'h-8 w-8', md: 'h-10 w-10', lg: 'h-12 w-12' } as const;
const TEXT_SIZES = { sm: 'text-lg', md: 'text-xl', lg: 'text-2xl' } as const;

export function Logo({ variant = 'light', size = 'md', showTagline = false, className }: LogoProps) {
  const light = variant === 'light';
  return (
    <div className={cn('flex items-center gap-3', className)}>
      <LogoMark className={MARK_SIZES[size]} />
      <div className="min-w-0 leading-tight">
        <p className={cn('font-bold tracking-tight', TEXT_SIZES[size], light ? 'text-white' : 'text-slate-900')}>
          Gondol<span className={light ? 'text-lime-300' : 'text-brand-600'}>IA</span>
        </p>
        {showTagline && (
          <p className={cn('truncate text-xs font-medium', light ? 'text-brand-200/80' : 'text-slate-500')}>
            Tu negocio siempre a tiempo
          </p>
        )}
      </div>
    </div>
  );
}
