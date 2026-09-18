import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate, useParams } from 'react-router-dom';
import { Store } from 'lucide-react';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import {
  BUSINESS_TYPES,
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  PLAN_MAX_BRANCHES,
  PLAN_MONTHLY_PRICE_PER_BRANCH,
  STOCK_ROTATION_LABELS,
  TENANT_MODULES,
  TENANT_MODULE_DESCRIPTIONS,
  TENANT_MODULE_LABELS,
  TENANT_MODULE_MONTHLY_PRICE,
  TENANT_PLANS,
  type BusinessType,
  type StockRotation,
  type TenantModule,
  type TenantPlan,
} from '@/api/types';
import {
  Alert,
  Button,
  Card,
  CardHeader,
  Checkbox,
  ErrorState,
  Field,
  Input,
  PageHeader,
  PageSpinner,
  Select,
  Textarea,
} from '@/components/ui';
import { formatMoney, pluralize } from '@/lib/format';
import { platformApi, platformKeys } from '../api';
import { NewUserFields } from '../components/NewUserFields';
import { PrivacyNote } from '../components/PrivacyNote';
import { PLAN_MODULE_PRESET } from '../moduleMath';
import type { CreateTenantRequest, NewUserRequest, UpdateTenantRequest } from '../types';

const EMPTY_USER: NewUserRequest = { fullName: '', email: '', password: '' };

interface FormState {
  name: string;
  legalName: string;
  taxId: string;
  businessType: BusinessType;
  plan: TenantPlan;
  contactName: string;
  contactEmail: string;
  contactPhone: string;
  address: string;
  city: string;
  province: string;
  notes: string;
  stockRotation: StockRotation;
  planChangeReason: string;
  branchName: string;
  branchCode: string;
  branchAddress: string;
  branchCity: string;
  branchProvince: string;
  boss: NewUserRequest;
  admin: NewUserRequest;
  employee: NewUserRequest;
}

const INITIAL: FormState = {
  name: '',
  legalName: '',
  taxId: '',
  businessType: 'ALMACEN',
  plan: 'BASICO',
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  address: '',
  city: '',
  province: '',
  notes: '',
  stockRotation: 'FIFO',
  planChangeReason: '',
  branchName: 'Sucursal Principal',
  branchCode: '',
  branchAddress: '',
  branchCity: '',
  branchProvince: '',
  boss: EMPTY_USER,
  admin: EMPTY_USER,
  employee: EMPTY_USER,
};

const blank = (value: string) => (value.trim() ? value.trim() : undefined);

