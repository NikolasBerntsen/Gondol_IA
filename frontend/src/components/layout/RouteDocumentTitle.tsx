import { useLayoutEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { routeTitle } from '@/config/routeTitles';
import { formatDocumentTitle } from '@/lib/documentTitle';

/**
 * Título de la pestaña según la ruta ("Alertas · GondolIA"; WCAG 2.4.2). Corre como efecto de layout, antes que los
 * efectos de las páginas: así `PageHeader` (o `useDocumentTitle`) lo puede precisar después con el nombre del
 * producto, del cliente o el estado de la pantalla.
 */
export function RouteDocumentTitle() {
  const { pathname } = useLocation();
  useLayoutEffect(() => {
    document.title = formatDocumentTitle(routeTitle(pathname));
  }, [pathname]);
  return null;
}
