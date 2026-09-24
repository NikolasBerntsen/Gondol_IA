import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Ban, ClipboardList, Printer, Receipt, X } from 'lucide-react';
import { getErrorMessage } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { useBranchQueryKey } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { StatusPill } from '@/components/gondola';
import {
  Button,
  ButtonLink,
  Card,
  ConfirmDialog,
  ErrorState,
  Field,
  Pagination,
  PageHeader,
  Segmented,
  Skeleton,
  Table,
  Tabs,
  Textarea,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatDateTime, formatMoney, formatTime } from '@/lib/format';
import { posApi, posKeys } from '../api';
import { SessionReport } from '../components/SessionReport';
import {
  PAYMENT_METHOD_LABELS,
  type PosSaleSummary,
  type PosSessionStatus,
  type PosSessionSummary,
} from '../types';

type ScopeTab = 'mine' | 'all';
type StatusFilter = 'ALL' | PosSessionStatus;

/** Turnos de caja (SPEC §15.3): listado, reporte Z y ventas del turno con anulación. */
export default function PosSessionsPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('TENANT_ADMIN');
  const branchColumn = useBranchColumn<PosSessionSummary>();

  const [scope, setScope] = useState<ScopeTab>(isAdmin ? 'all' : 'mine');
  const [status, setStatus] = useState<StatusFilter>('ALL');
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<number | null>(null);

  useEffect(() => setPage(0), [scope, status]);

  const params = {
    status: status === 'ALL' ? undefined : status,
    mine: scope === 'mine',
    page,
    size: 20,
  };

  const sessionsQuery = useQuery({
    queryKey: useBranchQueryKey(...posKeys.sessions(params)),
    queryFn: () => posApi.sessions(params),
    placeholderData: keepPreviousData,
  });

  const columns: Array<TableColumn<PosSessionSummary> | null> = [
    {
      id: 'opened',
      header: 'Turno',
      mobile: 'title',
      cell: (row) => (
        <span>
          <span className="block font-semibold text-foreground">{row.registerName ?? 'Caja'}</span>
          <span className="block text-xs text-muted-foreground">
            {formatDateTime(row.openedAt)}
            {row.closedAt ? ` → ${formatTime(row.closedAt)}` : ''}
          </span>
        </span>
      ),
    },
    {
      id: 'cashier',
      header: 'Cajero',
      mobile: 'subtitle',
      cell: (row) => (
        <span>
          {row.openedByName ?? '—'}
          {row.mine ? <span className="ml-1.5 text-xs text-muted-foreground">(vos)</span> : null}
        </span>
      ),
    },
    branchColumn,
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) =>
        row.status === 'OPEN' ? (
          <StatusPill tone="ok">Abierto</StatusPill>
        ) : row.closedWithoutSales ? (
          // Cerró sin ninguna venta vigente (lo fija el servidor con el arqueo del cierre, no el aviso del mostrador):
          // queda a la vista en el historial.
          <StatusPill tone="warn" solid>
            Cerrado sin ventas
          </StatusPill>
        ) : (
          <StatusPill tone="neutral" solid>
            Cerrado
          </StatusPill>
        ),
    },
    {
      id: 'sales',
      header: 'Ventas',
      align: 'right',
      mobile: 'field',
      cell: (row) => (
        <span className="tabular-nums">
          {row.salesCount}
          {row.voidedCount > 0 ? (
            <span className="ml-1 text-xs text-crit-ink">· {row.voidedCount} anul.</span>
          ) : null}
        </span>
      ),
    },
    {
      id: 'total',
      header: 'Total',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="font-semibold tabular-nums">{formatMoney(row.salesTotal)}</span>,
    },
    {
      id: 'difference',
      header: 'Diferencia',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) =>
        row.difference === null ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <span
            className={cn(
              'font-semibold tabular-nums',
              row.difference === 0 ? 'text-ok-ink' : 'text-crit-ink',
            )}
          >
            {row.difference === 0 ? 'Justa' : formatMoney(row.difference, { decimals: 2 })}
          </span>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        title="Turnos de caja"
        description={
          isAdmin
            ? 'Reportes Z, arqueos y ventas de cada turno del punto de venta.'
            : 'Tus turnos, con el reporte de cierre y las ventas que cobraste.'
        }
        icon={ClipboardList}
        actions={
          <ButtonLink to="/app/pos" variant="outline">
            Ir al mostrador
          </ButtonLink>
        }
      >
        <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
          {isAdmin ? (
            <Tabs
              ariaLabel="Alcance de los turnos"
              value={scope}
              onChange={setScope}
              tabs={[
                { value: 'all', label: 'Todos los turnos' },
                { value: 'mine', label: 'Mis turnos' },
              ]}
            />
          ) : null}
          <Segmented
            label="Estado del turno"
            value={status}
            onChange={setStatus}
            options={[
              { value: 'ALL', label: 'Todos' },
              { value: 'OPEN', label: 'Abiertos' },
              { value: 'CLOSED', label: 'Cerrados' },
            ]}
          />
        </div>
      </PageHeader>

      <Card padding="none" className="mt-4 overflow-hidden">
        <Table
          columns={columns}
          data={sessionsQuery.data?.content}
          rowKey={(row) => row.id}
          loading={sessionsQuery.isPending}
          error={sessionsQuery.isError ? sessionsQuery.error : undefined}
          onRetry={() => void sessionsQuery.refetch()}
          onRowClick={(row) => setSelectedId(row.id)}
          rowClassName={(row) => (row.id === selectedId ? 'bg-muted/60' : undefined)}
          caption="Turnos de caja del punto de venta"
          empty={{
            icon: ClipboardList,
            title: scope === 'mine' ? 'Todavía no abriste ningún turno' : 'Todavía no hay turnos',
            description: 'Cuando alguien abra su caja en el mostrador, el turno aparece acá con su reporte Z.',
          }}
          footer={
            sessionsQuery.data && sessionsQuery.data.totalPages > 1 ? (
              <Pagination {...pageInfo(sessionsQuery.data)} onPageChange={setPage} />
            ) : null
          }
        />
      </Card>

      {selectedId !== null ? (
        <SessionDetail sessionId={selectedId} onClose={() => setSelectedId(null)} isAdmin={isAdmin} />
      ) : null}
    </>
  );
}

