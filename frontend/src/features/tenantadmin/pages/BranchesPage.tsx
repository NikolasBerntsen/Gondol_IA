import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, MapPin, PackageX, PencilLine, Plus, Power, PowerOff, Users } from 'lucide-react';
import { useMemo, useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { PLAN_LABELS } from '@/api/types';
import { StatusPill } from '@/components/gondola';
import {
  Alert,
  Badge,
  Button,
  Card,
  ConfirmDialog,
  PageHeader,
  Table,
  Toggle,
  type TableColumn,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { pluralize } from '@/lib/format';
import { branchesApi, tenantAdminKeys } from '../api';
import { BranchFormDialog } from '../components/BranchFormDialog';
import type { BranchLimits, TenantBranch } from '../types';

interface Blocker {
  /** Motivo corto, visible al lado del botón deshabilitado. */
  short: string;
  /** Explicación completa, en el tooltip. */
  full: string;
}

/** Por qué no se puede desactivar una sucursal (SPEC §3.5). `null` = se puede. */
function deactivationBlocker(branch: TenantBranch, activeBranches: number): Blocker | null {
  if (activeBranches <= 1) {
    return {
      short: 'Es tu única sucursal activa',
      full: 'Es la única sucursal activa: tu comercio necesita al menos una.',
    };
  }
  if (branch.hasStock) {
    return {
      short: 'Todavía tiene mercadería',
      full: 'Todavía tiene stock. Transferilo a otra sucursal o descartalo antes de desactivarla.',
    };
  }
  return null;
}

export default function BranchesPage() {
  const queryClient = useQueryClient();
  const [showInactive, setShowInactive] = useState(false);
  const [editing, setEditing] = useState<TenantBranch | null>(null);
  const [formOpen, setFormOpen] = useState(false);
  const [deactivating, setDeactivating] = useState<TenantBranch | null>(null);

  const branchesQuery = useQuery({
    queryKey: tenantAdminKeys.branchesList(true),
    queryFn: () => branchesApi.list(true),
  });
  const limitsQuery = useQuery({ queryKey: tenantAdminKeys.branchLimits(), queryFn: branchesApi.limits });

  const limits = limitsQuery.data;
  const branches = useMemo(() => branchesQuery.data ?? [], [branchesQuery.data]);
  const activeBranches = useMemo(() => branches.filter((branch) => branch.active).length, [branches]);
  const visible = useMemo(
    () => (showInactive ? branches : branches.filter((branch) => branch.active)),
    [branches, showInactive],
  );
  const inactiveCount = branches.length - activeBranches;
  const canCreate = !!limits && limits.activeBranches < limits.maxBranches;

  const deactivate = useMutation({
    mutationFn: (branch: TenantBranch) => branchesApi.deactivate(branch.id),
    onSuccess: (branch) => {
      toast.success(`${branch.name} quedó desactivada.`);
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.branches });
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.users });
      setDeactivating(null);
    },
    onError: (error) => toast.error(getErrorMessage(error)),
  });

  const activate = useMutation({
    mutationFn: (branch: TenantBranch) => branchesApi.activate(branch.id),
    onSuccess: (branch) => {
      toast.success(`${branch.name} volvió a estar activa.`);
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.branches });
    },
    onError: (error) => toast.error(getErrorMessage(error)),
  });

  const openCreate = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const columns: Array<TableColumn<TenantBranch> | null> = [
    {
      id: 'branch',
      header: 'Sucursal',
      mobile: 'title',
      cell: (branch) => (
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="truncate font-medium text-foreground">{branch.name}</span>
            {branch.code && <span className="font-mono text-xs text-muted-foreground">{branch.code}</span>}
          </div>
          <span className="flex items-center gap-1 truncate text-sm text-muted-foreground">
            {branch.address || branch.city ? (
              <>
                <MapPin className="size-3.5 shrink-0" aria-hidden="true" />
                {[branch.address, branch.city, branch.province].filter(Boolean).join(', ')}
              </>
            ) : (
              'Sin dirección cargada'
            )}
          </span>
        </div>
      ),
    },
    {
      id: 'employees',
      header: 'Equipo',
      mobile: 'field',
      cell: (branch) => (
        <span className="inline-flex items-center gap-1.5 tabular-nums text-base text-foreground">
          <Users className="size-4 text-muted-foreground" aria-hidden="true" />
          {branch.employeeCount}
          <span className="text-sm text-muted-foreground">
            {branch.employeeCount === 1 ? 'persona' : 'personas'}
          </span>
        </span>
      ),
    },
    {
      id: 'stock',
      header: 'Stock',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (branch) => (
        <span className="text-sm text-muted-foreground">{branch.hasStock ? 'Con mercadería' : 'Sin mercadería'}</span>
      ),
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (branch) =>
        branch.active ? <StatusPill tone="ok">Activa</StatusPill> : <StatusPill tone="neutral">Desactivada</StatusPill>,
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      mobile: 'actions',
      headerClassName: 'w-64',
      cell: (branch) => {
        const blocker = deactivationBlocker(branch, activeBranches);
        return (
          <div className="flex flex-col items-end gap-0.5">
            <div className="flex items-center gap-1">
              <Button
                variant="ghost"
                size="sm"
                leftIcon={<PencilLine aria-hidden="true" />}
                onClick={() => {
                  setEditing(branch);
                  setFormOpen(true);
                }}
              >
                Editar
              </Button>
              {branch.active ? (
                <Button
                  variant="ghost"
                  size="sm"
                  leftIcon={<PowerOff aria-hidden="true" />}
                  disabled={blocker !== null}
                  title={blocker?.full}
                  onClick={() => setDeactivating(branch)}
                >
                  Desactivar
                </Button>
              ) : (
                <Button
                  variant="ghost"
                  size="sm"
                  leftIcon={<Power aria-hidden="true" />}
                  loading={activate.isPending && activate.variables?.id === branch.id}
                  disabled={!canCreate}
                  title={canCreate ? undefined : 'Llegaste al máximo de sucursales activas.'}
                  onClick={() => activate.mutate(branch)}
                >
                  Reactivar
                </Button>
              )}
            </div>
            {branch.active && blocker && (
              <span className="text-right text-xs text-muted-foreground">No se puede: {blocker.short.toLowerCase()}</span>
            )}
            {!branch.active && !canCreate && (
              <span className="text-right text-xs text-muted-foreground">No se puede: llegaste al máximo del plan</span>
            )}
          </div>
        );
      },
    },
  ];

  return (
    <>
      <PageHeader
        title="Sucursales"
        description="Cada local lleva su propio stock, sus lotes, sus ventas y sus alertas."
        icon={Building2}
        actions={
          <Button
            onClick={openCreate}
            leftIcon={<Plus aria-hidden="true" />}
            disabled={!canCreate}
            title={canCreate ? undefined : 'Llegaste al máximo de sucursales activas de tu plan.'}
          >
            Crear sucursal
          </Button>
        }
      />

      <div className="grid gap-6">
        <PlanLimitPanel limits={limits} loading={limitsQuery.isPending} />

        {inactiveCount > 0 && (
          <Toggle
            checked={showInactive}
            onChange={setShowInactive}
            size="sm"
            label={`Mostrar las ${pluralize(inactiveCount, 'sucursal desactivada', 'sucursales desactivadas')}`}
          />
        )}

        <Card padding="none">
          <Table<TenantBranch>
            columns={columns}
            data={visible}
            rowKey={(branch) => branch.id}
            loading={branchesQuery.isPending}
            error={branchesQuery.error}
            onRetry={() => void branchesQuery.refetch()}
            caption="Sucursales del comercio con su equipo y su estado"
            rowClassName={(branch) => (branch.active ? undefined : 'opacity-70')}
            empty={{
              icon: Building2,
              title: 'Todavía no tenés sucursales',
              description: 'Creá tu primer local para empezar a cargar mercadería.',
              action: (
                <Button onClick={openCreate} leftIcon={<Plus aria-hidden="true" />}>
                  Crear sucursal
                </Button>
              ),
            }}
          />
        </Card>
      </div>

      <BranchFormDialog open={formOpen} onClose={() => setFormOpen(false)} branch={editing} />

      <ConfirmDialog
        open={deactivating !== null}
        onClose={() => setDeactivating(null)}
        onConfirm={() => {
          if (deactivating) deactivate.mutate(deactivating);
        }}
        loading={deactivate.isPending}
        tone="danger"
        title={`¿Desactivar ${deactivating?.name ?? 'la sucursal'}?`}
        confirmLabel="Desactivar sucursal"
        description="Deja de aparecer en el selector de sucursales y nadie puede cargar ni vender ahí. La podés reactivar cuando quieras: el historial no se borra."
      >
        {deactivating && deactivating.employeeCount > 0 && (
          <Alert tone="warn" title="Hay gente asignada">
            {pluralize(deactivating.employeeCount, 'persona trabaja', 'personas trabajan')} en esta sucursal. Si no
            tienen otra asignada, no van a poder operar hasta que les asignes una.
          </Alert>
        )}
      </ConfirmDialog>
    </>
  );
}

