import { Home, SearchX } from 'lucide-react';
import { ButtonLink } from '@/components/ui/Button';

export default function NotFoundPage() {
  return (
    <div className="flex min-h-[60vh] flex-col items-center justify-center px-4 text-center">
      <span className="flex h-16 w-16 items-center justify-center rounded-3xl bg-brand-100 text-brand-700">
        <SearchX className="h-8 w-8" aria-hidden="true" />
      </span>
      <p className="mt-6 text-sm font-semibold uppercase tracking-widest text-brand-600">Error 404</p>
      <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">No encontramos esta página</h1>
      <p className="mt-2 max-w-md text-sm text-slate-500 sm:text-base">
        Puede que el enlace esté mal escrito o que la sección ya no exista.
      </p>
      <ButtonLink to="/" className="mt-6" leftIcon={<Home className="h-4 w-4" aria-hidden="true" />}>
        Ir al inicio
      </ButtonLink>
    </div>
  );
}