// ---------------------------------------------------------------------------

function SessionDetail({
  sessionId,
  onClose,
  isAdmin,
}: {
  sessionId: number;
  onClose: () => void;
  isAdmin: boolean;
}) {
  const queryClient = useQueryClient();
  const [voidTarget, setVoidTarget] = useState<PosSaleSummary | null>(null);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<string | null>(null);

  const sessionQuery = useQuery({
    queryKey: posKeys.session(sessionId),
    queryFn: () => posApi.session(sessionId),
  });

  const salesParams = { sessionId, page: 0, size: 100 };
  const salesQuery = useQuery({
    queryKey: posKeys.sales(salesParams),
    queryFn: () => posApi.sales(salesParams),
  });

  const voidSale = useMutation({
    mutationFn: () => {
      if (!voidTarget) throw new Error('sin venta');
      return posApi.voidSale(voidTarget.id, reason.trim());
    },
    meta: { errorToast: false },
    onSuccess: () => {
      toast.success('Anulaste la venta.', { description: 'El stock volvió a sus lotes.' });
      setVoidTarget(null);
      setReason('');
      void queryClient.invalidateQueries({ queryKey: ['pos'] });
      void queryClient.invalidateQueries({ queryKey: ['products'] });
      void queryClient.invalidateQueries({ queryKey: ['movements'] });
    },
    onError: (error) => setReasonError(getErrorMessage(error)),
  });

  const session = sessionQuery.data;
  const canVoid = (sale: PosSaleSummary) =>
    sale.status === 'COMPLETED' && (isAdmin || (session?.mine === true && session.status === 'OPEN'));

  const saleColumns: Array<TableColumn<PosSaleSummary> | null> = [
    {
      id: 'ticket',
      header: 'Ticket',
      mobile: 'title',
      cell: (row) => (
        <span>
          <span className="block font-mono text-base font-semibold text-foreground">{row.ticketCode}</span>
          <span className="block text-xs text-muted-foreground">{formatTime(row.createdAt)}</span>
        </span>
      ),
    },
    {
      id: 'items',
      header: 'Ítems',
      align: 'right',
      mobile: 'field',
      cell: (row) => (
        <span className="tabular-nums">
          {row.itemsCount} · {row.units} u.
        </span>
      ),
    },
    {
      id: 'methods',
      header: 'Medios',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) => (
        <span className="text-sm text-muted-foreground">
          {row.paymentMethods.map((method) => PAYMENT_METHOD_LABELS[method]).join(' + ') || '—'}
        </span>
      ),
    },
    {
      id: 'total',
      header: 'Total',
      align: 'right',
      mobile: 'field',
      cell: (row) => (
        <span className={cn('font-semibold tabular-nums', row.status === 'VOIDED' && 'text-muted-foreground line-through')}>
          {formatMoney(row.total, { decimals: 2 })}
        </span>
      ),
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) =>
        row.status === 'VOIDED' ? (
          <StatusPill tone="crit" solid>
            Anulada
          </StatusPill>
        ) : row.hasShortage ? (
          <StatusPill tone="warn">Con faltante</StatusPill>
        ) : (
          <StatusPill tone="ok">Cobrada</StatusPill>
        ),
    },
    {
      id: 'actions',
      header: '',
      align: 'right',
      mobile: 'actions',
      cell: (row) => (
        <span className="flex items-center justify-end gap-1">
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/app/pos/sales/${row.id}/ticket`} target="_blank" rel="noopener">
              <Printer className="h-4 w-4" aria-hidden="true" />
              <span className="ml-1.5">Ticket</span>
            </Link>
          </Button>
          {canVoid(row) ? (
            <Button
              variant="ghost"
              size="sm"
              className="text-crit-ink"
              onClick={() => {
                setVoidTarget(row);
                setReason('');
                setReasonError(null);
              }}
              leftIcon={<Ban aria-hidden="true" />}
            >
              Anular
            </Button>
          ) : null}
        </span>
      ),
    },
  ];

  return (
    <section className="mt-6" aria-label="Detalle del turno">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="font-display text-lg font-semibold leading-7 tracking-[-0.01em]">Reporte del turno</h2>
          <p className="text-base text-muted-foreground">
            {session
              ? `${session.registerName ?? 'Caja'} · ${session.branchName ?? 'Sucursal'} · ${session.openedByName ?? 'Cajero'}`
              : 'Cargando el reporte…'}
          </p>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose} leftIcon={<X aria-hidden="true" />}>
          Cerrar
        </Button>
      </div>

      {sessionQuery.isPending ? (
        <div className="mt-3 grid gap-4 lg:grid-cols-2">
          <Skeleton className="h-64 rounded-panel" />
          <Skeleton className="h-64 rounded-panel" />
        </div>
      ) : sessionQuery.isError ? (
        <Card padding="md" className="mt-3">
          <ErrorState
            error={sessionQuery.error}
            onRetry={() => void sessionQuery.refetch()}
            title="No pudimos traer el reporte"
          />
        </Card>
      ) : session ? (
        <SessionReport session={session} className="mt-3" />
      ) : null}

      <h3 className="mt-6 font-display text-md font-semibold leading-6">Ventas del turno</h3>
      <Card padding="none" className="mt-2 overflow-hidden">
        <Table
          columns={saleColumns}
          data={salesQuery.data?.content}
          rowKey={(row) => row.id}
          loading={salesQuery.isPending}
          error={salesQuery.isError ? salesQuery.error : undefined}
          onRetry={() => void salesQuery.refetch()}
          rowSeverity={(row) => (row.status === 'VOIDED' ? 'crit' : row.hasShortage ? 'warn' : 'none')}
          caption="Ventas cobradas en el turno"
          empty={{
            icon: Receipt,
            title: 'Todavía no hay ventas en este turno',
            description: 'Las ventas cobradas en el mostrador aparecen acá con su ticket.',
          }}
        />
      </Card>

      <ConfirmDialog
        open={!!voidTarget}
        onClose={() => {
          setVoidTarget(null);
          setReasonError(null);
        }}
        onConfirm={() => {
          if (!reason.trim()) {
            setReasonError('Contanos por qué anulás la venta.');
            return;
          }
          setReasonError(null);
          voidSale.mutate();
        }}
        title={`Anular el ticket ${voidTarget?.ticketCode ?? ''}`}
        description="Las unidades vuelven a sus lotes y la venta deja de contar en el arqueo y en las estadísticas. No se puede deshacer."
        confirmLabel="Anular venta"
        tone="danger"
        loading={voidSale.isPending}
      >
        <Field label="Motivo de la anulación" error={reasonError ?? undefined}>
          <Textarea
            rows={2}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={300}
            invalid={!!reasonError}
            placeholder="Ej.: el cliente se arrepintió"
          />
        </Field>
      </ConfirmDialog>
    </section>
  );
}
