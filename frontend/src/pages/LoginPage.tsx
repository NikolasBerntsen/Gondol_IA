import {
  Building2,
  CalendarClock,
  ChevronDown,
  Eye,
  EyeOff,
  Lock,
  LogIn,
  Mail,
  Sparkles,
  UserRound,
  type LucideIcon,
} from 'lucide-react';
import { useId, useRef, useState, type FormEvent } from 'react';
import { Navigate, useLocation, useNavigate, useSearchParams, type Location } from 'react-router-dom';
import { ApiError, getErrorMessage } from '@/api/client';
import { ROLE_LABELS, type MeDto, type Role } from '@/api/types';
import { useAuth, type SessionNotice } from '@/auth/AuthContext';
import { roleHome } from '@/auth/roleHome';
import { Logo } from '@/components/layout/Logo';
import { Alert, type AlertTone } from '@/components/ui/Alert';
import { Button } from '@/components/ui/Button';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { canAccessPath } from '@/config/access';
import { cn } from '@/lib/cn';

const PLATFORM_PASSWORD = 'Gondolia2026!';
const TENANT_PASSWORD = 'Demo2026!';

interface DemoAccount {
  email: string;
  role: Role;
  note?: string;
}

interface DemoGroup {
  title: string;
  detail: string;
  password: string;
  accounts: DemoAccount[];
}

/** Cuentas sembradas por el módulo de datos demo (SPEC §11). */
const DEMO_GROUPS: DemoGroup[] = [
  {
    title: 'Equipo GondolIA',
    detail: 'Consola de dueños y soporte',
    password: PLATFORM_PASSWORD,
    accounts: [
      { email: 'dueno@gondolia.app', role: 'PLATFORM_OWNER' },
      { email: 'socia@gondolia.app', role: 'PLATFORM_OWNER' },
      { email: 'soporte@gondolia.app', role: 'SUPPORT_AGENT' },
      { email: 'soporte2@gondolia.app', role: 'SUPPORT_AGENT' },
    ],
  },
  {
    title: 'Almacén Don Pepe',
    detail: 'CABA · 1 sucursal · FIFO',
    password: TENANT_PASSWORD,
    accounts: [
      { email: 'jefe@donpepe.com', role: 'TENANT_BOSS' },
      { email: 'admin@donpepe.com', role: 'TENANT_ADMIN' },
      { email: 'empleado@donpepe.com', role: 'TENANT_EMPLOYEE' },
    ],
  },
  {
    title: 'Dietética Vida Sana',
    detail: 'Córdoba · 2 sucursales · FEFO',
    password: TENANT_PASSWORD,
    accounts: [
      { email: 'jefe@vidasana.com', role: 'TENANT_BOSS' },
      { email: 'admin@vidasana.com', role: 'TENANT_ADMIN' },
      { email: 'empleado@vidasana.com', role: 'TENANT_EMPLOYEE', note: 'Solo Nueva Córdoba' },
    ],
  },
  {
    title: 'Minimercado El Sol',
    detail: 'Rosario · 3 sucursales · FIFO',
    password: TENANT_PASSWORD,
    accounts: [
      { email: 'jefe@elsol.com', role: 'TENANT_BOSS' },
      { email: 'admin@elsol.com', role: 'TENANT_ADMIN' },
      { email: 'empleado@elsol.com', role: 'TENANT_EMPLOYEE', note: 'Centro y Fisherton' },
      { email: 'empleado.echesortu@elsol.com', role: 'TENANT_EMPLOYEE', note: 'Solo Echesortu' },
    ],
  },
  {
    title: 'Kiosco La Esquina',
    detail: 'Comercio deshabilitado (muestra el bloqueo)',
    password: TENANT_PASSWORD,
    accounts: [{ email: 'admin@laesquina.com', role: 'TENANT_ADMIN' }],
  },
];

const SHOW_DEMO_ACCOUNTS = import.meta.env.VITE_SHOW_DEMO_ACCOUNTS !== 'false';

