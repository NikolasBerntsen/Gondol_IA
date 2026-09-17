import type { LucideIcon } from 'lucide-react';
import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react';
import { cn } from '@/lib/cn';

export interface TabItem<V extends string = string> {
  value: V;
  label: ReactNode;
  icon?: LucideIcon;
  /** Contador opcional (p. ej. pendientes). */
  count?: number;
  disabled?: boolean;
}

export interface TabsProps<V extends string = string> {
  tabs: ReadonlyArray<TabItem<V>>;
  value: V;
  onChange: (value: V) => void;
  /** `underline` (defecto) para secciones de página; `pills` para filtros compactos. */
  variant?: 'underline' | 'pills';
  /** Nombre accesible del grupo de pestañas. */
  ariaLabel?: string;
  /** Prefijo de ids para conectar con paneles: el panel lleva `id={`${idPrefix}-panel-${value}`}`. */
  idPrefix?: string;
  className?: string;
}

/**
 * Pestañas accesibles (flechas, Inicio/Fin). Scrollean en horizontal en pantallas chicas.
 * Para filtrar una lista con contadores vivos preferí `Segmented` (`@/components/ui`).
 */
export function Tabs<V extends string = string>({
  tabs,
  value,
  onChange,
  variant = 'underline',
  ariaLabel,
  idPrefix,
  className,
}: TabsProps<V>) {
  const autoId = useId();
  const prefix = idPrefix ?? `tabs-${autoId.replace(/:/g, '')}`;
  const listRef = useRef<HTMLDivElement>(null);

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const enabled = tabs.filter((tab) => !tab.disabled);
    const index = enabled.findIndex((tab) => tab.value === value);
    let next: TabItem<V> | undefined;
    if (event.key === 'ArrowRight') next = enabled[(index + 1) % enabled.length];
    else if (event.key === 'ArrowLeft') next = enabled[(index - 1 + enabled.length) % enabled.length];
    else if (event.key === 'Home') next = enabled[0];
    else if (event.key === 'End') next = enabled[enabled.length - 1];
    if (!next) return;
    event.preventDefault();
    onChange(next.value);
    listRef.current?.querySelector<HTMLElement>(`[data-value="${CSS.escape(next.value)}"]`)?.focus();
  };

  return (
    <div
      ref={listRef}
      role="tablist"
      aria-label={ariaLabel}
      onKeyDown={onKeyDown}
      className={cn(
        'flex max-w-full overflow-x-auto [scrollbar-width:none] [&::-webkit-scrollbar]:hidden',
        variant === 'underline' ? 'gap-1 border-b border-border' : 'gap-0.5 rounded-control border border-border bg-muted p-0.5',
        className,
      )}
    >
      {tabs.map((tab) => {
        const selected = tab.value === value;
        const Icon = tab.icon;
        return (
          <button
            key={tab.value}
            type="button"
            role="tab"
            id={`${prefix}-tab-${tab.value}`}
            aria-selected={selected}
            aria-controls={idPrefix ? `${prefix}-panel-${tab.value}` : undefined}
            tabIndex={selected ? 0 : -1}
            disabled={tab.disabled}
            data-value={tab.value}
            onClick={() => onChange(tab.value)}
            className={cn(
              'inline-flex shrink-0 items-center gap-2 whitespace-nowrap text-base font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50',
              variant === 'underline'
                ? cn(
                    '-mb-px border-b-2 px-3 py-2.5',
                    selected
                      ? 'border-primary text-primary'
                      : 'border-transparent text-muted-foreground hover:border-input hover:text-foreground',
                  )
                : cn(
                    'h-8 rounded-[6px] px-3',
                    selected
                      ? 'bg-card text-foreground shadow-[0_0_0_1px_hsl(var(--border))]'
                      : 'text-muted-foreground hover:text-foreground',
                  ),
            )}
          >
            {Icon && <Icon className="h-4 w-4" aria-hidden="true" />}
            {tab.label}
            {tab.count !== undefined && (
              <span
                className={cn(
                  'rounded-tag px-1 font-mono text-[11px] tabular-nums',
                  selected ? 'bg-primary/10 text-primary' : 'bg-background text-muted-foreground',
                )}
              >
                {tab.count}
              </span>
            )}
          </button>
        );
      })}
    </div>
  );
}
