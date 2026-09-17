import { useMemo, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import { Blocks } from 'lucide-react';
import {
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  PLAN_MONTHLY_PRICE_PER_BRANCH,
  TENANT_MODULES,
  TENANT_MODULE_LABELS,
  TENANT_PLANS,
  TENANT_STATUS_LABELS,
  type TenantModule,
  type TenantPlan,
  type TenantStatus,
} from '@/api/types';
import {
  Card,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  Switch,
  Table,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatMoney, formatNumber, pluralize } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { platformApi, platformKeys } from '../api';
import { ModuleAdoptionCards } from '../components/ModuleAdoptionCards';
import { ModuleDisableDialog } from '../components/ModuleDisableDialog';
import { PlanBadge, TenantStatusPill, tenantRowSeverity } from '../components/PlanBadge';
import { PrivacyNote } from '../components/PrivacyNote';
import { useModuleToggle } from '../hooks/useModuleToggle';
import { estimatedMonthlyFee } from '../moduleMath';
import type { TenantModulesRow } from '../types';

/** Etiqueta corta para el encabezado de cada columna de módulo. */
const SHORT_LABELS: Record<TenantModule, string> = {
  POS_GONDOLIA: 'POS GondolIA',
  POS_INTEGRATION: 'POS propio',
  MULTI_BRANCH: 'Multi-sucursal',
};

const PAGE_SIZE = 20;

/** Módulos por cliente (SPEC §14.3): adopción, matriz con switches y cuota estimada en vivo. */
export default function ModulesMatrixPage() {
  const [params, setParams] = useSearchParams();
  const [query, setQuery] = useState(() => params.get('q') ?? '');
  const debouncedQuery = useDebounce(query, 300);

  const status = (params.get('status') as TenantStatus | null) ?? undefined;
  const plan = (params.get('plan') as TenantPlan | null) ?? undefined;
  const page = Number(params.get('page') ?? '0');

  const patch = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    Object.entries(changes).forEach(([key, value]) => {
      if (value === undefined || value === '') next.delete(key);
      else next.set(key, value);
    });
    if (!('page' in changes)) next.delete('page');
    setParams(next, { replace: true });
  };

  const listParams = {
    q: debouncedQuery.trim() || undefined,
    status,
    plan,
    page,
    size: PAGE_SIZE,
    sort: 'name',
  };

  const catalog = useQuery({ queryKey: platformKeys.moduleCatalog, queryFn: platformApi.modules.catalog });
  const matrix = useQuery({
    queryKey: platformKeys.moduleMatrix(listParams),
    queryFn: () => platformApi.modules.matrix(listParams),
    placeholderData: keepPreviousData,
  });

  const toggler = useModuleToggle();

  const rows = matrix.data?.content ?? [];
  const visibleMrr = useMemo(
    () =>
      rows.reduce(
        (total, row) =>
          total +
          estimatedMonthlyFee({
            plan: row.plan,
            status: row.status,
            activeBranchCount: row.activeBranchCount,
            modules: row.modules,
          }),
        0,
      ),
    [rows],
  );

  const hasFilters = Boolean(listParams.q || status || plan);

  const moduleColumn = (module: TenantModule): TableColumn<TenantModulesRow> => ({
    id: module,
    header: SHORT_LABELS[module],
    align: 'center',
    headerClassName: 'w-[112px] whitespace-normal px-2 text-center leading-4',
    className: 'px-2 text-center',
    mobile: 'field',
    mobileLabel: TENANT_MODULE_LABELS[module],
    cell: (row) => (
      <Switch
        checked={row.modules[module]}
        onCheckedChange={(checked) =>
          toggler.toggle(
            {
              tenantId: row.tenantId,
              tenantName: row.tenantName,
              status: row.status,
              plan: row.plan,
              activeBranchCount: row.activeBranchCount,
              modules: row.modules,
            },
            module,
            checked,
          )
        }
        aria-label={`${TENANT_MODULE_LABELS[module]} para ${row.tenantName}`}
        disabled={
          row.status === 'CANCELLED' ||
          (toggler.isPending && toggler.savingTenantId === row.tenantId && toggler.savingModule === module)
        }
      />
    ),
  });

  const columns: Array<TableColumn<TenantModulesRow>> = [
    {
      id: 'tenant',
      header: 'Cliente',
      mobile: 'title',
      className: 'min-w-[190px]',
      cell: (row) => (
        <div className="min-w-0">
          <Link
            to={`/app/owner/tenants/${row.tenantId}`}
            className={cn(
              'font-semibold hover:underline',
              row.status === 'ACTIVE' ? 'text-foreground' : 'text-muted-foreground',
            )}
          >
            {row.tenantName}
          </Link>
          <div className="text-xs text-muted-foreground">
            {BUSINESS_TYPE_LABELS[row.businessType]}
            {row.city ? ` · ${row.city}` : ''}
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
      id: 'branches',
      header: 'Suc.',
      align: 'right',
      headerClassName: 'w-[64px] whitespace-normal px-2 text-right leading-4',
      className: 'px-2 text-right font-medium tabular-nums',
      mobile: 'field',
      mobileLabel: 'Sucursales activas',
      cell: (row) => formatNumber(row.activeBranchCount),
    },
    ...TENANT_MODULES.map(moduleColumn),
    {
      id: 'fee',
      header: 'MRR estimado',
      align: 'right',
      className: 'whitespace-nowrap pr-4 text-right tabular-nums',
      mobile: 'field',
      mobileLabel: 'MRR estimado',
      cell: (row) =>
        row.status === 'ACTIVE' ? (
          <span className="font-semibold">{formatMoney(row.estimatedMonthlyFee)}</span>
        ) : (
          <span className="text-muted-foreground">{formatMoney(0)} · no factura</span>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow="Consola de dueños"
        title="Módulos por cliente"
        icon={Blocks}
        description="Activá o desactivá funciones por comercio. Los cambios aplican al instante para todos sus usuarios."
        actions={
          <div className="text-left sm:text-right">
            <div className="text-sm text-muted-foreground">MRR estimado de esta lista</div>
            <div className="font-display text-xl font-semibold leading-8 tabular-nums">{formatMoney(visibleMrr)}</div>
          </div>
        }
      />

      <div className="flex flex-col gap-5">
        <PrivacyNote>
          <strong className="font-semibold">Solo ves datos administrativos:</strong> nunca el stock, las ventas ni los
          chats de tus clientes.
        </PrivacyNote>

        <ModuleAdoptionCards items={catalog.data} loading={catalog.isPending} />

        <Card padding="none" className="flex min-w-0 flex-col">
          <div className="flex flex-col gap-3 px-4 py-3 sm:px-5 md:flex-row md:flex-wrap md:items-end">
            <div className="w-full md:w-[280px]">
              <SearchInput
                value={query}
                onValueChange={(value) => {
                  setQuery(value);
                  patch({ q: value || undefined });
                }}
                placeholder="Buscar cliente o ciudad"
                label="Buscar cliente"
              />
            </div>
            <div className="flex flex-col gap-3 sm:flex-row">
              <Select
                aria-label="Filtrar por plan"
                value={plan ?? ''}
                onChange={(event) => patch({ plan: event.target.value || undefined })}
                containerClassName="sm:w-[190px]"
                options={[
                  { value: '', label: 'Todos los planes' },
                  ...TENANT_PLANS.map((value) => ({
                    value,
                    label: `${PLAN_LABELS[value]} · ${formatMoney(PLAN_MONTHLY_PRICE_PER_BRANCH[value])}`,
                  })),
                ]}
              />
              <Select
                aria-label="Filtrar por estado"
                value={status ?? ''}
                onChange={(event) => patch({ status: event.target.value || undefined })}
                containerClassName="sm:w-[170px]"
                options={[
                  { value: '', label: 'Todos los estados' },
                  ...(['ACTIVE', 'DISABLED', 'CANCELLED'] as TenantStatus[]).map((value) => ({
                    value,
                    label: TENANT_STATUS_LABELS[value],
                  })),
                ]}
              />
            </div>
            <div className="hidden flex-1 md:block" />
            <span className="text-sm text-muted-foreground" aria-live="polite">
              {matrix.data
                ? `${formatNumber(rows.length)} de ${pluralize(matrix.data.totalElements, 'cliente', 'clientes')}`
                : 'Cargando clientes…'}
            </span>
          </div>

          <Table
            columns={columns}
            data={rows}
            rowKey={(row) => row.tenantId}
            loading={matrix.isPending}
            error={matrix.isError ? matrix.error : undefined}
            onRetry={() => void matrix.refetch()}
            rowSeverity={(row) => tenantRowSeverity(row.status)}
            rowClassName={(row) => (row.status === 'CANCELLED' ? 'text-muted-foreground' : undefined)}
            caption="Clientes y módulos habilitados"
            className="border-t border-border"
            empty={{
              title: hasFilters ? 'Ningún cliente coincide con los filtros' : 'Todavía no hay clientes',
              description: hasFilters
                ? 'Probá con otro texto, plan o estado.'
                : 'Cuando des de alta un comercio vas a poder manejar sus módulos desde acá.',
            }}
            footer={
              matrix.data && matrix.data.totalPages > 1 ? (
                <Pagination
                  {...pageInfo(matrix.data)}
                  onPageChange={(next) => patch({ page: String(next) })}
                  disabled={matrix.isFetching}
                />
              ) : undefined
            }
          />
          <p className="border-t border-border px-4 py-3 text-sm text-muted-foreground sm:px-5">
            MRR = sucursales activas × (precio del plan + adicionales de módulos). Los clientes deshabilitados o dados
            de baja no facturan.
          </p>
        </Card>
      </div>

      <ModuleDisableDialog
        pending={toggler.pending}
        onClose={toggler.closePending}
        onConfirm={toggler.confirmDisable}
        loading={toggler.isPending}
      />
    </>
  );
}
