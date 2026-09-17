import { useQuery } from '@tanstack/react-query';
import {
  Activity,
  Building2,
  LifeBuoy,
  ShieldAlert,
  Store,
  TrendingUp,
  Users,
  Wallet,
} from 'lucide-react';
import {
  BUSINESS_TYPES,
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  PLAN_MONTHLY_PRICE_PER_BRANCH,
  ROLE_LABELS,
  TENANT_PLANS,
  type Role,
} from '@/api/types';
import {
  ButtonLink,
  Card,
  CardHeader,
  ErrorState,
  PageHeader,
  StatCard,
  Skeleton,
} from '@/components/ui';
import { formatMoney, formatNumber, formatPercent, pluralize } from '@/lib/format';
import { platformApi, platformKeys } from '../api';
import { BreakdownList } from '../components/BreakdownList';
import { GrowthChart } from '../components/GrowthChart';
import { ModuleAdoptionCards } from '../components/ModuleAdoptionCards';
import { PrivacyNote } from '../components/PrivacyNote';

const TENANT_ROLE_ORDER: Role[] = ['TENANT_BOSS', 'TENANT_ADMIN', 'TENANT_EMPLOYEE', 'TENANT_CASHIER'];

/** Métricas de la plataforma (SPEC §6.6): solo agregados administrativos. */
export default function OwnerMetricsPage() {
  const metrics = useQuery({ queryKey: platformKeys.metrics, queryFn: platformApi.metrics });
  const catalog = useQuery({ queryKey: platformKeys.moduleCatalog, queryFn: platformApi.modules.catalog });

  const data = metrics.data;

  return (
    <>
      <PageHeader
        eyebrow="Consola de dueños"
        title="Métricas de GondolIA"
        icon={TrendingUp}
        description="Cómo viene el negocio: clientes, sucursales, ingresos, uso, soporte y recalls."
        actions={
          <div className="flex flex-wrap gap-2">
            <ButtonLink to="/owner/tenants" variant="outline" leftIcon={<Store />}>
              Ver clientes
            </ButtonLink>
            <ButtonLink to="/owner/modules" leftIcon={<Activity />}>
              Módulos por cliente
            </ButtonLink>
          </div>
        }
      />

      <div className="flex flex-col gap-5">
        <PrivacyNote />

        {metrics.isError ? (
          <ErrorState error={metrics.error} onRetry={() => void metrics.refetch()} />
        ) : (
          <>
            <section aria-label="Indicadores" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <StatCard
                label="Clientes activos"
                value={data ? formatNumber(data.tenants.active) : '—'}
                icon={Store}
                tone="primary"
                loading={metrics.isPending}
                hint={
                  data
                    ? `${formatNumber(data.tenants.total)} en total · ${formatNumber(data.tenants.newLast30d)} altas en 30 días`
                    : undefined
                }
                to="/owner/tenants?status=ACTIVE"
              />
              <StatCard
                label="MRR estimado"
                value={data ? formatMoney(data.revenue.estimatedMrr) : '—'}
                icon={Wallet}
                tone="ok"
                loading={metrics.isPending}
                hint={
                  data
                    ? `${formatPercent(data.revenue.freemiumToPaidConversionPct)} de los activos con plan pago`
                    : undefined
                }
              />
              <StatCard
                label="Sucursales activas"
                value={data ? formatNumber(data.branches.active) : '—'}
                icon={Building2}
                tone="info"
                loading={metrics.isPending}
                hint={
                  data
                    ? `${formatNumber(data.branches.avgPerActiveTenant)} por cliente · ${pluralize(data.branches.multiBranchTenants, 'cliente multi-sucursal', 'clientes multi-sucursal')}`
                    : undefined
                }
              />
              <StatCard
                label="Clientes activos en 7 días"
                value={data ? formatNumber(data.engagement.activeTenants7d) : '—'}
                icon={Activity}
                tone="neutral"
                loading={metrics.isPending}
                hint={
                  data
                    ? `${formatNumber(data.engagement.activeTenants30d)} en 30 días · ${pluralize(data.engagement.activeUsers7d, 'usuario', 'usuarios')} entraron`
                    : undefined
                }
              />
            </section>

            <Card padding="none">
              <CardHeader
                title="Crecimiento de los últimos 12 meses"
                description="Clientes activos al cierre de cada mes, con las altas y las bajas."
              />
              <div className="p-4 sm:p-5">
                {metrics.isPending ? (
                  <Skeleton className="h-[260px] w-full" />
                ) : data && data.growth.length ? (
                  <GrowthChart data={data.growth} />
                ) : (
                  <p className="text-base text-muted-foreground">Todavía no hay historial para graficar.</p>
                )}
              </div>
            </Card>

            <div className="grid gap-5 lg:grid-cols-2">
              <Card padding="none">
                <CardHeader title="Clientes por plan" description="Con el precio mensual por sucursal activa." />
                <div className="p-4 sm:p-5">
                  {metrics.isPending ? (
                    <Skeleton className="h-32 w-full" />
                  ) : (
                    <BreakdownList
                      emptyLabel="Todavía no hay clientes."
                      rows={TENANT_PLANS.map((plan) => ({
                        key: plan,
                        label: PLAN_LABELS[plan],
                        hint: formatMoney(PLAN_MONTHLY_PRICE_PER_BRANCH[plan]),
                        value: data?.tenantsByPlan[plan] ?? 0,
                      }))}
                    />
                  )}
                </div>
              </Card>

              <Card padding="none">
                <CardHeader title="Clientes por rubro" description="En qué tipo de comercio se usa GondolIA." />
                <div className="p-4 sm:p-5">
                  {metrics.isPending ? (
                    <Skeleton className="h-32 w-full" />
                  ) : (
                    <BreakdownList
                      emptyLabel="Todavía no hay clientes."
                      rows={BUSINESS_TYPES.filter((type) => (data?.tenantsByBusinessType[type] ?? 0) > 0).map(
                        (type) => ({
                          key: type,
                          label: BUSINESS_TYPE_LABELS[type],
                          value: data?.tenantsByBusinessType[type] ?? 0,
                        }),
                      )}
                    />
                  )}
                </div>
              </Card>
            </div>

            <section className="space-y-3">
              <h2 className="gd-eyebrow text-muted-foreground">Adopción de módulos</h2>
              <ModuleAdoptionCards items={catalog.data} loading={catalog.isPending} />
            </section>

            <div className="grid gap-5 lg:grid-cols-3">
              <Card padding="none" className="lg:col-span-2">
                <CardHeader
                  title="Usuarios de los comercios"
                  icon={Users}
                  description="Cuántas cuentas hay por rol. No vemos qué hace cada una."
                />
                <div className="p-4 sm:p-5">
                  {metrics.isPending ? (
                    <Skeleton className="h-28 w-full" />
                  ) : (
                    <>
                      <p className="mb-3 text-base text-muted-foreground">
                        <strong className="font-display text-xl font-semibold tabular-nums text-foreground">
                          {formatNumber(data?.users.total ?? 0)}
                        </strong>{' '}
                        cuentas de comercio en total.
                      </p>
                      <BreakdownList
                        emptyLabel="Todavía no hay usuarios."
                        rows={TENANT_ROLE_ORDER.filter((role) => (data?.users.byRole[role] ?? 0) > 0).map((role) => ({
                          key: role,
                          label: ROLE_LABELS[role],
                          value: data?.users.byRole[role] ?? 0,
                        }))}
                      />
                    </>
                  )}
                </div>
              </Card>

              <div className="flex flex-col gap-5">
                <Card padding="none">
                  <CardHeader title="Soporte" icon={LifeBuoy} />
                  <dl className="grid grid-cols-2 gap-4 p-4 sm:p-5">
                    <Metric label="Tickets abiertos" value={data ? formatNumber(data.support.openTickets) : '—'} />
                    <Metric label="Sin asignar" value={data ? formatNumber(data.support.unassignedTickets) : '—'} />
                    <Metric
                      label="1ª respuesta"
                      value={
                        data?.support.avgFirstResponseMinutes != null
                          ? `${formatNumber(data.support.avgFirstResponseMinutes, { decimals: 1 })} min`
                          : '—'
                      }
                    />
                    <Metric
                      label="Resueltos en 30 días"
                      value={data ? formatNumber(data.support.resolvedLast30d) : '—'}
                    />
                    <Metric
                      label="Satisfacción"
                      value={
                        data?.support.avgRating != null
                          ? `${formatNumber(data.support.avgRating, { decimals: 1 })} / 5`
                          : 'Sin puntajes'
                      }
                    />
                  </dl>
                </Card>

                <Card padding="none">
                  <CardHeader title="Recalls" icon={ShieldAlert} />
                  <dl className="grid grid-cols-2 gap-4 p-4 sm:p-5">
                    <Metric label="Recalls activos" value={data ? formatNumber(data.recalls.activeRecalls) : '—'} />
                    <Metric
                      label="Comercios alcanzados"
                      value={data ? formatNumber(data.recalls.affectedTenantsTotal) : '—'}
                    />
                  </dl>
                  <p className="border-t border-border px-4 py-3 text-sm text-muted-foreground sm:px-5">
                    Sabemos cuántos comercios alcanzó cada recall, nunca cuáles ni qué productos tienen.
                  </p>
                </Card>
              </div>
            </div>
          </>
        )}
      </div>
    </>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="mt-0.5 font-display text-lg font-semibold tabular-nums text-foreground">{value}</dd>
    </div>
  );
}
