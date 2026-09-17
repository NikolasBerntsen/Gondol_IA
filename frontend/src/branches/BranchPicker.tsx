import { Store } from 'lucide-react';
import { Field } from '@/components/ui/Field';
import { Select } from '@/components/ui/Select';
import { cn } from '@/lib/cn';
import { useBranch } from './BranchContext';

export interface BranchPickerProps {
  /** Sucursal elegida (`null` = ninguna todavía). */
  value: number | null;
  onChange: (branchId: number | null) => void;
  label?: string;
  hint?: string;
  error?: string;
  disabled?: boolean;
  /** Muestra el selector aunque haya una sucursal elegida en el topbar (p. ej. destino de una transferencia). */
  alwaysVisible?: boolean;
  /** Sucursales que no se pueden elegir (p. ej. la de origen en una transferencia). */
  excludeIds?: number[];
  className?: string;
}

/**
 * Selector inline y obligatorio de sucursal para formularios de escritura cuando se está viendo
 * "Todas las sucursales" (SPEC §3.5, §9.6). Con una sucursal elegida en el topbar no se muestra
 * (salvo `alwaysVisible`). Usalo junto con `useWriteBranch()`.
 */
export function BranchPicker({
  value,
  onChange,
  label = 'Sucursal',
  hint = 'Estás viendo todas las sucursales: elegí en cuál registrar esta operación.',
  error,
  disabled,
  alwaysVisible = false,
  excludeIds,
  className,
}: BranchPickerProps) {
  const { enabled, branches, isAll } = useBranch();
  if (!enabled || (!alwaysVisible && (!isAll || branches.length <= 1))) return null;

  const options = branches
    .filter((branch) => !excludeIds?.includes(branch.id))
    .map((branch) => ({ value: branch.id, label: branch.code ? `${branch.name} (${branch.code})` : branch.name }));

  return (
    <div className={cn('rounded-control border border-primary/25 bg-primary/[0.06] p-3 sm:p-4', className)}>
      <Field label={label} hint={hint} error={error} required>
        <Select
          value={value ?? ''}
          disabled={disabled}
          placeholder="Elegí una sucursal"
          options={options}
          leftIcon={<Store className="h-4 w-4" aria-hidden="true" />}
          onChange={(event) => onChange(event.target.value ? Number(event.target.value) : null)}
        />
      </Field>
    </div>
  );
}
