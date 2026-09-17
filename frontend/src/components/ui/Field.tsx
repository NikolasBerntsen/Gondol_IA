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
  required?: boolean;
  /** Muestra "(opcional)" junto a la etiqueta. */
  optional?: boolean;
  /** Acción a la derecha de la etiqueta (p. ej. un link "Generar"). */
  labelAction?: ReactNode;
  className?: string;
  /** Un único control (Input, Select, Textarea…) o una función que recibe las props de accesibilidad. */
  children: ReactElement | ((props: FieldControlProps) => ReactNode);
}

/** Etiqueta + control + ayuda + error, con `id`/`aria-*` conectados automáticamente. */
export function Field({ label, htmlFor, hint, error, required, optional, labelAction, className, children }: FieldProps) {
  const autoId = useId();
  const childId =
    isValidElement<{ id?: unknown }>(children) && typeof children.props.id === 'string' ? children.props.id : undefined;
  const id = htmlFor ?? childId ?? `field-${autoId}`;
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;

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
    <div className={cn('space-y-1.5', className)}>
      {(label || labelAction) && (
        <div className="flex items-center justify-between gap-2">
          {label && (
            <label htmlFor={id} className="block text-sm font-medium text-slate-700">
              {label}
              {required && (
                <span className="ml-0.5 text-red-500" aria-hidden="true">
                  *
                </span>
              )}
              {optional && <span className="ml-1 font-normal text-slate-400">(opcional)</span>}
            </label>
          )}
          {labelAction}
        </div>
      )}
      {control}
      {hint && (
        <p id={hintId} className="text-xs text-slate-500">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="flex items-start gap-1.5 text-sm text-red-600">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}
