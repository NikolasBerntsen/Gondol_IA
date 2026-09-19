import { Building2, Check, ChevronDown, Store } from 'lucide-react';
import { useAuth } from '@/auth/AuthContext';
import { DropdownItem, DropdownLabel, DropdownPanel, DropdownSeparator, useDropdown } from '@/components/ui/Dropdown';
import { cn } from '@/lib/cn';
import { ALL_BRANCHES_LABEL, ALL_MY_BRANCHES_LABEL, useBranch } from './BranchContext';

export interface BranchSelectorProps {
  className?: string;
  /** Solo la sucursal, sin el nombre del comercio (en pantallas chicas ya se reduce solo). */
  compact?: boolean;
}

/**
 * Selector de alcance de la barra superior: "Minimercado El Sol · Todas las sucursales ▾"
 * (SPEC §1.1, §9.6; docs/design-system.md §7.1). Es un **control** (radio 8 px), no una píldora.
 * Oculto para roles de plataforma; solo texto cuando hay una única sucursal accesible.
 */
export function BranchSelector({ className, compact = false }: BranchSelectorProps) {
  const { me } = useAuth();
  const { enabled, branches, selectedBranchId, setBranch, canSelectAll, scopeLabel, isAll } = useBranch();
  const dropdown = useDropdown();

  if (!enabled || !me?.tenant) return null;

  const tenantName = me.tenant.name;
  const Icon = isAll ? Building2 : Store;
  const worksOnOneBranch = me.role === 'TENANT_EMPLOYEE' || me.role === 'TENANT_CASHIER';
  const allLabel = worksOnOneBranch ? ALL_MY_BRANCHES_LABEL : ALL_BRANCHES_LABEL;

  // shrink-0: el alcance es dato operativo; se encoge antes el buscador que el nombre de la sucursal.
  const base =
    'inline-flex h-9 min-w-0 max-w-[58vw] shrink-0 items-center gap-2 rounded-control border border-input bg-card px-2.5 text-sm font-semibold text-foreground sm:max-w-[min(42vw,320px)] xl:max-w-none';

  const label = (
    <span className="truncate">
      {!compact && <span className="hidden xl:inline">{tenantName} · </span>}
      {isAll ? allLabel : scopeLabel || tenantName}
    </span>
  );

  if (branches.length <= 1) {
    return (
      <span className={cn(base, className)} title={`${tenantName} · ${scopeLabel}`}>
        <Icon className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        {label}
      </span>
    );
  }

  const select = (scope: number | 'all') => {
    setBranch(scope);
    dropdown.close(true);
  };

  return (
    <div className={cn('relative min-w-0 shrink-0', className)}>
      <button
        {...dropdown.triggerProps}
        aria-label={`Alcance: ${scopeLabel}. Cambiar sucursal`}
        className={cn(
          base,
          'w-full transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          dropdown.open && 'bg-muted',
        )}
      >
        <Icon className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        {label}
        <ChevronDown
          className={cn('ml-auto h-4 w-4 shrink-0 text-muted-foreground transition-transform', dropdown.open && 'rotate-180')}
          aria-hidden="true"
        />
      </button>

      {dropdown.open && (
        <DropdownPanel
          {...dropdown.panelProps}
          align="end"
          aria-label="Elegí una sucursal"
          className="w-72 max-w-[calc(100vw-2rem)]"
        >
          <DropdownLabel>{tenantName}</DropdownLabel>
          {canSelectAll && (
            <>
              <DropdownItem
                checked={selectedBranchId === 'all'}
                icon={<Building2 />}
                description={`Consolidado de ${branches.length} sucursales`}
                trailing={selectedBranchId === 'all' ? <Check className="h-4 w-4 text-primary" aria-hidden="true" /> : null}
                onClick={() => select('all')}
              >
                {allLabel}
              </DropdownItem>
              <DropdownSeparator />
            </>
          )}
          <div className="max-h-72 overflow-y-auto">
            {branches.map((branch) => {
              const checked = selectedBranchId === branch.id;
              return (
                <DropdownItem
                  key={branch.id}
                  checked={checked}
                  icon={<Store />}
                  description={branch.code ?? undefined}
                  trailing={checked ? <Check className="h-4 w-4 text-primary" aria-hidden="true" /> : null}
                  onClick={() => select(branch.id)}
                >
                  {branch.name}
                </DropdownItem>
              );
            })}
          </div>
        </DropdownPanel>
      )}
    </div>
  );
}