/** En celulares no se enfoca el email al entrar para no abrir el teclado encima del formulario. */
const AUTOFOCUS_EMAIL = typeof window !== 'undefined' && window.matchMedia('(min-width: 1024px)').matches;

const VALUE_BULLETS: ReadonlyArray<{ icon: LucideIcon; title: string; text: string }> = [
  {
    icon: CalendarClock,
    title: 'Vencimientos bajo control',
    text: 'Cada lote con su fecha. Te avisamos antes de que la mercadería se pierda.',
  },
  {
    icon: Sparkles,
    title: 'Recomendaciones con IA',
    text: 'Qué reponer, cuándo pedir y qué descuento aplicar para vender a tiempo.',
  },
  {
    icon: Building2,
    title: 'Todas tus sucursales',
    text: 'Stock, ventas y alertas por local o consolidado, en tiempo real.',
  },
];

interface FormErrors {
  email?: string;
  password?: string;
}

function noticeAlert(notice: SessionNotice | null, sessionExpired: boolean): { tone: AlertTone; text: string } | null {
  if (notice?.kind === 'message') return { tone: 'warning', text: notice.message };
  if (notice?.kind === 'expired' || sessionExpired) {
    return { tone: 'info', text: 'Tu sesión expiró. Volvé a iniciar sesión para continuar.' };
  }
  if (notice?.kind === 'logout') return { tone: 'success', text: 'Cerraste sesión. ¡Hasta pronto!' };
  return null;
}

function destinationAfterLogin(user: MeDto, from: Location | undefined): string {
  if (user.mustChangePassword) return '/profile';
  if (from && from.pathname !== '/' && canAccessPath(user.role, from.pathname)) {
    return `${from.pathname}${from.search}${from.hash}`;
  }
  return roleHome(user.role);
}

