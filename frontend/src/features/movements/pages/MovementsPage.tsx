import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ArrowLeftRight, Plus } from 'lucide-react';
import { useState } from 'react';
import { useBranchQueryKey } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { BarcodeDigits, ExpiryChip } from '@/components/gondola';
import {
  Badge,
  Button,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  Table,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { formatDateTime, formatMoney, formatNumber } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { movementsApi } from '../api';
import { AdjustmentDialog } from '../components/AdjustmentDialog';
import { DateRangeFilter, EMPTY_RANGE, type DateRange } from '../components/DateRangeFilter';
import {
  MOVEMENT_SOURCES,
  MOVEMENT_SOURCE_LABELS_EXT,
  MOVEMENT_TYPES_EXT,
  MOVEMENT_TYPE_LABELS_EXT,
  type Movement,
  type MovementSourceExt,
  type MovementTypeExt,
} from '../types';

/** Severidad de la fila: las bajas y las anulaciones se destacan (Góndola UI §5.5). */
function severityOf(type: MovementTypeExt) {
  if (type === 'WASTE_EXPIRED' || type === 'RECALL_REMOVAL') return 'crit' as const;
  if (type === 'WASTE_DAMAGED' || type === 'SALE_VOID') return 'warn' as const;
  return undefined;
}

function typeTone(type: MovementTypeExt) {
  switch (type) {
    case 'ENTRY':
    case 'ADJUSTMENT_IN':
    case 'TRANSFER_IN':
      return 'ok' as const;
    case 'WASTE_EXPIRED':
    case 'RECALL_REMOVAL':
      return 'crit' as const;
    case 'WASTE_DAMAGED':
    case 'SALE_VOID':
      return 'warn' as const;
    default:
      return 'neutral' as const;
  }
}

export default function MovementsPage() {
  const [range, setRange] = useState<DateRange>(EMPTY_RANGE);
  const [type, setType] = useState('');
  const [source, setSource] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [adjusting, setAdjusting] = useState(false);
  const q = useDebounce(search.trim(), 300);

  const params = {
    type: (type || undefined) as MovementTypeExt | undefined,
    source: (source || undefined) as MovementSourceExt | undefined,
    q: q || undefined,
    from: range.from || undefined,
    to: range.to || undefined,
    page,
    size: 20,
  };
  const queryKey = useBranchQueryKey('movements', 'list', params);
  const movements = useQuery({
    queryKey,
    queryFn: () => movementsApi.list(params),
    placeholderData: keepPreviousData,
  });

  const branchCol = useBranchColumn<Movement>({ mobile: 'subtitle' });

  const columns: Array<TableColumn<Movement> | null> = [
    {
      id: 'product',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          <span className="truncate font-semibold text-foreground">{row.productName}</span>
          <span className="flex flex-wrap items-center gap-1.5">
            {row.barcode ? <BarcodeDigits code={row.barcode} digitsOnly /> : null}
            {row.expiryDate ? (
              <ExpiryChip expiry={row.expiryDate} lot={row.lotNumber} />
            ) : row.lotNumber ? (
              <Badge tone="neutral" className="font-mono">
                {row.lotNumber}
              </Badge>
            ) : null}
          </span>
        </span>
      ),
    },
    branchCol,
    {
      id: 'type',
      header: 'Movimiento',
      mobile: 'field',
      // El origen va debajo del tipo: con columna propia la tabla no entra en 1360 px.
      cell: (row) => (
        <span className="flex flex-col items-start gap-0.5">
          <Badge tone={typeTone(row.type)}>{row.typeLabel}</Badge>
          <span className="text-xs text-muted-foreground">{row.sourceLabel}</span>
        </span>
      ),
    },
    {
      id: 'quantity',
      header: 'Cantidad',
      align: 'right',
      mobile: 'aside',
      cell: (row) => (
        <span
          className={`font-semibold tabular-nums ${
            row.signedQuantity > 0 ? 'text-ok-ink' : 'text-crit-ink'
          }`}
        >
          {row.signedQuantity > 0 ? '+' : '−'}
          {formatNumber(Math.abs(row.signedQuantity))}
        </span>
      ),
    },
    {
      id: 'amount',
      header: 'Importe',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) => (
        <span className="tabular-nums text-muted-foreground">
          {formatMoney(row.totalAmount)}
          {row.discountPct ? (
            <span className="ml-1 text-xs text-warn-ink">−{formatNumber(row.discountPct)}%</span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'batchRef',
      header: 'Referencia',
      hideBelow: 'xl',
      mobile: 'field',
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          <span className="font-mono text-xs text-muted-foreground">{row.batchRef ?? '—'}</span>
          {row.reason ? <span className="truncate text-xs text-muted-foreground">{row.reason}</span> : null}
        </span>
      ),
    },
    {
      id: 'occurredAt',
      header: 'Fecha',
      align: 'right',
      mobile: 'field',
      cell: (row) => (
        <span className="flex flex-col gap-0.5 text-right">
          <span className="tabular-nums text-foreground">{formatDateTime(row.occurredAt)}</span>
          <span className="text-xs text-muted-foreground">{row.userName ?? '—'}</span>
        </span>
      ),
    },
  ];

  const resetPage = <T,>(setter: (value: T) => void) => (value: T) => {
    setter(value);
    setPage(0);
  };

  return (
    <>
      <PageHeader
        title="Movimientos"
        icon={ArrowLeftRight}
        description="Todo lo que entró y salió del stock, con su lote, origen y responsable."
        actions={
          <Button leftIcon={<Plus className="h-4 w-4" />} onClick={() => setAdjusting(true)}>
            Registrar ajuste
          </Button>
        }
      >
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <SearchInput
            value={search}
            onValueChange={resetPage(setSearch)}
            label="Buscar por producto, código, lote o referencia"
            placeholder="Buscá por producto, código, lote o referencia…"
            inputSize="sm"
            containerClassName="lg:max-w-xs"
          />
          <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-center">
            <Select
              selectSize="sm"
              aria-label="Filtrar por tipo de movimiento"
              value={type}
              placeholder="Todos los movimientos"
              options={MOVEMENT_TYPES_EXT.map((value) => ({
                value,
                label: MOVEMENT_TYPE_LABELS_EXT[value],
              }))}
              onChange={(event) => resetPage(setType)(event.target.value)}
            />
            <Select
              selectSize="sm"
              aria-label="Filtrar por origen"
              value={source}
              placeholder="Todos los orígenes"
              options={MOVEMENT_SOURCES.map((value) => ({
                value,
                label: MOVEMENT_SOURCE_LABELS_EXT[value],
              }))}
              onChange={(event) => resetPage(setSource)(event.target.value)}
            />
            <DateRangeFilter value={range} onChange={resetPage(setRange)} label="Rango de fechas" />
          </div>
        </div>
      </PageHeader>

      <Table
        columns={columns}
        data={movements.data?.content}
        rowKey={(row) => row.id}
        loading={movements.isPending}
        error={movements.isError ? movements.error : undefined}
        onRetry={() => void movements.refetch()}
        rowSeverity={(row) => severityOf(row.type)}
        caption="Historial de movimientos de stock"
        empty={{
          icon: ArrowLeftRight,
          title: 'No hay movimientos',
          description: 'Cuando cargues mercadería o registres una venta los vas a ver acá.',
        }}
        footer={
          (movements.data?.totalPages ?? 0) > 1 ? (
            <Pagination
              {...pageInfo(movements.data)}
              onPageChange={setPage}
              disabled={movements.isFetching}
            />
          ) : undefined
        }
      />

      <AdjustmentDialog open={adjusting} onClose={() => setAdjusting(false)} />
    </>
  );
}
