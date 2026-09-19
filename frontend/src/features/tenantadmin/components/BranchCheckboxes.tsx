import { Building2 } from 'lucide-react';
import { Checkbox } from '@/components/ui';
import { cn } from '@/lib/cn';
import type { TenantBranch } from '../types';

interface BranchCheckboxesProps {
  branches: TenantBranch[];
  selected: number[];
  onChange: (ids: number[]) => void;
  disabled?: boolean;
}

/**
 * Sucursales en las que trabaja un empleado o cajero. Solo se ofrecen las activas: el backend rechaza las
 * desactivadas (SPEC §3.5).
 */
export function BranchCheckboxes({ branches, selected, onChange, disabled }: BranchCheckboxesProps) {
  if (branches.length === 0) {
    return (
      <p className="rounded-control border border-dashed border-border px-3 py-2.5 text-sm text-muted-foreground">
        No hay sucursales activas para asignar. Creá una en Sucursales.
      </p>
    );
  }

  const toggle = (id: number, checked: boolean) => {
    onChange(checked ? [...selected, id] : selected.filter((current) => current !== id));
  };

  return (
    <div className="gd-scroll max-h-52 space-y-1 overflow-y-auto rounded-control border border-input p-1.5">
      {branches.map((branch) => {
        const checked = selected.includes(branch.id);
        return (
          <label
            key={branch.id}
            className={cn(
              'flex cursor-pointer items-center gap-2.5 rounded-control px-2 py-2 transition-colors',
              checked ? 'bg-primary/10' : 'hover:bg-muted',
              disabled && 'cursor-not-allowed opacity-60',
            )}
          >
            <Checkbox
              checked={checked}
              disabled={disabled}
              onCheckedChange={(value) => toggle(branch.id, value === true)}
              aria-label={branch.name}
            />
            <Building2 className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span className="min-w-0 flex-1 truncate text-base text-foreground">{branch.name}</span>
            {branch.code && <span className="font-mono text-xs text-muted-foreground">{branch.code}</span>}
          </label>
        );
      })}
    </div>
  );
}
