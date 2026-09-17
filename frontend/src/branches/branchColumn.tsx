import { Store } from 'lucide-react';
import { useMemo } from 'react';
import type { TableColumn, TableMobileSlot } from '@/components/ui/Table';
import { useBranch } from './BranchContext';

export interface BranchColumnOptions {
  header?: string;
  mobile?: TableMobileSlot;
  hideBelow?: 'lg' | 'xl';
}

/** Columna "Sucursal" para tablas de datos por sucursal (filas con `branchName`). */
export function branchColumn<T extends { branchName?: string | null }>(options: BranchColumnOptions = {}): TableColumn<T> {
  const { header = 'Sucursal', mobile = 'subtitle', hideBelow } = options;
  return {
    id: 'branch',
    header,
    mobile,
    hideBelow,
    cell: (row) => (
      <span className="inline-flex max-w-full items-center gap-1.5 whitespace-nowrap text-slate-600">
        <Store className="h-3.5 w-3.5 shrink-0 text-slate-400" aria-hidden="true" />
        <span className="truncate">{row.branchName ?? '—'}</span>
      </span>
    ),
  };
}

/**
 * Columna "Sucursal" solo cuando se ve el consolidado (`isAll`), como pide SPEC §9.6.
 * Devuelve `null` en otro caso: `Table` ignora las columnas `null`.
 */
export function useBranchColumn<T extends { branchName?: string | null }>(
  options: BranchColumnOptions = {},
): TableColumn<T> | null {
  const { isAll } = useBranch();
  const { header, mobile, hideBelow } = options;
  return useMemo(
    () => (isAll ? branchColumn<T>({ header, mobile, hideBelow }) : null),
    [isAll, header, mobile, hideBelow],
  );
}
