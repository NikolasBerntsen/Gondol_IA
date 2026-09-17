import { ChevronDown } from 'lucide-react';
import { forwardRef, type ReactNode, type SelectHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';
import {
  CONTROL_SIZE_CLASSES,
  controlBaseClasses,
  controlInvalidClasses,
  isAriaInvalid,
  type ControlSize,
} from './Input';

export interface SelectOption<V extends string | number = string> {
  value: V;
  label: string;
  disabled?: boolean;
}

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  options?: ReadonlyArray<SelectOption<string | number>>;
  /** Opción vacía inicial (valor `""`). */
  placeholder?: string;
  invalid?: boolean;
  selectSize?: ControlSize;
  /** Ícono decorativo a la izquierda. */
  leftIcon?: ReactNode;
  containerClassName?: string;
}

/** `<select>` nativo con estilo de la app (mejor experiencia en mobile que un combo propio). */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select(
  { options, placeholder, invalid, selectSize = 'md', leftIcon, className, containerClassName, children, ...props },
  ref,
) {
  const hasError = invalid || isAriaInvalid(props['aria-invalid']);
  return (
    <div className={cn('relative', containerClassName)}>
      {leftIcon && (
        <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-slate-400 [&_svg]:h-4 [&_svg]:w-4">
          {leftIcon}
        </span>
      )}
      <select
        ref={ref}
        aria-invalid={hasError || undefined}
        className={cn(
          controlBaseClasses,
          CONTROL_SIZE_CLASSES[selectSize],
          'cursor-pointer appearance-none pr-9',
          leftIcon && 'pl-10',
          hasError && controlInvalidClasses,
          className,
        )}
        {...props}
      >
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options?.map((option) => (
          <option key={option.value} value={option.value} disabled={option.disabled}>
            {option.label}
          </option>
        ))}
        {children}
      </select>
      <ChevronDown
        className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400"
        aria-hidden="true"
      />
    </div>
  );
});
