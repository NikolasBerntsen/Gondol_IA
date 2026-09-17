import { useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { Ban, Blocks, MoreHorizontal, Pencil, Plus, RotateCcw, Store, Trash2 } from 'lucide-react';
import {
  BUSINESS_TYPES,
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  TENANT_MODULES,
  TENANT_MODULE_LABELS,
  TENANT_PLANS,
  type BusinessType,
  type TenantModule,
  type TenantPlan,
  type TenantStatus,
} from '@/api/types';
import {
  Badge,
  Button,
  ButtonLink,
  Card,
  DropdownItem,
  DropdownPanel,
  DropdownSeparator,
  PageHeader,
  Pagination,
  SearchInput,
  Segmented,
  Select,
  Table,
  pageInfo,
  useDropdown,
  type TableColumn,
} from '@/components/ui';
import { formatDateTime, formatMoney, formatNumber, formatRelative, pluralize } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { platformApi, platformKeys } from '../api';
import { PlanBadge, TenantStatusPill, tenantRowSeverity } from '../components/PlanBadge';
import { PrivacyNote } from '../components/PrivacyNote';
import {
  TenantStatusDialog,
  type TenantAction,
  type TenantActionTarget,
} from '../components/TenantStatusDialog';
import { TENANT_MODULE_SHORT } from '../moduleMath';
import type { TenantSummary } from '../types';

const PAGE_SIZE = 20;

type StatusFilter = 'ALL' | TenantStatus;

/** Listado de clientes de GondolIA (SPEC §6.6): filtros, estados y acciones administrativas. */
export default function TenantsPage() {
  const [params, setParams] = useSearchParams();
  const [query, setQuery] = useState(() => params.get('q') ?? '');
  const debouncedQuery = useDebounce(query, 300);
  const [pending, setPending] = useState<{ action: TenantAction; tenant: TenantActionTarget } | null>(null);

  const status = (params.get('status') as StatusFilter | null) ?? 'ALL';
  const plan = (params.get('plan') as TenantPlan | null) ?? undefined;
  const businessType = (params.get('businessType') as BusinessType | null) ?? undefined;
  const module = (params.get('module') as TenantModule | null) ?? undefined;
  const page = Number(params.get('page') ?? '0');

  const patch = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => {
      if (value === undefined || value === '' || value === 'ALL') next.delete(key);
      else next.set(key, value);
    });
    if (!('page' in changes)) next.delete('page');
    setParams(next, { replace: true });
  };

  const listParams = {
    q: debouncedQuery.trim() || undefined,
    status: status === 'ALL' ? undefined : status,
    plan,
    businessType,
    module,
    page,
    size: PAGE_SIZE,
    sort: 'name',
  };

  const metrics = useQuery({ queryKey: platformKeys.metrics, queryFn: platformApi.metrics });
  const tenants = useQuery({
    queryKey: platformKeys.tenantList(listParams),
    queryFn: () => platformApi.tenants.list(listParams),
    placeholderData: keepPreviousData,
  });

  const counts = metrics.data?.tenants;
  const hasFilters = Boolean(listParams.q || listParams.status || plan || businessType || module);

  const columns: Array<TableColumn<TenantSummary>> = [
    {
      id: 'name',
      header: 'Cliente',
      mobile: 'title',
      className: 'min-w-[180px]',
      cell: (row) => (
        <div className="min-w-0">
          <Link to={`/owner/tenants/${row.id}`} className="font-semibold text-foreground hover:underline">
            {row.name}
          </Link>
          <div className="text-xs text-muted-foreground">
            {BUSINESS_TYPE_LABELS[row.businessType]}
            {row.city ? ` · ${row.city}` : ''}
            {row.contactName ? ` · ${row.contactName}` : ''}
          </div>
        </div>
      ),
    },
    {
      id: 'plan',
      header: 'Plan',
      mobile: 'aside',
      cell: (row) => <PlanBadge plan={row.plan} />,
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) => <TenantStatusPill status={row.status} />,
    },
    {
      id: 'modules',
      header: 'Módulos',
      hideBelow: 'xl',
      mobile: 'field',
      mobileLabel: 'Módulos',
      cell: (row) =>
        row.modules.length ? (
          <div className="flex flex-wrap gap-1">
            {row.modules.map((value) => (
              <Badge key={value} tone="primary" size="sm" title={TENANT_MODULE_LABELS[value]}>
                {TENANT_MODULE_SHORT[value]}
              </Badge>
            ))}
          </div>
        ) : (
          <span className="text-muted-foreground">Sin módulos</span>
        ),
    },
    {
      id: 'branches',
      header: 'Suc.',
      align: 'right',
      headerClassName: 'w-[76px] px-2',
      className: 'px-2 text-right tabular-nums',
      mobile: 'field',
      mobileLabel: 'Sucursales activas',
      cell: (row) => (
        <span>
          {formatNumber(row.activeBranchCount)}
          <span className="text-muted-foreground"> / {formatNumber(row.branchCount)}</span>
        </span>
      ),
    },
    {
      id: 'users',
      header: 'Usu.',
      align: 'right',
      headerClassName: 'w-[64px] px-2',
      className: 'px-2 text-right tabular-nums',
      hideBelow: 'xl',
      mobile: 'field',
      mobileLabel: 'Usuarios',
      cell: (row) => formatNumber(row.userCount),
    },
    {
      id: 'activity',
      header: 'Actividad',
      hideBelow: 'xl',
      className: 'whitespace-nowrap',
      mobile: 'field',
      mobileLabel: 'Última actividad',
      cell: (row) =>
        row.lastActivityAt ? (
          <span title={formatDateTime(row.lastActivityAt)}>{formatRelative(row.lastActivityAt)}</span>
        ) : (
          <span className="text-muted-foreground">Nunca entró</span>
        ),
    },
    {
      id: 'fee',
      header: 'Cuota',
      align: 'right',
      className: 'whitespace-nowrap text-right tabular-nums',
      mobile: 'field',
      mobileLabel: 'Cuota mensual',
      cell: (row) =>
        row.status === 'ACTIVE' ? (
          <span className="font-semibold">{formatMoney(row.monthlyFee)}</span>
        ) : (
          <span className="text-muted-foreground">No factura</span>
        ),
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      headerClassName: 'w-[56px]',
      mobile: 'actions',
      cell: (row) => <TenantRowMenu tenant={row} onAction={(action) => setPending({ action, tenant: row })} />,
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Consola de dueños"
        title="Clientes"
        icon={Store}
        description="Los comercios que usan GondolIA: alta, plan, módulos y estado de la cuenta."
        actions={
          <ButtonLink to="/owner/tenants/new" leftIcon={<Plus />}>
            Dar de alta un cliente
          </ButtonLink>
        }
      >
        <div className="flex flex-col gap-3">
          <Segmented
            label="Filtrar por estado"
            value={status}
            onChange={(value) => patch({ status: value })}
            options={[
              { value: 'ALL', label: 'Todos', count: counts?.total },
              { value: 'ACTIVE', label: 'Activos', count: counts?.active, tone: 'ok' },
              { value: 'DISABLED', label: 'Deshabilitados', count: counts?.disabled, tone: 'crit' },
              { value: 'CANCELLED', label: 'Dados de baja', count: counts?.cancelled },
            ]}
          />
          <div className="flex flex-col gap-3 md:flex-row md:flex-wrap md:items-center">
            <div className="w-full md:w-[280px]">
              <SearchInput
                value={query}
                onValueChange={(value) => {
                  setQuery(value);
                  patch({ q: value || undefined });
                }}
                placeholder="Nombre, razón social, contacto, ciudad o CUIT"
                label="Buscar cliente"
              />
            </div>
            <Select
              aria-label="Filtrar por plan"
              value={plan ?? ''}
              onChange={(event) => patch({ plan: event.target.value || undefined })}
              containerClassName="md:w-[170px]"
              options={[
                { value: '', label: 'Todos los planes' },
                ...TENANT_PLANS.map((value) => ({ value, label: PLAN_LABELS[value] })),
              ]}
            />
            <Select
              aria-label="Filtrar por rubro"
              value={businessType ?? ''}
              onChange={(event) => patch({ businessType: event.target.value || undefined })}
              containerClassName="md:w-[180px]"
              options={[
                { value: '', label: 'Todos los rubros' },
                ...BUSINESS_TYPES.map((value) => ({ value, label: BUSINESS_TYPE_LABELS[value] })),
              ]}
            />
            <Select
              aria-label="Filtrar por módulo habilitado"
              value={module ?? ''}
              onChange={(event) => patch({ module: event.target.value || undefined })}
              containerClassName="md:w-[230px]"
              options={[
                { value: '', label: 'Todos los módulos' },
                ...TENANT_MODULES.map((value) => ({ value, label: `Con ${TENANT_MODULE_LABELS[value]}` })),
              ]}
            />
            {hasFilters ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setQuery('');
                  setParams(new URLSearchParams(), { replace: true });
                }}
              >
                Limpiar filtros
              </Button>
            ) : null}
          </div>
        </div>
      </PageHeader>

      <div className="flex flex-col gap-5">
        <PrivacyNote />

        <Card padding="none" className="flex min-w-0 flex-col">
          <div className="flex items-center justify-between gap-3 px-4 py-3 sm:px-5">
            <span className="text-sm text-muted-foreground" aria-live="polite">
              {tenants.data
                ? `${pluralize(tenants.data.totalElements, 'cliente', 'clientes')} ${hasFilters ? 'con estos filtros' : 'en total'}`
                : 'Cargando clientes…'}
            </span>
            <ButtonLink to="/owner/modules" variant="ghost" size="sm" leftIcon={<Blocks />}>
              Ver la matriz de módulos
            </ButtonLink>
          </div>

          <Table
            columns={columns}
            data={tenants.data?.content}
            rowKey={(row) => row.id}
            loading={tenants.isPending}
            error={tenants.isError ? tenants.error : undefined}
            onRetry={() => void tenants.refetch()}
            rowSeverity={(row) => tenantRowSeverity(row.status)}
            caption="Clientes de GondolIA"
            className="min-w-0 border-t border-border"
            empty={{
              icon: Store,
              title: hasFilters ? 'Ningún cliente coincide con los filtros' : 'Todavía no hay clientes',
              description: hasFilters
                ? 'Probá con otro texto, plan, rubro, módulo o estado.'
                : 'Dá de alta el primer comercio para empezar.',
              action: hasFilters ? undefined : (
                <ButtonLink to="/owner/tenants/new" leftIcon={<Plus />}>
                  Dar de alta un cliente
                </ButtonLink>
              ),
            }}
            footer={
              tenants.data && tenants.data.totalPages > 1 ? (
                <Pagination
                  {...pageInfo(tenants.data)}
                  onPageChange={(next) => patch({ page: String(next) })}
                  disabled={tenants.isFetching}
                />
              ) : undefined
            }
          />
        </Card>
      </div>

      <TenantStatusDialog
        action={pending?.action ?? null}
        tenant={pending?.tenant ?? null}
        onClose={() => setPending(null)}
      />
    </>
  );
}

