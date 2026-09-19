import { Blocks, Building2, Check, Eye, EyeOff, KeyRound, LogOut, Palette, ShieldCheck, Store, UserRound } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { authApi } from '@/api/auth';
import { ApiError, getErrorMessage } from '@/api/client';
import {
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  ROLE_LABELS,
  STOCK_ROTATION_LABELS,
  TENANT_MODULE_LABELS,
} from '@/api/types';
import { useAuth, useCurrentUser } from '@/auth/AuthContext';
import { roleHome } from '@/auth/roleHome';
import { Alert } from '@/components/ui/Alert';
import { Avatar } from '@/components/ui/Avatar';
import { Badge } from '@/components/ui/Badge';
import { Button } from '@/components/ui/Button';
import { Card, CardHeader } from '@/components/ui/Card';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { PageHeader } from '@/components/ui/PageHeader';
import { cn } from '@/lib/cn';
import { useModules } from '@/modules/useModules';
import { RESOLVED_THEME_LABELS, THEME_OPTIONS, useTheme } from '@/theme';

const MIN_PASSWORD_LENGTH = 8;

interface PasswordForm {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}

type PasswordErrors = Partial<Record<keyof PasswordForm, string>>;

const EMPTY_FORM: PasswordForm = { currentPassword: '', newPassword: '', confirmPassword: '' };

function passwordStrength(password: string): { score: 0 | 1 | 2 | 3; label: string } {
  if (!password) return { score: 0, label: '' };
  let score = 0;
  if (password.length >= MIN_PASSWORD_LENGTH) score += 1;
  if (password.length >= 12) score += 1;
  if (/[a-z]/.test(password) && /[A-Z]/.test(password)) score += 1;
  if (/\d/.test(password) && /[^A-Za-z0-9]/.test(password)) score += 1;
  if (password.length < MIN_PASSWORD_LENGTH) return { score: 1, label: 'Muy corta' };
  if (score <= 2) return { score: 1, label: 'Débil' };
  if (score === 3) return { score: 2, label: 'Aceptable' };
  return { score: 3, label: 'Fuerte' };
}

const STRENGTH_COLORS = ['bg-border', 'bg-crit', 'bg-warn', 'bg-ok'] as const;

export default function ProfilePage() {
  const me = useCurrentUser();
  const { logout } = useAuth();
  const forced = me.mustChangePassword;

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader
        title={forced ? 'Cambiá tu contraseña' : 'Mi perfil'}
        description={forced ? 'Es necesario para seguir usando GondolIA.' : 'Tus datos y la seguridad de tu cuenta.'}
        icon={forced ? KeyRound : UserRound}
        actions={
          forced ? (
            <Button variant="outline" onClick={() => logout()} leftIcon={<LogOut aria-hidden="true" />}>
              Cerrar sesión
            </Button>
          ) : undefined
        }
      />

      {forced && (
        <Alert tone="warn" title="Por seguridad, elegí una contraseña nueva" className="mb-6">
          Tu contraseña fue creada o restablecida por un administrador. Cambiala ahora para acceder al resto de las secciones.
        </Alert>
      )}

      {forced ? (
        <ChangePasswordCard />
      ) : (
        <div className="grid items-start gap-6 lg:grid-cols-5">
          <AccountCard />
          <div className="grid gap-6 lg:col-span-3">
            <ChangePasswordCard />
            <AppearanceCard />
          </div>
        </div>
      )}
    </div>
  );
}

function InfoRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
      <dt className="text-base text-muted-foreground">{label}</dt>
      <dd className="text-base font-medium text-foreground sm:text-right">{children}</dd>
    </div>
  );
}

