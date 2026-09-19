import { Home, ShieldX } from 'lucide-react';
import { useLocation } from 'react-router-dom';
import { ROLE_LABELS, isTenantRole, type Role } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { roleHome } from '@/auth/roleHome';
import { useShellLayout } from '@/components/layout/shellLayout';
import { ButtonLink } from '@/components/ui/Button';
import { rolesForPath } from '@/config/access';
import { cn } from '@/lib/cn';
import { useDocumentTitle } from '@/lib/documentTitle';

/**
 * Qué hacer cuando `role` no puede abrir `pathname`, según de quién es la pantalla (SPEC §3.3, §9.5). Solo se manda
 * a "pedíselo al administrador" a quien tiene un administrador que se lo pueda dar: al propio administrador, o a
 * cualquiera que entra a la consola de GondolIA, eso no le sirve de nada.
 */
export function forbiddenHint(role: Role, pathname: string): string {
  const forTenants = rolesForPath(pathname).some(isTenantRole);
  if (isTenantRole(role)) {
    if (!forTenants) return 'Esta sección es del equipo de GondolIA y no forma parte de la gestión de tu comercio.';
    if (role === 'TENANT_ADMIN') return 'Si creés que es un error, escribinos desde Soporte.';
    return 'Si necesitás usarla, pedíselo al administrador de tu comercio.';
  }
  if (forTenants) return 'Es una pantalla de los comercios: la usan sus propios equipos.';
  return 'Es una sección de otro rol del equipo de GondolIA.';
}

export default function ForbiddenPage() {
  const { me } = useAuth();
  const { pathname } = useLocation();
  // Fuera del AppShell (ruta del ticket) o en la variante compacta nadie puso el padding de página.
  const { compact, inShell } = useShellLayout();
  useDocumentTitle('Acceso denegado');

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
            esta pantalla. {forbiddenHint(me.role, pathname)}
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
