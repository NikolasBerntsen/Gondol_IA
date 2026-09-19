import { NAVIGATION, isNavItemActive, type NavItem } from './navigation';

/**
 * Títulos de las rutas que no son un ítem del menú (o que lo precisan). Van de lo más específico a lo general y se
 * miran antes que el menú.
 */
const EXTRA_ROUTE_TITLES: ReadonlyArray<readonly [RegExp, string]> = [
  [/^\/login\/?$/, 'Iniciar sesión'],
  [/^\/profile\/?$/, 'Mi perfil'],
  [/^\/notifications\/?$/, 'Notificaciones'],
  [/^\/owner\/tenants\/new\/?$/, 'Dar de alta un cliente'],
  [/^\/owner\/tenants\/[^/]+\/edit\/?$/, 'Editar cliente'],
  [/^\/owner\/tenants\/[^/]+\/?$/, 'Cliente'],
  [/^\/owner\/announcements\/new\/?$/, 'Publicar un aviso'],
  [/^\/app\/pos\/sales\/[^/]+\/ticket\/?$/, 'Ticket de venta'],
  [/^\/app\/pos\/sessions\/?$/, 'Turnos de caja'],
  [/^\/app\/products\/new\/?$/, 'Nuevo producto'],
  [/^\/app\/products\/[^/]+\/edit\/?$/, 'Editar producto'],
  [/^\/app\/products\/[^/]+\/?$/, 'Producto'],
];

/** Todos los ítems del menú de todos los roles, sin repetir. */
const NAV_ITEMS: readonly NavItem[] = Array.from(
  new Map(
    Object.values(NAVIGATION)
      .flat()
      .flatMap((section) => section.items)
      .map((navItem) => [navItem.to, navItem] as const),
  ).values(),
);

/**
 * Título de la pantalla de una ruta ("Alertas", "Producto", "Iniciar sesión"), o `null` si la ruta no existe. Sale
 * del mismo menú que ve el usuario, así el título de la pestaña coincide con el ítem marcado en el riel.
 */
export function routeTitle(pathname: string): string | null {
  const extra = EXTRA_ROUTE_TITLES.find(([pattern]) => pattern.test(pathname));
  if (extra) return extra[1];
  // El ítem más específico que coincide (p. ej. "/app/pos/registers" antes que "/app/pos").
  const match = NAV_ITEMS.filter((navItem) => isNavItemActive(navItem, pathname)).sort(
    (a, b) => b.to.length - a.to.length,
  )[0];
  return match?.label ?? null;
}
