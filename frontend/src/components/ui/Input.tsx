import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type ControlSize = 'sm' | 'md' | 'lg';

/**
 * Clases compartidas por Input, Select y Textarea (docs/design-system.md §6):
 * radio de control (8 px), borde `--input`, foco con borde `ring` + halo al 25 %,
 * `aria-invalid` pinta el borde en `crit`.
 */
export const controlBaseClasses =
  'block w-full rounded-control border border-input bg-card text-foreground transition-colors ' +
  'placeholder:text-muted-foreground hover:border-muted-foreground/60 ' +
  'focus-visible:border-ring focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/25 ' +
  'disabled:cursor-not-allowed disabled:bg-muted disabled:text-muted-foreground read-only:bg-muted/60';

export const controlInvalidClasses = 'border-crit focus-visible:border-crit focus-visible:ring-crit/25';

/** Alturas: sm 32 · md 36 (base) · lg 44 (táctil). 16 px en móvil para evitar el zoom de iOS. */
export const CONTROL_SIZE_CLASSES: Record<ControlSize, string> = {
  sm: 'h-8 px-2.5 text-base',
  md: 'h-9 px-3 text-md sm:text-base',
  lg: 'h-11 px-3.5 text-md',
};

export function isAriaInvalid(value: unknown): boolean {
  return value === true || value === 'true';
}

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  invalid?: boolean;
  inputSize?: ControlSize;
  /** Ícono a la izquierda (decorativo). */
  leftIcon?: ReactNode;
  /** Elemento a la derecha (p. ej. un botón para mostrar la contraseña). */
  rightElement?: ReactNode;
  containerClassName?: string;
}

export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { invalid, inputSize = 'md', leftIcon, rightElement, className, containerClassName, ...props },
  ref,
) {
  const hasError = invalid || isAriaInvalid(props['aria-invalid']);
  const input = (
    <input
      ref={ref}
      aria-invalid={hasError || undefined}
      className={cn(
        controlBaseClasses,
        CONTROL_SIZE_CLASSES[inputSize],
        hasError && controlInvalidClasses,
        leftIcon && 'pl-9',
        rightElement && 'pr-10',
        className,
      )}
      {...props}
    />
  );
  if (!leftIcon && !rightElement) return input;
  return (
    <div className={cn('relative', containerClassName)}>
      {leftIcon && (
        <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-muted-foreground [&_svg]:h-4 [&_svg]:w-4">
          {leftIcon}
        </span>
      )}
      {input}
      {rightElement && <span className="absolute inset-y-0 right-1 flex items-center">{rightElement}</span>}
    </div>
  );
});
