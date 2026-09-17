import { Building2, Check, ChevronDown, Store } from 'lucide-react';
import { useAuth } from '@/auth/AuthContext';
import { DropdownItem, DropdownLabel, DropdownPanel, DropdownSeparator, useDropdown } from '@/components/ui/Dropdown';
import { cn } from '@/lib/cn';
import { ALL_BRANCHES_LABEL, ALL_MY_BRANCHES_LABEL, useBranch } from './BranchContext';

export interface BranchSelectorProps {
  className?: string;
  /** Siempre en versión reducida (ícono + sucursal, sin el nombre del comercio). En mobile ya se reduce solo. */
  compact?: boolean;
}

/**
 * Selector de sucursal del topbar: nombre del comercio + sucursal elegida (SPEC §1.1, §9.6).
 * Oculto para roles de plataforma; solo texto cuando hay una única sucursal.
 */
export function BranchSelector({ className, compact = false }: BranchSelectorProps) {
  const { me } = useAuth();
  const { enabled, branches, selectedBranchId, setBranch, canSelectAll, scopeLabel, isAll } = useBranch();
  const dropdown = useDropdown();

  if (!enabled || !me?.tenant) return null;

  const tenantName = me.tenant.name;
  const Icon = isAll ? Building2 : Store;
  const allLabel = me.role === 'TENANT_EMPLOYEE' ? ALL_MY_BRANCHES_LABEL : ALL_BRANCHES_LABEL;

  const content = (
    <>
      <span
        className={cn(
          'flex shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand-700',
          compact ? 'h-8 w-8' : 'h-8 w-8 sm:h-9 sm:w-9',
        )}
      >
        <Icon className="h-[18px] w-[18px]" aria-hidden="true" />
      </span>
      <span className="min-w-0 text-left leading-tight">
        {!compact && <span className="hidden truncate text-xs font-medium text-slate-500 sm:block">{tenantName}</span>}
        <span className="block truncate text-sm font-semibold text-slate-900">
          {isAll ? (
            <>
              <span className="sm:hidden">Todas</span>
              <span className="hidden sm:inline">{allLabel}</span>
            </>
          ) : (
            scopeLabel || tenantName
          )}
        </span>
      </span>
    </>
  );

  if (branches.length <= 1) {
    return (
      <div
        className={cn('flex min-w-0 max-w-[16rem] items-center gap-2.5 rounded-2xl py-1 pl-1 pr-3', className)}
        title={`${tenantName} · ${scopeLabel}`}
      >
        {content}
      </div>
    );
  }

  const select = (scope: number | 'all') => {
    setBranch(scope);
    dropdown.close(true);
  };

  return (
    <div className={cn('relative min-w-0', className)}>
      <button
        {...dropdown.triggerProps}
        aria-label={`Sucursal: ${scopeLabel}. Cambiar sucursal`}
        className={cn(
          'flex w-full min-w-0 max-w-[18rem] items-center gap-2.5 rounded-2xl border border-slate-200 bg-white py-1 pl-1 pr-2.5 shadow-sm transition',
          'hover:border-brand-300 hover:bg-brand-50/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
          dropdown.open && 'border-brand-300 ring-2 ring-brand-500/20',
        )}
      >
        {content}
        <ChevronDown
          className={cn('ml-auto h-4 w-4 shrink-0 text-slate-400 transition-transform', dropdown.open && 'rotate-180')}
          aria-hidden="true"
        />
      </button>
      {dropdown.open && (
        <DropdownPanel {...dropdown.panelProps} align="start" aria-label="Elegí una sucursal" className="w-72 max-w-[calc(100vw-1.5rem)]">
          <DropdownLabel>{tenantName}</DropdownLabel>
          {canSelectAll && (
            <>
              <DropdownItem
                checked={selectedBranchId === 'all'}
                icon={<Building2 />}
                description={`Consolidado de ${branches.length} sucursales`}
                trailing={selectedBranchId === 'all' ? <Check className="h-4 w-4 text-brand-600" aria-hidden="true" /> : null}
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
                  trailing={checked ? <Check className="h-4 w-4 text-brand-600" aria-hidden="true" /> : null}
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
