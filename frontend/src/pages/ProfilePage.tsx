import { Building2, Eye, EyeOff, KeyRound, LogOut, ShieldCheck, Store, UserRound } from 'lucide-react';
import { useState, type FormEvent, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { authApi } from '@/api/auth';
import { ApiError, getErrorMessage } from '@/api/client';
import { BUSINESS_TYPE_LABELS, PLAN_LABELS, ROLE_LABELS, STOCK_ROTATION_LABELS } from '@/api/types';
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

const STRENGTH_COLORS = ['bg-slate-200', 'bg-red-500', 'bg-amber-500', 'bg-emerald-500'] as const;

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
            <Button variant="outline" onClick={() => logout()} leftIcon={<LogOut className="h-4 w-4" aria-hidden="true" />}>
              Cerrar sesión
            </Button>
          ) : undefined
        }
      />

      {forced && (
        <Alert tone="warning" title="Por seguridad, elegí una contraseña nueva" className="mb-6">
          Tu contraseña fue creada o restablecida por un administrador. Cambiala ahora para acceder al resto de las secciones.
        </Alert>
      )}

      <div className={forced ? 'grid gap-6' : 'grid gap-6 lg:grid-cols-5'}>
        {!forced && <AccountCard />}
        <ChangePasswordCard className={forced ? undefined : 'lg:col-span-3'} />
      </div>
    </div>
  );
}

function InfoRow({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-0.5 py-2.5 sm:flex-row sm:items-center sm:justify-between sm:gap-4">
      <dt className="text-sm text-slate-500">{label}</dt>
      <dd className="text-sm font-medium text-slate-800 sm:text-right">{children}</dd>
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
        <h2 className="mt-3 text-lg font-semibold text-slate-900">{me.fullName}</h2>
        <p className="break-all text-sm text-slate-500">{me.email}</p>
        <Badge tone="brand" className="mt-2" icon={ShieldCheck}>
          {ROLE_LABELS[me.role]}
        </Badge>
      </div>

      {tenant && (
        <>
          <dl className="mt-6 divide-y divide-slate-100 border-t border-slate-100">
            <InfoRow label="Comercio">{tenant.name}</InfoRow>
            <InfoRow label="Rubro">{BUSINESS_TYPE_LABELS[tenant.businessType] ?? tenant.businessType}</InfoRow>
            <InfoRow label="Plan">{PLAN_LABELS[tenant.plan] ?? tenant.plan}</InfoRow>
            <InfoRow label="Rotación de stock">{STOCK_ROTATION_LABELS[tenant.stockRotation] ?? tenant.stockRotation}</InfoRow>
          </dl>
          <div className="mt-4 border-t border-slate-100 pt-4">
            <p className="flex items-center gap-2 text-sm font-medium text-slate-700">
              <Building2 className="h-4 w-4 text-slate-400" aria-hidden="true" />
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
              <p className="mt-2 text-sm text-slate-500">
                Todavía no tenés sucursales asignadas. Pedile al administrador de tu comercio que te asigne una.
              </p>
            )}
          </div>
        </>
      )}
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
      className="rounded-lg p-2 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
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
        {submitError && <Alert tone="danger">{submitError}</Alert>}

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
                  className={`h-1.5 flex-1 rounded-full ${strength.score >= step ? STRENGTH_COLORS[strength.score] : 'bg-slate-200'}`}
                />
              ))}
            </div>
            <span className="w-20 text-right text-xs font-medium text-slate-500">{strength.label}</span>
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