function AccountCard() {
  const me = useCurrentUser();
  const tenant = me.tenant;
  const seesAllBranches = me.role === 'TENANT_ADMIN' || me.role === 'TENANT_BOSS';

  return (
    <Card className="lg:col-span-2">
      <div className="flex flex-col items-center text-center">
        <Avatar name={me.fullName} size="xl" />
        <h2 className="mt-3 font-display text-lg font-semibold text-foreground">{me.fullName}</h2>
        <p className="break-all text-base text-muted-foreground">{me.email}</p>
        <Badge tone="primary" className="mt-2" icon={ShieldCheck}>
          {ROLE_LABELS[me.role]}
        </Badge>
      </div>

      {tenant && (
        <>
          <dl className="mt-6 divide-y divide-border border-t border-border">
            <InfoRow label="Comercio">{tenant.name}</InfoRow>
            <InfoRow label="Rubro">{BUSINESS_TYPE_LABELS[tenant.businessType] ?? tenant.businessType}</InfoRow>
            <InfoRow label="Plan">{PLAN_LABELS[tenant.plan] ?? tenant.plan}</InfoRow>
            <InfoRow label="Rotación de stock">{STOCK_ROTATION_LABELS[tenant.stockRotation] ?? tenant.stockRotation}</InfoRow>
          </dl>
          <ModulesRow />
          <div className="mt-4 border-t border-border pt-4">
            <p className="flex items-center gap-2 text-base font-semibold text-foreground">
              <Building2 className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
              {seesAllBranches ? 'Acceso a todas las sucursales' : 'Tus sucursales asignadas'}
            </p>
            {me.branches.length > 0 ? (
              <ul className="mt-2 flex flex-wrap gap-1.5">
                {me.branches.map((branch) => (
                  <li key={branch.id}>
                    <Badge tone="neutral" icon={Store}>
                      {branch.name}
                    </Badge>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="mt-2 text-base text-muted-foreground">
                Todavía no tenés sucursales asignadas. Pedile al administrador de tu comercio que te asigne una.
              </p>
            )}
          </div>
        </>
      )}
    </Card>
  );
}

/** Funciones que los dueños de GondolIA habilitaron para el comercio (SPEC §14). */
function ModulesRow() {
  const { modules } = useModules();
  return (
    <div className="mt-4 border-t border-border pt-4">
      <p className="flex items-center gap-2 text-base font-semibold text-foreground">
        <Blocks className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
        Funciones habilitadas
      </p>
      {modules.length > 0 ? (
        <ul className="mt-2 flex flex-wrap gap-1.5">
          {modules.map((module) => (
            <li key={module}>
              <Badge tone="primary">{TENANT_MODULE_LABELS[module]}</Badge>
            </li>
          ))}
        </ul>
      ) : (
        <p className="mt-2 text-base text-muted-foreground">
          Tu comercio usa GondolIA con las funciones incluidas. Escribinos si querés sumar el punto de venta.
        </p>
      )}
    </div>
  );
}

/**
 * Tema de la app (docs/frontend-guide.md §7.1): Sistema / Claro / Oscuro. Es el mismo ajuste que el botón de la
 * barra superior; se guarda en este navegador.
 */
function AppearanceCard() {
  const { preference, resolved, setPreference } = useTheme();

  return (
    <Card>
      <CardHeader
        icon={Palette}
        title="Apariencia"
        description="Elegí cómo se ve GondolIA. Se guarda en este navegador: en otro dispositivo podés elegir otro."
      />
      <div role="radiogroup" aria-label="Tema" className="mt-5 grid gap-3 sm:grid-cols-3">
        {THEME_OPTIONS.map((option) => {
          const selected = option.value === preference;
          const Icon = option.icon;
          return (
            <label
              key={option.value}
              className={cn(
                'relative flex cursor-pointer flex-col gap-3 rounded-panel border p-4 transition-colors has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-2 has-[:focus-visible]:ring-offset-card',
                selected ? 'border-primary bg-primary/5' : 'border-border hover:bg-muted',
              )}
            >
              <input
                type="radio"
                name="theme-preference"
                className="sr-only"
                value={option.value}
                checked={selected}
                onChange={() => setPreference(option.value)}
              />
              <span className="flex items-center justify-between gap-3">
                <span
                  className={cn(
                    'grid size-9 shrink-0 place-items-center rounded-control',
                    selected ? 'bg-primary text-primary-foreground' : 'bg-muted text-muted-foreground',
                  )}
                >
                  <Icon className="size-5" aria-hidden="true" />
                </span>
                <span
                  className={cn(
                    'grid size-5 shrink-0 place-items-center rounded-full border',
                    selected ? 'border-primary bg-primary text-primary-foreground' : 'border-input',
                  )}
                  aria-hidden="true"
                >
                  {selected && <Check className="size-3.5" />}
                </span>
              </span>
              <span className="min-w-0">
                <span className="block text-md font-semibold text-foreground">{option.label}</span>
                <span className="mt-0.5 block text-sm text-muted-foreground">
                  {option.description}
                  {option.value === 'system' && ` Ahora se ve ${RESOLVED_THEME_LABELS[resolved]}.`}
                </span>
              </span>
            </label>
          );
        })}
      </div>
    </Card>
  );
}

function ChangePasswordCard({ className }: { className?: string }) {
  const me = useCurrentUser();
  const { updateToken, refreshMe, logout } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState<PasswordForm>(EMPTY_FORM);
  const [errors, setErrors] = useState<PasswordErrors>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [visible, setVisible] = useState(false);
  const strength = passwordStrength(form.newPassword);

  const update = (field: keyof PasswordForm) => (value: string) => {
    setForm((prev) => ({ ...prev, [field]: value }));
    if (errors[field]) setErrors((prev) => ({ ...prev, [field]: undefined }));
  };

  const validate = (): PasswordErrors => {
    const next: PasswordErrors = {};
    if (!form.currentPassword) next.currentPassword = 'Ingresá tu contraseña actual.';
    if (!form.newPassword) next.newPassword = 'Ingresá la contraseña nueva.';
    else if (form.newPassword.length < MIN_PASSWORD_LENGTH)
      next.newPassword = `Tiene que tener al menos ${MIN_PASSWORD_LENGTH} caracteres.`;
    else if (form.newPassword === form.currentPassword) next.newPassword = 'Tiene que ser distinta de la actual.';
    if (!form.confirmPassword) next.confirmPassword = 'Repetí la contraseña nueva.';
    else if (form.confirmPassword !== form.newPassword) next.confirmPassword = 'Las contraseñas no coinciden.';
    return next;
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    setSubmitError(null);
    const validation = validate();
    setErrors(validation);
    if (Object.values(validation).some(Boolean)) return;

    setSaving(true);
    try {
      const response = await authApi.changePassword({
        currentPassword: form.currentPassword,
        newPassword: form.newPassword,
      });
      if (response && typeof response === 'object' && response.token) {
        updateToken(response.token);
        const wasForced = me.mustChangePassword;
        await refreshMe().catch(() => null);
        setForm(EMPTY_FORM);
        toast.success('Actualizaste tu contraseña.');
        if (wasForced) navigate(roleHome(me.role), { replace: true });
      } else {
        // Sin token nuevo el actual quedó invalidado: hay que volver a ingresar.
        logout('Actualizaste tu contraseña. Iniciá sesión con la nueva.');
      }
    } catch (error) {
      if (error instanceof ApiError) {
        const fieldErrors = error.fieldErrorMap;
        if (fieldErrors.currentPassword || fieldErrors.newPassword) {
          setErrors({ currentPassword: fieldErrors.currentPassword, newPassword: fieldErrors.newPassword });
        } else if (error.status === 401 || error.is('BAD_CREDENTIALS', 'INVALID_CURRENT_PASSWORD', 'WRONG_PASSWORD')) {
          setErrors({ currentPassword: error.message || 'La contraseña actual no es correcta.' });
        } else if (error.is('SAME_PASSWORD')) {
          setErrors({ newPassword: error.message || 'Tiene que ser distinta de la actual.' });
        } else {
          setSubmitError(getErrorMessage(error));
        }
      } else {
        setSubmitError(getErrorMessage(error));
      }
    } finally {
      setSaving(false);
    }
  };

  const toggle = (
    <button
      type="button"
      onClick={() => setVisible((value) => !value)}
      className="grid h-8 w-8 place-items-center rounded-control text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      aria-label={visible ? 'Ocultar contraseñas' : 'Mostrar contraseñas'}
      aria-pressed={visible}
    >
      {visible ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
    </button>
  );

  return (
    <Card className={className}>
      <CardHeader
        icon={KeyRound}
        title="Cambiar contraseña"
        description={`Usá al menos ${MIN_PASSWORD_LENGTH} caracteres. Te recomendamos combinar letras, números y símbolos.`}
      />
      <form onSubmit={onSubmit} noValidate className="mt-5 space-y-4">
        {submitError && <Alert tone="crit">{submitError}</Alert>}

        {/* Ayuda a los gestores de contraseñas a asociar la contraseña nueva a la cuenta. */}
        <input type="email" name="username" autoComplete="username" value={me.email} readOnly hidden />

        <Field label="Contraseña actual" error={errors.currentPassword} required>
          <Input
            type={visible ? 'text' : 'password'}
            autoComplete="current-password"
            value={form.currentPassword}
            onChange={(event) => update('currentPassword')(event.target.value)}
            rightElement={toggle}
          />
        </Field>

        <Field label="Contraseña nueva" error={errors.newPassword} required>
          <Input
            type={visible ? 'text' : 'password'}
            autoComplete="new-password"
            value={form.newPassword}
            onChange={(event) => update('newPassword')(event.target.value)}
          />
        </Field>
        {form.newPassword && (
          <div className="-mt-2 flex items-center gap-3" aria-live="polite">
            <div className="flex flex-1 gap-1.5" aria-hidden="true">
              {[1, 2, 3].map((step) => (
                <span
                  key={step}
                  className={`h-1.5 flex-1 rounded-full ${strength.score >= step ? STRENGTH_COLORS[strength.score] : 'bg-border'}`}
                />
              ))}
            </div>
            <span className="w-20 text-right text-xs font-medium text-muted-foreground">{strength.label}</span>
          </div>
        )}

        <Field label="Repetí la contraseña nueva" error={errors.confirmPassword} required>
          <Input
            type={visible ? 'text' : 'password'}
            autoComplete="new-password"
            value={form.confirmPassword}
            onChange={(event) => update('confirmPassword')(event.target.value)}
          />
        </Field>

        <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
          <Button type="submit" loading={saving} leftIcon={<KeyRound className="h-4 w-4" aria-hidden="true" />}>
            Guardar contraseña
          </Button>
        </div>
      </form>
    </Card>
  );
}