export default function LoginPage() {
  const { status, me, login, notice } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const from = (location.state as { from?: Location } | null)?.from;

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitError, setSubmitError] = useState<{ title: string; message: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [demoOpen, setDemoOpen] = useState(false);
  const passwordRef = useRef<HTMLInputElement>(null);
  const submitRef = useRef<HTMLButtonElement>(null);
  const demoPanelId = useId();

  if (status === 'authenticated' && me && !submitting) {
    return <Navigate to={destinationAfterLogin(me, from)} replace />;
  }

  const sessionAlert = submitError ? null : noticeAlert(notice, searchParams.get('motivo') === 'sesion');

  const validate = (): FormErrors => {
    const next: FormErrors = {};
    if (!email.trim()) next.email = 'Ingresá tu email.';
    else if (!/^\S+@\S+\.\S+$/.test(email.trim())) next.email = 'Revisá el formato del email.';
    if (!password) next.password = 'Ingresá tu contraseña.';
    return next;
  };

  const onSubmit = async (event: FormEvent) => {
    event.preventDefault();
    const validation = validate();
    setErrors(validation);
    setSubmitError(null);
    if (validation.email || validation.password) {
      (validation.email ? document.getElementById('login-email') : passwordRef.current)?.focus();
      return;
    }
    setSubmitting(true);
    try {
      const user = await login(email, password);
      navigate(destinationAfterLogin(user, from), { replace: true });
    } catch (error) {
      const blocked = error instanceof ApiError && error.is('TENANT_DISABLED', 'TENANT_CANCELLED', 'USER_DISABLED');
      setSubmitError({
        title: blocked ? 'No podés ingresar' : 'No pudimos iniciar sesión',
        message:
          error instanceof ApiError && error.is('BAD_CREDENTIALS')
            ? error.message || 'El email o la contraseña no son correctos.'
            : getErrorMessage(error),
      });
      setSubmitting(false);
      passwordRef.current?.select();
    }
  };

  const fillDemo = (account: DemoAccount, groupPassword: string) => {
    setEmail(account.email);
    setPassword(groupPassword);
    setErrors({});
    setSubmitError(null);
    submitRef.current?.focus();
  };

  return (
    <div className="flex min-h-dvh bg-app">
      {/* Panel de marca */}
      <aside className="relative hidden w-[46%] max-w-2xl flex-col justify-between overflow-hidden bg-gradient-to-br from-brand-800 via-brand-900 to-brand-950 p-10 text-white lg:flex xl:p-14">
        <div className="pointer-events-none absolute -right-24 -top-24 h-80 w-80 rounded-full bg-brand-500/20 blur-3xl" aria-hidden="true" />
        <div className="pointer-events-none absolute -bottom-32 -left-20 h-96 w-96 rounded-full bg-lime-300/10 blur-3xl" aria-hidden="true" />
        <svg className="pointer-events-none absolute inset-0 h-full w-full opacity-[0.07]" aria-hidden="true">
          <defs>
            <pattern id="login-dots" width="28" height="28" patternUnits="userSpaceOnUse">
              <circle cx="2" cy="2" r="1.5" fill="currentColor" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#login-dots)" />
        </svg>

        <Logo variant="light" size="lg" className="relative" />

        <div className="relative space-y-10">
          <div className="space-y-4">
            <p className="inline-flex items-center gap-2 rounded-full bg-white/10 px-3 py-1 text-xs font-semibold uppercase tracking-wider text-lime-200 ring-1 ring-inset ring-white/15">
              <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
              Inventario inteligente para tu comercio
            </p>
            <h2 className="text-4xl font-bold leading-tight tracking-tight xl:text-5xl">
              Tu negocio <span className="text-lime-300">siempre a tiempo</span>
            </h2>
            <p className="max-w-md text-base text-brand-100/80">
              Controlá productos, lotes y vencimientos, y dejá que GondolIA te diga qué hacer antes de que sea tarde.
            </p>
          </div>

          <ul className="space-y-5">
            {VALUE_BULLETS.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-4">
                <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-white/10 text-lime-300 ring-1 ring-inset ring-white/10">
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </span>
                <div>
                  <p className="font-semibold">{title}</p>
                  <p className="text-sm text-brand-100/75">{text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <p className="relative text-sm text-brand-200/70">Productos de hoy, clientes de siempre · © {new Date().getFullYear()} GondolIA</p>
      </aside>

      {/* Formulario */}
      <main className="flex flex-1 flex-col">
        <div className="bg-gradient-to-br from-brand-800 to-brand-950 px-5 pb-16 pt-8 text-white lg:hidden">
          <Logo variant="light" showTagline />
        </div>

        <div className="-mt-10 flex flex-1 items-start justify-center px-4 pb-10 sm:px-6 lg:mt-0 lg:items-center lg:py-12">
          <div className="w-full max-w-md">
            <div className="rounded-3xl border border-slate-200/70 bg-white p-6 shadow-xl shadow-slate-900/5 sm:p-8 lg:border-0 lg:bg-transparent lg:p-0 lg:shadow-none">
              <div className="mb-6 space-y-1.5">
                <h1 className="text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">Iniciá sesión</h1>
                <p className="text-sm text-slate-500 sm:text-base">Ingresá con el email y la contraseña de tu cuenta.</p>
              </div>

              {sessionAlert && (
                <Alert tone={sessionAlert.tone} className="mb-5">
                  {sessionAlert.text}
                </Alert>
              )}
              {submitError && (
                <Alert tone="danger" title={submitError.title} className="mb-5">
                  {submitError.message}
                </Alert>
              )}

              <form onSubmit={onSubmit} noValidate className="space-y-4">
                <Field label="Email" error={errors.email} required>
                  <Input
                    id="login-email"
                    type="email"
                    inputMode="email"
                    autoComplete="username"
                    autoCapitalize="none"
                    spellCheck={false}
                    placeholder="nombre@comercio.com"
                    inputSize="lg"
                    leftIcon={<Mail aria-hidden="true" />}
                    value={email}
                    onChange={(event) => {
                      setEmail(event.target.value);
                      if (errors.email) setErrors((prev) => ({ ...prev, email: undefined }));
                    }}
                    autoFocus={AUTOFOCUS_EMAIL}
                  />
                </Field>

                <Field label="Contraseña" error={errors.password} required>
                  <Input
                    ref={passwordRef}
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    placeholder="Tu contraseña"
                    inputSize="lg"
                    leftIcon={<Lock aria-hidden="true" />}
                    value={password}
                    onChange={(event) => {
                      setPassword(event.target.value);
                      if (errors.password) setErrors((prev) => ({ ...prev, password: undefined }));
                    }}
                    rightElement={
                      <button
                        type="button"
                        onClick={() => setShowPassword((value) => !value)}
                        className="rounded-lg p-2 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500"
                        aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                        aria-pressed={showPassword}
                      >
                        {showPassword ? <EyeOff className="h-5 w-5" aria-hidden="true" /> : <Eye className="h-5 w-5" aria-hidden="true" />}
                      </button>
                    }
                  />
                </Field>

                <Button
                  ref={submitRef}
                  type="submit"
                  size="lg"
                  fullWidth
                  loading={submitting}
                  rightIcon={<LogIn className="h-5 w-5" aria-hidden="true" />}
                  className="!mt-6"
                >
                  {submitting ? 'Ingresando…' : 'Ingresar'}
                </Button>
              </form>

              <p className="mt-5 text-center text-xs text-slate-500">
                ¿Olvidaste tu contraseña? Pedile al administrador de tu comercio que te la restablezca.
              </p>
            </div>

            {SHOW_DEMO_ACCOUNTS && (
              <section className="mt-6 overflow-hidden rounded-2xl border border-brand-200 bg-brand-50/70">
                <button
                  type="button"
                  onClick={() => setDemoOpen((open) => !open)}
                  aria-expanded={demoOpen}
                  aria-controls={demoPanelId}
                  className="flex w-full items-center gap-3 px-4 py-3 text-left transition hover:bg-brand-100/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-brand-500"
                >
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-white text-brand-700 shadow-sm">
                    <UserRound className="h-5 w-5" aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-semibold text-brand-900">Cuentas de demostración</span>
                    <span className="block text-xs text-brand-700/80">Tocá una cuenta para completar el formulario.</span>
                  </span>
                  <ChevronDown
                    className={cn('h-5 w-5 shrink-0 text-brand-700 transition-transform', demoOpen && 'rotate-180')}
                    aria-hidden="true"
                  />
                </button>

                {demoOpen && (
                  <div id={demoPanelId} className="max-h-[26rem] space-y-4 overflow-y-auto border-t border-brand-200 bg-white/70 px-3 py-4">
                    {DEMO_GROUPS.map((group) => (
                      <div key={group.title}>
                        <div className="flex flex-wrap items-baseline justify-between gap-x-3 px-1">
                          <p className="text-sm font-semibold text-slate-800">{group.title}</p>
                          <p className="text-xs text-slate-500">{group.detail}</p>
                        </div>
                        <ul className="mt-1.5 grid gap-1.5">
                          {group.accounts.map((account) => (
                            <li key={account.email}>
                              <button
                                type="button"
                                onClick={() => fillDemo(account, group.password)}
                                className={cn(
                                  'flex w-full items-center justify-between gap-3 rounded-xl border px-3 py-2 text-left transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand-500',
                                  email === account.email
                                    ? 'border-brand-400 bg-brand-50'
                                    : 'border-slate-200 bg-white hover:border-brand-300 hover:bg-brand-50/50',
                                )}
                              >
                                <span className="min-w-0">
                                  <span className="block truncate text-sm font-medium text-slate-800">{account.email}</span>
                                  {account.note && <span className="block text-xs text-slate-500">{account.note}</span>}
                                </span>
                                <span className="shrink-0 rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600">
                                  {ROLE_LABELS[account.role]}
                                </span>
                              </button>
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                    <p className="px-1 text-xs text-slate-500">
                      Contraseñas: equipo GondolIA <code className="font-semibold text-slate-700">{PLATFORM_PASSWORD}</code> ·
                      comercios <code className="font-semibold text-slate-700">{TENANT_PASSWORD}</code>
                    </p>
                  </div>
                )}
              </section>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}
