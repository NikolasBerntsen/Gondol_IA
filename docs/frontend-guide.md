# Guía del frontend de GondolIA

Guía para los módulos que construyen pantallas sobre la fundación del frontend (SPEC §9, §14, §15, §16).
Stack: React 18 + TypeScript estricto + Vite 5 + Tailwind 3 + React Router 6 + TanStack Query 5 + STOMP.
**El lenguaje visual es "Góndola UI"**: la fuente de verdad es `docs/design-system.md` y el prototipo
`design/gondola-ui/`. Esta guía explica cómo usarlo dentro del frontend real.

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 (proxy /api y /ws → http://localhost:8080)
npm run build      # tsc -b && vite build (tiene que pasar sin errores)
npm run typecheck
```

- Backend en otro puerto/host: `GONDOLIA_BACKEND_URL=http://192.168.0.10:8080 npm run dev`.
- El dev server escucha en la red local (`host: true`); para usar la cámara desde el celular hace falta HTTPS (ver §11).
  El proxy reescribe el header `Origin` al del backend, así que abrirlo desde otra IP (celular, `127.0.0.1`) no choca con el CORS.
- Variables opcionales (`frontend/.env.local`): `VITE_HTTPS_PORT` (defecto `8443`), `VITE_SHOW_DEMO_ACCOUNTS=false` para ocultar las cuentas demo del login.

---

## 1. Estructura y propiedad

```
src/
├── App.tsx                     rutas (solo fundación) · main.tsx (tipografías + index.css)
├── index.css                   tokens de Góndola UI (claro/oscuro) + utilidades gd-*
├── api/        client.ts (axios + helpers), types.ts (enums/DTOs compartidos), auth.ts, notifications.ts
├── auth/       AuthContext (useAuth), RequireAuth, RequireRole, tokenStorage, roleHome
├── branches/   BranchContext (useBranch, useBranchQueryKey, useWriteBranch), BranchSelector, BranchPicker, branchColumn
├── modules/    useModules, RequireModule, ModuleDisabledPage          ← módulos por tenant (SPEC §14)
├── realtime/   StompProvider, useStompSubscription, useStompPublish, useSessionEvents
├── theme/      ThemeProvider, useTheme, THEME_OPTIONS (importar desde '@/theme')   ← tema Sistema/Claro/Oscuro (§7.1)
├── components/ui/          kit base (importar desde '@/components/ui')
├── components/gondola/     componentes firma (PriceTag, ExpiryChip, Ticket80mm…)
├── components/scanner/     BarcodeScanner, useBarcodeWedge, CameraCapture
├── components/layout/      AppShell, Sidebar (riel), Topbar, ThemeToggle, NotificationBell, UserMenu, Logo
├── components/notifications/  NotificationItem, useNotificationActions
├── config/     navigation.ts (menú por rol + módulo), access.ts (ROLE_GROUPS, canAccessPath, requiredModuleForPath)
├── lib/        format.ts, cn.ts, useDebounce.ts, queryClient.ts, secureContext.ts, focus.ts, scrollLock.ts
├── pages/      Login, Profile, Notifications, NotFound, Forbidden
└── features/<modulo>/      pages/ (ya existen como placeholder), api.ts, types.ts, components/, hooks/
```

Reglas:

- Cada módulo **solo** toca `src/features/<modulo>/`. Reemplazá el contenido de tus páginas placeholder
  manteniendo el nombre del archivo y `export default function NombrePage()`.
- Imports con alias `@/` (`import { Button } from '@/components/ui'`).
- Todo texto visible en **español rioplatense** ("Cargá", "Elegí", "No tenés…"), botones con verbo + objeto.
- Si necesitás algo en la fundación (un componente, un tipo compartido), pedilo o hacé el cambio mínimo compatible y reportalo.
- Los archivos de `components/ui` se llaman en PascalCase (convención del repo), aunque vengan de shadcn.
  Al copiar un componente nuevo de shadcn: renombrá el archivo y cambiá `@/lib/utils` por `@/lib/cn`.

---

## 2. Llamadas a la API

`@/api/client` exporta la instancia `api` (axios, `baseURL: '/api'`) y helpers tipados. El interceptor agrega
`Authorization: Bearer <token>` y `X-Branch-Id` (sucursal elegida o `all`), y todas las requests rechazan con
`ApiError` (nunca con el error crudo de axios).

```ts
// features/catalog/api.ts
import { apiDelete, apiGet, apiPost, apiPut, uploadFile } from '@/api/client';
import type { PageResponse } from '@/api/types';
import type { ProductDetail, ProductListItem, ProductRequest, ProductListParams } from './types';

export const productsApi = {
  list: (params: ProductListParams) => apiGet<PageResponse<ProductListItem>>('/tenant/products', params),
  get: (id: number) => apiGet<ProductDetail>(`/tenant/products/${id}`),
  create: (body: ProductRequest) => apiPost<ProductDetail>('/tenant/products', body),
  update: (id: number, body: ProductRequest) => apiPut<ProductDetail>(`/tenant/products/${id}`, body),
  remove: (id: number) => apiDelete(`/tenant/products/${id}`),
  ocrLabel: (file: Blob) => uploadFile<OcrResult>('/tenant/ocr/label', file, { fileName: 'etiqueta.jpg' }),
};
```

| Helper | Uso |
|---|---|
| `apiGet<T>(url, params?, config?)` | GET. Los params `undefined`/`null`/`''` se omiten; los arrays se repiten (`a=1&a=2`). |
| `apiPost<T>(url, body?, config?)` · `apiPut` · `apiPatch` · `apiDelete<T>(url, config?)` | Devuelven `response.data`. |
| `uploadFile<T>(url, file, { fieldName='file', fields, fileName, method, onProgress, signal })` | `multipart/form-data`. `fields` agrega campos de texto. `file` puede ser `null` si solo mandás campos. |
| `fetchBlob(url)` | Descarga autenticada como `Blob` (imágenes). Acepta rutas con o sin `/api`. |
| `downloadFile(url, fallbackName, params?)` | Descarga y guarda un archivo (plantilla CSV/XLSX, export del catálogo, errores de importación). |
| `getErrorMessage(err, fallback?)` | Mensaje en español para mostrar (usa `ErrorResponse.message`). |
| `getFieldErrors(err)` | `{ campo: mensaje }` de un `VALIDATION_ERROR`. |
| `isApiError(err, ...codes)` | Type guard: `isApiError(err, 'BRANCH_REQUIRED')`. |

`ApiError` tiene `status` (0 = sin conexión), `code`, `message`, `fieldErrors`, `is(...codes)`, `fieldError(campo)`, `fieldErrorMap`.

Opciones extra de `config` (axios):

- `branch`: sucursal solo para esa request (`branch: 4`, `branch: 'all'`, `branch: null` = sin header).
- `skipAuthHandling: true`: un 401/403 de sesión no cierra la sesión (solo flujos especiales).

Comportamiento global (no lo repliquen): 401 → cierra sesión y va a `/login?motivo=sesion`; 403 con
`TENANT_DISABLED | TENANT_CANCELLED | USER_DISABLED` → cierra sesión mostrando el mensaje del backend;
403 `BRANCH_FORBIDDEN` → vuelve a pedir `/api/auth/me` y, si la sucursal elegida ya no es accesible, el selector
vuelve al valor por defecto y se refrescan las queries.

**403 `MODULE_DISABLED`** (SPEC §14.1) no cierra sesión: mostralo con `getErrorMessage(err)` o, mejor, evitá
llegar ahí ocultando la acción con `useModules()` (§5).

---

## 3. Sucursales

El catálogo es por comercio; stock, lotes, ventas, alertas, IA, POS y recalls son **por sucursal** (SPEC §3.5).
El usuario elige la sucursal en la barra superior (`BranchSelector`); la fundación la persiste
(`gondolia.branch.<userId>`), la manda en `X-Branch-Id` e invalida todas las queries al cambiarla.
Default: jefe/admin con más de una sucursal → "Todas las sucursales"; empleado y **cajero** → su sucursal.

```ts
import { useBranch } from '@/branches/BranchContext';

const {
  branches,          // BranchRef[] accesibles ({ id, name, code })
  selectedBranchId,  // number | 'all'
  currentBranch,     // BranchRef | null (null si isAll)
  isAll,             // vista consolidada
  canSelectAll,      // hay más de una sucursal accesible
  setBranch,         // cambiar desde código (p. ej. "Ver esta sucursal")
  scopeLabel,        // "Todas las sucursales" | nombre
  branchName,        // (id) => nombre
  enabled,           // false para roles de plataforma
} = useBranch();
```

### 3.1 Query keys por sucursal (obligatorio)

Toda query de datos por sucursal incluye la sucursal **al final** de la key:

```ts
import { useBranchQueryKey } from '@/branches/BranchContext';

const params = { q, page, bucket };
const queryKey = useBranchQueryKey('expirations', 'list', params); // ['expirations','list',params,{branch:3}]
const query = useQuery({ queryKey, queryFn: () => expirationsApi.list(params) });

// Invalidar después de una mutación: el prefijo sirve para todas las sucursales.
queryClient.invalidateQueries({ queryKey: ['expirations'] });
```

Datos que no dependen de la sucursal (categorías, proveedores, usuarios, avisos, tickets, importaciones)
no necesitan el segmento. Usá siempre `useBranchQueryKey` (no armes el `{ branch }` a mano): al cambiar de
sucursal la fundación reconoce ese segmento final para no repetir requests que ya salieron con la sucursal nueva.

Convención de keys: `[recurso, 'list' | 'detail' | 'stats', params|id, {branch}?]`, definidas en
`features/<modulo>/api.ts` (`posKeys`, `importKeys`…).

### 3.2 Escrituras: `useWriteBranch` + `BranchPicker`

Las escrituras por sucursal (carga de lotes, ventas, ajustes, descarte, resolución de recall, apertura de caja,
importación con stock) requieren **una** sucursal. Con "Todas las sucursales" y más de una accesible hay que pedirla:

```tsx
import { useWriteBranch } from '@/branches/BranchContext';
import { BranchPicker } from '@/branches/BranchPicker';

function IntakeForm() {
  const writeBranch = useWriteBranch(); // { needsPicker, branchId, setBranchId, isReady }
  const [branchError, setBranchError] = useState<string>();

  const submit = () => {
    if (!writeBranch.isReady) return setBranchError('Elegí una sucursal.');
    mutation.mutate({ ...values, branchId: writeBranch.branchId });
  };

  return (
    <form>
      <BranchPicker value={writeBranch.branchId} onChange={writeBranch.setBranchId} error={branchError} />
      {/* resto del formulario */}
    </form>
  );
}
```

- `BranchPicker` no se muestra si hay una sucursal elegida arriba (`alwaysVisible` para casos como el destino de
  una transferencia; `excludeIds` para excluir la de origen).
- Mandá siempre `branchId` en el body: el backend lo prioriza sobre el header.
- Si igual llega `400 BRANCH_REQUIRED`, mostrá `getErrorMessage(err)` junto al picker.

### 3.3 Columna "Sucursal"

En tablas con `isAll` se muestra la columna "Sucursal" (las filas traen `branchId` y `branchName`):

```tsx
import { useBranchColumn } from '@/branches/branchColumn';

const branchCol = useBranchColumn<ExpirationRow>();          // null si no es vista consolidada
const columns: Array<TableColumn<ExpirationRow> | null> = [productCol, branchCol, expiryCol, qtyCol];
<Table columns={columns} ... />                              // Table ignora las columnas null
```

---

## 4. React Query

- `QueryClient` global: `staleTime` 30 s, sin reintentos para errores 4xx, 2 reintentos para red/5xx.
- Listas paginadas: `placeholderData: keepPreviousData` para no parpadear al cambiar de página.
- Después de mutar: `invalidateQueries` con el prefijo del recurso (y de los recursos afectados: una venta del POS
  cambia stock, vencimientos, ventas y dashboard).
- Errores de mutaciones: si la mutación **no** define `onError`, la fundación muestra `toast.error(getErrorMessage(err))`.
  Si lo manejás vos, definí `onError` o `meta: { errorToast: false }`.
- Éxitos: `toast.success('Ingreso registrado.')` (import de `sonner`), en participio.
- Estados: `isPending` → `Skeleton`/`PageSpinner`; `isError` → `<ErrorState error={q.error} onRetry={q.refetch} />`;
  sin datos → `<EmptyState />`. **Las tres siempre**, en todas las pantallas.

```tsx
const queryClient = useQueryClient();
const createProduct = useMutation({
  mutationFn: productsApi.create,
  onSuccess: (product) => {
    toast.success('Guardaste el producto.');
    queryClient.invalidateQueries({ queryKey: ['products'] });
    navigate(`/app/products/${product.id}`);
  },
  onError: (error) => setErrors(getFieldErrors(error)),
});
```

---

## 5. Roles, permisos y módulos

```ts
const { me, isTenantUser, isPlatformUser, hasRole, logout, refreshMe } = useAuth();
const me = useCurrentUser();       // MeDto garantizado (dentro de rutas protegidas)
me.tenant?.stockRotation;          // 'FIFO' | 'FEFO'
me.tenant?.modules;                // TenantModule[] habilitados
me.tenant?.maxBranches;            // máximo EFECTIVO (sin MULTI_BRANCH es 1)
me.branches;                       // sucursales accesibles
hasRole('TENANT_ADMIN');           // preferí los permisos de useAccess() (abajo)
```

### 5.1 Roles

Seis roles (SPEC §3.1): `PLATFORM_OWNER`, `SUPPORT_AGENT`, `TENANT_BOSS`, `TENANT_ADMIN`, `TENANT_EMPLOYEE`,
**`TENANT_CASHIER`** (etiqueta "Cajero", inicio `/app/pos`). Grupos en `@/config/access` (espejo de `Roles.java`):
`ALL`, `OWNER`, `SUPPORT`, `TENANT_ANY`, `TENANT_DASHBOARD`, `TENANT_INVENTORY`, **`TENANT_INVENTORY_READ`**
(jefe + admin + empleado), `TENANT_ADMIN` y **`TENANT_POS`** (admin + empleado + cajero).

**Regla (SPEC §3.3): todo botón o enlace visible para un rol funciona para ese rol; lo que un rol no puede hacer no se
le muestra.** Nunca dibujes un botón que termina en "No tenés acceso a esta sección" o en un 403. Los permisos están
centralizados en `PERMISSIONS` de `@/config/access` (`products.view`, `products.write`, `products.delete`,
`intake.use`, `imports.use`, `expirations.view`, `expirations.discard`, `sales.view`, `sales.write`,
`movements.view`, `movements.adjust`, `transfers.view`, `transfers.write`, `recommendations.decide`,
`alerts.manage`, `insights.run`, `recalls.resolve`, `pos.use`, `pos.admin`, …) y los usan las rutas de `App.tsx`
(`<RequireRole roles={rolesWith('products.view')} />`), el menú y las páginas:

```tsx
import { useAccess } from '@/auth/useAccess';

const { can, canOpen } = useAccess();
{can('products.write') && <ButtonLink to="/app/products/new">Nuevo producto</ButtonLink>}
{can('recommendations.decide') ? <Button onClick={accept}>Aceptar</Button> : <span>Solo lectura</span>}
// Links armados a mano: canOpen() mira el rol Y el módulo de la ruta.
{canOpen(`/app/pos/sales/${id}/ticket`) ? <Link to={…}>Ticket</Link> : <Badge>{ticketCode}</Badge>}
```

Fuera de componentes: `can(role, permiso)`, `canAccessPath(role, ruta)` y `linkTargetFor(role, link)` (lo usa la
campana para no mandar a nadie a "Acceso denegado"). El jefe tiene la vista resumida pero **ve** inventario, ficha
de producto, vencimientos, ventas, movimientos y transferencias (sin botones de carga ni edición) y **decide** sobre
recomendaciones y alertas. El cajero solo ve POS + Avisos/Seguridad alimentaria/Soporte. El backend igual valida.

### 5.2 Módulos por tenant (SPEC §14)

```tsx
import { useModules } from '@/modules/useModules';
import { TENANT_MODULE_LABELS } from '@/api/types';

const { modules, hasModule, maxBranches, isTenant } = useModules();

{hasModule('POS_GONDOLIA') && <ButtonLink to="/app/pos">Ir al punto de venta</ButtonLink>}
```

| Módulo | Etiqueta | Habilita |
|---|---|---|
| `POS_GONDOLIA` | Punto de venta GondolIA | Cajas, turnos, cobro, tickets, anulaciones (§15) |
| `POS_INTEGRATION` | Integración con POS propio | API keys, importación CSV de ventas, simulador |
| `MULTI_BRANCH` | Multi-sucursal | Más de una sucursal, transferencias, vista consolidada |

Patrón de uso:

1. **Menú**: el ítem declara `module` en `config/navigation.ts` y la fundación lo oculta solo.
2. **Ruta**: `<RequireModule module="POS_GONDOLIA" />` envuelve las rutas del módulo en `App.tsx` y muestra
   `ModuleDisabledPage` si entran por URL ("Esta función no está habilitada para tu comercio…" + link a Soporte).
3. **Dentro de la página**: escondé botones y links con `hasModule(...)`; nunca muestres una acción que el backend
   va a rechazar con 403 `MODULE_DISABLED`.
4. **En vivo**: si los dueños cambian los módulos llega `{"type":"MODULES_CHANGED"}` por `/user/queue/session`;
   la fundación hace `refreshMe()`, muestra el toast "Se actualizaron las funciones habilitadas" y, si la pantalla
   abierta dependía del módulo que desactivaron, vuelve al inicio del rol. **No te suscribas de nuevo a eso.**

La consola de dueños (módulo C) usa `TENANT_MODULES`, `TENANT_MODULE_LABELS`, `TENANT_MODULE_DESCRIPTIONS`,
`TENANT_MODULE_MONTHLY_PRICE` y el tipo `TenantModuleStatus` de `@/api/types`.

---

## 6. Tiempo real (STOMP)

La conexión única la maneja `StompProvider` (conecta al iniciar sesión, reconecta con backoff y re-suscribe).

```ts
import { useStompSubscription } from '@/realtime/useStompSubscription';
import { useStompPublish } from '@/realtime/useStompPublish';
import { useStompConnected } from '@/realtime/StompProvider';

useStompSubscription<TicketEvent>(
  ticketId ? `/topic/tickets/${ticketId}` : null,
  (event) => {
    if (event.event === 'MESSAGE') queryClient.invalidateQueries({ queryKey: ['tickets', ticketId] });
  },
  enabled, // opcional
);

const publish = useStompPublish();
publish(`/app/tickets/${ticketId}/typing`, { typing: true }); // false si no hay conexión

const live = useStompConnected(); // para un indicador "En vivo"
```

Ya resuelto por la fundación (no volver a suscribirse para lo mismo):

- `/user/queue/session` → `FORCE_LOGOUT` (cierra sesión) y `MODULES_CHANGED` (`useSessionEvents`).
- `/user/queue/notifications` → contador, lista y toast de la campana (`NotificationBell`).
- `/user/queue/security-alerts` lo consume `SecurityAlertHost` (módulo D).

---

## 7. Diseño: tokens de Góndola UI

Todo color sale de un **token** (`src/index.css`), nunca de un literal ni de la paleta de Tailwind
(`slate-500`, `brand-600` y `emerald-*` ya no existen). Los tokens tienen versión clara y oscura; cada usuario elige
**Sistema / Claro / Oscuro** (§7.1). **Probá siempre las dos.**

| Token / clase | Cuándo se usa |
|---|---|
| `bg-background` | Lienzo de la app (lo pone el shell; la página no lo repite). |
| `bg-card` · `text-foreground` | Paneles, tablas, barra superior, popovers. |
| `bg-muted` · `text-muted-foreground` | Hover, segmentados, zonas secundarias; bajadas y metadatos. |
| `border-border` · `border-input` | Divisores y bordes de panel · borde de controles. |
| `bg-primary` · `text-primary` · `ring-ring` | Verde góndola: acción principal, ítem activo, foco. |
| `bg-accent` | **Amarillo de etiqueta de precio.** Solo `PriceTag`, el total del POS y ofertas. Nunca botones ni bordes. |
| `bg-ok/-soft` `text-ok-ink` | Stock suficiente, operación completada. |
| `bg-warn/-soft` `text-warn-ink` | Por vencer, stock bajo, advertencias que no bloquean. |
| `bg-crit/-soft` `text-crit-ink` | Vencido, sin stock, recall, errores que bloquean. |
| `bg-info/-soft` `text-info-ink` | Próximo, IA, avisos neutrales. |
| `bg-rail` `text-rail-foreground` … | Solo el riel de navegación. |
| `bg-paper` `text-paper-ink` | Ticket térmico (sigue siendo papel en tema oscuro). |
| `bg-camera` | Visor de cámara. |

Reglas rápidas (detalle en `docs/design-system.md`):

- **Radios por rol:** `rounded-control` (8, botones e inputs) · `rounded-panel` (12, paneles y popovers) ·
  `rounded-dialog` (16, diálogos) · `rounded-tag` (4, chips de dato) · tablas sin radio · `rounded-full` **solo** estados.
- **Bordes antes que sombras.** `shadow-pop` solo para lo que flota (diálogo, popover, menú).
- **Tipografías:** `font-display` (Bricolage: títulos de página, KPI, precios) · `font-sans` (Figtree: todo lo demás) ·
  `font-mono` (JetBrains Mono: códigos, lotes, fechas en chips, tickets, atajos). Se instalan con
  `@fontsource-variable/*` en `main.tsx`: **sin CDN** (el contenedor funciona sin internet).
- **Escala de texto cerrada:** `text-xs` 12 · `text-sm` 13 · `text-base` 14 (base de UI) · `text-read` 15 (lectura) ·
  `text-md` 16 · `text-lg` 20 · `text-xl` 26 · `text-2xl` 34 · `text-3xl` 48.
- `tabular-nums` en toda cifra que se compara (columnas, totales, contadores).
- Utilidades propias: `gd-stripe-crit|warn|info|ok|none` (franja de severidad), `gd-eyebrow` (rótulo en mayúsculas),
  `gd-kbd`, `gd-scroll`, `gd-skeleton`, `gd-no-print`.
- `cn(...)` (de `@/lib/cn`) conoce estas escalas: `cn('rounded-panel', 'rounded-tag')` deja solo la última.

### 7.1 Tema: Sistema / Claro / Oscuro

Cada usuario elige el tema; la preferencia se guarda **por navegador** en `localStorage["gondolia.theme"]`
(`system` por defecto) y no viaja con la cuenta.

| Pieza | Qué hace |
|---|---|
| `public/theme-init.js` (`<script src>` bloqueante en el `<head>` de `index.html`) | Lee la preferencia y pone `data-theme` **antes del primer pintado** (sin parpadeo). Es un archivo aparte y no un script inline porque la CSP de nginx (`frontend/nginx/snippets/gondolia-csp.conf`) solo permite `script-src 'self'`: no agregues scripts inline en `index.html`. |
| `ThemeProvider` (`@/theme`, montado en la raíz de `App.tsx`) | Mantiene el atributo: `data-theme="light\|dark"` o **sin atributo** para "Sistema" (el CSS sigue a `prefers-color-scheme`). Escucha el cambio del sistema operativo (`matchMedia`) y de otras pestañas (`storage`), actualiza `<meta name="theme-color">` / `color-scheme` y cambia sin transiciones para que todo pase a la vez. Funciona en el login, el splash y el ticket (fuera del AppShell). |
| `useTheme()` | `{ preference: 'system' \| 'light' \| 'dark', resolved: 'light' \| 'dark', setPreference }`. `resolved` es el tema que se ve. |
| `ThemeToggle` (`@/components/layout/ThemeToggle`) | Botón "Cambiar tema" (Monitor / Sol / Luna según la preferencia) con menú Sistema / Claro / Oscuro. Está en la barra superior para **todos los roles**, también en la variante compacta del POS, y en la esquina del login. |
| Menú de usuario · perfil | Sección "Tema" (segmentado) en `UserMenu` y tarjeta "Apariencia" en `/profile`: es el mismo ajuste. |
| `THEME_OPTIONS` | Etiquetas, íconos y textos de las tres opciones, para no repetirlos. |

```tsx
import { useTheme } from '@/theme';

const { preference, resolved, setPreference } = useTheme();
setPreference('dark');           // 'system' vuelve a seguir al sistema operativo
```

**Colores leídos desde JS.** Lo normal es no leer colores: pasale a recharts, SVG y estilos inline
**strings con tokens** (`'hsl(var(--primary))'`), que el navegador resuelve en vivo y cambian solos con el tema (así
están `features/analytics/components/chart.tsx`, `Sparkline` y `GrowthChart`). Solo si necesitás el **valor**
(canvas, `getComputedStyle(...).getPropertyValue('--x')`, una librería que no entiende `var()`):

- leelo en el momento de usarlo (como la captura de pantalla del soporte), o
- recalculalo cuando cambia el tema, poniendo `resolved` en las dependencias:

```tsx
const { resolved } = useTheme();
const colors = useMemo(() => {
  const css = getComputedStyle(document.documentElement);
  return { primary: `hsl(${css.getPropertyValue('--primary').trim()})` };
}, [resolved]);
```

Nunca guardes un color leído en una constante de módulo ni en un `useRef` sin dependencia: queda con el tema de
cuando cargó la pantalla. El `Toaster` (sonner) recibe `theme={resolved}` y sus variables `--normal-*` apuntan a
tokens (`ThemedToaster` en `App.tsx`).

**Impresión:** `@media print` vuelve a poner los tokens claros aunque la pantalla esté en oscuro, así el ticket sale en
papel claro con tinta oscura (§9.1). No hace falta nada en la página.

---

## 8. Componentes

### 8.1 Kit base (`@/components/ui`)

| Componente | Props principales |
|---|---|
| `Button` | `variant: default \| secondary \| outline \| ghost \| destructive \| link` (siguen andando `primary`, `danger`, `danger-outline`), `size: sm \| default \| lg \| xl \| icon \| icon-sm`, `loading`, `leftIcon`, `rightIcon`, `fullWidth`, `asChild`. `type="button"` por defecto. |
| `ButtonLink` | Igual que `Button` pero es un `<Link to>` de react-router. `buttonClasses({...})` para otros elementos. |
| `Card` (= `Panel`), `CardHeader`, `CardFooter` | `Card padding: none \| sm \| md \| lg`. `CardHeader title description icon actions`. Sin sombra: borde + `rounded-panel`. |
| `Badge` | Etiqueta de **dato** (radio 4): `tone: neutral \| primary \| ok \| warn \| crit \| info`, `size`, `dot`, `icon`, `solid`, `pill`. Helpers: `severityTone`, `expiryBucketTone`, `stockStatusTone`, `reorderStatusTone`. |
| `Alert` | `tone: info \| ok \| warn \| crit \| brand`, `title`, `icon` (o `null`), `action`. `warn`/`crit` se anuncian con `role="alert"`. |
| `Input` | `invalid`, `inputSize: sm \| md \| lg`, `leftIcon`, `rightElement`. 16 px en móvil (evita el zoom de iOS). |
| `Select` | `<select>` nativo: `options: {value,label,disabled}[]`, `placeholder`, `invalid`, `selectSize`, `leftIcon`. Es el control por defecto de los formularios. |
| `SelectMenu` + `SelectTrigger/SelectValue/SelectContent/SelectItem` | Select flotante de Radix, para menús con contenido rico. |
| `Textarea` | `invalid`, `rows`. |
| `Checkbox` | Radix: `checked` (`boolean \| 'indeterminate'`), `onCheckedChange`, `label`, `description`. |
| `Switch` / `Toggle` | `Switch` (Radix, `checked`/`onCheckedChange`, `size`) para tablas; `Toggle` (`checked`, `onChange(checked)`, `label`, `description`) para filas de configuración. |
| `Field` | `label`, `hint`, `error`, `required` (solo `aria`), `optional` (muestra "(opcional)"), `labelAction`. Conecta `id`/`aria-*` con su hijo. Góndola UI marca lo opcional, no lo obligatorio. |
| `SearchInput` | `value`, `onValueChange`, `onSearch` (Enter), `placeholder`, `inputSize`. Combinar con `useDebounce`. |
| `Segmented` | Filtros excluyentes con contadores: `value`, `onChange`, `options: {value,label,count,tone,icon}[]`, `label`, `size`. Flechas del teclado incluidas. |
| `QtyStepper` | `value`, `onChange`, `label`, `min`, `max`, `size: sm \| md \| lg` (44 px para el POS y la carga móvil). |
| `WizardSteps` | `steps: string[]`, `current`. Solo para procesos secuenciales reales (importación). |
| `Kbd` | Tecla de atajo (`F2`, `F4`, `Esc`). Se oculta en pantallas táctiles. |
| `Modal` | `open`, `onClose`, `title`, `description`, `footer`, `size: sm \| md \| lg \| xl \| full`, `preventClose`, `closeOnOverlayClick`, `hideCloseButton`, `initialFocusRef`. Radix Dialog: foco atrapado, ESC y scroll bloqueado. En móvil se ancla abajo. `data-autofocus` marca el control inicial. |
| `Dialog*` | Primitivas de Radix (`Dialog`, `DialogContent`, `DialogHeader`…) para diálogos a medida (p. ej. la hoja de cobro). |
| `ConfirmDialog` | `open`, `onClose`, `onConfirm` (puede devolver promesa), `title`, `description`, `confirmLabel` (verbo exacto), `tone: primary \| danger`, `children` (p. ej. campo "motivo"). Con `tone="danger"` el foco arranca en "Cancelar": Enter no dispara la acción destructiva. |
| `Table<T>` | `columns: TableColumn<T>[]` (`id`, `header`, `cell(row)`, `align`, `className`, `headerClassName`, `hideBelow: lg \| xl`, `mobile: title \| subtitle \| aside \| field \| actions \| hidden`, `mobileLabel`), `data`, `rowKey`, `loading`, `error`, `onRetry`, `empty`, `onRowClick`, `rowClassName`, **`rowSeverity`** (franja de 4 px), `mobileLayout: cards \| scroll`, `renderMobileCard`, `dense`, `footer`, `caption`. |
| `TableRoot`, `TableHeader`, `TableBody`, `TableRow`, `TableHead`, `TableCell`, `TableFooter`, `TableCaption` | Primitivas para tablas a medida (con `SeverityRow`). |
| `Pagination` | `page` (0-based), `totalPages`, `totalElements`, `size`, `onPageChange`, `disabled`. `pageInfo(data)` arma las props desde un `PageResponse`. |
| `Tabs<V>` | `tabs: {value,label,icon,count,disabled}[]`, `value`, `onChange`, `variant: underline \| pills`, `ariaLabel`, `idPrefix`. |
| `PageHeader` | `title`, `description`, `eyebrow`, `icon`, `actions`, `back: {to,label}`, `children` (filtros/tabs debajo), `documentTitle` (título de la pestaña "… · GondolIA"; por defecto `title` si es texto, `null` deja el de la ruta). Cada ruta ya tiene un título (`config/routeTitles.ts`); las pantallas sin `PageHeader` lo precisan con `useDocumentTitle` (`lib/documentTitle.ts`). |
| `StatCard` | `label`, `value`, **`money`**, `icon`, `tone: primary \| ok \| warn \| crit \| info \| neutral`, `hint`, `trend: {value,label,invert}`, `sparkline`, `loading`, `to`. El número va en una línea. Para montos pasá **`money={importe}`** (en vez de `value={formatMoney(importe)}`): si no entra en la tarjeta, la tarjeta sola muestra el compacto (`$ 11,97 M`) con el importe exacto en el `title` y para lectores de pantalla. Sin `money` (conteos, fechas), si no entra se achica la letra hasta que entre. |
| `EmptyState` | `icon`, `title` (qué falta), `description` (próxima acción), `action`, `size`, `bordered`. |
| `ErrorState` | `error` (se traduce), `message`, `title`, `onRetry`, `retrying`, `size`. |
| `Spinner`, `PageSpinner`, `Skeleton` | Cargas en línea, de sección y esqueletos (`<Skeleton className="h-4 w-32" />`). |
| `Progress` | Barra de progreso (Radix) para la aplicación de una importación. |
| `Tooltip` + `TooltipProvider/Trigger/Content` | Requiere un `TooltipProvider` arriba. |
| `Truncate` (`useTruncationTitle`) | Texto de una línea (`lines={2}` o `3` para `line-clamp`) que, **solo si queda cortado**, expone el valor completo en el `title` (docs/design-system.md §3). `as` (`span` por defecto; sirve `p`, `div`, `dd`, `Link`…), `fullText` (cuando los hijos no son texto plano), `className`. Usalo en vez de `className="truncate"` para todo dato del comercio: nombres, marcas, correos, asuntos, notas, archivos. `useTruncationTitle(texto)` hace lo mismo desde un componente propio (así lo usa el rótulo de `StatCard`). |
| `Popover`, `DropdownMenu*`, `Sheet*`, `Separator`, `Label` | Primitivas de Radix ya adaptadas a los tokens. |
| `useDropdown`, `DropdownPanel`, `DropdownItem`, `DropdownSeparator`, `DropdownLabel` | Desplegable propio (click afuera, ESC, flechas) para paneles con contenido libre. El panel se corre solo para quedar dentro de la pantalla con 16 px de margen. Al abrir enfoca el ítem marcado (`aria-checked`); `useDropdown({ initialFocus: 'first' })` enfoca siempre el primero (menús de acciones con una opción marcada adentro, como el tema del menú de usuario). |
| `AuthImage` | `src` (ruta `/api/...` protegida), `alt`, `fallback`, `placeholderClassName`. |
| `Avatar` | `name`, `size: sm \| md \| lg \| xl`. |
| `SecureContextWarning` | Aviso de cámara en HTTP (ver §11). |

### 8.2 Componentes firma (`@/components/gondola`)

Son los que le dan identidad al producto (docs/design-system.md §5). Usalos tal cual: no hagas variantes propias.

| Componente | Props | Cuándo |
|---|---|---|
| `PriceTag` | `price`, `size: sm \| md \| lg \| xl`, `label`, `unit`, `perUnit`, `listPrice`, `offer` | Total del POS, precio en la ficha, ofertas por vencimiento. **Una sola etiqueta grande por pantalla**; nunca para montos contables. |
| `ExpiryChip` | `expiry` (ISO), `lot`, `bucket` (del backend), `thresholds`, `longYear`, `showDays` | Tablas de vencimientos, líneas del carrito, lotes en la carga. Acompañalo con una píldora o franja. |
| `LotRankChip` | `rank`, `rotation` (`me.tenant.stockRotation`), `discounted` | Lotes de **un mismo producto y sucursal**. `rank=1` → "1º sale"; con `discounted` → "En liquidación · sale primero" (SPEC §4.2). |
| `StatusPill` | `tone: ok \| warn \| crit \| info \| neutral`, `solid`, `dot`, `size` | Estado de una fila. Es el único elemento totalmente redondeado. |
| `StockStatusPill` | `status: OUT \| LOW \| OK \| SIN_STOCK \| CRITICO \| BAJO` | Sin stock (sólido) / Crítico / Bajo / OK. En consolidado, el **peor** estado. |
| `SeverityRow`, `SeverityItem`, `SeverityStripe`, `stripeClass`, `severityStripe` | `severity: crit \| warn \| info \| ok \| none` | Franja de 4 px en filas y listas rectas ("Para hoy", notificaciones). Con el componente `Table` usá `rowSeverity`. |
| `Ticket80mm` | `data: Ticket80mmData` | Vista previa del comprobante después de cobrar, en el historial y en la página de impresión. Siempre con la leyenda "Comprobante no válido como factura". |
| `BarcodeDigits` | `code`, `width`, `digitsOnly`, `bare` | Producto detectado por el escáner, alerta de recall, ficha. En listados largos usá `digitsOnly`. |
| `Sparkline` | `data: number[]`, `tone`, `width`, `height` | Minigráfico decorativo dentro de un `StatCard`. |

```tsx
import { ExpiryChip, LotRankChip, PriceTag, StockStatusPill } from '@/components/gondola';

<PriceTag price={16270.5} size="lg" label="Total" />
<ExpiryChip expiry={lot.expiryDate} lot={lot.lotNumber} bucket={lot.bucket} showDays />
<LotRankChip rank={index + 1} rotation={me.tenant.stockRotation} discounted={!!lot.discountPct} />
<StockStatusPill status={row.stockStatus} />
```

### 8.3 Escáner y cámara (`@/components/scanner`)

Compartidos por la carga de mercadería (A1) y el POS (H).

```tsx
import { BarcodeScanner, CameraCapture, useBarcodeWedge } from '@/components/scanner';

// 1. Cámara (celular y notebook): cámara trasera por defecto, cambio de cámara, linterna, pitido + vibración.
<BarcodeScanner
  onDetected={(code) => lookup(code)}
  active={!paymentOpen}                    // apaga la cámara sin desmontar
  hint="Apuntá al código de barras del producto"
  fallback={<Button onClick={openManual}>Ingresar el código a mano</Button>}
/>

// 2. Lector USB de caja (keyboard wedge): captura ráfagas rápidas terminadas en Enter en toda la página.
useBarcodeWedge({ onScan: (code) => addToCart(code) });
// opciones: { enabled, minLength=6, maxKeyIntervalMs=50, captureWhileTyping=false }

// 3. Foto para el OCR de etiquetas: devuelve un Blob listo para uploadFile().
<CameraCapture
  captureLabel="Leer vencimiento y lote"
  busy={ocr.isPending}
  onCapture={(photo) => ocr.mutate(photo)}
/>
```

- Los tres avisan en español si falta el permiso, no hay cámara o la página no es contexto seguro, y ofrecen la
  alternativa manual (o subir una foto). **Siempre dejá un camino sin cámara.**
- `useBarcodeWedge` ignora las teclas mientras el foco está en un input (el lector ya escribe ahí); en el POS, donde
  el buscador está siempre enfocado, leé el código desde ese input o usá `captureWhileTyping: true` con cuidado.
- `scanFeedback()` (pitido + vibración) está exportado por si confirmás una lectura por otro camino.

---

## 9. Layout, shell e impresión

- `AppShell` pone riel, barra superior y el contenedor (`max-w-7xl`, padding responsive). La página solo renderiza
  su contenido: `PageHeader` + secciones. **No agreguen** `min-h-screen`, fondos de página ni otro contenedor centrado.
- **Variante compacta**: en `/app/pos` el riel arranca colapsado y el shell **no** pone padding ni ancho máximo
  (la terminal maneja su layout de dos paneles y su propio scroll). Si tu pantalla puede aparecer en las dos
  variantes, leé `useShellLayout()` de `@/components/layout/shellLayout` (también se re-exporta desde `AppShell`):
  devuelve `{ compact, inShell }` y el padding lo ponés vos cuando `compact` o `!inShell`
  (`inShell` es `false` fuera del AppShell, p. ej. en la ruta del ticket).
- Rutas que comparten página (`/support` y `/support/tickets/:id`, `/app/support` y `/app/support/:id`) no se remontan
  al navegar entre ellas: el estado de la lista se conserva y el detalle se decide con `useParams()`.
- El menú lateral se arma en `src/config/navigation.ts` (lo mantiene la fundación). El buscador de la barra superior
  navega a `/app/inventory?q=<texto>`: la página de inventario debe leer `q` de `useSearchParams`.

### 9.1 Imprimir el ticket

`/app/pos/sales/:id/ticket` se renderiza **fuera del AppShell** (ver `App.tsx`) y es la única pantalla pensada para
la impresora:

```tsx
export default function PosTicketPage() {
  const ticket = useQuery(...);
  return (
    <div className="min-h-dvh bg-background px-4 py-6">
      <div className="mx-auto flex w-full max-w-[420px] flex-col items-center gap-4">
        {/* Los controles no se imprimen */}
        <div className="gd-no-print flex w-full items-center justify-between">
          <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>Volver</Button>
          <Button size="sm" onClick={() => window.print()}>Imprimir</Button>
        </div>
        <Ticket80mm data={ticket.data} />
      </div>
    </div>
  );
}
```

- `gd-no-print` (en `index.css`) oculta cualquier cosa al imprimir; el `@media print` global ya pone fondo blanco (el
  body y `--background`, así `bg-background` no imprime el lienzo gris) y **fuerza los tokens claros** (aunque el
  usuario tenga el tema oscuro, se imprime en claro).
- El ticket mide 302 px (80 mm) y usa `--paper`/`--paper-ink`: en pantalla sigue siendo papel en tema oscuro.
- Después de cobrar, mostrá el mismo `Ticket80mm` dentro del diálogo con "Imprimir" y "Nueva venta"; el link a esta
  página sirve para reimprimir desde el historial.

---

## 10. Mobile y accesibilidad

Diseñá **mobile first**: los empleados cargan mercadería con el celular y el cajero trabaja parado.

- Funciona de 375 px para arriba **sin scroll horizontal de página**; las tablas scrollean solas.
- Riel → drawer debajo de `lg` (1024 px). `Table` pasa a tarjetas debajo de `md`: definí `mobile: 'title'` en la
  columna principal y `mobile: 'aside'` en el estado. Los chips que no cortan línea (`ExpiryChip`, `LotRankChip`,
  píldoras) van en `aside`: en `field` la grilla es de dos columnas y se recortan contra el borde de la tarjeta.
- Grillas de KPI: `grid gap-4 sm:grid-cols-2 xl:grid-cols-4`.
- Acciones de formulario: `flex flex-col-reverse gap-2 sm:flex-row sm:justify-end`. En la carga con cámara y en el POS
  la acción principal va abajo, `size="xl"` y `fullWidth` (56 px, al alcance del pulgar, con `env(safe-area-inset-bottom)`).
- Filtros apilados en mobile (`flex flex-col gap-3 md:flex-row`).
- El `SupportWidget` flota abajo a la derecha y **no tapa nada**: mientras está montado publica
  `--support-bubble-space` (88 px) y el `<main>` del shell lo suma como `padding-bottom`, así el final de cualquier
  pantalla se puede desplazar por encima de la burbuja (escritorio y mobile). Si tu pantalla tiene una barra de
  acción pegada abajo (`sticky bottom-0`), marcala con `data-bottom-action-bar=""` (la burbuja se corre arriba de
  ella) **y** poné el aire en el contenedor de la barra con `pb-[var(--support-bubble-space,0px)]`, no en el scroll:
  así la barra sigue pegada al borde (lo hacen el POS, la carga de mercadería y el asistente de importación).
  Con un diálogo o una hoja abierta la burbuja se esconde sola.
- Botones solo-ícono con `aria-label`; `Field` para etiquetas; foco visible (ya incluido); `role="alert"` en lo que
  bloquea; `aria-live` en el vuelto y en las lecturas de OCR.
- Nunca dependas solo del color: estado = **palabra + forma + color** (píldora, chip o franja).

---

## 11. Cámara y contexto seguro

Los navegadores solo permiten la cámara en HTTPS o `localhost`. En pantallas con escáner/OCR:

```tsx
import { SecureContextWarning } from '@/components/ui';
import { isCameraSupported } from '@/lib/secureContext';

<SecureContextWarning className="mb-4" />            {/* solo aparece si hace falta */}
{isCameraSupported() ? <BarcodeScanner onDetected={...} /> : <ManualBarcodeInput />}
```

`getSecureUrl()` devuelve la URL equivalente en `https://<host>:<VITE_HTTPS_PORT|8443>`; `LOCAL_CA_CERT_PATH` es el
certificado de la CA local que sirve nginx. `BarcodeScanner` y `CameraCapture` ya muestran su propio aviso.

---

## 12. Formato (`@/lib/format`)

Siempre es-AR y zona `America/Argentina/Buenos_Aires`. Valores nulos → `—`.

| Función | Ejemplo |
|---|---|
| `formatMoney(8450000)` | `$ 8.450.000` (sin decimales si es entero; `$ 1.234,50` si no; `{ decimals: 2 }` fuerza centavos) |
| `formatMoneyCompact(11974748.39)` | `$ 11,97 M` (`850000` → `$ 850 mil`). **Solo** donde no entra el número completo y siempre con el valor exacto en el `title`/tooltip; nunca en montos que se cobran (POS, `PriceTag`, tickets). |
| `splitMoney(16270.5)` | `{ int: '16.270', cents: '50' }` (lo usa `PriceTag`) |
| `formatNumber(1234.5)` · `formatNumber(x, { decimals: 1 })` | `1.234,5` |
| `formatPercent(37.5)` (0..100) · `formatRatio(0.78)` (0..1) | `37,5%` · `78%` |
| `formatDate('2026-09-25')` / instante | `25/09/2026` (los `LocalDate` no se corren por UTC) |
| `formatDateCompact('2026-09-25')` | `25/09/26` (chips y tickets) |
| `formatDateTime(instante)` · `formatTime(instante)` | `25/09/2026 14:30` · `14:30` |
| `formatLongDate()` | `Jueves, 17 de septiembre de 2026` |
| `formatShortDate('2026-09-17')` | `17 sep` (ejes de gráficos) |
| `formatRelative(instante)` | `recién`, `hace 5 min`, `hace 2 h`, `ayer`, `hace 3 días`, luego la fecha |
| `daysUntil('2026-09-25')` · `formatDaysLeft(8)` | `8` · `Vence en 8 días` / `Vence hoy` / `Venció hace 3 días` |
| `todayLocalDate()` · `toLocalDateString(date)` | `2026-09-17` (para la API y `<input type="date">`) |
| `pluralize(3, 'producto')` · `formatBytes(n)` · `initials(nombre)` · `capitalize(s)` | `3 productos` · `1,2 MB` · `LG` |

Otros: `cn(...)` combina clases de Tailwind (conoce los radios y tamaños de Góndola UI); `useDebounce(value, 300)`.

Etiquetas en español para enums (todas en `@/api/types`): `ROLE_LABELS`, `PLAN_LABELS`, `BUSINESS_TYPE_LABELS`,
`STOCK_ROTATION_LABELS`, `LOT_STATUS_LABELS`, `MOVEMENT_TYPE_LABELS`, `MOVEMENT_SOURCE_LABELS`, `EXPIRY_BUCKET_LABELS`,
`STOCK_STATUS_LABELS`, `REORDER_STATUS_LABELS`, `ALERT_TYPE_LABELS`, `ALERT_STATUS_LABELS`, `SEVERITY_LABELS`,
`SALES_PATTERN_LABELS`, `RECOMMENDATION_TYPE_LABELS`, `RECOMMENDATION_STATUS_LABELS`, `ANNOUNCEMENT_*_LABELS`,
`RECALL_*_LABELS`, `TICKET_*_LABELS`, `NOTIFICATION_TYPE_LABELS`, `TENANT_STATUS_LABELS`, `TENANT_MODULE_LABELS`.

---

## 13. Charts (recharts)

- Colores con `hsl(var(--primary))`, `hsl(var(--info))`…: nunca literales, así funcionan en los dos temas y cambian
  en vivo cuando el usuario cambia el tema. Si necesitás el valor resuelto, dependé de `useTheme().resolved` (§7.1).
- Grilla solo horizontal y tenue, ejes en `muted-foreground` de 12 px, tooltip propio con superficie `card`,
  `rounded-panel` y `shadow-pop`.
- Área verde para stock (eje izquierdo) + línea tinta para ventas (eje derecho), con el punto final destacado.

---

## 14. Checklist de una página nueva

1. Tipos en `features/<modulo>/types.ts` y funciones en `features/<modulo>/api.ts` con los helpers de `@/api/client`.
2. Queries con keys por sucursal (`useBranchQueryKey`) si los datos son por sucursal; columna "Sucursal" con `useBranchColumn`.
3. Escrituras por sucursal con `useWriteBranch` + `BranchPicker` y `branchId` en el body.
4. Acciones visibles según **rol** (`hasRole`, `ROLE_GROUPS`) y **módulo** (`hasModule`).
5. `PageHeader` + estados de carga / error / vacío, los tres con texto en voseo.
6. Componentes firma donde correspondan (precio, vencimiento, lote, estado de stock, código de barras).
7. Toasts de éxito en participio; errores con `getErrorMessage`.
8. Probar en **375 px** y en escritorio, en **tema claro y oscuro** (cambialo con el botón de la barra superior, con
   la pantalla abierta: nada tiene que quedar con los colores del tema anterior). `npm run build` sin errores.
