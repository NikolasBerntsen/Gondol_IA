import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowDownWideNarrow, CalendarClock, RotateCcw, Save, Settings, Sparkles, Store } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { STOCK_ROTATION_LABELS, type StockRotation } from '@/api/types';
import { ExpiryChip } from '@/components/gondola';
import { Alert, Button, Card, CardHeader, ErrorState, Field, Input, PageHeader, Skeleton, Tabs } from '@/components/ui';
import { tenantAdminKeys, tenantSettingsApi } from '../api';
import { AccountPanel } from '../components/AccountPanel';
import { RotationChoice } from '../components/RotationChoice';
import type { TenantSettings, TenantSettingsRequest } from '../types';

type TabValue = 'EXPIRY' | 'REPLENISHMENT' | 'ROTATION' | 'ACCOUNT';

interface FormState {
  stockRotation: StockRotation;
  expiryWarningDays: string;
  expiryCriticalDays: string;
  defaultLeadTimeDays: string;
  targetCoverageDays: string;
  /** Nivel de servicio en porcentaje (0,5 → "50"). */
  serviceLevelPct: string;
  maxDiscountPct: string;
}

const LIMITS = {
  expiryWarningDays: { min: 1, max: 180 },
  expiryCriticalDays: { min: 1, max: 60 },
  defaultLeadTimeDays: { min: 0, max: 60 },
  targetCoverageDays: { min: 1, max: 180 },
  serviceLevelPct: { min: 50, max: 99.9 },
  maxDiscountPct: { min: 0, max: 80 },
} as const;

function toForm(settings: TenantSettings): FormState {
  return {
    stockRotation: settings.stockRotation,
    expiryWarningDays: String(settings.expiryWarningDays),
    expiryCriticalDays: String(settings.expiryCriticalDays),
    defaultLeadTimeDays: String(settings.defaultLeadTimeDays),
    targetCoverageDays: String(settings.targetCoverageDays),
    serviceLevelPct: String(Math.round(settings.serviceLevel * 1000) / 10),
    maxDiscountPct: String(settings.maxDiscountPct),
  };
}

function toRequest(form: FormState, currency: string): TenantSettingsRequest {
  return {
    currency,
    stockRotation: form.stockRotation,
    expiryWarningDays: Number(form.expiryWarningDays),
    expiryCriticalDays: Number(form.expiryCriticalDays),
    defaultLeadTimeDays: Number(form.defaultLeadTimeDays),
    targetCoverageDays: Number(form.targetCoverageDays),
    serviceLevel: Math.round(Number(form.serviceLevelPct) * 10) / 1000,
    maxDiscountPct: Number(form.maxDiscountPct),
  };
}

function validate(form: FormState): Record<string, string> {
  const errors: Record<string, string> = {};
  const check = (key: keyof typeof LIMITS, label: string, unit: string) => {
    const raw = form[key];
    const value = Number(raw);
    if (raw.trim() === '' || Number.isNaN(value)) {
      errors[key] = `Escribí ${label} en ${unit}.`;
      return;
    }
    const { min, max } = LIMITS[key];
    if (value < min || value > max) {
      errors[key] = `Tiene que estar entre ${min} y ${max}.`;
    }
  };
  check('expiryCriticalDays', 'los días críticos', 'días');
  check('expiryWarningDays', 'los días de aviso', 'días');
  check('defaultLeadTimeDays', 'la demora del proveedor', 'días');
  check('targetCoverageDays', 'la cobertura objetivo', 'días');
  check('serviceLevelPct', 'el nivel de servicio', 'porcentaje');
  check('maxDiscountPct', 'el descuento máximo', 'porcentaje');

  if (!errors.expiryWarningDays && !errors.expiryCriticalDays) {
    if (Number(form.expiryCriticalDays) >= Number(form.expiryWarningDays)) {
      errors.expiryWarningDays = 'Los días de aviso tienen que ser más que los críticos.';
    }
  }
  return errors;
}

