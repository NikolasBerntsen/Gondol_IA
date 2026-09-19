import { useEffect, useMemo, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { ChevronDown, EyeOff, RotateCcw, Search } from 'lucide-react';
import {
  Button,
  Card,
  Checkbox,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuTrigger,
  ErrorState,
  Input,
  Pagination,
  Segmented,
  Skeleton,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRoot,
  TableRow,
  TooltipProvider,
  pageInfo,
} from '@/components/ui';
import { SeverityRow, StatusPill } from '@/components/gondola';
import { useBranch } from '@/branches/BranchContext';
import { getErrorMessage } from '@/api/client';
import { cn } from '@/lib/cn';
import { formatNumber } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { importKeys, importsApi } from '../api';
import { ROW_ACTION_LABELS, ROW_STATUS_LABELS, ROW_STATUS_TONES, rowSeverity } from '../labels';
import { EditableCell } from './EditableCell';
import type { ImportFieldDto, ImportJob, ImportRow } from '../types';

const PAGE_SIZE = 25;
const BRANCH_LIST_ID = 'gd-import-branches';

type Filter = 'ALL' | 'VALID' | 'WARNING' | 'ERROR' | 'SKIPPED' | 'IMPORTED' | 'FAILED';

export interface ReviewStepProps {
  job: ImportJob;
  fields: ImportFieldDto[];
  onJobChange: (job: ImportJob) => void;
  /** Importación ya aplicada o cancelada: se ve el resultado de cada fila pero no se edita. */
  readOnly?: boolean;
}

/**
 * Paso 3 «Revisión» (SPEC §16.4): contadores vivos, filtro por estado, búsqueda, edición inline celda por celda
 * con el motivo de cada marca, selección y acciones masivas.
 */
export function ReviewStep({ job, fields, onJobChange, readOnly = false }: ReviewStepProps) {
  const queryClient = useQueryClient();
  const { branches } = useBranch();
  const [filter, setFilter] = useState<Filter>(!readOnly && job.errorRows > 0 ? 'ERROR' : 'ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [savingRow, setSavingRow] = useState<number | null>(null);
  const query = useDebounce(search, 300);

  useEffect(() => setPage(0), [filter, query]);

  const params = { status: filter, q: query || undefined, page, size: PAGE_SIZE };
  const rows = useQuery({
    queryKey: importKeys.rows(job.id, params),
    queryFn: () => importsApi.rows(job.id, params),
    placeholderData: keepPreviousData,
  });

  /** Columnas visibles: solo los campos que el usuario mapeó, en el orden del catálogo. */
  const columns = useMemo(
    () => fields.filter((field) => Boolean(job.columnMapping[field.key])),
    [fields, job.columnMapping],
  );

  const refresh = (updated: ImportJob) => {
    onJobChange(updated);
    queryClient.invalidateQueries({ queryKey: ['imports', 'rows', job.id] });
    queryClient.invalidateQueries({ queryKey: importKeys.detail(job.id) });
  };

  const patch = useMutation({
    mutationFn: ({ rowId, field, value }: { rowId: number; field: string; value: string }) =>
      importsApi.patchRow(job.id, rowId, { [field]: value }),
    onMutate: ({ rowId }) => setSavingRow(rowId),
    onSuccess: ({ row, job: updated }) => {
      refresh(updated);
      if (row.status === 'VALID') toast.success(`Corregiste la fila ${row.rowNumber}: ya es válida.`);
      else if (row.status === 'WARNING') {
        toast.success(`Corregiste la fila ${row.rowNumber}.`, {
          description: 'Quedó con una advertencia: se puede importar igual.',
        });
      }
    },
    onSettled: () => setSavingRow(null),
  });

  const bulk = useMutation({
    mutationFn: (body: Parameters<typeof importsApi.bulk>[1]) => importsApi.bulk(job.id, body),
    onSuccess: ({ affectedRows, job: updated }, variables) => {
      refresh(updated);
      setSelected(new Set());
      const label =
        variables.action === 'SKIP'
          ? 'Omitiste'
          : variables.action === 'UNSKIP'
            ? 'Restauraste'
            : 'Actualizaste';
      toast.success(`${label} ${formatNumber(affectedRows)} ${affectedRows === 1 ? 'fila' : 'filas'}.`);
    },
    onError: (error) => toast.error(getErrorMessage(error)),
  });

  const content = rows.data?.content ?? [];
  const allVisibleSelected = content.length > 0 && content.every((row) => selected.has(row.id));
  const someVisibleSelected = content.some((row) => selected.has(row.id));
  const selectedIds = [...selected];

  const toggleAll = (checked: boolean) => {
    const next = new Set(selected);
    content.forEach((row) => (checked ? next.add(row.id) : next.delete(row.id)));
    setSelected(next);
  };

  const cellAlign = (type: ImportFieldDto['type']) => (type === 'number' || type === 'integer' ? 'right' : undefined);
  // Como en la referencia: monoespaciada solo para códigos, lotes y fechas; los nombres se leen mejor en la sans.
  const cellMono = (field: ImportFieldDto) =>
    field.type === 'date' || field.key === 'barcode' || field.key === 'lotNumber';

  return (
    <TooltipProvider delayDuration={80}>
      <datalist id={BRANCH_LIST_ID}>
        {branches.map((branch) => (
          <option key={branch.id} value={branch.name} />
        ))}
      </datalist>

      <Card padding="none" className="flex min-w-0 flex-col">
        <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-5">
          <div className="gd-scroll -mx-1 w-full max-w-full overflow-x-auto px-1 py-1 sm:w-auto">
            <Segmented
              label="Filtrar filas por estado"
              className="grid w-full grid-cols-2 sm:inline-flex sm:w-auto"
              value={filter}
              onChange={setFilter}
              options={readOnly ? readOnlyOptions(job) : editOptions(job)}
            />
          </div>
          <div className="w-full sm:w-[260px]">
            <label htmlFor="import-search" className="sr-only">
              Buscar en las filas
            </label>
            <Input
              id="import-search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Buscar fila, código o nombre"
              leftIcon={<Search className="h-4 w-4" aria-hidden="true" />}
            />
          </div>
        </div>

        {selected.size && !readOnly ? (
          <div
            role="region"
            aria-label="Acciones masivas"
            className="flex flex-wrap items-center gap-2 border-y border-primary/25 bg-primary/[0.06] px-4 py-2 sm:px-5"
          >
            <span className="mr-2 text-base font-semibold text-foreground">
              {selected.size} {selected.size === 1 ? 'fila seleccionada' : 'filas seleccionadas'}
            </span>
            <Button
              size="sm"
              variant="outline"
              leftIcon={<EyeOff aria-hidden="true" />}
              loading={bulk.isPending}
              onClick={() => bulk.mutate({ rowIds: selectedIds, action: 'SKIP' })}
            >
              Omitir filas
            </Button>
            <Button
              size="sm"
              variant="outline"
              leftIcon={<RotateCcw aria-hidden="true" />}
              loading={bulk.isPending}
              onClick={() => bulk.mutate({ rowIds: selectedIds, action: 'UNSKIP' })}
            >
              Restaurar
            </Button>
            {job.columnMapping.branch || job.options.importStock ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="sm" variant="outline" rightIcon={<ChevronDown aria-hidden="true" />}>
                    Asignar sucursal
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  <DropdownMenuLabel>Sucursal de las filas elegidas</DropdownMenuLabel>
                  {branches.map((branch) => (
                    <DropdownMenuItem
                      key={branch.id}
                      onSelect={() =>
                        bulk.mutate({
                          rowIds: selectedIds,
                          action: 'SET_FIELD',
                          field: 'branch',
                          value: branch.name,
                        })
                      }
                    >
                      {branch.name}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : null}
            <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
              Quitar selección
            </Button>
          </div>
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border px-4 py-2 text-sm text-muted-foreground sm:px-5">
            <span>
              {rows.data
                ? `Mostrando ${formatNumber(content.length)} de ${formatNumber(rows.data.totalElements)} filas`
                : 'Cargando filas…'}
            </span>
            <span className="hidden sm:inline">
              {readOnly
                ? 'Pasá el mouse por una celda marcada para ver el motivo'
                : 'Tocá una celda marcada para corregirla y ver el motivo'}
            </span>
          </div>
        )}

        {rows.isPending ? (
          <div className="space-y-2 p-4">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : rows.isError ? (
          <div className="p-4">
            <ErrorState error={rows.error} onRetry={() => rows.refetch()} />
          </div>
        ) : (
          <TableRoot containerClassName="gd-scroll border-t border-border">
            <TableHeader>
              <TableRow>
                {readOnly ? null : (
                  <TableHead className="w-10 pl-4">
                    <Checkbox
                      aria-label="Seleccionar las filas visibles"
                      checked={allVisibleSelected ? true : someVisibleSelected ? 'indeterminate' : false}
                      onCheckedChange={(checked) => toggleAll(checked === true)}
                    />
                  </TableHead>
                )}
                <TableHead className="text-right">Fila</TableHead>
                {columns.map((field) => (
                  <TableHead
                    key={field.key}
                    className={cn('px-2', cellAlign(field.type) === 'right' && 'text-right')}
                  >
                    {field.label}
                  </TableHead>
                ))}
                <TableHead>Estado</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {content.map((row) => (
                <ReviewRow
                  key={row.id}
                  row={row}
                  columns={columns}
                  selected={selected.has(row.id)}
                  saving={savingRow === row.id}
                  readOnly={readOnly}
                  onSelect={(checked) => {
                    const next = new Set(selected);
                    if (checked) next.add(row.id);
                    else next.delete(row.id);
                    setSelected(next);
                  }}
                  onCommit={(field, value) => patch.mutate({ rowId: row.id, field, value })}
                  onUnskip={() => bulk.mutate({ rowIds: [row.id], action: 'UNSKIP' })}
                  cellAlign={cellAlign}
                  cellMono={cellMono}
                />
              ))}
            </TableBody>
          </TableRoot>
        )}

        {!rows.isPending && !rows.isError && content.length === 0 ? (
          <p className="border-t border-border px-5 py-8 text-base text-muted-foreground">
            {filter === 'ERROR'
              ? 'No quedan filas con error: podés continuar a confirmar.'
              : query
                ? 'Ninguna fila coincide con la búsqueda.'
                : 'No hay filas en este estado.'}
          </p>
        ) : null}

        {rows.data && rows.data.totalPages > 1 ? (
          <div className="border-t border-border px-4 sm:px-5">
            <Pagination {...pageInfo(rows.data)} onPageChange={setPage} />
          </div>
        ) : null}
      </Card>
    </TooltipProvider>
  );
}

interface ReviewRowProps {
  readOnly: boolean;
  row: ImportRow;
  columns: ImportFieldDto[];
  selected: boolean;
  saving: boolean;
  onSelect: (checked: boolean) => void;
  onCommit: (field: string, value: string) => void;
  onUnskip: () => void;
  cellAlign: (type: ImportFieldDto['type']) => 'right' | undefined;
  cellMono: (field: ImportFieldDto) => boolean;
}

function ReviewRow({
  readOnly,
  row,
  columns,
  selected,
  saving,
  onSelect,
  onCommit,
  onUnskip,
  cellAlign,
  cellMono,
}: ReviewRowProps) {
  const rowMessages = row.messages.filter((message) => message.field === null);
  const locked = readOnly || row.status === 'SKIPPED' || row.status === 'IMPORTED' || row.status === 'FAILED';
  return (
    <SeverityRow
      severity={rowSeverity(row.status)}
      data-state={selected ? 'selected' : undefined}
      className={cn(row.status === 'SKIPPED' && 'opacity-55')}
    >
      {readOnly ? null : (
        <TableCell className="w-10 pl-4">
          <Checkbox
            aria-label={`Seleccionar la fila ${row.rowNumber}`}
            checked={selected}
            onCheckedChange={(checked) => onSelect(checked === true)}
          />
        </TableCell>
      )}
      <TableCell className="text-right font-mono text-sm tabular-nums text-muted-foreground">
        {row.rowNumber}
      </TableCell>
      {columns.map((field) => (
        <TableCell key={field.key} className="min-w-[130px] px-1.5 py-1">
          <EditableCell
            value={row.data[field.key] ?? ''}
            label={field.label}
            rowNumber={row.rowNumber}
            messages={row.messages.filter((message) => message.field === field.key)}
            mono={cellMono(field)}
            align={cellAlign(field.type)}
            disabled={locked}
            saving={saving}
            suggestionsId={field.type === 'branch' ? BRANCH_LIST_ID : undefined}
            onCommit={(value) => onCommit(field.key, value)}
          />
        </TableCell>
      ))}
      <TableCell className="whitespace-nowrap">
        <div className="flex items-center gap-1.5">
          <div>
            <StatusPill tone={ROW_STATUS_TONES[row.status]}>{ROW_STATUS_LABELS[row.status]}</StatusPill>
            {row.action && row.status !== 'SKIPPED' ? (
              <p className="mt-0.5 text-xs text-muted-foreground">{ROW_ACTION_LABELS[row.action]}</p>
            ) : null}
            {rowMessages.map((message, index) => (
              <p
                key={index}
                className={cn('mt-0.5 max-w-[240px] text-xs', message.level === 'ERROR' ? 'text-crit-ink' : 'text-muted-foreground')}
              >
                {message.message}
              </p>
            ))}
          </div>
          {row.status === 'SKIPPED' && !readOnly ? (
            <Button
              variant="ghost"
              size="icon-sm"
              aria-label={`Restaurar la fila ${row.rowNumber}`}
              onClick={onUnskip}
            >
              <RotateCcw aria-hidden="true" />
            </Button>
          ) : null}
        </div>
      </TableCell>
    </SeverityRow>
  );
}

type FilterOption = { value: Filter; label: string; count?: string; tone?: 'ok' | 'warn' | 'crit' };

/** Filtros mientras se revisa: estados de la validación. */
function editOptions(job: ImportJob): FilterOption[] {
  return [
    { value: 'ALL', label: 'Todas', count: formatNumber(job.totalRows) },
    { value: 'VALID', label: 'Válidas', count: formatNumber(job.validRows), tone: 'ok' },
    { value: 'WARNING', label: 'Advertencias', count: formatNumber(job.warningRows), tone: 'warn' },
    { value: 'ERROR', label: 'Errores', count: formatNumber(job.errorRows), tone: job.errorRows > 0 ? 'crit' : 'ok' },
    { value: 'SKIPPED', label: 'Omitidas', count: formatNumber(job.skippedRows) },
  ];
}

/** Filtros de una importación terminada: qué pasó con cada fila. */
function readOnlyOptions(job: ImportJob): FilterOption[] {
  const failed = job.result?.rowsFailed ?? 0;
  return [
    { value: 'ALL', label: 'Todas', count: formatNumber(job.totalRows) },
    { value: 'IMPORTED', label: 'Importadas', tone: 'ok' },
    { value: 'SKIPPED', label: 'Omitidas', count: formatNumber(job.skippedRows) },
    { value: 'FAILED', label: 'No se aplicaron', count: formatNumber(failed), tone: failed > 0 ? 'crit' : undefined },
  ];
}
