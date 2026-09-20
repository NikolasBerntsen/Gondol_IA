import { Card, Skeleton } from '@/components/ui';
import { formatMoney, formatNumber, pluralize } from '@/lib/format';
import type { ModuleCatalogItem } from '../types';

export interface ModuleAdoptionCardsProps {
  items: ModuleCatalogItem[] | undefined;
  loading?: boolean;
}

/** Adopción de los tres módulos entre los clientes activos (SPEC §14.3). */
export function ModuleAdoptionCards({ items, loading }: ModuleAdoptionCardsProps) {
  if (loading) {
    return (
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <Card key={i} padding="md" className="flex flex-col gap-3">
            <Skeleton className="h-4 w-40" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-8 w-24" />
            <Skeleton className="h-2 w-full" />
          </Card>
        ))}
      </div>
    );
  }

  if (!items?.length) {
    return null;
  }

  return (
    // Tres columnas recién desde `xl`: a 768–1279 px el nombre y la descripción del módulo quedaban
    // en una columna de 60 px y las palabras largas se salían de la tarjeta.
    <section aria-label="Adopción de módulos" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {items.map((item) => {
        const pct = Math.round(item.adoptionPct);
        return (
          <Card key={item.module} padding="md" className="flex flex-col gap-3">
            <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1">
              <div className="min-w-[8rem] flex-1">
                <h3 className="text-base font-semibold text-foreground">{item.name}</h3>
                <p className="mt-0.5 break-words text-sm text-muted-foreground">{item.description}</p>
              </div>
              <span className="shrink-0 whitespace-nowrap rounded-tag bg-muted px-1.5 py-0.5 font-mono text-xs font-semibold text-muted-foreground">
                {item.monthlyPricePerBranch > 0 ? `+${formatMoney(item.monthlyPricePerBranch)}/suc.` : 'Sin adicional'}
              </span>
            </div>
            <div className="flex items-end justify-between gap-3">
              <div className="font-display text-2xl font-semibold leading-none tabular-nums">{pct}%</div>
              <div className="text-sm text-muted-foreground">
                <span className="font-semibold tabular-nums text-foreground">{formatNumber(item.enabledTenants)}</span>{' '}
                de {pluralize(item.activeTenants, 'cliente activo', 'clientes activos')}
              </div>
            </div>
            <div
              className="h-2 overflow-hidden rounded-full bg-muted"
              role="img"
              aria-label={`Adopción ${pct}% de ${item.name}`}
            >
              <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${pct}%` }} />
            </div>
          </Card>
        );
      })}
    </section>
  );
}
