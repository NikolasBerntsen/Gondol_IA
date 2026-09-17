import { Home, ShieldX } from 'lucide-react';
import { ROLE_LABELS } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { roleHome } from '@/auth/roleHome';
import { ButtonLink } from '@/components/ui/Button';

export default function ForbiddenPage() {
  const { me } = useAuth();
  const isTenantUser = me?.tenant != null;

  return (
    <div role="alert" className="flex min-h-[60vh] flex-col items-center justify-center px-4 text-center">
      <span className="flex h-16 w-16 items-center justify-center rounded-3xl bg-red-50 text-red-600">
        <ShieldX className="h-8 w-8" aria-hidden="true" />
      </span>
      <p className="mt-6 text-sm font-semibold uppercase tracking-widest text-red-600">Acceso denegado</p>
      <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">No tenés acceso a esta sección</h1>
      <p className="mt-2 max-w-md text-sm text-slate-500 sm:text-base">
        {me ? (
          <>
            Tu rol <span className="font-semibold text-slate-700">{ROLE_LABELS[me.role]}</span> no tiene permisos para ver
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
        className="mt-6"
        leftIcon={<Home className="h-4 w-4" aria-hidden="true" />}
      >
        {me ? 'Ir a mi inicio' : 'Iniciar sesión'}
      </ButtonLink>
    </div>
  );
}
