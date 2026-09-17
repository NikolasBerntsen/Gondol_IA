import { Search, X } from 'lucide-react';
import { forwardRef, type InputHTMLAttributes } from 'react';
import { cn } from '@/lib/cn';
import { Input, type ControlSize } from './Input';

export interface SearchInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'size' | 'type'> {
  value: string;
  onValueChange: (value: string) => void;
  /** Se llama al presionar Enter (útil para búsquedas que no son "en vivo"). */
  onSearch?: (value: string) => void;
  inputSize?: ControlSize;
  /** Etiqueta accesible (por defecto el placeholder). */
  label?: string;
  containerClassName?: string;
}

/**
 * Buscador con ícono y botón para limpiar. Para filtrar mientras se escribe combinalo con `useDebounce`:
 * `const q = useDebounce(search, 300)`.
 */
export const SearchInput = forwardRef<HTMLInputElement, SearchInputProps>(function SearchInput(
  { value, onValueChange, onSearch, inputSize = 'md', label, placeholder = 'Buscar…', containerClassName, className, onKeyDown, ...props },
  ref,
) {
  return (
    <div role="search" className={cn('relative w-full', containerClassName)}>
      <Input
        ref={ref}
        type="search"
        inputMode="search"
        enterKeyHint="search"
        autoComplete="off"
        value={value}
        placeholder={placeholder}
        aria-label={label ?? placeholder}
        inputSize={inputSize}
        leftIcon={<Search aria-hidden="true" />}
        rightElement={
          value ? (
            <button
              type="button"
              onClick={() => onValueChange('')}
              className="grid h-7 w-7 place-items-center rounded-control text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              aria-label="Limpiar búsqueda"
            >
              <X className="h-4 w-4" aria-hidden="true" />
            </button>
          ) : undefined
        }
        className={cn('[&::-webkit-search-cancel-button]:hidden', className)}
        onChange={(event) => onValueChange(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter' && onSearch) {
            event.preventDefault();
            onSearch(value.trim());
          }
          onKeyDown?.(event);
        }}
        {...props}
      />
    </div>
  );
});
