import { Loader2, WifiOff } from 'lucide-react';
import type { ReactNode } from 'react';
import { Button } from '@/components/ui/Button';
import { Logo } from './Logo';

export interface SplashScreenProps {
  label?: string;
}

/** Pantalla completa de carga (validación de sesión, primer chunk). */
export function SplashScreen({ label = 'Cargando…' }: SplashScreenProps) {
  return (
    <div role="status" className="flex min-h-dvh flex-col items-center justify-center gap-6 bg-background px-4">
      <Logo variant="dark" size="lg" />
      <div className="flex items-center gap-2 text-base text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" />
        <span>{label}</span>
      </div>
    </div>
  );
}

export interface SplashErrorProps {
  title?: string;
  message: ReactNode;
  onRetry: () => void;
  onLogout?: () => void;
}

/** Pantalla completa cuando no se pudo validar la sesión (p. ej. servidor caído). */
export function SplashError({ title = 'No pudimos conectarnos con GondolIA', message, onRetry, onLogout }: SplashErrorProps) {
  return (
    <div role="alert" className="flex min-h-dvh flex-col items-center justify-center gap-6 bg-background px-4 text-center">
      <Logo variant="dark" size="lg" />
      <div className="w-full max-w-md rounded-panel border border-border bg-card p-6">
        <span className="mx-auto grid h-12 w-12 place-items-center rounded-control bg-crit-soft text-crit-ink">
          <WifiOff className="h-6 w-6" aria-hidden="true" />
        </span>
        <h1 className="mt-4 font-display text-lg font-semibold text-foreground">{title}</h1>
        <p className="mt-1 text-base text-muted-foreground">{message}</p>
        <div className="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-center">
          {onLogout && (
            <Button variant="outline" onClick={onLogout}>
              Cerrar sesión
            </Button>
          )}
          <Button onClick={onRetry}>Reintentar</Button>
        </div>
      </div>
    </div>
  );
}
