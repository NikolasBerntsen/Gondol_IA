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
  /** Prefijo de ids para conectar con paneles: el panel debe tener `id={`${idPrefix}-panel-${value}`}`. */
  idPrefix?: string;
  className?: string;
}

/** Pestañas accesibles (flechas izquierda/derecha, Inicio/Fin). Scrollean horizontalmente en mobile. */
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
        variant === 'underline' ? 'gap-1 border-b border-slate-200' : 'gap-1.5 rounded-2xl bg-slate-100 p-1',
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
              'inline-flex shrink-0 items-center gap-2 whitespace-nowrap text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500 disabled:pointer-events-none disabled:opacity-50',
              variant === 'underline'
                ? cn(
                    '-mb-px border-b-2 px-3 py-2.5',
                    selected
                      ? 'border-brand-600 text-brand-700'
                      : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-700',
                  )
                : cn(
                    'rounded-xl px-3 py-1.5',
                    selected ? 'bg-white text-brand-700 shadow-sm' : 'text-slate-600 hover:text-slate-900',
                  ),
            )}
          >
            {Icon && <Icon className="h-4 w-4" aria-hidden="true" />}
            {tab.label}
            {tab.count !== undefined && (
              <span
                className={cn(
                  'min-w-[1.25rem] rounded-full px-1.5 py-0.5 text-center text-[11px] font-semibold leading-none',
                  selected ? 'bg-brand-100 text-brand-700' : 'bg-slate-200/70 text-slate-600',
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
