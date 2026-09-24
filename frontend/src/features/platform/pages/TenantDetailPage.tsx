import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import {
  Ban,
  Building2,
  Copy,
  History,
  KeyRound,
  Pencil,
  RotateCcw,
  Store,
  Trash2,
  Users,
} from 'lucide-react';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import {
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  PLAN_MAX_BRANCHES,
  PLAN_MONTHLY_PRICE_PER_BRANCH,
  ROLE_LABELS,
  STOCK_ROTATION_LABELS,
  TENANT_MODULE_LABELS,
  type Role,
  type TenantModule,
} from '@/api/types';
import { useAccess } from '@/auth/useAccess';
import { StatusPill } from '@/components/gondola';
import {
  Alert,
  Button,
  ButtonLink,
  Card,
  CardHeader,
  ErrorState,
  Modal,
  PageHeader,
  PageSpinner,
  Skeleton,
  Switch,
  Table,
  Truncate,
  type TableColumn,
} from '@/components/ui';
import { formatDate, formatDateTime, formatMoney, formatNumber, formatRelative, pluralize } from '@/lib/format';
import { platformApi, platformKeys } from '../api';
import { ModuleDisableDialog } from '../components/ModuleDisableDialog';
import { PlanBadge, TenantStatusPill } from '../components/PlanBadge';
import { PrivacyNote } from '../components/PrivacyNote';
import {
  TenantStatusDialog,
  type TenantAction,
} from '../components/TenantStatusDialog';
import { useModuleToggle } from '../hooks/useModuleToggle';
import { TENANT_EVENT_LABELS, type TenantBranchDto, type TenantEventDto, type TenantUserDto } from '../types';
import { useConsoleRole } from '../useConsoleRole';

const ROLE_ORDER: Role[] = ['TENANT_BOSS', 'TENANT_ADMIN', 'TENANT_EMPLOYEE', 'TENANT_CASHIER'];

/**
 * Detalle administrativo de un cliente (SPEC §6.6 y §14.3). Soporte también lo abre para resolver tickets: edita los
 * datos, los módulos y la contraseña del administrador, pero no cambia el estado de la cuenta (SPEC §3.3).
 */
