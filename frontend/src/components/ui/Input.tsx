import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export type ControlSize = 'sm' | 'md' | 'lg';

/** Clases compartidas por Input, Select y Textarea. */
export const controlBaseClasses =
  'block w-full rounded-xl border border-slate-300 bg-white text-slate-900 shadow-sm transition placeholder:text-slate-400 ' +
  'focus:border-brand-500 focus:outline-none focus:ring-2 focus:ring-brand-500/25 ' +
  'disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500 read-only:bg-slate-50';

export const controlInvalidClasses = 'border-red-400 focus:border-red-500 focus:ring-red-500/25';

/** En mobile se usa 16px para evitar el zoom automático de iOS al enfocar. */
export const CONTROL_SIZE_CLASSES: Record<ControlSize, string> = {
  sm: 'h-8 px-2.5 text-sm',
  md: 'h-10 px-3 text-base sm:text-sm',
  lg: 'h-12 px-4 text-base',
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
        leftIcon && 'pl-10',
        rightElement && 'pr-11',
        className,
      )}
      {...props}
    />
  );
  if (!leftIcon && !rightElement) return input;
  return (
    <div className={cn('relative', containerClassName)}>
      {leftIcon && (
        <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400 [&_svg]:h-4 [&_svg]:w-4">
          {leftIcon}
        </span>
      )}
      {input}
      {rightElement && <span className="absolute inset-y-0 right-1.5 flex items-center">{rightElement}</span>}
    </div>
  );
});
