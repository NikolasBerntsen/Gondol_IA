import { Slot } from '@radix-ui/react-slot';
import { cva, type VariantProps } from 'class-variance-authority';
import { Loader2 } from 'lucide-react';
import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react';
import { Link, type LinkProps } from 'react-router-dom';
import { cn } from '@/lib/cn';

/**
 * Góndola UI · Button (docs/design-system.md §6).
 * Radio de control (8 px), sin sombras. El amarillo (`accent`) NO es una variante de botón:
 * se reserva para precios y totales.
 *
 * Variantes preferidas: `default` (primaria) · `secondary` · `outline` · `ghost` · `destructive` · `link`.
 * Se aceptan además los nombres del kit anterior (`primary` = `default`, `danger` = `destructive`,
 * `danger-outline`) para no romper el código ya escrito.
 */
export type ButtonVariant =
  | 'default'
  | 'primary'
  | 'secondary'
  | 'outline'
  | 'ghost'
  | 'destructive'
  | 'danger'
  | 'danger-outline'
  | 'link';

/** Alturas: sm 32 · default/md 36 · lg 44 (táctil) · xl 56 (acción principal del POS y la carga móvil). */
export type ButtonSize = 'default' | 'sm' | 'md' | 'lg' | 'xl' | 'icon' | 'icon-sm';

export const buttonVariants = cva(
  'inline-flex select-none items-center justify-center gap-2 whitespace-nowrap rounded-control font-sans font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
  {
    variants: {
      variant: {
        default: 'bg-primary text-primary-foreground hover:bg-primary/90 active:bg-primary/80',
        primary: 'bg-primary text-primary-foreground hover:bg-primary/90 active:bg-primary/80',
        secondary: 'bg-muted text-foreground hover:bg-border/70 active:bg-border',
        outline: 'border border-input bg-card text-foreground hover:bg-muted active:bg-muted/70',
        ghost: 'text-foreground hover:bg-muted active:bg-border/60',
        destructive: 'bg-crit text-crit-foreground hover:bg-crit/90 active:bg-crit/80',
        danger: 'bg-crit text-crit-foreground hover:bg-crit/90 active:bg-crit/80',
        'danger-outline': 'border border-crit/40 bg-card text-crit-ink hover:bg-crit-soft active:bg-crit-soft',
        link: 'h-auto px-0 text-primary underline-offset-4 hover:underline',
      },
      size: {
        default: 'h-9 px-3.5 text-base',
        md: 'h-9 px-3.5 text-base',
        sm: 'h-8 px-2.5 text-sm',
        lg: 'h-11 px-5 text-md',
        xl: 'h-14 px-6 text-md [&_svg]:size-5',
        icon: 'h-9 w-9',
        'icon-sm': 'h-8 w-8',
      },
      fullWidth: { true: 'w-full', false: '' },
    },
    defaultVariants: { variant: 'default', size: 'default', fullWidth: false },
  },
);

export interface ButtonStyleOptions {
  variant?: ButtonVariant;
  size?: ButtonSize;
  fullWidth?: boolean;
  className?: string;
}

/** Clases de botón para aplicar a otros elementos (`<a>`, `<label>`…). */
export function buttonClasses({ variant, size, fullWidth, className }: ButtonStyleOptions = {}): string {
  return cn(buttonVariants({ variant, size, fullWidth }), className);
}

export interface ButtonProps
  extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'color'>,
    Omit<VariantProps<typeof buttonVariants>, 'variant' | 'size' | 'fullWidth'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  /** Muestra un spinner, deshabilita el botón y anuncia `aria-busy`. */
  loading?: boolean;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
  /** Renderiza el hijo (p. ej. un `<Link>`) con el estilo del botón. */
  asChild?: boolean;
}

/** Botón base. Por defecto `type="button"`: en formularios usá `type="submit"` explícitamente. */
export const Button = forwardRef<HTMLButtonElement, ButtonProps>(function Button(
  {
    variant,
    size,
    loading = false,
    leftIcon,
    rightIcon,
    fullWidth,
    className,
    children,
    disabled,
    asChild = false,
    type = 'button',
    ...props
  },
  ref,
) {
  const classes = buttonClasses({ variant, size, fullWidth, className });
  if (asChild) {
    return (
      <Slot ref={ref} className={classes} {...props}>
        {children}
      </Slot>
    );
  }
  return (
    <button
      ref={ref}
      type={type}
      className={classes}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      {...props}
    >
      {loading ? <Loader2 className="animate-spin" aria-hidden="true" /> : leftIcon}
      {children}
      {!loading && rightIcon}
    </button>
  );
});

export interface ButtonLinkProps extends LinkProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
  leftIcon?: ReactNode;
  rightIcon?: ReactNode;
  fullWidth?: boolean;
}

/** `<Link>` de react-router con apariencia de botón. */
export const ButtonLink = forwardRef<HTMLAnchorElement, ButtonLinkProps>(function ButtonLink(
  { variant, size, leftIcon, rightIcon, fullWidth, className, children, ...props },
  ref,
) {
  return (
    <Link
      ref={ref}
      className={buttonClasses({
        variant,
        size,
        fullWidth,
        className: typeof className === 'string' ? className : undefined,
      })}
      {...props}
    >
      {leftIcon}
      {children}
      {rightIcon}
    </Link>
  );
});
