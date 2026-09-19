import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';

/** Nombre de la app: título por defecto y sufijo de cada pantalla. */
export const APP_NAME = 'GondolIA';

/** "Alertas" → "Alertas · GondolIA" (WCAG 2.4.2: cada pantalla con un título que la distinga). */
export function formatDocumentTitle(title?: string | null): string {
  const clean = title?.replace(/\s+/g, ' ').trim();
  return clean && clean !== APP_NAME ? `${clean} · ${APP_NAME}` : APP_NAME;
}

/**
 * Pone el título de la pestaña de la pantalla. `RouteDocumentTitle` ya deja uno por ruta al navegar; este hook lo
 * precisa con lo que la página sabe (el nombre del producto o del cliente). Se reaplica al cambiar la ruta, porque
 * una misma página puede atender varias (la bandeja de soporte con y sin ticket abierto).
 */
export function useDocumentTitle(title: string | null | undefined): void {
  const { pathname } = useLocation();
  useEffect(() => {
    if (title?.trim()) document.title = formatDocumentTitle(title);
  }, [title, pathname]);
}
