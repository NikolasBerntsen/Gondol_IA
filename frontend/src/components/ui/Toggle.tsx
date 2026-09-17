import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface ToggleProps {
  checked: boolean;
  onChange: (checked: boolean) => void;
  label?: ReactNode;
  description?: ReactNode;
  disabled?: boolean;
  size?: 'sm' | 'md';
  id?: string;
  /** Nombre accesible cuando no hay `label` visible. */
  ariaLabel?: string;
  className?: string;
}

/** Interruptor on/off (`role="switch"`). */
export function Toggle({
  checked,
  onChange,
  label,
  description,
  disabled = false,
  size = 'md',
  id,
  ariaLabel,
  className,
}: ToggleProps) {
  const autoId = useId();
  const switchId = id ?? `toggle-${autoId}`;
  const descriptionId = description ? `${switchId}-description` : undefined;
  const small = size === 'sm';

  const control = (
    <button
      id={switchId}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label ? undefined : ariaLabel}
      aria-labelledby={label ? `${switchId}-label` : undefined}
      aria-describedby={descriptionId}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={cn(
        'relative inline-flex shrink-0 cursor-pointer rounded-full border-2 border-transparent transition-colors duration-200',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
        small ? 'h-5 w-9' : 'h-6 w-11',
        checked ? 'bg-brand-600' : 'bg-slate-300',
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          'pointer-events-none inline-block transform rounded-full bg-white shadow ring-0 transition duration-200',
          small ? 'h-4 w-4' : 'h-5 w-5',
          checked ? (small ? 'translate-x-4' : 'translate-x-5') : 'translate-x-0',
        )}
      />
    </button>
  );

  if (!label && !description) return <span className={className}>{control}</span>;

  return (
    <div className={cn('flex items-start justify-between gap-4', disabled && 'opacity-70', className)}>
      <div className="min-w-0 text-sm">
        {label && (
          <label id={`${switchId}-label`} htmlFor={switchId} className="cursor-pointer font-medium text-slate-800">
            {label}
          </label>
        )}
        {description && (
          <p id={descriptionId} className="mt-0.5 text-slate-500">
            {description}
          </p>
        )}
      </div>
      {control}
    </div>
  );
}
