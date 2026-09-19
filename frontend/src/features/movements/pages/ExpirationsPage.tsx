import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarClock, Trash2 } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { useAccess } from '@/auth/useAccess';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { BarcodeDigits, ExpiryChip, LotRankChip, StatusPill } from '@/components/gondola';
import {
  Badge,
  Button,
  ConfirmDialog,
  Field,
  Input,
  Pagination,
  PageHeader,
  SearchInput,
  Segmented,
  StatCard,
  Table,
  Textarea,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { formatMoney, formatNumber } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { expirationsApi, movementKeys } from '../api';
import {
  EXPIRATION_BUCKETS,
  EXPIRATION_BUCKET_LABELS,
  type ExpirationBucket,
  type ExpirationBucketFilter,
  type ExpirationRow,
  type ExpirationTotals,
} from '../types';

const BUCKET_TONE: Record<ExpirationBucket, 'crit' | 'warn' | 'info'> = {
  EXPIRED: 'crit',
  CRITICAL: 'crit',
  WARNING: 'warn',
  UPCOMING: 'info',
};

/** `Segmented` solo admite ok/warn/crit: "Próximo" queda sin tono (es informativo). */
const SEGMENTED_TONE: Record<ExpirationBucket, 'crit' | 'warn' | undefined> = {
  EXPIRED: 'crit',
  CRITICAL: 'crit',
  WARNING: 'warn',
  UPCOMING: undefined,
};

function rowSeverity(bucket: ExpirationBucket) {
  return BUCKET_TONE[bucket];
}

/** Texto del bucket para la píldora de estado de la fila. */
function daysLabel(row: ExpirationRow): string {
  if (row.daysLeft < 0) return `Venció hace ${formatNumber(-row.daysLeft)} d`;
  if (row.daysLeft === 0) return 'Vence hoy';
  return `Vence en ${formatNumber(row.daysLeft)} d`;
}

export default function ExpirationsPage() {
  const queryClient = useQueryClient();
  const { me } = useAuth();
  const { can } = useAccess();
  const { isAll, scopeLabel } = useBranch();
  // Solo la etiqueta genérica ("Todas las sucursales") va en minúscula dentro de la frase; el nombre de
  // una sucursal es un nombre propio y conserva sus mayúsculas ("de Sucursal Centro").
  const scopeText = isAll ? scopeLabel.toLowerCase() : scopeLabel;
  // El jefe ve los vencimientos pero no descarta (SPEC §3.3): sin botones de descarte.
  const canDiscard = can('expirations.discard');
  const canViewProduct = can('products.view');
  const rotation = me?.tenant?.stockRotation ?? 'FIFO';

  const [bucket, setBucket] = useState<ExpirationBucketFilter>('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [discarding, setDiscarding] = useState<ExpirationRow | null>(null);
  const [discardQuantity, setDiscardQuantity] = useState('');
  const [discardReason, setDiscardReason] = useState('');
  const [bulkOpen, setBulkOpen] = useState(false);
  const q = useDebounce(search.trim(), 300);

  const summaryKey = useBranchQueryKey('expirations', 'summary');
  const summary = useQuery({ queryKey: summaryKey, queryFn: () => expirationsApi.summary() });

  const params = { bucket, q: q || undefined, page, size: 20 };
  const listKey = useBranchQueryKey('expirations', 'list', params);
  const rows = useQuery({
    queryKey: listKey,
    queryFn: () => expirationsApi.list(params),
    placeholderData: keepPreviousData,
  });

  const invalidate = () => {
    queryClient.invalidateQueries({ queryKey: movementKeys.expirations });
    queryClient.invalidateQueries({ queryKey: movementKeys.movements });
    queryClient.invalidateQueries({ queryKey: ['products'] });
  };

  const discard = useMutation({
    mutationFn: () =>
      expirationsApi.discard(discarding?.lotId as number, {
        quantity: discardQuantity ? Number(discardQuantity) : null,
        reason: discardReason.trim() || undefined,
      }),
    onSuccess: (movement) => {
      toast.success(`Descartaste ${formatNumber(movement.quantity)} u. del lote.`);
      invalidate();
      setDiscarding(null);
      setDiscardQuantity('');
      setDiscardReason('');
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos descartar el lote.')),
  });

  const bulkDiscard = useMutation({
    mutationFn: () => expirationsApi.discardAllExpired({ reason: 'Descarte masivo de vencidos' }),
    onSuccess: (result) => {
      if (result.lots === 0) {
        toast.info('No había lotes vencidos para descartar.');
      } else {
        toast.success(
          `Descartaste ${formatNumber(result.units)} u. de ${formatNumber(result.lots)} ${
            result.lots === 1 ? 'lote' : 'lotes'
          } por ${formatMoney(result.costValue)} a costo.`,
        );
      }
      if (result.failed > 0) {
        toast.warning(`${formatNumber(result.failed)} lotes no se pudieron descartar. Revisalos a mano.`);
      }
      invalidate();
      setBulkOpen(false);
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos descartar los vencidos.')),
  });

  const totals = summary.data;
  const expiredLots = totals?.expired.lots ?? 0;

  const kpi = (label: string, data: ExpirationTotals | undefined, tone: 'crit' | 'warn' | 'info', hint: string) => (
    <StatCard
      label={label}
      value={formatNumber(data?.lots ?? 0)}
      tone={tone}
      loading={summary.isPending}
      hint={`${formatNumber(data?.units ?? 0)} u. · ${formatMoney(data?.costValue ?? 0)} · ${hint}`}
    />
  );

  const branchCol = useBranchColumn<ExpirationRow>({ mobile: 'subtitle' });

  const columns: Array<TableColumn<ExpirationRow> | null> = [
    {
      id: 'product',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          {canViewProduct ? (
            <Link
              to={`/app/products/${row.productId}`}
              className="truncate font-semibold text-foreground underline-offset-2 hover:underline"
            >
              {row.productName}
            </Link>
          ) : (
            <span className="truncate font-semibold text-foreground">{row.productName}</span>
          )}
          <span className="flex flex-wrap items-center gap-1.5">
            {row.barcode ? <BarcodeDigits code={row.barcode} digitsOnly /> : null}
            {row.categoryName ? (
              <span className="text-xs text-muted-foreground">{row.categoryName}</span>
            ) : null}
          </span>
        </span>
      ),
    },
    branchCol,
    {
      id: 'expiry',
      header: 'Vencimiento',
      mobile: 'aside',
      // Sin `showDays`: los días los dice la columna "Estado" y así la tabla entra en 1360 px.
      cell: (row) => <ExpiryChip expiry={row.expiryDate} lot={row.lotNumber} bucket={row.bucket} />,
    },
    {
      id: 'rank',
      header: 'Salida',
      mobile: 'aside',
      cell: (row) =>
        row.rotationRank ? (
          <LotRankChip rank={row.rotationRank} rotation={rotation} discounted={!!row.discountPct} />
        ) : (
          <Badge tone="neutral">No vendible</Badge>
        ),
    },
    {
      id: 'status',
      header: 'Estado',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) => <StatusPill tone={BUCKET_TONE[row.bucket]}>{daysLabel(row)}</StatusPill>,
    },
    {
      id: 'quantity',
      header: 'Unidades',
      align: 'right',
      mobile: 'field',
      // Unidades y valor a costo van juntos: con columnas separadas la tabla no entra en 1360 px.
      cell: (row) => (
        <span className="flex flex-col items-end gap-0.5">
          <span className="font-semibold tabular-nums">{formatNumber(row.quantity)}</span>
          <span className="text-xs tabular-nums text-muted-foreground">
            {formatMoney(row.costValue)} a costo
          </span>
        </span>
      ),
    },
    canDiscard
      ? {
          id: 'actions',
          header: <span className="sr-only">Acciones</span>,
          align: 'right',
          mobile: 'actions',
          cell: (row) => (
            <Button
              variant="outline"
              size="sm"
              className="whitespace-nowrap"
              leftIcon={<Trash2 className="h-3.5 w-3.5" />}
              onClick={() => {
                setDiscarding(row);
                setDiscardQuantity('');
                setDiscardReason('');
              }}
            >
              Descartar
            </Button>
          ),
        }
      : null,
  ];

  const filterOptions = [
    { value: 'ALL' as const, label: 'Todos' },
    ...EXPIRATION_BUCKETS.map((value) => ({
      value,
      label: EXPIRATION_BUCKET_LABELS[value],
      count:
        value === 'EXPIRED'
          ? totals?.expired.lots
          : value === 'CRITICAL'
            ? totals?.critical.lots
            : value === 'WARNING'
              ? totals?.warning.lots
              : totals?.upcoming.lots,
      tone: SEGMENTED_TONE[value],
    })),
  ];

  const maxDiscard = discarding?.quantity ?? 0;
  const parsedDiscard = discardQuantity ? Number(discardQuantity) : maxDiscard;
  const discardInvalid = discardQuantity !== '' && (parsedDiscard < 1 || parsedDiscard > maxDiscard);

  return (
    <>
      <PageHeader
        title="Vencimientos"
        icon={CalendarClock}
        description={`Lotes por vencer y vencidos de ${scopeText}.`}
        actions={
          canDiscard ? (
            <Button
              variant="destructive"
              leftIcon={<Trash2 className="h-4 w-4" />}
              disabled={expiredLots === 0}
              onClick={() => setBulkOpen(true)}
            >
              Descartar todos los vencidos
            </Button>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
          <Segmented<ExpirationBucketFilter>
            value={bucket}
            onChange={(value) => {
              setBucket(value);
              setPage(0);
            }}
            options={filterOptions}
            label="Filtrar por bucket de vencimiento"
            size="sm"
          />
          <SearchInput
            value={search}
            onValueChange={(value) => {
              setSearch(value);
              setPage(0);
            }}
            label="Buscar por producto, código o lote"
            placeholder="Buscá por producto, código o lote…"
            inputSize="sm"
            containerClassName="lg:max-w-xs"
          />
        </div>
      </PageHeader>

      <div className="mb-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {kpi('Vencidos', totals?.expired, 'crit', canDiscard ? 'para descartar' : 'sin descartar')}
        {kpi('Críticos', totals?.critical, 'crit', `hasta ${totals?.criticalDays ?? 0} días`)}
        {kpi('Por vencer', totals?.warning, 'warn', `hasta ${totals?.warningDays ?? 0} días`)}
        {kpi('Próximos', totals?.upcoming, 'info', `hasta ${totals?.upcomingDays ?? 30} días`)}
      </div>

      <Table
        columns={columns}
        data={rows.data?.content}
        rowKey={(row) => row.lotId}
        loading={rows.isPending}
        error={rows.isError ? rows.error : undefined}
        onRetry={() => void rows.refetch()}
        rowSeverity={(row) => rowSeverity(row.bucket)}
        caption="Lotes por vencer y vencidos, ordenados por vencimiento"
        empty={{
          icon: CalendarClock,
          title: bucket === 'ALL' ? 'No hay lotes por vencer' : `No hay lotes en «${EXPIRATION_BUCKET_LABELS[bucket as ExpirationBucket]}»`,
          description: isAll
            ? 'Cuando cargues mercadería con vencimiento cercano la vas a ver acá.'
            : 'Probá con otro bucket o cambiá de sucursal.',
        }}
        footer={
          (rows.data?.totalPages ?? 0) > 1 ? (
            <Pagination {...pageInfo(rows.data)} onPageChange={setPage} disabled={rows.isFetching} />
          ) : undefined
        }
      />

      <ConfirmDialog
        open={!!discarding}
        onClose={() => setDiscarding(null)}
        onConfirm={() => discard.mutateAsync()}
        tone="danger"
        title="Descartar el lote"
        description={
          discarding
            ? `${discarding.productName} · lote ${discarding.lotNumber ?? `#${discarding.lotId}`} de ${
                discarding.branchName
              }, con ${formatNumber(discarding.quantity)} u. por ${formatMoney(discarding.costValue)} a costo. Se registra una baja por vencimiento.`
            : undefined
        }
        confirmLabel="Descartar lote"
        loading={discard.isPending}
        confirmDisabled={discardInvalid}
      >
        <div className="flex flex-col gap-3">
          <Field
            label="Cantidad a descartar"
            optional
            hint={`Dejalo vacío para descartar las ${formatNumber(maxDiscard)} u. del lote.`}
            error={discardInvalid ? `Tiene que estar entre 1 y ${formatNumber(maxDiscard)}.` : undefined}
          >
            <Input
              type="number"
              min={1}
              max={maxDiscard}
              step={1}
              inputMode="numeric"
              value={discardQuantity}
              placeholder={String(maxDiscard)}
              onChange={(event) => setDiscardQuantity(event.target.value)}
              className="tabular-nums"
            />
          </Field>
          <Field label="Motivo" optional>
            <Textarea
              rows={2}
              maxLength={300}
              value={discardReason}
              placeholder="Vencido en góndola…"
              onChange={(event) => setDiscardReason(event.target.value)}
            />
          </Field>
        </div>
      </ConfirmDialog>

      <ConfirmDialog
        open={bulkOpen}
        onClose={() => setBulkOpen(false)}
        onConfirm={() => bulkDiscard.mutateAsync()}
        tone="danger"
        title="Descartar todos los lotes vencidos"
        description={
          totals
            ? `Vas a dar de baja ${formatNumber(totals.expired.lots)} ${
                totals.expired.lots === 1 ? 'lote vencido' : 'lotes vencidos'
              } con ${formatNumber(totals.expired.units)} u. por ${formatMoney(
                totals.expired.costValue,
              )} a costo en ${scopeText}. Esta acción no se puede deshacer.`
            : undefined
        }
        confirmLabel="Descartar los vencidos"
        loading={bulkDiscard.isPending}
      />
    </>
  );
}
