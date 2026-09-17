import { Home, ShieldX } from 'lucide-react';
import { ROLE_LABELS } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { roleHome } from '@/auth/roleHome';
import { useShellLayout } from '@/components/layout/shellLayout';
import { ButtonLink } from '@/components/ui/Button';
import { cn } from '@/lib/cn';

export default function ForbiddenPage() {
  const { me } = useAuth();
  const isTenantUser = me?.tenant != null;
  // Fuera del AppShell (ruta del ticket) o en la variante compacta nadie puso el padding de página.
  const { compact, inShell } = useShellLayout();

  return (
    <div
      role="alert"
      className={cn(
        'flex min-h-[60vh] flex-col items-start justify-center gap-3',
        (compact || !inShell) && 'mx-auto w-full max-w-7xl px-4 sm:px-6 lg:px-8',
      )}
    >
      <span className="grid h-12 w-12 place-items-center rounded-control bg-crit-soft text-crit-ink">
        <ShieldX className="h-6 w-6" aria-hidden="true" />
      </span>
      <p className="gd-eyebrow text-crit-ink">Acceso denegado</p>
      <h1 className="font-display text-xl font-bold tracking-[-0.015em] text-foreground sm:text-2xl">
        No tenés acceso a esta sección
      </h1>
      <p className="max-w-[52ch] text-read text-muted-foreground">
        {me ? (
          <>
            Tu rol <span className="font-semibold text-foreground">{ROLE_LABELS[me.role]}</span> no tiene permisos para ver
            esta pantalla.{' '}
            {isTenantUser
              ? 'Si necesitás usarla, pedíselo al administrador de tu comercio.'
              : 'Si creés que es un error, hablalo con el equipo de GondolIA.'}
          </>
        ) : (
          'Iniciá sesión con una cuenta que tenga permisos para ver esta pantalla.'
        )}
      </p>
      <ButtonLink
        to={me ? roleHome(me.role) : '/login'}
        className="mt-1"
        leftIcon={<Home aria-hidden="true" />}
      >
        {me ? 'Ir a mi inicio' : 'Iniciar sesión'}
      </ButtonLink>
    </div>
  );
}
