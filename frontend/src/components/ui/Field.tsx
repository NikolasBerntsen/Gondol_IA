import { AlertCircle } from 'lucide-react';
import { cloneElement, isValidElement, useId, type ReactElement, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

/** Props de accesibilidad que `Field` inyecta en su control. */
export interface FieldControlProps {
  id: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
  'aria-required'?: boolean;
}

export interface FieldProps {
  label?: ReactNode;
  /** Id del control; si no se indica se toma el `id` del hijo o se genera uno. */
  htmlFor?: string;
  hint?: ReactNode;
  /** Mensaje de error; marca el control como inválido. */
  error?: ReactNode;
  /** Solo accesibilidad (`aria-required`): Góndola UI marca lo **opcional**, no lo obligatorio. */
  required?: boolean;
  /** Muestra "(opcional)" junto a la etiqueta. */
  optional?: boolean;
  /** Acción a la derecha de la etiqueta (p. ej. un link "Generar"). */
  labelAction?: ReactNode;
  className?: string;
  /** Un único control (Input, Select, Textarea…) o una función que recibe las props de accesibilidad. */
  children: ReactElement | ((props: FieldControlProps) => ReactNode);
}

/** `aria-describedby` para controles que no usan `Field` (p. ej. un grupo de radios). */
export function fieldDescribedBy(id: string, opts: { hint?: unknown; error?: unknown }): string | undefined {
  return [opts.error ? `${id}-error` : null, opts.hint ? `${id}-hint` : null].filter(Boolean).join(' ') || undefined;
}

/** Etiqueta + control + error + ayuda, con `id`/`aria-*` conectados automáticamente. */
export function Field({ label, htmlFor, hint, error, required, optional, labelAction, className, children }: FieldProps) {
  const autoId = useId();
  const childId =
    isValidElement<{ id?: unknown }>(children) && typeof children.props.id === 'string' ? children.props.id : undefined;
  const id = htmlFor ?? childId ?? `field-${autoId}`;
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [errorId, hintId].filter(Boolean).join(' ') || undefined;

  const controlProps: FieldControlProps = {
    id,
    'aria-describedby': describedBy,
    'aria-invalid': error ? true : undefined,
    'aria-required': required || undefined,
  };

  const control =
    typeof children === 'function'
      ? children(controlProps)
      : isValidElement(children)
        ? cloneElement(children as ReactElement<Partial<FieldControlProps>>, controlProps)
        : children;

  return (
    <div className={cn('flex min-w-0 flex-col gap-1.5', className)}>
      {(label || labelAction) && (
        <div className="flex items-baseline justify-between gap-2">
          {label && (
            <label htmlFor={id} className="flex items-baseline gap-1.5 text-sm font-semibold text-foreground">
              {label}
              {optional && <span className="text-xs font-normal text-muted-foreground">(opcional)</span>}
            </label>
          )}
          {labelAction}
        </div>
      )}
      {control}
      {error && (
        <p id={errorId} className="flex items-start gap-1.5 text-sm text-crit-ink">
          <AlertCircle className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
          <span>{error}</span>
        </p>
      )}
      {hint && (
        <p id={hintId} className="text-sm text-muted-foreground">
          {hint}
        </p>
      )}
    </div>
  );
}