/** Acciones administrativas de una fila, según el estado del cliente. */
function TenantRowMenu({ tenant, onAction }: { tenant: TenantSummary; onAction: (action: TenantAction) => void }) {
  const dropdown = useDropdown();
  const navigate = useNavigate();

  const run = (action: TenantAction) => {
    dropdown.close();
    onAction(action);
  };

  const go = (path: string) => {
    dropdown.close();
    navigate(path);
  };

  return (
    <div className="relative inline-block text-left">
      <Button
        {...dropdown.triggerProps}
        variant="ghost"
        size="icon-sm"
        aria-label={`Acciones de ${tenant.name}`}
      >
        <MoreHorizontal className="h-4 w-4" aria-hidden="true" />
      </Button>
      {dropdown.open && (
        <DropdownPanel {...dropdown.panelProps} align="end" aria-label={`Acciones de ${tenant.name}`} className="w-60">
          <DropdownItem icon={<Store />} onClick={() => go(`/owner/tenants/${tenant.id}`)}>
            Ver el detalle
          </DropdownItem>
          <DropdownItem icon={<Pencil />} onClick={() => go(`/owner/tenants/${tenant.id}/edit`)}>
            Editar los datos
          </DropdownItem>
          <DropdownSeparator />
          {tenant.status === 'ACTIVE' ? (
            <DropdownItem icon={<Ban />} tone="danger" onClick={() => run('disable')}>
              Deshabilitar el acceso
            </DropdownItem>
          ) : null}
          {tenant.status === 'DISABLED' ? (
            <DropdownItem icon={<RotateCcw />} onClick={() => run('enable')}>
              Habilitar el acceso
            </DropdownItem>
          ) : null}
          {tenant.status !== 'CANCELLED' ? (
            <DropdownItem icon={<Ban />} tone="danger" onClick={() => run('cancel')}>
              Dar de baja
            </DropdownItem>
          ) : (
            <>
              <DropdownItem icon={<RotateCcw />} onClick={() => run('reactivate')}>
                Reactivar
              </DropdownItem>
              <DropdownItem icon={<Trash2 />} tone="danger" onClick={() => run('delete')}>
                Eliminar definitivamente
              </DropdownItem>
            </>
          )}
        </DropdownPanel>
      )}
    </div>
  );
}
