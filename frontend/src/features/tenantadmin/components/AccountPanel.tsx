import { useQuery } from '@tanstack/react-query';
import { LifeBuoy, Store } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  TENANT_MODULE_LABELS,
  TENANT_STATUS_LABELS,
  type TenantStatus,
} from '@/api/types';
import { StatusPill, type Tone } from '@/components/gondola';
import { Alert, Badge, Card, CardHeader, ErrorState, Skeleton } from '@/components/ui';
import { formatDate, formatMoney } from '@/lib/format';
import { tenantAdminKeys, tenantSettingsApi } from '../api';

const STATUS_TONES: Record<TenantStatus, Tone> = {
  ACTIVE: 'ok',
  DISABLED: 'warn',
  CANCELLED: 'crit',
};

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
      <dt className="text-base text-muted-foreground">{label}</dt>
      <dd className="text-base font-medium text-foreground sm:text-right">{children ?? '—'}</dd>
    </div>
  );
}

/** Datos administrativos del comercio: solo lectura, los cambia GondolIA (SPEC §6.9). */
export function AccountPanel() {
  const accountQuery = useQuery({ queryKey: tenantAdminKeys.accountDetail(), queryFn: tenantSettingsApi.account });

  if (accountQuery.isPending) {
    return (
      <Card>
        <Skeleton className="h-5 w-48" />
        <div className="mt-4 space-y-3">
          {Array.from({ length: 6 }).map((_, index) => (
            <Skeleton key={index} className="h-4 w-full" />
          ))}
        </div>
      </Card>
    );
  }

  if (accountQuery.isError) {
    return (
      <Card>
        <ErrorState error={accountQuery.error} onRetry={() => void accountQuery.refetch()} />
      </Card>
    );
  }

  const account = accountQuery.data;

  return (
    <div className="grid gap-4">
      <Card padding="md">
        <CardHeader
          icon={Store}
          title={account.name}
          description="Estos datos los administra GondolIA. Si algo cambió, escribinos desde Soporte."
          actions={<StatusPill tone={STATUS_TONES[account.status]}>{TENANT_STATUS_LABELS[account.status]}</StatusPill>}
        />
        <dl className="divide-y divide-border border-t border-border">
          <Row label="Razón social">{account.legalName}</Row>
          <Row label="CUIT">{account.taxId ? <span className="font-mono">{account.taxId}</span> : null}</Row>
          <Row label="Rubro">{BUSINESS_TYPE_LABELS[account.businessType] ?? account.businessType}</Row>
          <Row label="Plan">
            <span className="inline-flex items-center gap-2">
              <Badge tone="primary">{PLAN_LABELS[account.plan]}</Badge>
              <span className="tabular-nums text-muted-foreground">
                {formatMoney(account.estimatedMonthlyFee)} por mes
              </span>
            </span>
          </Row>
          <Row label="Sucursales activas">
            <span className="tabular-nums">
              {account.activeBranches} de {account.maxBranches}
            </span>
          </Row>
          <Row label="Cliente desde">{formatDate(account.createdAt)}</Row>
        </dl>
      </Card>

      <Card padding="md">
        <CardHeader title="Contacto registrado" description="A quién llama GondolIA por temas de la cuenta." />
        <dl className="divide-y divide-border border-t border-border">
          <Row label="Responsable">{account.contactName}</Row>
          <Row label="Email">{account.contactEmail}</Row>
          <Row label="Teléfono">{account.contactPhone}</Row>
          <Row label="Dirección">
            {[account.address, account.city, account.province].filter(Boolean).join(', ') || null}
          </Row>
        </dl>
      </Card>

      <Card padding="md">
        <CardHeader title="Funciones habilitadas" description="Los módulos que contrataste con GondolIA." />
        {account.modules.length === 0 ? (
          <p className="text-base text-muted-foreground">
            Tu comercio usa solo las funciones incluidas: inventario, lotes y vencimientos, alertas, IA, avisos y
            soporte.
          </p>
        ) : (
          <div className="flex flex-wrap gap-2">
            {account.modules.map((module) => (
              <Badge key={module} tone="primary">
                {TENANT_MODULE_LABELS[module]}
              </Badge>
            ))}
          </div>
        )}
      </Card>

      <Alert
        tone="info"
        icon={LifeBuoy}
        title="¿Necesitás cambiar algo de estos datos?"
        action={
          <Link to="/app/support" className="text-sm font-medium text-primary hover:underline">
            Ir a Soporte
          </Link>
        }
      >
        El nombre, el plan, el CUIT y los módulos los administra GondolIA: contactá a soporte y lo resolvemos.
      </Alert>
    </div>
  );
}
