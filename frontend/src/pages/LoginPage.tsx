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
import { ThemeToggle } from '@/components/layout/ThemeToggle';
import { Alert, type AlertTone } from '@/components/ui/Alert';
import { Button } from '@/components/ui/Button';
import { Field } from '@/components/ui/Field';
import { Input } from '@/components/ui/Input';
import { Truncate } from '@/components/ui/Truncate';
import { canAccessPath, requiredModuleForPath } from '@/config/access';
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
    detail: 'CABA · 1 sucursal · FIFO · POS GondolIA',
    password: TENANT_PASSWORD,
    accounts: [
      { email: 'jefe@donpepe.com', role: 'TENANT_BOSS' },
      { email: 'admin@donpepe.com', role: 'TENANT_ADMIN' },
      { email: 'empleado@donpepe.com', role: 'TENANT_EMPLOYEE' },
      { email: 'cajero@donpepe.com', role: 'TENANT_CASHIER' },
    ],
  },
  {
    title: 'Dietética Vida Sana',
    detail: 'Córdoba · 2 sucursales · FEFO · POS propio',
    password: TENANT_PASSWORD,
    accounts: [
      { email: 'jefe@vidasana.com', role: 'TENANT_BOSS' },
      { email: 'admin@vidasana.com', role: 'TENANT_ADMIN' },
      { email: 'empleado@vidasana.com', role: 'TENANT_EMPLOYEE', note: 'Solo Nueva Córdoba' },
    ],
  },
  {
    title: 'Minimercado El Sol',
    detail: 'Rosario · 3 sucursales · FIFO · los 3 módulos',
    password: TENANT_PASSWORD,
    accounts: [
      { email: 'jefe@elsol.com', role: 'TENANT_BOSS' },
      { email: 'admin@elsol.com', role: 'TENANT_ADMIN' },
      { email: 'empleado@elsol.com', role: 'TENANT_EMPLOYEE', note: 'Centro y Fisherton' },
      { email: 'empleado.echesortu@elsol.com', role: 'TENANT_EMPLOYEE', note: 'Solo Echesortu' },
      { email: 'cajero@elsol.com', role: 'TENANT_CASHIER', note: 'Centro' },
      { email: 'cajero.fisherton@elsol.com', role: 'TENANT_CASHIER', note: 'Fisherton' },
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
  if (notice?.kind === 'message') return { tone: 'warn', text: notice.message };
  if (notice?.kind === 'expired' || sessionExpired) {
    return { tone: 'info', text: 'Tu sesión expiró. Volvé a iniciar sesión para continuar.' };
  }
  if (notice?.kind === 'logout') return { tone: 'ok', text: 'Cerraste sesión. ¡Hasta pronto!' };
  return null;
}

/** La pantalla de la que venía solo sirve si el rol la puede abrir y el comercio tiene su módulo. */
function canOpen(user: MeDto, pathname: string): boolean {
  if (!canAccessPath(user.role, pathname)) return false;
  const required = requiredModuleForPath(pathname);
  return !required || (user.tenant?.modules ?? []).includes(required);
}

function destinationAfterLogin(user: MeDto, from: Location | undefined): string {
  if (user.mustChangePassword) return '/profile';
  if (from && from.pathname !== '/' && canOpen(user, from.pathname)) {
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
    <div className="flex min-h-dvh bg-background">
      {/* Panel de marca: el mismo verde profundo del riel, plano y sin degradados. */}
      <aside className="relative hidden w-[46%] max-w-2xl flex-col justify-between overflow-hidden bg-rail p-10 text-rail-foreground lg:flex xl:p-14">
        <svg className="pointer-events-none absolute inset-0 h-full w-full opacity-[0.06]" aria-hidden="true">
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
            <p className="gd-eyebrow inline-flex items-center gap-2 text-rail-muted">
              <Sparkles className="h-3.5 w-3.5" aria-hidden="true" />
              Inventario inteligente para tu comercio
            </p>
            <h2 className="max-w-[16ch] font-display text-2xl font-bold leading-tight tracking-[-0.02em] text-rail-strong xl:text-3xl xl:leading-[1.05]">
              Tu negocio <span className="text-accent">siempre a tiempo</span>
            </h2>
            <p className="max-w-[46ch] text-read text-rail-foreground/80">
              Controlá productos, lotes y vencimientos, y dejá que GondolIA te diga qué hacer antes de que sea tarde.
            </p>
          </div>

          <ul className="space-y-5">
            {VALUE_BULLETS.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-3.5">
                <span className="grid h-10 w-10 shrink-0 place-items-center rounded-control bg-rail-strong/10 text-rail-strong">
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </span>
                <div>
                  <p className="font-semibold text-rail-strong">{title}</p>
                  <p className="text-base text-rail-foreground/75">{text}</p>
                </div>
              </li>
            ))}
          </ul>
        </div>

        <p className="relative text-sm text-rail-muted">
          Productos de hoy, clientes de siempre · © {new Date().getFullYear()} GondolIA
        </p>
      </aside>

      {/* Formulario */}
      <main className="relative flex flex-1 flex-col">
        {/* Tema: en móvil queda sobre el encabezado verde del riel; en escritorio, sobre el lienzo. */}
        <ThemeToggle
          className="absolute right-3 top-6 z-10 sm:right-4 lg:top-4"
          triggerClassName="text-rail-foreground hover:bg-rail-hover hover:text-rail-strong aria-expanded:bg-rail-hover aria-expanded:text-rail-strong focus-visible:ring-accent lg:text-muted-foreground lg:hover:bg-muted lg:hover:text-foreground lg:aria-expanded:bg-muted lg:aria-expanded:text-foreground lg:focus-visible:ring-ring"
        />
        <div className="bg-rail px-5 pb-14 pt-8 text-rail-foreground lg:hidden">
          <Logo variant="light" showTagline />
        </div>

        <div className="-mt-8 flex flex-1 items-start justify-center px-4 pb-10 sm:px-6 lg:mt-0 lg:items-center lg:py-12">
          <div className="w-full max-w-md">
            <div className="rounded-panel border border-border bg-card p-5 sm:p-7 lg:border-0 lg:bg-transparent lg:p-0">
              <div className="mb-6 space-y-1.5">
                <h1 className="font-display text-xl font-bold tracking-[-0.015em] text-foreground sm:text-2xl">
                  Iniciá sesión
                </h1>
                <p className="text-read text-muted-foreground">Ingresá con el email y la contraseña de tu cuenta.</p>
              </div>

              {sessionAlert && (
                <Alert tone={sessionAlert.tone} className="mb-5">
                  {sessionAlert.text}
                </Alert>
              )}
              {submitError && (
                <Alert tone="crit" title={submitError.title} className="mb-5">
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
                        className="grid h-8 w-8 place-items-center rounded-control text-muted-foreground transition-colors hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                        aria-pressed={showPassword}
                      >
                        {showPassword ? (
                          <EyeOff className="h-[18px] w-[18px]" aria-hidden="true" />
                        ) : (
                          <Eye className="h-[18px] w-[18px]" aria-hidden="true" />
                        )}
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
                  rightIcon={<LogIn aria-hidden="true" />}
                  className="!mt-6"
                >
                  {submitting ? 'Ingresando…' : 'Ingresar'}
                </Button>
              </form>

              <p className="mt-5 text-sm text-muted-foreground">
                ¿Olvidaste tu contraseña? Pedile al administrador de tu comercio que te la restablezca.
              </p>
            </div>

            {SHOW_DEMO_ACCOUNTS && (
              <section className="mt-6 overflow-hidden rounded-panel border border-border bg-card">
                <button
                  type="button"
                  onClick={() => setDemoOpen((open) => !open)}
                  aria-expanded={demoOpen}
                  aria-controls={demoPanelId}
                  className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring"
                >
                  <span className="grid h-9 w-9 shrink-0 place-items-center rounded-control bg-primary/10 text-primary">
                    <UserRound className="h-[18px] w-[18px]" aria-hidden="true" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block text-base font-semibold text-foreground">Cuentas de demostración</span>
                    <span className="block text-sm text-muted-foreground">
                      Tocá una cuenta para completar el formulario.
                    </span>
                  </span>
                  <ChevronDown
                    className={cn('h-5 w-5 shrink-0 text-muted-foreground transition-transform', demoOpen && 'rotate-180')}
                    aria-hidden="true"
                  />
                </button>

                {demoOpen && (
                  <div
                    id={demoPanelId}
                    className="gd-scroll max-h-[26rem] space-y-4 overflow-y-auto border-t border-border px-3 py-4"
                  >
                    {DEMO_GROUPS.map((group) => (
                      <div key={group.title}>
                        <div className="flex flex-wrap items-baseline justify-between gap-x-3 px-1">
                          <p className="text-base font-semibold text-foreground">{group.title}</p>
                          <p className="text-sm text-muted-foreground">{group.detail}</p>
                        </div>
                        <ul className="mt-1.5 grid gap-1.5">
                          {group.accounts.map((account) => (
                            <li key={account.email}>
                              <button
                                type="button"
                                onClick={() => fillDemo(account, group.password)}
                                className={cn(
                                  'flex w-full items-center justify-between gap-3 rounded-control border px-3 py-2 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                                  email === account.email
                                    ? 'border-primary bg-primary/[0.08]'
                                    : 'border-border bg-card hover:bg-muted',
                                )}
                              >
                                <span className="min-w-0">
                                  <Truncate className="block font-mono text-base text-foreground">
                                    {account.email}
                                  </Truncate>
                                  {account.note && (
                                    <span className="block text-sm text-muted-foreground">{account.note}</span>
                                  )}
                                </span>
                                <span className="shrink-0 rounded-tag bg-muted px-1.5 py-0.5 text-[11px] font-semibold text-muted-foreground">
                                  {ROLE_LABELS[account.role]}
                                </span>
                              </button>
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                    <p className="px-1 text-sm text-muted-foreground">
                      Contraseñas: equipo GondolIA{' '}
                      <code className="font-mono font-semibold text-foreground">{PLATFORM_PASSWORD}</code> · comercios{' '}
                      <code className="font-mono font-semibold text-foreground">{TENANT_PASSWORD}</code>
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