/** Alta y edición de un cliente (SPEC §6.6 y §14.3). */
export default function TenantFormPage() {
  const { id } = useParams<{ id: string }>();
  const tenantId = id ? Number(id) : undefined;
  const isEdit = tenantId !== undefined;
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [form, setForm] = useState<FormState>(INITIAL);
  const [modules, setModules] = useState<TenantModule[]>(PLAN_MODULE_PRESET[INITIAL.plan]);
  const [modulesTouched, setModulesTouched] = useState(false);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState<string>();
  const [originalPlan, setOriginalPlan] = useState<TenantPlan | null>(null);

  const detail = useQuery({
    queryKey: platformKeys.tenantDetail(tenantId ?? 0),
    queryFn: () => platformApi.tenants.get(tenantId as number),
    enabled: isEdit,
  });

  useEffect(() => {
    const data = detail.data;
    if (!data) return;
    setOriginalPlan(data.plan);
    setForm((previous) => ({
      ...previous,
      name: data.name,
      legalName: data.legalName ?? '',
      taxId: data.taxId ?? '',
      businessType: data.businessType,
      plan: data.plan,
      contactName: data.contactName ?? '',
      contactEmail: data.contactEmail ?? '',
      contactPhone: data.contactPhone ?? '',
      address: data.address ?? '',
      city: data.city ?? '',
      province: data.province ?? '',
      notes: data.notes ?? '',
      stockRotation: data.stockRotation,
      planChangeReason: '',
    }));
  }, [detail.data]);

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) =>
    setForm((previous) => ({ ...previous, [key]: value }));

  const changePlan = (plan: TenantPlan) => {
    set('plan', plan);
    if (!isEdit && !modulesTouched) setModules(PLAN_MODULE_PRESET[plan]);
  };

  const toggleModule = (module: TenantModule, enabled: boolean) => {
    setModulesTouched(true);
    setModules((previous) =>
      enabled ? [...new Set([...previous, module])] : previous.filter((value) => value !== module),
    );
  };

  const save = useMutation({
    mutationFn: async () => {
      const common = {
        name: form.name.trim(),
        legalName: blank(form.legalName),
        taxId: blank(form.taxId),
        businessType: form.businessType,
        plan: form.plan,
        contactName: blank(form.contactName),
        contactEmail: blank(form.contactEmail),
        contactPhone: blank(form.contactPhone),
        address: blank(form.address),
        city: blank(form.city),
        province: blank(form.province),
        notes: blank(form.notes),
        stockRotation: form.stockRotation,
      };
      if (isEdit) {
        const body: UpdateTenantRequest = {
          ...common,
          planChangeReason: originalPlan !== form.plan ? blank(form.planChangeReason) : undefined,
        };
        return platformApi.tenants.update(tenantId as number, body);
      }
      const body: CreateTenantRequest = {
        ...common,
        firstBranch: {
          name: blank(form.branchName),
          code: blank(form.branchCode),
          address: blank(form.branchAddress) ?? blank(form.address),
          city: blank(form.branchCity) ?? blank(form.city),
          province: blank(form.branchProvince) ?? blank(form.province),
        },
        boss: form.boss,
        admin: form.admin,
        employee: form.employee,
        modules,
      };
      return platformApi.tenants.create(body);
    },
    onSuccess: (tenant) => {
      toast.success(isEdit ? 'Guardaste los datos del cliente.' : 'Diste de alta al cliente.');
      void queryClient.invalidateQueries({ queryKey: platformKeys.tenants });
      void queryClient.invalidateQueries({ queryKey: platformKeys.metrics });
      void queryClient.invalidateQueries({ queryKey: ['platform', 'modules'] });
      navigate(`/owner/tenants/${tenant.id}`);
    },
    onError: (error) => {
      const fieldErrors = getFieldErrors(error);
      setErrors(fieldErrors);
      setFormError(Object.keys(fieldErrors).length ? undefined : getErrorMessage(error, 'No pudimos guardar.'));
    },
    meta: { errorToast: false },
  });

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setErrors({});
    setFormError(undefined);
    save.mutate();
  };

  if (isEdit && detail.isPending) return <PageSpinner label="Cargando el cliente…" />;
  if (isEdit && detail.isError) {
    return <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />;
  }

  const planChanged = isEdit && originalPlan !== null && originalPlan !== form.plan;
  const activeBranches = detail.data?.activeBranchCount ?? 0;
  const newLimit = modules.includes('MULTI_BRANCH') || isEdit ? PLAN_MAX_BRANCHES[form.plan] : 1;
  const downgradeWarning = planChanged && activeBranches > PLAN_MAX_BRANCHES[form.plan];
  const extras = modules.reduce((total, module) => total + TENANT_MODULE_MONTHLY_PRICE[module], 0);

  return (
    <form onSubmit={submit} noValidate>
      <PageHeaderSection isEdit={isEdit} name={detail.data?.name} tenantId={tenantId} />

      <div className="flex flex-col gap-5">
        {!isEdit ? <PrivacyNote /> : null}

        <Card padding="none">
          <CardHeader title="Datos del comercio" description="Lo que identifica al cliente y su facturación." />
          <div className="grid gap-4 p-4 sm:grid-cols-2 sm:p-5">
            <Field label="Nombre del comercio" error={errors.name} className="sm:col-span-2">
              <Input
                value={form.name}
                onChange={(event) => set('name', event.target.value)}
                maxLength={150}
                autoFocus={!isEdit}
              />
            </Field>
            <Field label="Razón social" optional error={errors.legalName}>
              <Input value={form.legalName} onChange={(event) => set('legalName', event.target.value)} maxLength={200} />
            </Field>
            <Field label="CUIT" optional error={errors.taxId}>
              <Input
                value={form.taxId}
                onChange={(event) => set('taxId', event.target.value)}
                maxLength={20}
                className="font-mono"
                placeholder="30-12345678-9"
              />
            </Field>
            <Field label="Rubro" error={errors.businessType}>
              <Select
                value={form.businessType}
                onChange={(event) => set('businessType', event.target.value as BusinessType)}
                options={BUSINESS_TYPES.map((value) => ({ value, label: BUSINESS_TYPE_LABELS[value] }))}
              />
            </Field>
            <Field
              label="Rotación de stock"
              hint="Con qué criterio sale primero la mercadería del cliente."
              error={errors.stockRotation}
            >
              <Select
                value={form.stockRotation}
                onChange={(event) => set('stockRotation', event.target.value as StockRotation)}
                options={(['FIFO', 'FEFO'] as StockRotation[]).map((value) => ({
                  value,
                  label: STOCK_ROTATION_LABELS[value],
                }))}
              />
            </Field>
            <Field label="Dirección" optional error={errors.address} className="sm:col-span-2">
              <Input value={form.address} onChange={(event) => set('address', event.target.value)} maxLength={200} />
            </Field>
            <Field label="Ciudad" optional error={errors.city}>
              <Input value={form.city} onChange={(event) => set('city', event.target.value)} maxLength={100} />
            </Field>
            <Field label="Provincia" optional error={errors.province}>
              <Input value={form.province} onChange={(event) => set('province', event.target.value)} maxLength={100} />
            </Field>
            <Field label="Notas internas" optional error={errors.notes} className="sm:col-span-2">
              <Textarea
                value={form.notes}
                onChange={(event) => set('notes', event.target.value)}
                rows={3}
                maxLength={2000}
                placeholder="Datos para tu equipo: cómo llegó el cliente, acuerdos, recordatorios."
              />
            </Field>
          </div>
        </Card>

        <Card padding="none">
          <CardHeader title="Contacto" description="A quién llamar o escribirle por la cuenta." />
          <div className="grid gap-4 p-4 sm:grid-cols-3 sm:p-5">
            <Field label="Nombre" optional error={errors.contactName}>
              <Input
                value={form.contactName}
                onChange={(event) => set('contactName', event.target.value)}
                maxLength={150}
              />
            </Field>
            <Field label="Email" optional error={errors.contactEmail}>
              <Input
                type="email"
                value={form.contactEmail}
                onChange={(event) => set('contactEmail', event.target.value)}
                maxLength={150}
              />
            </Field>
            <Field label="Teléfono" optional error={errors.contactPhone}>
              <Input
                value={form.contactPhone}
                onChange={(event) => set('contactPhone', event.target.value)}
                maxLength={50}
              />
            </Field>
          </div>
        </Card>

        <Card padding="none">
          <CardHeader
            title="Plan y módulos"
            description="El plan fija el precio por sucursal y el máximo de sucursales; los módulos suman adicionales."
          />
          <div className="space-y-4 p-4 sm:p-5">
            <div className="grid gap-4 sm:grid-cols-2">
              <Field label="Plan" error={errors.plan}>
                <Select
                  value={form.plan}
                  onChange={(event) => changePlan(event.target.value as TenantPlan)}
                  options={TENANT_PLANS.map((value) => ({
                    value,
                    label: `${PLAN_LABELS[value]} · ${formatMoney(PLAN_MONTHLY_PRICE_PER_BRANCH[value])} por sucursal · hasta ${pluralize(PLAN_MAX_BRANCHES[value], 'sucursal', 'sucursales')}`,
                  }))}
                />
              </Field>
              <div className="flex items-end">
                <p className="text-sm text-muted-foreground">
                  Cuota estimada:{' '}
                  <strong className="font-semibold tabular-nums text-foreground">
                    {formatMoney(PLAN_MONTHLY_PRICE_PER_BRANCH[form.plan] + extras)}
                  </strong>{' '}
                  por sucursal activa
                  {isEdit ? '' : ` · hasta ${pluralize(newLimit, 'sucursal', 'sucursales')}`}.
                </p>
              </div>
            </div>

            {planChanged ? (
              <Field label="Motivo del cambio de plan" optional error={errors.planChangeReason}>
                <Input
                  value={form.planChangeReason}
                  onChange={(event) => set('planChangeReason', event.target.value)}
                  maxLength={300}
                  placeholder="Queda en el historial del cliente."
                />
              </Field>
            ) : null}

            {downgradeWarning ? (
              <Alert tone="warn" title="Ojo con el límite de sucursales">
                El cliente tiene {pluralize(activeBranches, 'sucursal activa', 'sucursales activas')} y el plan{' '}
                {PLAN_LABELS[form.plan]} permite hasta {PLAN_MAX_BRANCHES[form.plan]}. Si guardás así, el backend va a
                rechazar el cambio: pedile que desactive las sucursales de más.
              </Alert>
            ) : null}

            {isEdit ? (
              <Alert tone="info">
                Los módulos de un cliente se cambian desde su detalle o desde la matriz de módulos, porque el cambio
                aplica al instante para todos sus usuarios.
              </Alert>
            ) : (
              <fieldset className="space-y-2">
                <legend className="text-base font-semibold text-foreground">Módulos habilitados</legend>
                <p className="text-sm text-muted-foreground">
                  Arrancan con el preset del plan {PLAN_LABELS[form.plan]}. Podés cambiarlos ahora o después.
                </p>
                <div className="space-y-2 pt-1">
                  {TENANT_MODULES.map((module) => (
                    <Checkbox
                      key={module}
                      checked={modules.includes(module)}
                      onCheckedChange={(checked) => toggleModule(module, checked === true)}
                      label={
                        <span className="flex flex-wrap items-center gap-2">
                          {TENANT_MODULE_LABELS[module]}
                          <span className="rounded-tag bg-muted px-1.5 py-0.5 font-mono text-xs font-semibold text-muted-foreground">
                            {TENANT_MODULE_MONTHLY_PRICE[module] > 0
                              ? `+${formatMoney(TENANT_MODULE_MONTHLY_PRICE[module])}/suc.`
                              : 'Sin adicional'}
                          </span>
                        </span>
                      }
                      description={TENANT_MODULE_DESCRIPTIONS[module]}
                    />
                  ))}
                </div>
                {!modules.includes('MULTI_BRANCH') ? (
                  <p className="pt-1 text-sm text-muted-foreground">
                    Sin Multi-sucursal el cliente va a poder tener una sola sucursal activa.
                  </p>
                ) : null}
              </fieldset>
            )}
          </div>
        </Card>

        {!isEdit ? (
          <>
            <Card padding="none">
              <CardHeader
                title="Primera sucursal"
                description="Todo comercio arranca con una. Si dejás los datos vacíos toma la dirección del comercio."
              />
              <div className="grid gap-4 p-4 sm:grid-cols-2 sm:p-5">
                <Field label="Nombre de la sucursal" error={errors['firstBranch.name']}>
                  <Input
                    value={form.branchName}
                    onChange={(event) => set('branchName', event.target.value)}
                    maxLength={100}
                  />
                </Field>
                <Field label="Código" optional error={errors['firstBranch.code']}>
                  <Input
                    value={form.branchCode}
                    onChange={(event) => set('branchCode', event.target.value)}
                    maxLength={20}
                    className="font-mono"
                    placeholder="CEN"
                  />
                </Field>
                <Field label="Dirección" optional error={errors['firstBranch.address']} className="sm:col-span-2">
                  <Input
                    value={form.branchAddress}
                    onChange={(event) => set('branchAddress', event.target.value)}
                    maxLength={200}
                    placeholder={form.address || 'La misma que el comercio'}
                  />
                </Field>
                <Field label="Ciudad" optional error={errors['firstBranch.city']}>
                  <Input
                    value={form.branchCity}
                    onChange={(event) => set('branchCity', event.target.value)}
                    maxLength={100}
                    placeholder={form.city || 'La misma que el comercio'}
                  />
                </Field>
                <Field label="Provincia" optional error={errors['firstBranch.province']}>
                  <Input
                    value={form.branchProvince}
                    onChange={(event) => set('branchProvince', event.target.value)}
                    maxLength={100}
                    placeholder={form.province || 'La misma que el comercio'}
                  />
                </Field>
              </div>
            </Card>

            <Card padding="none">
              <CardHeader
                title="Usuarios iniciales"
                description="Se crean las tres cuentas del comercio. El empleado queda asignado a la primera sucursal."
              />
              <div className="space-y-4 p-4 sm:p-5">
                <NewUserFields
                  name="boss"
                  title="Jefe"
                  description="Ve todo el comercio y todas las sucursales, incluidos los reportes."
                  value={form.boss}
                  onChange={(value) => set('boss', value)}
                  errors={errors}
                />
                <NewUserFields
                  name="admin"
                  title="Administrador"
                  description="Maneja catálogo, stock, usuarios y configuración del comercio."
                  value={form.admin}
                  onChange={(value) => set('admin', value)}
                  errors={errors}
                />
                <NewUserFields
                  name="employee"
                  title="Empleado"
                  description="Carga mercadería y trabaja solo en la primera sucursal."
                  value={form.employee}
                  onChange={(value) => set('employee', value)}
                  errors={errors}
                />
              </div>
            </Card>
          </>
        ) : null}

        {formError ? <Alert tone="crit">{formError}</Alert> : null}
        {Object.keys(errors).length ? (
          <Alert tone="crit" title="Revisá los campos marcados">
            Hay {pluralize(Object.keys(errors).length, 'dato', 'datos')} que el servidor no aceptó.
          </Alert>
        ) : null}

        <div className="flex flex-col-reverse gap-2 pb-2 sm:flex-row sm:justify-end">
          <Button
            variant="outline"
            onClick={() => navigate(isEdit ? `/owner/tenants/${tenantId}` : '/owner/tenants')}
            disabled={save.isPending}
          >
            Cancelar
          </Button>
          <Button type="submit" loading={save.isPending} leftIcon={<Store />}>
            {isEdit ? 'Guardar los cambios' : 'Dar de alta el cliente'}
          </Button>
        </div>
      </div>
    </form>
  );
}

function PageHeaderSection({
  isEdit,
  name,
  tenantId,
}: {
  isEdit: boolean;
  name: string | undefined;
  tenantId: number | undefined;
}) {
  return (
    <PageHeader
      eyebrow="Consola de dueños"
      title={isEdit ? `Editar ${name ?? 'cliente'}` : 'Dar de alta un cliente'}
      description={
        isEdit
          ? 'Cambiá los datos administrativos y el plan. Los módulos se manejan desde el detalle.'
          : 'Creás el comercio, su primera sucursal y las tres cuentas con las que van a entrar.'
      }
      back={
        isEdit && tenantId !== undefined
          ? { to: `/owner/tenants/${tenantId}`, label: 'Volver al cliente' }
          : { to: '/owner/tenants', label: 'Volver a clientes' }
      }
    />
  );
}
