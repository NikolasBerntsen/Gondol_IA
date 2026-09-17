import { useId, type ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { Switch } from './Switch';

export interface ToggleProps {
  checked: boolean;
  /** Recibe el nuevo valor (no un evento). */
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

/**
 * Interruptor con etiqueta y bajada (fila de configuración). Envuelve `Switch` (Radix):
 * si solo necesitás el control suelto (p. ej. dentro de una tabla) usá `Switch` directamente.
 */
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

  const control = (
    <Switch
      id={switchId}
      size={size}
      checked={checked}
      onCheckedChange={onChange}
      disabled={disabled}
      aria-label={label ? undefined : ariaLabel}
      aria-labelledby={label ? `${switchId}-label` : undefined}
      aria-describedby={descriptionId}
    />
  );

  if (!label && !description) return <span className={className}>{control}</span>;

  return (
    <div className={cn('flex items-start justify-between gap-4', disabled && 'opacity-70', className)}>
      <div className="min-w-0 text-base">
        {label && (
          <label id={`${switchId}-label`} htmlFor={switchId} className="cursor-pointer font-medium text-foreground">
            {label}
          </label>
        )}
        {description && (
          <p id={descriptionId} className="mt-0.5 text-sm text-muted-foreground">
            {description}
          </p>
        )}
      </div>
      {control}
    </div>
  );
}
