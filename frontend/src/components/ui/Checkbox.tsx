import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode;
  description?: ReactNode;
}

export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, description, className, id, disabled, ...props },
  ref,
) {
  const autoId = useId();
  const inputId = id ?? `checkbox-${autoId}`;
  const descriptionId = description ? `${inputId}-description` : undefined;
  return (
    <div className={cn('flex items-start gap-3', disabled && 'opacity-60', className)}>
      <input
        ref={ref}
        id={inputId}
        type="checkbox"
        disabled={disabled}
        aria-describedby={descriptionId}
        className="mt-0.5 h-4 w-4 shrink-0 cursor-pointer rounded border-slate-300 accent-brand-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed"
        {...props}
      />
      {(label || description) && (
        <div className="min-w-0 text-sm">
          {label && (
            <label htmlFor={inputId} className="cursor-pointer font-medium text-slate-700">
              {label}
            </label>
          )}
          {description && (
            <p id={descriptionId} className="text-slate-500">
              {description}
            </p>
          )}
        </div>
      )}
    </div>
  );
});