function PlanLimitPanel({ limits, loading }: { limits: BranchLimits | undefined; loading: boolean }) {
  if (loading || !limits) {
    return <Card className="h-24 animate-pulse" />;
  }

  const used = limits.activeBranches;
  const max = limits.maxBranches;
  const pct = max > 0 ? Math.min(100, Math.round((used / max) * 100)) : 100;
  const full = used >= max;

  return (
    <Card>
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <p className="gd-eyebrow text-muted-foreground">Plan {PLAN_LABELS[limits.plan]}</p>
          <p className="mt-1 font-display text-xl font-semibold tabular-nums text-foreground">
            {used} de {max} {max === 1 ? 'sucursal activa' : 'sucursales activas'}
          </p>
          {limits.totalBranches > limits.activeBranches && (
            <p className="text-sm text-muted-foreground">
              {pluralize(limits.totalBranches - limits.activeBranches, 'sucursal desactivada', 'sucursales desactivadas')}{' '}
              sin contar para el abono.
            </p>
          )}
        </div>
        <div className="w-full sm:max-w-xs">
          <div className="h-2 overflow-hidden rounded-full bg-muted" role="presentation">
            <div
              className={cn('h-full rounded-full transition-all', full ? 'bg-warn' : 'bg-primary')}
              style={{ width: `${pct}%` }}
            />
          </div>
          <p className="mt-2 text-sm text-muted-foreground">
            {full ? 'Llegaste al máximo de tu plan.' : `Podés crear ${max - used} más.`}
          </p>
        </div>
      </div>

      {!limits.multiBranchEnabled && (
        <Alert tone="info" title="Multi-sucursal no está habilitado" icon={PackageX} className="mt-4">
          Tu comercio funciona con una sola sucursal. Tu plan {PLAN_LABELS[limits.plan]} permite hasta{' '}
          <strong>{limits.planMaxBranches}</strong>: contactá a GondolIA para activar el módulo Multi-sucursal y sumar
          locales, transferencias entre sucursales y la vista consolidada.
        </Alert>
      )}
      {limits.multiBranchEnabled && full && (
        <Alert tone="warn" title="Sin lugar para más sucursales" className="mt-4">
          Desactivá una sucursal o contactá a soporte para pasar a un plan con más locales. La suscripción se cobra por
          sucursal activa.
        </Alert>
      )}
      <div className="mt-4 flex flex-col gap-2 text-sm text-muted-foreground sm:flex-row sm:items-center">
        <Badge tone="neutral" size="sm" className="self-start">
          Abono por sucursal activa
        </Badge>
        <span>Desactivar una sucursal la saca del abono del mes siguiente.</span>
      </div>
    </Card>
  );
}