export default function TenantDetailPage() {
  const { can } = useAccess();
  const { eyebrow } = useConsoleRole();
  const { id } = useParams<{ id: string }>();
  const tenantId = Number(id);
  const queryClient = useQueryClient();
  const [action, setAction] = useState<TenantAction | null>(null);
  const [temporaryPassword, setTemporaryPassword] = useState<{ email: string; fullName: string; password: string } | null>(
    null,
  );
  const [resetOpen, setResetOpen] = useState(false);

  const detail = useQuery({
    queryKey: platformKeys.tenantDetail(tenantId),
    queryFn: () => platformApi.tenants.get(tenantId),
    enabled: Number.isFinite(tenantId),
  });

  const modules = useQuery({
    queryKey: platformKeys.tenantModules(tenantId),
    queryFn: () => platformApi.modules.ofTenant(tenantId),
    enabled: Number.isFinite(tenantId),
  });

  const toggler = useModuleToggle();

  const resetPassword = useMutation({
    mutationFn: () => platformApi.tenants.resetAdminPassword(tenantId),
    onSuccess: (response) => {
      setResetOpen(false);
      setTemporaryPassword({
        email: response.email,
        fullName: response.fullName,
        password: response.temporaryPassword,
      });
      void queryClient.invalidateQueries({ queryKey: platformKeys.tenantDetail(tenantId) });
    },
    onError: (error) => toast.error(getErrorMessage(error, 'No pudimos restablecer la contraseña.')),
    meta: { errorToast: false },
  });

  if (detail.isPending) return <PageSpinner label="Cargando el cliente…" />;
  if (detail.isError) return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;

  const tenant = detail.data;
  const moduleTarget = {
    tenantId: tenant.id,
    tenantName: tenant.name,
    status: tenant.status,
    plan: tenant.plan,
    activeBranchCount: tenant.activeBranchCount,
    modules: {
      POS_GONDOLIA: tenant.modules.includes('POS_GONDOLIA'),
      POS_INTEGRATION: tenant.modules.includes('POS_INTEGRATION'),
      MULTI_BRANCH: tenant.modules.includes('MULTI_BRANCH'),
    } as Record<TenantModule, boolean>,
  };

  const branchColumns: Array<TableColumn<TenantBranchDto>> = [
    {
      id: 'name',
      header: 'Sucursal',
      mobile: 'title',
      cell: (row) => (
        <div>
          <div className="font-semibold text-foreground">{row.name}</div>
          {row.code ? <div className="font-mono text-xs text-muted-foreground">{row.code}</div> : null}
        </div>
      ),
    },
    { id: 'city', header: 'Ciudad', mobile: 'field', cell: (row) => row.city ?? '—' },
    {
      id: 'active',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) => (
        <StatusPill tone={row.active ? 'ok' : 'neutral'}>{row.active ? 'Activa' : 'Inactiva'}</StatusPill>
      ),
    },
    {
      id: 'createdAt',
      header: 'Alta',
      align: 'right',
      className: 'text-right tabular-nums',
      mobile: 'field',
      mobileLabel: 'Alta',
      cell: (row) => formatDate(row.createdAt),
    },
  ];

  const userColumns: Array<TableColumn<TenantUserDto>> = [
    {
      id: 'user',
      header: 'Usuario',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <div className="font-semibold text-foreground">{row.fullName}</div>
          <Truncate as="div" className="text-xs text-muted-foreground">
            {row.email}
          </Truncate>
        </div>
      ),
    },
    { id: 'role', header: 'Rol', mobile: 'field', cell: (row) => ROLE_LABELS[row.role] },
    {
      id: 'active',
      header: 'Cuenta',
      mobile: 'aside',
      cell: (row) => (
        <StatusPill tone={row.active ? 'ok' : 'crit'} solid={!row.active}>
          {row.active ? 'Activa' : 'Inactiva'}
        </StatusPill>
      ),
    },
    {
      id: 'lastLogin',
      header: 'Último ingreso',
      align: 'right',
      className: 'text-right',
      mobile: 'field',
      mobileLabel: 'Último ingreso',
      cell: (row) =>
        row.lastLoginAt ? (
          <span title={formatDateTime(row.lastLoginAt)}>{formatRelative(row.lastLoginAt)}</span>
        ) : (
          <span className="text-muted-foreground">Nunca entró</span>
        ),
    },
  ];

  return (
    <>
      <PageHeader
        eyebrow={eyebrow}
        title={tenant.name}
        icon={Store}
        back={{ to: '/owner/tenants', label: 'Volver a clientes' }}
        description={
          <span className="flex flex-wrap items-center gap-2">
            <TenantStatusPill status={tenant.status} />
            <PlanBadge plan={tenant.plan} />
            <span className="text-muted-foreground">
              {BUSINESS_TYPE_LABELS[tenant.businessType]}
              {tenant.city ? ` · ${tenant.city}` : ''}
              {tenant.province ? `, ${tenant.province}` : ''}
            </span>
          </span>
        }
        actions={
          <div className="flex flex-wrap gap-2">
            {can('platform.tenants.edit') ? (
              <ButtonLink to={`/owner/tenants/${tenant.id}/edit`} variant="outline" leftIcon={<Pencil />}>
                Editar
              </ButtonLink>
            ) : null}
            {/* Bloquear, dar de baja, reactivar y eliminar: decisiones comerciales del dueño. */}
            {can('platform.tenants.changeStatus') ? (
              <>
                {tenant.status === 'ACTIVE' ? (
                  <Button variant="danger-outline" leftIcon={<Ban />} onClick={() => setAction('disable')}>
                    Deshabilitar acceso
                  </Button>
                ) : null}
                {tenant.status === 'DISABLED' ? (
                  <Button leftIcon={<RotateCcw />} onClick={() => setAction('enable')}>
                    Habilitar acceso
                  </Button>
                ) : null}
                {tenant.status === 'CANCELLED' ? (
                  <>
                    <Button leftIcon={<RotateCcw />} onClick={() => setAction('reactivate')}>
                      Reactivar
                    </Button>
                    <Button variant="destructive" leftIcon={<Trash2 />} onClick={() => setAction('delete')}>
                      Eliminar
                    </Button>
                  </>
                ) : (
                  <Button variant="danger-outline" leftIcon={<Ban />} onClick={() => setAction('cancel')}>
                    Dar de baja
                  </Button>
                )}
              </>
            ) : null}
          </div>
        }
      />

      <div className="flex flex-col gap-5">
        <PrivacyNote />

        {tenant.status !== 'ACTIVE' && tenant.statusReason ? (
          <Alert
            tone={tenant.status === 'DISABLED' ? 'crit' : 'warn'}
            title={tenant.status === 'DISABLED' ? 'Acceso deshabilitado' : 'Cliente dado de baja'}
          >
            {tenant.statusReason}
            {tenant.statusChangedAt ? ` · ${formatDateTime(tenant.statusChangedAt)}` : ''}
          </Alert>
        ) : null}

        <div className="grid gap-5 lg:grid-cols-3">
          <Card padding="none" className="lg:col-span-2">
            <CardHeader className="px-4 pt-4 sm:px-5 sm:pt-5" title="Datos administrativos" />
            <dl className="grid gap-x-6 gap-y-4 p-4 sm:grid-cols-2 sm:p-5">
              <Detail label="Razón social" value={tenant.legalName} />
              <Detail label="CUIT" value={tenant.taxId} mono />
              <Detail label="Dirección" value={tenant.address} />
              <Detail label="Rubro" value={BUSINESS_TYPE_LABELS[tenant.businessType]} />
              <Detail label="Contacto" value={tenant.contactName} />
              <Detail label="Email de contacto" value={tenant.contactEmail} />
              <Detail label="Teléfono" value={tenant.contactPhone} />
              <Detail label="Rotación de stock" value={STOCK_ROTATION_LABELS[tenant.stockRotation]} />
              <Detail label="Alta" value={formatDate(tenant.createdAt)} />
              <Detail
                label="Última actividad"
                value={tenant.lastActivityAt ? formatRelative(tenant.lastActivityAt) : 'Nunca entró'}
              />
              {tenant.notes ? (
                <div className="sm:col-span-2">
                  <dt className="text-sm text-muted-foreground">Notas internas</dt>
                  <dd className="mt-1 whitespace-pre-wrap break-words text-read text-foreground">{tenant.notes}</dd>
                </div>
              ) : null}
            </dl>
          </Card>

          <Card padding="none">
            <CardHeader className="px-4 pt-4 sm:px-5 sm:pt-5" title="Plan y facturación" />
            <div className="space-y-4 p-4 sm:p-5">
              <div>
                <div className="text-sm text-muted-foreground">Cuota mensual estimada</div>
                <div className="font-display text-xl font-semibold tabular-nums">
                  {tenant.status === 'ACTIVE' ? formatMoney(tenant.monthlyFee) : 'No factura'}
                </div>
                <p className="mt-1 text-sm text-muted-foreground">
                  {pluralize(tenant.activeBranchCount, 'sucursal activa', 'sucursales activas')} ×{' '}
                  {formatMoney(PLAN_MONTHLY_PRICE_PER_BRANCH[tenant.plan])} del plan + adicionales de módulos.
                </p>
              </div>
              <dl className="grid grid-cols-2 gap-4">
                <Detail label="Plan" value={PLAN_LABELS[tenant.plan]} />
                <Detail
                  label="Máximo de sucursales"
                  value={`${formatNumber(tenant.maxBranches)}${
                    tenant.maxBranches < PLAN_MAX_BRANCHES[tenant.plan] ? ' (sin Multi-sucursal)' : ''
                  }`}
                />
                <Detail
                  label="Sucursales"
                  value={`${formatNumber(tenant.activeBranchCount)} activas de ${formatNumber(tenant.branchCount)}`}
                />
                <Detail label="Usuarios" value={formatNumber(tenant.userCount)} />
              </dl>
            </div>
          </Card>
        </div>

        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Módulos"
            description="El cambio aplica al instante: sus usuarios ven el menú actualizado sin volver a entrar."
          />
          <div className="divide-y divide-border border-t border-border">
            {modules.isPending ? (
              <div className="space-y-3 p-4 sm:p-5">
                <Skeleton className="h-6 w-full" />
                <Skeleton className="h-6 w-full" />
                <Skeleton className="h-6 w-full" />
              </div>
            ) : modules.isError ? (
              <div className="p-4 sm:p-5">
                <ErrorState error={modules.error} onRetry={() => void modules.refetch()} size="sm" />
              </div>
            ) : (
              (modules.data ?? []).map((status) => (
                <div key={status.module} className="flex items-start justify-between gap-4 p-4 sm:px-5">
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-base font-semibold text-foreground">{status.name}</span>
                      <span className="rounded-tag bg-muted px-1.5 py-0.5 font-mono text-xs font-semibold text-muted-foreground">
                        {status.monthlyPricePerBranch > 0
                          ? `+${formatMoney(status.monthlyPricePerBranch)}/suc.`
                          : 'Sin adicional'}
                      </span>
                    </div>
                    <p className="mt-0.5 text-sm text-muted-foreground">{status.description}</p>
                    {status.updatedAt ? (
                      <p className="mt-1 text-xs text-muted-foreground">
                        Último cambio {formatRelative(status.updatedAt)}
                        {status.updatedByName ? ` · ${status.updatedByName}` : ''}
                      </p>
                    ) : null}
                  </div>
                  <Switch
                    size="md"
                    checked={status.enabled}
                    onCheckedChange={(checked) => toggler.toggle(moduleTarget, status.module, checked)}
                    aria-label={`${status.name} para ${tenant.name}`}
                    disabled={
                      tenant.status === 'CANCELLED' ||
                      (toggler.isPending && toggler.savingModule === status.module)
                    }
                  />
                </div>
              ))
            )}
          </div>
          {tenant.status === 'CANCELLED' ? (
            <p className="border-t border-border px-4 py-3 text-sm text-muted-foreground sm:px-5">
              El cliente está dado de baja: reactivalo para poder cambiar sus módulos.
            </p>
          ) : null}
        </Card>

        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Sucursales"
            icon={Building2}
            description="Solo datos administrativos: nunca vemos su stock ni sus ventas."
          />
          <Table
            columns={branchColumns}
            data={tenant.branches}
            rowKey={(row) => row.id}
            rowSeverity={(row) => (row.active ? 'none' : undefined)}
            caption={`Sucursales de ${tenant.name}`}
            className="min-w-0 border-t border-border"
            empty={{ icon: Building2, title: 'Sin sucursales cargadas' }}
          />
        </Card>

        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Usuarios"
            icon={Users}
            description={ROLE_ORDER.filter((role) => tenant.usersByRole[role])
              .map((role) => `${tenant.usersByRole[role]} ${ROLE_LABELS[role].toLowerCase()}`)
              .join(' · ')}
            actions={
              can('platform.tenants.resetAdminPassword') ? (
                <Button variant="outline" size="sm" leftIcon={<KeyRound />} onClick={() => setResetOpen(true)}>
                  Restablecer contraseña del admin
                </Button>
              ) : undefined
            }
          />
          <Table
            columns={userColumns}
            data={tenant.users}
            rowKey={(row) => row.id}
            rowSeverity={(row) => (row.active ? 'none' : 'warn')}
            caption={`Usuarios de ${tenant.name}`}
            className="min-w-0 border-t border-border"
            empty={{ icon: Users, title: 'Sin usuarios cargados' }}
          />
        </Card>

        <Card padding="none">
          <CardHeader
            className="p-4 sm:p-5"
            title="Historial"
            icon={History}
            description="Altas, cambios de plan, bloqueos y módulos."
          />
          {tenant.events.length === 0 ? (
            <p className="border-t border-border p-4 text-base text-muted-foreground sm:p-5">
              Todavía no hay movimientos registrados.
            </p>
          ) : (
            <ol className="divide-y divide-border border-t border-border">
              {tenant.events.map((event) => (
                <li key={event.id} className="flex flex-col gap-1 p-4 sm:flex-row sm:items-start sm:gap-4 sm:px-5">
                  <div className="w-full shrink-0 font-mono text-xs text-muted-foreground sm:w-36">
                    {formatDateTime(event.createdAt)}
                  </div>
                  <div className="min-w-0">
                    <div className="text-base font-semibold text-foreground">{eventTitle(event)}</div>
                    {event.reason ? <p className="text-sm text-muted-foreground">{event.reason}</p> : null}
                    <p className="text-xs text-muted-foreground">{event.actorName ?? 'Sistema'}</p>
                  </div>
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>

      <TenantStatusDialog
        action={action}
        tenant={{ id: tenant.id, name: tenant.name }}
        onClose={() => setAction(null)}
        redirectAfterDelete="/owner/tenants"
      />

      <ModuleDisableDialog
        pending={toggler.pending}
        onClose={toggler.closePending}
        onConfirm={toggler.confirmDisable}
        loading={toggler.isPending}
      />

      <Modal
        open={resetOpen}
        onClose={() => setResetOpen(false)}
        size="sm"
        title="Restablecer la contraseña del administrador"
        description="Se genera una contraseña temporal, se cierran sus sesiones y tiene que cambiarla al entrar."
        footer={
          <>
            <Button variant="outline" onClick={() => setResetOpen(false)} disabled={resetPassword.isPending}>
              Cancelar
            </Button>
            <Button onClick={() => resetPassword.mutate()} loading={resetPassword.isPending} leftIcon={<KeyRound />}>
              Generar contraseña
            </Button>
          </>
        }
      >
        <p className="text-base text-muted-foreground">
          La vas a ver <strong className="font-semibold text-foreground">una sola vez</strong>: copiala y dictásela al
          administrador de {tenant.name}.
        </p>
      </Modal>

      <TemporaryPasswordModal value={temporaryPassword} onClose={() => setTemporaryPassword(null)} />
    </>
  );
}

function eventTitle(event: TenantEventDto): string {
  const label = TENANT_EVENT_LABELS[event.type] ?? event.type;
  if (event.type === 'MODULE_ENABLED' || event.type === 'MODULE_DISABLED') {
    const module = event.fromValue as TenantModule | null;
    return module ? `${label}: ${TENANT_MODULE_LABELS[module] ?? module}` : label;
  }
  if (event.type === 'PLAN_CHANGED' && event.fromValue && event.toValue) {
    return `${label}: ${planLabel(event.fromValue)} → ${planLabel(event.toValue)}`;
  }
  return label;
}

/** Etiqueta del plan ("BASICO" → "Básico"); si llega un valor desconocido se muestra tal cual. */
function planLabel(value: string): string {
  return (PLAN_LABELS as Partial<Record<string, string>>)[value] ?? value;
}

function Detail({ label, value, mono }: { label: string; value: string | null | undefined; mono?: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className={`mt-0.5 break-words text-read text-foreground ${mono ? 'font-mono' : ''}`}>{value || '—'}</dd>
    </div>
  );
}

/** La contraseña temporal se muestra una sola vez (SPEC §6.6). */
function TemporaryPasswordModal({
  value,
  onClose,
}: {
  value: { email: string; fullName: string; password: string } | null;
  onClose: () => void;
}) {
  if (!value) return null;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(value.password);
      toast.success('Copiaste la contraseña temporal.');
    } catch {
      toast.error('No pudimos copiarla: seleccionala y copiala a mano.');
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      size="sm"
      title="Contraseña temporal generada"
      description={`Para ${value.fullName} (${value.email}).`}
      footer={
        <Button onClick={onClose} data-autofocus>
          Ya la anoté
        </Button>
      }
    >
      <Alert tone="warn" title="Se muestra una sola vez">
        Si cerrás esta ventana sin copiarla vas a tener que generar otra.
      </Alert>
      <div className="mt-3 flex items-center justify-between gap-3 rounded-control border border-border bg-muted px-3 py-3">
        <code className="select-all font-mono text-lg font-semibold tracking-wide text-foreground">
          {value.password}
        </code>
        <Button variant="outline" size="sm" leftIcon={<Copy />} onClick={() => void copy()}>
          Copiar
        </Button>
      </div>
    </Modal>
  );
}
