import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { keepPreviousData } from '@tanstack/react-query';
import { toast } from 'sonner';
import { Bell, BellOff, Check, CheckCheck, RefreshCw } from 'lucide-react';
import {
  Alert,
  Badge,
  Button,
  Card,
  PageHeader,
  Pagination,
  SearchInput,
  Segmented,
  Select,
  Table,
  pageInfo,
  severityTone,
} from '@/components/ui';
import type { SegmentedOption, TableColumn } from '@/components/ui';
import { StatusPill } from '@/components/gondola';
import type { StripeSeverity } from '@/components/gondola';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { useAccess } from '@/auth/useAccess';
import { ALERT_STATUS_LABELS, ALERT_TYPE_LABELS, SEVERITY_LABELS } from '@/api/types';
import type { AlertStatus, AlertType, Severity } from '@/api/types';
import { useDebounce } from '@/lib/useDebounce';
import { formatDateTime, formatNumber, formatRelative } from '@/lib/format';
import { alertsApi } from '../api';
import type { AlertRow } from '../types';

const PAGE_SIZE = 20;

const SEVERITY_STRIPE: Record<Severity, StripeSeverity> = {
  CRITICAL: 'crit',
  WARNING: 'warn',
  INFO: 'info',
};

const STATUS_FILTERS: ReadonlyArray<AlertStatus | 'ALL'> = ['OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'DISMISSED', 'ALL'];

const TYPE_OPTIONS = [
  { value: 'ALL', label: 'Todos los tipos' },
  ...(Object.keys(ALERT_TYPE_LABELS) as AlertType[]).map((type) => ({
    value: type,
    label: ALERT_TYPE_LABELS[type],
  })),
];

const SEVERITY_OPTIONS = [
  { value: 'ALL', label: 'Todas las severidades' },
  ...(Object.keys(SEVERITY_LABELS) as Severity[]).map((severity) => ({
    value: severity,
    label: SEVERITY_LABELS[severity],
  })),
];

export default function AlertsPage() {
  const { can } = useAccess();
  const { isAll } = useBranch();
  const queryClient = useQueryClient();
  // Marcar como vista, resolver y descartar: jefe y administrador (SPEC §3.3).
  const readOnly = !can('alerts.manage');
  const branchColumn = useBranchColumn<AlertRow>();

  const [status, setStatus] = useState<AlertStatus | 'ALL'>('OPEN');
  const [type, setType] = useState('ALL');
  const [severity, setSeverity] = useState('ALL');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const q = useDebounce(search, 300);

  const params = { status, type, severity, q: q || undefined, page, size: PAGE_SIZE };
  const listQuery = useQuery({
    queryKey: useBranchQueryKey('alerts', 'list', params),
    queryFn: () => alertsApi.list(params),
    placeholderData: keepPreviousData,
  });
  const countsQuery = useQuery({
    queryKey: useBranchQueryKey('alerts', 'counts'),
    queryFn: alertsApi.counts,
  });

  const counts = countsQuery.data;
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: ['alerts'] });
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  };

  const transition = useMutation({
    mutationFn: ({ id, action }: { id: number; action: 'acknowledge' | 'resolve' | 'dismiss' }) =>
      action === 'acknowledge'
        ? alertsApi.acknowledge(id)
        : action === 'resolve'
          ? alertsApi.resolve(id)
          : alertsApi.dismiss(id),
    onSuccess: (alert) => {
      const label =
        alert.status === 'ACKNOWLEDGED'
          ? 'Marcaste la alerta como vista.'
          : alert.status === 'RESOLVED'
            ? 'Resolviste la alerta.'
            : 'Descartaste la alerta. No se vuelve a abrir por una semana.';
      toast.success(label);
      invalidate();
    },
  });

  const statusOptions: Array<SegmentedOption<AlertStatus | 'ALL'>> = STATUS_FILTERS.map((value) => ({
    value,
    label: value === 'ALL' ? 'Todas' : ALERT_STATUS_LABELS[value],
    count:
      counts == null
        ? undefined
        : value === 'OPEN'
          ? counts.open
          : value === 'ACKNOWLEDGED'
            ? counts.acknowledged
            : value === 'RESOLVED'
              ? counts.resolved
              : value === 'DISMISSED'
                ? counts.dismissed
                : undefined,
    tone: value === 'OPEN' ? 'crit' : undefined,
  }));

  const columns: Array<TableColumn<AlertRow> | null> = [
    {
      id: 'alerta',
      header: 'Alerta',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <div className="font-semibold text-foreground">{row.title}</div>
          {row.message ? <p className="mt-0.5 text-sm text-muted-foreground">{row.message}</p> : null}
          {row.lotNumber ? <div className="mt-0.5 font-mono text-xs text-muted-foreground">Lote {row.lotNumber}</div> : null}
          {row.productId != null && can('products.view') ? (
            <Link
              to={`/app/products/${row.productId}`}
              className="mt-0.5 inline-block text-sm font-medium text-primary underline-offset-2 hover:underline"
            >
              Ver producto
            </Link>
          ) : null}
        </div>
      ),
    },
    branchColumn,
    {
      id: 'tipo',
      header: 'Tipo',
      mobile: 'field',
      hideBelow: 'lg',
      cell: (row) => <Badge tone="neutral">{ALERT_TYPE_LABELS[row.type]}</Badge>,
    },
    {
      id: 'severidad',
      header: 'Severidad',
      mobile: 'aside',
      cell: (row) => <Badge tone={severityTone(row.severity)}>{SEVERITY_LABELS[row.severity]}</Badge>,
    },
    {
      id: 'estado',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) => (
        <div className="flex flex-col items-start gap-1">
          <StatusPill tone={row.status === 'OPEN' ? 'crit' : row.status === 'ACKNOWLEDGED' ? 'warn' : 'neutral'}>
            {ALERT_STATUS_LABELS[row.status]}
          </StatusPill>
          {row.handledByName ? <span className="text-xs text-muted-foreground">{row.handledByName}</span> : null}
        </div>
      ),
    },
    {
      id: 'cuando',
      header: 'Abierta',
      mobile: 'field',
      hideBelow: 'xl',
      cell: (row) => (
        <span className="whitespace-nowrap text-sm text-muted-foreground" title={formatDateTime(row.createdAt)}>
          {formatRelative(row.createdAt)}
        </span>
      ),
    },
    readOnly
      ? null
      : {
          id: 'acciones',
          header: 'Acciones',
          align: 'right',
          mobile: 'actions',
          cell: (row) => {
            const closed = row.status === 'RESOLVED' || row.status === 'DISMISSED';
            if (closed) return <span className="text-sm text-muted-foreground">—</span>;
            return (
              <div className="flex flex-wrap items-center justify-end gap-1.5">
                {row.status === 'OPEN' ? (
                  <Button
                    size="sm"
                    variant="outline"
                    leftIcon={<Check aria-hidden="true" />}
                    disabled={transition.isPending}
                    onClick={() => transition.mutate({ id: row.id, action: 'acknowledge' })}
                  >
                    Vista
                  </Button>
                ) : null}
                <Button
                  size="sm"
                  leftIcon={<CheckCheck aria-hidden="true" />}
                  disabled={transition.isPending}
                  onClick={() => transition.mutate({ id: row.id, action: 'resolve' })}
                >
                  Resolver
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={transition.isPending}
                  onClick={() => transition.mutate({ id: row.id, action: 'dismiss' })}
                >
                  Descartar
                </Button>
              </div>
            );
          },
        },
  ];

  const data = listQuery.data;

  return (
    <div className="flex flex-col gap-5">
      <PageHeader
        title="Alertas"
        description={
          isAll
            ? 'Todo lo que el motor detectó en las sucursales que ves, ordenado por urgencia.'
            : 'Todo lo que el motor detectó en esta sucursal, ordenado por urgencia.'
        }
        icon={Bell}
        actions={
          <Button
            variant="outline"
            leftIcon={<RefreshCw aria-hidden="true" />}
            loading={listQuery.isFetching || countsQuery.isFetching}
            onClick={() => {
              void listQuery.refetch();
              void countsQuery.refetch();
            }}
          >
            Actualizar
          </Button>
        }
      >
        <div className="flex flex-col gap-3">
          <Segmented
            label="Estado de las alertas"
            value={status}
            onChange={(value) => {
              setStatus(value);
              setPage(0);
            }}
            options={statusOptions}
          />
          <div className="flex flex-col gap-3 md:flex-row">
            <SearchInput
              value={search}
              onValueChange={(value) => {
                setSearch(value);
                setPage(0);
              }}
              placeholder="Buscar por título o detalle…"
              className="md:max-w-xs"
            />
            <Select
              aria-label="Tipo de alerta"
              value={type}
              options={TYPE_OPTIONS}
              onChange={(event) => {
                setType(event.target.value);
                setPage(0);
              }}
              className="md:w-[236px]"
            />
            <Select
              aria-label="Severidad"
              value={severity}
              options={SEVERITY_OPTIONS}
              onChange={(event) => {
                setSeverity(event.target.value);
                setPage(0);
              }}
              className="md:w-[236px]"
            />
          </div>
        </div>
      </PageHeader>

      {readOnly ? (
        <Alert tone="info" title="Estás viendo las alertas en modo lectura">
          El jefe o el administrador del comercio las marcan como vistas, resueltas o descartadas.
        </Alert>
      ) : null}

      {counts && counts.open + counts.acknowledged > 0 ? (
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <span className="text-muted-foreground">Sin cerrar:</span>
          <Badge tone="crit" dot>
            {formatNumber(counts.critical)} críticas
          </Badge>
          <Badge tone="warn" dot>
            {formatNumber(counts.warning)} advertencias
          </Badge>
          <Badge tone="info" dot>
            {formatNumber(counts.info)} informativas
          </Badge>
        </div>
      ) : null}

      <Card padding="none">
        <Table
          columns={columns}
          data={data?.content}
          rowKey={(row) => row.id}
          loading={listQuery.isPending}
          error={listQuery.error}
          onRetry={() => void listQuery.refetch()}
          rowSeverity={(row) =>
            row.status === 'RESOLVED' || row.status === 'DISMISSED' ? 'none' : SEVERITY_STRIPE[row.severity]
          }
          caption="Alertas del alcance de sucursales elegido"
          empty={{
            icon: BellOff,
            title: 'No hay alertas con ese filtro',
            description:
              status === 'OPEN'
                ? 'Ninguna alerta abierta en este alcance: el stock, los vencimientos y las ventas están en orden.'
                : 'Probá con otro estado o quitá los filtros.',
          }}
          footer={
            data && data.totalPages > 1 ? <Pagination {...pageInfo(data)} onPageChange={setPage} /> : undefined
          }
        />
      </Card>
    </div>
  );
}
