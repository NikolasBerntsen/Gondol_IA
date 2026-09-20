import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowRight, Send, Truck } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { useAccess } from '@/auth/useAccess';
import { useBranch } from '@/branches/BranchContext';
import { BarcodeDigits, ExpiryChip } from '@/components/gondola';
import {
  Alert,
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  Field,
  PageHeader,
  Pagination,
  Select,
  Table,
  Tabs,
  Textarea,
  Truncate,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { formatDateTime, formatMoney, formatNumber } from '@/lib/format';
import { movementKeys, transfersApi } from '../api';
import { DateRangeFilter, EMPTY_RANGE, type DateRange } from '../components/DateRangeFilter';
import { TransferLotPicker } from '../components/TransferLotPicker';
import type { Transfer, TransferSummary, TransferableLot } from '../types';

type TabValue = 'new' | 'history';

interface Picked {
  quantity: number;
  lot: TransferableLot;
}

// ---------------------------------------------------------------------------
// Nueva transferencia
// ---------------------------------------------------------------------------

function TransferResultPanel({ transfer, onNew }: { transfer: Transfer; onNew: () => void }) {
  return (
    <Card padding="none" className="overflow-hidden">
      <CardHeader
        icon={Truck}
        title={`Transferencia ${transfer.batchRef} registrada`}
        description={`${transfer.fromBranchName} → ${transfer.toBranchName} · ${formatDateTime(transfer.occurredAt)}`}
        actions={<Button onClick={onNew}>Nueva transferencia</Button>}
      />
      {transfer.recalls.length > 0 ? (
        <div className="p-4 pb-0">
          <Alert tone="crit" title="Lotes alcanzados por un recall">
            Algún lote quedó en cuarentena en la sucursal de destino y no se puede vender:{' '}
            {transfer.recalls.map((recall) => recall.title).join(' · ')}.
          </Alert>
        </div>
      ) : null}
      <ul className="divide-y divide-border">
        {transfer.items.map((item) => (
          <li key={item.lotId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3">
            <span className="min-w-0">
              <Truncate className="block text-base font-semibold text-foreground">
                {item.productName}
              </Truncate>
              <span className="mt-0.5 flex flex-wrap items-center gap-1.5">
                {item.barcode ? <BarcodeDigits code={item.barcode} digitsOnly /> : null}
                {item.expiryDate ? (
                  <ExpiryChip expiry={item.expiryDate} lot={item.lotNumber} />
                ) : item.lotNumber ? (
                  <Badge tone="neutral" className="font-mono">
                    {item.lotNumber}
                  </Badge>
                ) : null}
                {item.quarantined ? <Badge tone="crit">En cuarentena</Badge> : null}
              </span>
            </span>
            <span className="shrink-0 font-semibold tabular-nums text-foreground">
              {formatNumber(item.quantity)} u.
            </span>
          </li>
        ))}
      </ul>
      <div className="flex items-center justify-between border-t border-border px-4 py-3">
        <span className="text-sm tabular-nums text-muted-foreground">
          {formatNumber(transfer.units)} u. en {transfer.items.length}{' '}
          {transfer.items.length === 1 ? 'lote' : 'lotes'}
        </span>
        <span className="font-display text-md tabular-nums text-foreground">
          {formatMoney(transfer.costValue)} a costo
        </span>
      </div>
    </Card>
  );
}

function NewTransferTab() {
  const queryClient = useQueryClient();
  const { branches } = useBranch();
  const { me } = useAuth();
  const rotation = me?.tenant?.stockRotation ?? 'FIFO';

  const [fromBranchId, setFromBranchId] = useState<number | null>(branches[0]?.id ?? null);
  const [toBranchId, setToBranchId] = useState<number | null>(null);
  const [picked, setPicked] = useState<Record<number, Picked>>({});
  const [note, setNote] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [result, setResult] = useState<Transfer | null>(null);

  const transfer = useMutation({
    mutationFn: () =>
      transfersApi.create({
        fromBranchId: fromBranchId as number,
        toBranchId: toBranchId as number,
        items: Object.entries(picked).map(([lotId, entry]) => ({
          lotId: Number(lotId),
          quantity: entry.quantity,
        })),
        note: note.trim() || undefined,
      }),
    onSuccess: (created) => {
      toast.success(`Registraste la transferencia ${created.batchRef}.`);
      setResult(created);
      setPicked({});
      setNote('');
      queryClient.invalidateQueries({ queryKey: movementKeys.transfers });
      queryClient.invalidateQueries({ queryKey: movementKeys.movements });
      queryClient.invalidateQueries({ queryKey: movementKeys.expirations });
      queryClient.invalidateQueries({ queryKey: ['products'] });
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos registrar la transferencia.')),
  });

  const entries = Object.values(picked);
  const units = entries.reduce((sum, entry) => sum + entry.quantity, 0);
  const canSubmit =
    fromBranchId != null && toBranchId != null && fromBranchId !== toBranchId && entries.length > 0;

  if (branches.length < 2) {
    return (
      <EmptyState
        icon={Truck}
        title="Necesitás al menos dos sucursales"
        description="Creá otra sucursal desde Configuración para poder mover mercadería entre ellas."
        bordered
      />
    );
  }

  if (result) {
    return <TransferResultPanel transfer={result} onNew={() => setResult(null)} />;
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div className="flex flex-col gap-4">
        <Card>
          <div className="grid items-end gap-3 sm:grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]">
            <Field label="Sucursal de origen">
              <Select
                value={fromBranchId ?? ''}
                options={branches.map((branch) => ({ value: branch.id, label: branch.name }))}
                onChange={(event) => {
                  const value = Number(event.target.value);
                  setFromBranchId(value);
                  setPicked({});
                  setPage(0);
                  if (value === toBranchId) setToBranchId(null);
                }}
              />
            </Field>
            <ArrowRight
              className="mb-2.5 hidden h-5 w-5 justify-self-center text-muted-foreground sm:block"
              aria-hidden="true"
            />
            <Field label="Sucursal de destino">
              <Select
                value={toBranchId ?? ''}
                placeholder="Elegí la sucursal"
                options={branches
                  .filter((branch) => branch.id !== fromBranchId)
                  .map((branch) => ({ value: branch.id, label: branch.name }))}
                onChange={(event) => setToBranchId(event.target.value ? Number(event.target.value) : null)}
              />
            </Field>
          </div>
        </Card>

        {fromBranchId != null ? (
          <TransferLotPicker
            fromBranchId={fromBranchId}
            rotation={rotation}
            selected={Object.fromEntries(
              Object.entries(picked).map(([lotId, entry]) => [Number(lotId), entry.quantity]),
            )}
            onChange={(lotId, quantity, lot) =>
              setPicked((current) => {
                const next = { ...current };
                if (quantity <= 0) delete next[lotId];
                else next[lotId] = { quantity, lot };
                return next;
              })
            }
            search={search}
            onSearchChange={(value) => {
              setSearch(value);
              setPage(0);
            }}
            page={page}
            onPageChange={setPage}
            disabled={transfer.isPending}
          />
        ) : null}
      </div>

      <aside className="flex flex-col gap-3 lg:sticky lg:top-4 lg:self-start">
        <Card padding="none" className="overflow-hidden">
          <CardHeader title="Resumen" description={`${formatNumber(units)} u. en ${entries.length} lotes`} />
          {entries.length === 0 ? (
            <p className="border-t border-border px-4 py-3 text-sm text-muted-foreground">
              Elegí los lotes y las unidades que querés mover.
            </p>
          ) : (
            <ul className="max-h-72 divide-y divide-border overflow-y-auto border-t border-border gd-scroll">
              {entries.map((entry) => (
                <li key={entry.lot.lotId} className="flex items-center justify-between gap-2 px-4 py-2">
                  <Truncate className="min-w-0 text-sm text-foreground">{entry.lot.productName}</Truncate>
                  <span className="shrink-0 text-sm font-semibold tabular-nums">{entry.quantity} u.</span>
                </li>
              ))}
            </ul>
          )}
          <div className="border-t border-border p-4">
            <Field label="Nota" optional>
              <Textarea
                rows={2}
                maxLength={300}
                value={note}
                placeholder="Reparto semanal, reposición de góndola…"
                onChange={(event) => setNote(event.target.value)}
              />
            </Field>
            <Button
              size="xl"
              fullWidth
              className="mt-3"
              leftIcon={<Send className="h-4 w-4" />}
              loading={transfer.isPending}
              disabled={!canSubmit}
              onClick={() => transfer.mutate()}
            >
              Transferir
            </Button>
            {toBranchId == null ? (
              <p className="mt-2 text-center text-xs text-muted-foreground">Elegí la sucursal de destino.</p>
            ) : (
              <p className="mt-2 text-center text-xs text-muted-foreground">
                Los lotes conservan número, vencimiento, costo y antigüedad para la rotación.
              </p>
            )}
          </div>
        </Card>
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Historial
// ---------------------------------------------------------------------------

function HistoryTab() {
  const [range, setRange] = useState<DateRange>(EMPTY_RANGE);
  const [page, setPage] = useState(0);

  const params = { from: range.from || undefined, to: range.to || undefined, page, size: 20 };
  const transfers = useQuery({
    queryKey: ['transfers', 'list', params],
    queryFn: () => transfersApi.list(params),
    placeholderData: keepPreviousData,
  });

  const columns: Array<TableColumn<TransferSummary>> = [
    {
      id: 'batchRef',
      header: 'Transferencia',
      mobile: 'title',
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          <span className="font-mono text-sm font-semibold text-foreground">{row.batchRef}</span>
          <span className="text-xs tabular-nums text-muted-foreground">{formatDateTime(row.occurredAt)}</span>
        </span>
      ),
    },
    {
      id: 'route',
      header: 'Recorrido',
      mobile: 'subtitle',
      cell: (row) => (
        <span className="flex items-center gap-1.5 text-foreground">
          <Truncate>{row.fromBranchName}</Truncate>
          <ArrowRight className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
          <Truncate>{row.toBranchName}</Truncate>
        </span>
      ),
    },
    {
      id: 'items',
      header: 'Lotes',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.itemsCount)}</span>,
    },
    {
      id: 'units',
      header: 'Unidades',
      align: 'right',
      mobile: 'aside',
      cell: (row) => <span className="font-semibold tabular-nums">{formatNumber(row.units)}</span>,
    },
    {
      id: 'cost',
      header: 'Valor a costo',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums text-muted-foreground">{formatMoney(row.costValue)}</span>,
    },
    {
      id: 'user',
      header: 'Registró',
      hideBelow: 'xl',
      mobile: 'field',
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          <span className="text-muted-foreground">{row.userName ?? '—'}</span>
          {row.note ? <Truncate className="text-xs text-muted-foreground">{row.note}</Truncate> : null}
        </span>
      ),
    },
  ];

  return (
    <div className="flex flex-col gap-4">
      <div className="flex justify-end">
        <DateRangeFilter
          value={range}
          onChange={(value) => {
            setRange(value);
            setPage(0);
          }}
          label="Rango de fechas de las transferencias"
        />
      </div>

      <Table
        columns={columns}
        data={transfers.data?.content}
        rowKey={(row) => row.batchRef}
        loading={transfers.isPending}
        error={transfers.isError ? transfers.error : undefined}
        onRetry={() => void transfers.refetch()}
        caption="Historial de transferencias entre sucursales"
        empty={{
          icon: Truck,
          title: 'Todavía no hay transferencias',
          description: 'Cuando se mueva mercadería de una sucursal a otra la vas a ver acá; el lote conserva su antigüedad.',
        }}
        footer={
          (transfers.data?.totalPages ?? 0) > 1 ? (
            <Pagination {...pageInfo(transfers.data)} onPageChange={setPage} disabled={transfers.isFetching} />
          ) : undefined
        }
      />
    </div>
  );
}

// ---------------------------------------------------------------------------

export default function TransfersPage() {
  const { can } = useAccess();
  // El jefe ve el historial pero no transfiere (SPEC §3.3): sin la pestaña "Nueva transferencia".
  const canTransfer = can('transfers.write');
  const [tab, setTab] = useState<TabValue>(canTransfer ? 'new' : 'history');

  return (
    <>
      <PageHeader
        title="Transferencias"
        icon={Truck}
        description={
          canTransfer
            ? 'Mové mercadería entre tus sucursales sin perder la antigüedad de los lotes.'
            : 'Historial de la mercadería que se movió entre tus sucursales.'
        }
      >
        {canTransfer ? (
          <Tabs<TabValue>
            value={tab}
            onChange={setTab}
            ariaLabel="Secciones de transferencias"
            tabs={[
              { value: 'new', label: 'Nueva transferencia', icon: Send },
              { value: 'history', label: 'Historial', icon: Truck },
            ]}
          />
        ) : null}
      </PageHeader>

      {canTransfer && tab === 'new' ? <NewTransferTab /> : <HistoryTab />}
    </>
  );
}
