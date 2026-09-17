import { AlertTriangle, RotateCw } from 'lucide-react';
import { Component, type ErrorInfo, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';

interface RouteErrorBoundaryProps {
  /** Al cambiar (p. ej. la ruta) se descarta el error y se vuelve a intentar mostrar la página. */
  resetKey: string;
  children: ReactNode;
}

interface RouteErrorBoundaryState {
  error: Error | null;
}

/** Nombres de error de los navegadores cuando falla la descarga de un chunk (p. ej. tras un redeploy). */
function isChunkLoadError(error: Error): boolean {
  return /Loading chunk|dynamically imported module|Importing a module script failed|ChunkLoadError/i.test(
    `${error.name} ${error.message}`,
  );
}

/**
 * Evita que un error de render de una página deje la app en blanco. Se reinicia cuando cambia `resetKey`
 * (la ruta) sin remontar la página: así pantallas maestro-detalle como `/support/tickets/:id` conservan su estado.
 */
export class RouteErrorBoundary extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  state: RouteErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): RouteErrorBoundaryState {
    return { error };
  }

  componentDidUpdate(previous: RouteErrorBoundaryProps) {
    if (this.state.error && previous.resetKey !== this.props.resetKey) {
      this.setState({ error: null });
    }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Error al mostrar la página', error, info.componentStack);
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;

    const chunk = isChunkLoadError(error);
    return (
      <div role="alert" className="flex min-h-[50vh] flex-col items-center justify-center gap-4 px-6 text-center">
        <span className="grid h-12 w-12 place-items-center rounded-control bg-crit-soft text-crit-ink">
          <AlertTriangle className="h-6 w-6" aria-hidden="true" />
        </span>
        <div className="max-w-md space-y-1">
          <h2 className="font-display text-lg font-semibold text-foreground">
            {chunk ? 'Hay una versión nueva de GondolIA' : 'Algo salió mal al mostrar esta sección'}
          </h2>
          <p className="text-base text-muted-foreground">
            {chunk
              ? 'Recargá la página para seguir trabajando con la última versión.'
              : 'Probá recargar la página. Si el problema sigue, escribinos desde Soporte.'}
          </p>
        </div>
        <Button onClick={() => window.location.reload()} leftIcon={<RotateCw aria-hidden="true" />}>
          Recargar página
        </Button>
      </div>
    );
  }
}