export default function SettingsPage() {
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<TabValue>('EXPIRY');
  const [form, setForm] = useState<FormState | null>(null);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();

  const settingsQuery = useQuery({ queryKey: tenantAdminKeys.settingsDetail(), queryFn: tenantSettingsApi.get });
  const settings = settingsQuery.data;

  useEffect(() => {
    if (settings) setForm(toForm(settings));
  }, [settings]);

  const dirty = useMemo(() => {
    if (!settings || !form) return false;
    return JSON.stringify(form) !== JSON.stringify(toForm(settings));
  }, [settings, form]);

  const mutation = useMutation({
    mutationFn: (values: FormState) => tenantSettingsApi.update(toRequest(values, settings?.currency ?? 'ARS')),
    onSuccess: (saved) => {
      queryClient.setQueryData(tenantAdminKeys.settingsDetail(), saved);
      queryClient.invalidateQueries({ queryKey: tenantAdminKeys.settings });
      setErrors({});
      setFormError(undefined);
      toast.success('Configuración guardada.');
    },
    onError: (error) => {
      setErrors(getFieldErrors(error));
      setFormError(getErrorMessage(error));
    },
  });

  const update = (patch: Partial<FormState>) => setForm((state) => (state ? { ...state, ...patch } : state));

  const save = () => {
    if (!form) return;
    const next = validate(form);
    setErrors(next);
    setFormError(undefined);
    if (Object.keys(next).length > 0) {
      toast.error('Revisá los valores marcados en rojo.');
      return;
    }
    mutation.mutate(form);
  };

  return (
    <>
      <PageHeader
        title="Configuración"
        description="Cómo GondolIA avisa, repone y descuenta el stock de tu comercio."
        icon={Settings}
        actions={
          tab !== 'ACCOUNT' ? (
            <div className="flex flex-col-reverse gap-2 sm:flex-row sm:items-center">
              {dirty && (
                <Button
                  variant="ghost"
                  leftIcon={<RotateCcw aria-hidden="true" />}
                  onClick={() => {
                    if (settings) setForm(toForm(settings));
                    setErrors({});
                    setFormError(undefined);
                  }}
                  disabled={mutation.isPending}
                >
                  Descartar cambios
                </Button>
              )}
              <Button
                onClick={save}
                loading={mutation.isPending}
                disabled={!dirty || !form}
                leftIcon={<Save aria-hidden="true" />}
                title={dirty ? undefined : 'No hay cambios para guardar.'}
              >
                Guardar cambios
              </Button>
            </div>
          ) : undefined
        }
      >
        <Tabs<TabValue>
          value={tab}
          onChange={setTab}
          ariaLabel="Secciones de la configuración"
          tabs={[
            { value: 'EXPIRY', label: 'Alertas y vencimientos', icon: CalendarClock },
            { value: 'REPLENISHMENT', label: 'Reposición e IA', icon: Sparkles },
            { value: 'ROTATION', label: 'Rotación de stock', icon: ArrowDownWideNarrow },
            { value: 'ACCOUNT', label: 'Datos del comercio', icon: Store },
          ]}
        />
      </PageHeader>

      {settingsQuery.isPending && (
        <Card>
          <Skeleton className="h-5 w-56" />
          <div className="mt-4 grid gap-4 md:grid-cols-2">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </div>
        </Card>
      )}

      {settingsQuery.isError && (
        <Card>
          <ErrorState error={settingsQuery.error} onRetry={() => void settingsQuery.refetch()} />
        </Card>
      )}

      {form && settings && (
        <div className="grid gap-6">
          {formError && (
            <Alert tone="crit" title="No se pudo guardar">
              {formError}
            </Alert>
          )}

          {tab === 'EXPIRY' && (
            <Card padding="md">
              <CardHeader
                icon={CalendarClock}
                title="Cuándo avisamos por un vencimiento"
                description="Definen los colores de los vencimientos, las alertas automáticas y las recomendaciones de descuento."
              />
              <div className="grid gap-4 md:grid-cols-2">
                <Field
                  label="Días críticos"
                  error={errors.expiryCriticalDays}
                  hint="Faltando estos días o menos, el lote se marca Crítico (rojo) y entra en las alertas urgentes."
                  required
                >
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={LIMITS.expiryCriticalDays.min}
                    max={LIMITS.expiryCriticalDays.max}
                    value={form.expiryCriticalDays}
                    onChange={(event) => update({ expiryCriticalDays: event.target.value })}
                    rightElement={<span className="text-sm text-muted-foreground">días</span>}
                  />
                </Field>
                <Field
                  label="Días de aviso"
                  error={errors.expiryWarningDays}
                  hint="Antes del crítico: el lote aparece Por vencer (naranja) para que lo liquides a tiempo."
                  required
                >
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={LIMITS.expiryWarningDays.min}
                    max={LIMITS.expiryWarningDays.max}
                    value={form.expiryWarningDays}
                    onChange={(event) => update({ expiryWarningDays: event.target.value })}
                    rightElement={<span className="text-sm text-muted-foreground">días</span>}
                  />
                </Field>
              </div>

              <div className="mt-5 rounded-panel border border-border bg-muted/60 p-4">
                <p className="gd-eyebrow text-muted-foreground">Así se van a ver tus lotes</p>
                <div className="mt-3 flex flex-wrap items-center gap-2">
                  <ExpiryChip expiry={daysFromToday(-2)} bucket="EXPIRED" showDays />
                  <ExpiryChip expiry={daysFromToday(Math.max(0, Number(form.expiryCriticalDays) - 1))} bucket="CRITICAL" showDays />
                  <ExpiryChip expiry={daysFromToday(Math.max(1, Number(form.expiryWarningDays) - 1))} bucket="WARNING" showDays />
                  <ExpiryChip expiry={daysFromToday(45)} bucket="OK" showDays />
                </div>
                <p className="mt-3 text-sm text-muted-foreground">
                  Vencido · Crítico (hasta {form.expiryCriticalDays || '—'} días) · Por vencer (hasta{' '}
                  {form.expiryWarningDays || '—'} días) · Próximo (hasta 30 días) · sin marca el resto.
                </p>
              </div>
            </Card>
          )}

          {tab === 'REPLENISHMENT' && (
            <Card padding="md">
              <CardHeader
                icon={Sparkles}
                title="Cómo calcula la IA las reposiciones"
                description="Con estos números la IA arma el punto de pedido, la cantidad sugerida y los descuentos por vencimiento."
              />
              <div className="grid gap-4 md:grid-cols-2">
                <Field
                  label="Demora del proveedor"
                  error={errors.defaultLeadTimeDays}
                  hint="Cuánto tarda en llegar un pedido cuando el proveedor no tiene su propia demora cargada."
                  required
                >
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={LIMITS.defaultLeadTimeDays.min}
                    max={LIMITS.defaultLeadTimeDays.max}
                    value={form.defaultLeadTimeDays}
                    onChange={(event) => update({ defaultLeadTimeDays: event.target.value })}
                    rightElement={<span className="text-sm text-muted-foreground">días</span>}
                  />
                </Field>
                <Field
                  label="Cobertura objetivo"
                  error={errors.targetCoverageDays}
                  hint="Para cuántos días de venta querés tener stock después de reponer."
                  required
                >
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={LIMITS.targetCoverageDays.min}
                    max={LIMITS.targetCoverageDays.max}
                    value={form.targetCoverageDays}
                    onChange={(event) => update({ targetCoverageDays: event.target.value })}
                    rightElement={<span className="text-sm text-muted-foreground">días</span>}
                  />
                </Field>
                <Field
                  label="Nivel de servicio"
                  error={errors.serviceLevelPct}
                  hint="Qué tan seguido querés no quedarte sin stock. Más alto = más stock de seguridad."
                  required
                >
                  <Input
                    type="number"
                    inputMode="decimal"
                    step="0.5"
                    min={LIMITS.serviceLevelPct.min}
                    max={LIMITS.serviceLevelPct.max}
                    value={form.serviceLevelPct}
                    onChange={(event) => update({ serviceLevelPct: event.target.value })}
                    rightElement={<span className="text-sm text-muted-foreground">%</span>}
                  />
                </Field>
                <Field
                  label="Descuento máximo"
                  error={errors.maxDiscountPct}
                  hint="Tope que puede sugerir la IA para liquidar un lote por vencer."
                  required
                >
                  <Input
                    type="number"
                    inputMode="numeric"
                    min={LIMITS.maxDiscountPct.min}
                    max={LIMITS.maxDiscountPct.max}
                    value={form.maxDiscountPct}
                    onChange={(event) => update({ maxDiscountPct: event.target.value })}
                    rightElement={<span className="text-sm text-muted-foreground">%</span>}
                  />
                </Field>
              </div>
              <Alert tone="info" title="Los cambios se aplican en el próximo análisis" className="mt-5">
                La IA recalcula todas las noches y cuando apretás “Recalcular” en Inteligencia IA.
              </Alert>
            </Card>
          )}

          {tab === 'ROTATION' && (
            <Card padding="md">
              <CardHeader
                icon={ArrowDownWideNarrow}
                title="De qué lote sale cada venta"
                description="Cuando vendés sin elegir el lote, GondolIA descuenta siguiendo este orden. Los lotes vencidos nunca se venden."
              />
              <RotationChoice
                value={form.stockRotation}
                onChange={(stockRotation) => update({ stockRotation })}
                disabled={mutation.isPending}
              />
              <Alert tone="warn" title="Los lotes en liquidación salen siempre primero" className="mt-4">
                Si aceptaste un descuento por vencimiento, ese lote se vende antes que el resto; dentro de cada grupo
                vale el orden que elijas acá.
              </Alert>
              <p className="mt-4 text-sm text-muted-foreground">
                Rotación actual guardada: <strong>{STOCK_ROTATION_LABELS[settings.stockRotation]}</strong>. El cambio
                afecta a las ventas nuevas; los movimientos ya registrados no se tocan.
              </p>
            </Card>
          )}

          {tab === 'ACCOUNT' && <AccountPanel />}
        </div>
      )}
    </>
  );
}

/** Fecha ISO a N días de hoy, para la vista previa de los chips de vencimiento. */
function daysFromToday(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}
