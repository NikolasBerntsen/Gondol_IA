import * as CheckboxPrimitive from '@radix-ui/react-checkbox';
import { Check, Minus } from 'lucide-react';
import { forwardRef, useId, type ComponentPropsWithoutRef, type ElementRef, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface CheckboxProps extends ComponentPropsWithoutRef<typeof CheckboxPrimitive.Root> {
  /** Etiqueta clickeable a la derecha. */
  label?: ReactNode;
  description?: ReactNode;
  /** Clases del contenedor (cuando hay `label`/`description`). */
  containerClassName?: string;
}

/**
 * Casilla de verificación (Radix). Radio de etiqueta (4 px) y estado indeterminado.
 * `onCheckedChange(checked)` recibe `boolean | 'indeterminate'` (no es un evento nativo).
 *
 * ```tsx
 * <Checkbox checked={all} onCheckedChange={(v) => setAll(v === true)} label="Seleccionar todo" />
 * ```
 */
export const Checkbox = forwardRef<ElementRef<typeof CheckboxPrimitive.Root>, CheckboxProps>(function Checkbox(
  { label, description, className, containerClassName, id, disabled, ...props },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? `checkbox-${autoId}`;
  const descriptionId = description ? `${inputId}-description` : undefined;

  const box = (
    <CheckboxPrimitive.Root
      ref={ref}
      id={inputId}
      disabled={disabled}
      aria-describedby={descriptionId}
      className={cn(
        'peer grid h-4 w-4 shrink-0 place-items-center rounded-tag border border-input bg-card transition-colors',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background',
        'disabled:cursor-not-allowed disabled:opacity-50',
        'data-[state=checked]:border-primary data-[state=checked]:bg-primary data-[state=checked]:text-primary-foreground',
        'data-[state=indeterminate]:border-primary data-[state=indeterminate]:bg-primary data-[state=indeterminate]:text-primary-foreground',
        className,
      )}
      {...props}
    >
      <CheckboxPrimitive.Indicator className="flex items-center justify-center text-current">
        {props.checked === 'indeterminate' ? (
          <Minus className="h-3 w-3" strokeWidth={3} aria-hidden="true" />
        ) : (
          <Check className="h-3 w-3" strokeWidth={3} aria-hidden="true" />
        )}
      </CheckboxPrimitive.Indicator>
    </CheckboxPrimitive.Root>
  );

  if (!label && !description) return box;

  return (
    <div className={cn('flex items-start gap-2.5', disabled && 'opacity-60', containerClassName)}>
      <span className="mt-0.5 flex">{box}</span>
      <div className="min-w-0 text-base">
        {label && (
          <label htmlFor={inputId} className="cursor-pointer font-medium text-foreground">
            {label}
          </label>
        )}
        {description && (
          <p id={descriptionId} className="text-sm text-muted-foreground">
            {description}
          </p>
        )}
      </div>
    </div>
  );
});
