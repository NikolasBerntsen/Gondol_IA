import { Home, SearchX } from 'lucide-react';
import { ButtonLink } from '@/components/ui/Button';

export default function NotFoundPage() {
  return (
    <div className="flex min-h-[60vh] flex-col items-start justify-center gap-3">
      <span className="grid h-12 w-12 place-items-center rounded-control border border-dashed border-input bg-muted text-muted-foreground">
        <SearchX className="h-6 w-6" aria-hidden="true" />
      </span>
      <p className="gd-eyebrow">Error 404</p>
      <h1 className="font-display text-xl font-bold tracking-[-0.015em] text-foreground sm:text-2xl">
        No encontramos esta página
      </h1>
      <p className="max-w-[52ch] text-read text-muted-foreground">
        Puede que el enlace esté mal escrito o que la sección ya no exista.
      </p>
      <ButtonLink to="/" className="mt-1" leftIcon={<Home aria-hidden="true" />}>
        Ir al inicio
      </ButtonLink>
    </div>
  );
}
