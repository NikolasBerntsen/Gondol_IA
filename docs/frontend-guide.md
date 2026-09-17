# Guía del frontend de GondolIA

Guía para los módulos que construyen pantallas sobre la fundación del frontend (SPEC §9).
Stack: React 18 + TypeScript estricto + Vite 5 + Tailwind 3 + React Router 6 + TanStack Query 5 + STOMP.

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173 (proxy /api y /ws → http://localhost:8080)
npm run build      # tsc -b && vite build (tiene que pasar sin errores)
npm run typecheck
```

- Backend en otro puerto/host: `GONDOLIA_BACKEND_URL=http://192.168.0.10:8080 npm run dev`.
- El dev server escucha en la red local (`host: true`); para usar la cámara desde el celular hace falta HTTPS (ver §10).
  El proxy reescribe el header `Origin` al del backend, así que abrirlo desde otra IP (celular, `127.0.0.1`) no choca con el CORS del backend.
- Variables opcionales (`frontend/.env.local`): `VITE_HTTPS_PORT` (defecto `8443`), `VITE_SHOW_DEMO_ACCOUNTS=false` para ocultar las cuentas demo del login.

---

## 1. Estructura y propiedad

```
src/
├── App.tsx                     rutas (solo fundación)
├── api/        client.ts (axios + helpers), types.ts (enums/DTOs compartidos), auth.ts, notifications.ts
├── auth/       AuthContext (useAuth), RequireAuth, RequireRole, tokenStorage, roleHome
├── branches/   BranchContext (useBranch, useBranchQueryKey, useWriteBranch), BranchSelector, BranchPicker, branchColumn
├── realtime/   StompProvider, useStompSubscription, useStompPublish, useSessionEvents
├── components/ui/          componentes base (importar desde '@/components/ui')
├── components/layout/      AppShell, Sidebar, Topbar, NotificationBell, UserMenu, Logo
├── components/notifications/  NotificationItem, useNotificationActions
├── config/     navigation.ts (menú por rol), access.ts (ROLE_GROUPS, canAccessPath)
├── lib/        format.ts, cn.ts, useDebounce.ts, queryClient.ts, secureContext.ts, focus.ts, scrollLock.ts
├── pages/      Login, Profile, Notifications, NotFound, Forbidden
└── features/<modulo>/      pages/ (ya existen como placeholder), api.ts, types.ts, components/, hooks/
```

Reglas:

- Cada módulo **solo** toca `src/features/<modulo>/`. Reemplazá el contenido de tus páginas placeholder
  manteniendo el nombre del archivo y `export default function NombrePage()`.
- Imports con alias `@/` (`import { Button } from '@/components/ui'`).
- Todo texto visible en español rioplatense ("Cargá", "Elegí", "No tenés…").
- Si necesitás algo en la fundación (un componente, un tipo compartido), pedilo o hacé el cambio mínimo compatible y reportalo.

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
| `uploadFile<T>(url, file, { fieldName='file', fields, fileName, method, onProgress, signal })` | `multipart/form-data`. `fields` agrega campos de texto (p. ej. `{ body: 'Hola' }` para soporte). `file` puede ser `null` si solo mandás campos. |
| `fetchBlob(url)` | Descarga autenticada como `Blob` (imágenes). Acepta rutas con o sin `/api`. |
| `downloadFile(url, fallbackName, params?)` | Descarga y guarda un archivo (p. ej. plantilla CSV). |
| `getErrorMessage(err, fallback?)` | Mensaje en español para mostrar (usa `ErrorResponse.message`). |
| `getFieldErrors(err)` | `{ campo: mensaje }` de un `VALIDATION_ERROR`. |
| `isApiError(err, ...codes)` | Type guard: `isApiError(err, 'BRANCH_REQUIRED')`. |

`ApiError` tiene `status` (0 = sin conexión), `code`, `message`, `fieldErrors`, `is(...codes)`, `fieldError(campo)`, `fieldErrorMap`.

Opciones extra de `config` (axios):

- `branch`: sucursal solo para esa request (`branch: 4`, `branch: 'all'`, `branch: null` = sin header).
- `skipAuthHandling: true`: un 401/403 de sesión no cierra la sesión (solo flujos especiales).

Comportamiento global (no lo repliquen): 401 → cierra sesión y va a `/login?motivo=sesion`; 403 con
`TENANT_DISABLED | TENANT_CANCELLED | USER_DISABLED` → cierra sesión mostrando el mensaje del backend;
403 `BRANCH_FORBIDDEN` (sucursal desactivada o desasignada con la sesión abierta) → vuelve a pedir `/api/auth/me`
y, si la sucursal elegida ya no es accesible, el selector vuelve al valor por defecto y se refrescan las queries.

---

## 3. Sucursales

El catálogo es por comercio; stock, lotes, ventas, alertas, IA y recalls son **por sucursal** (SPEC §3.5).
El usuario elige la sucursal en el topbar (`BranchSelector`); la fundación la persiste
(`gondolia.branch.<userId>`), la manda en `X-Branch-Id` e invalida todas las queries al cambiarla.
Default: jefe/admin con más de una sucursal → "Todas las sucursales"; si no, la primera por nombre. Una sucursal
guardada que ya no es accesible se descarta sola.

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

Datos que no dependen de la sucursal (categorías, proveedores, usuarios, avisos, tickets) no necesitan el segmento.
Usá siempre `useBranchQueryKey` (no armes el `{ branch }` a mano): al cambiar de sucursal la fundación reconoce ese
segmento final para no repetir requests que ya salieron con la sucursal nueva.

### 3.2 Escrituras: `useWriteBranch` + `BranchPicker`

Las escrituras por sucursal (carga de lotes, ventas, ajustes, descarte, resolución de recall, simulador POS) requieren
**una** sucursal. Con "Todas las sucursales" y más de una sucursal accesible hay que pedirla en el formulario:

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

- `BranchPicker` no se muestra si hay una sucursal elegida en el topbar (usá `alwaysVisible` para casos como el destino de una transferencia; `excludeIds` para excluir la de origen).
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
- Keys como arrays que empiezan con el recurso: `['products', 'list', params]`, `['products', 'detail', id]`.
  Definilas en `features/<modulo>/api.ts` (p. ej. `productKeys`) y agregá la sucursal con `useBranchQueryKey` cuando corresponda.
- Listas paginadas: `placeholderData: keepPreviousData` para no parpadear al cambiar de página.
- Después de mutar: `invalidateQueries` con el prefijo del recurso (y de los recursos afectados: una venta cambia stock, vencimientos y dashboard).
- Errores de mutaciones: si la mutación **no** define `onError`, la fundación muestra `toast.error(getErrorMessage(err))`.
  Si la manejás vos (mensaje en el formulario), definí `onError` o `meta: { errorToast: false }`.
  Si pasás `onError` recién en `mutate(vars, { onError })`, agregá `meta: { errorToast: false }` para no duplicar.
- Éxitos: `toast.success('Guardaste el producto.')` (import de `sonner`).
- Estados de carga/error/vacío: `isPending` → esqueleto o `PageSpinner`; `isError` → `<ErrorState error={q.error} onRetry={q.refetch} />`; sin datos → `<EmptyState />`.

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

## 5. Autenticación y roles

```ts
const { me, isTenantUser, isPlatformUser, hasRole, logout, refreshMe } = useAuth();
const me = useCurrentUser();       // MeDto garantizado (dentro de rutas protegidas)
me.tenant?.stockRotation;          // 'FIFO' | 'FEFO'
me.branches;                       // sucursales accesibles
hasRole('TENANT_ADMIN');           // p. ej. mostrar "Eliminar" solo al admin
```

- Grupos de roles (espejo de `Roles.java`): `ROLE_GROUPS` en `@/config/access` (`TENANT_DASHBOARD`, `TENANT_INVENTORY`, `TENANT_ADMIN`, `TENANT_ANY`, `OWNER`, `SUPPORT`).
- Las rutas ya están protegidas en `App.tsx`; dentro de la página ocultá acciones según la matriz §3.3 (el backend igual valida).
- Etiquetas en español para enums: `ROLE_LABELS`, `PLAN_LABELS`, `BUSINESS_TYPE_LABELS`, `STOCK_ROTATION_LABELS`,
  `LOT_STATUS_LABELS`, `MOVEMENT_TYPE_LABELS`, `MOVEMENT_SOURCE_LABELS`, `EXPIRY_BUCKET_LABELS`, `STOCK_STATUS_LABELS`,
  `REORDER_STATUS_LABELS`, `ALERT_TYPE_LABELS`, `ALERT_STATUS_LABELS`, `SEVERITY_LABELS`, `SALES_PATTERN_LABELS`,
  `RECOMMENDATION_TYPE_LABELS`, `RECOMMENDATION_STATUS_LABELS`, `ANNOUNCEMENT_*_LABELS`, `RECALL_*_LABELS`,
  `TICKET_*_LABELS`, `NOTIFICATION_TYPE_LABELS`, `TENANT_STATUS_LABELS` — todas en `@/api/types`.

---

## 6. Tiempo real (STOMP)

La conexión única la maneja `StompProvider` (conecta al iniciar sesión, reconecta con backoff y re-suscribe).

```ts
import { useStompSubscription } from '@/realtime/useStompSubscription';
import { useStompPublish } from '@/realtime/useStompPublish';
import { useStompConnected } from '@/realtime/StompProvider';

// Se suscribe al montar, se cancela al desmontar; sobrevive reconexiones y cambios de handler.
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

- `/user/queue/session` → `FORCE_LOGOUT` cierra sesión con toast (`useSessionEvents`).
- `/user/queue/notifications` → contador, lista y toast de la campana (`NotificationBell`).
- `/user/queue/security-alerts` lo consume `SecurityAlertHost` (módulo D).

---

## 7. Componentes UI (`@/components/ui`)

| Componente | Props principales |
|---|---|
| `Button` | `variant: primary \| secondary \| outline \| ghost \| danger \| danger-outline \| link`, `size: sm \| md \| lg \| xl \| icon \| icon-sm`, `loading`, `leftIcon`, `rightIcon`, `fullWidth`. `type="button"` por defecto. |
| `ButtonLink` | Igual que `Button` pero es un `<Link to>` de react-router. `buttonClasses({...})` para otros elementos. |
| `Card`, `CardHeader`, `CardFooter` | `Card padding: none \| sm \| md \| lg`. `CardHeader title description icon actions`. |
| `Badge` | `tone: neutral \| brand \| success \| warning \| orange \| danger \| info \| purple`, `size`, `dot`, `icon`. Tonos listos: `severityTone`, `expiryBucketTone`, `stockStatusTone`, `reorderStatusTone`. |
| `Alert` | `tone: info \| success \| warning \| danger \| brand`, `title`, `icon` (o `null`), `action`. |
| `Input` | `invalid`, `inputSize: sm \| md \| lg`, `leftIcon`, `rightElement`. En mobile usa 16 px (evita el zoom de iOS). |
| `Select` | `options: {value,label,disabled}[]`, `placeholder` (opción vacía), `invalid`, `selectSize`, `leftIcon`. Nativo. |
| `Textarea` | `invalid`, `rows`. |
| `Checkbox` | `label`, `description`. |
| `Toggle` | `checked`, `onChange(checked)`, `label`, `description`, `size: sm \| md`, `ariaLabel`. |
| `Field` | `label`, `hint`, `error`, `required`, `optional`, `labelAction`. Conecta `id`/`aria-*` con su hijo. |
| `SearchInput` | `value`, `onValueChange`, `onSearch` (Enter), `placeholder`, `inputSize`. Combinar con `useDebounce`. |
| `Modal` | `open`, `onClose`, `title`, `description`, `footer`, `size: sm \| md \| lg \| xl \| full`, `preventClose`, `closeOnOverlayClick`, `initialFocusRef`. Portal, ESC, foco atrapado; hoja inferior en mobile. `data-autofocus` marca el control inicial. |
| `ConfirmDialog` | `open`, `onClose`, `onConfirm` (puede devolver promesa), `title`, `description`, `confirmLabel`, `tone: primary \| danger`, `children` (p. ej. campo "motivo"). |
| `Table<T>` | `columns: TableColumn<T>[]` (`id`, `header`, `cell(row)`, `align`, `className`, `headerClassName`, `hideBelow: lg \| xl`, `mobile: title \| subtitle \| aside \| field \| actions \| hidden`, `mobileLabel`), `data`, `rowKey`, `loading`, `error`, `onRetry`, `empty: {icon,title,description,action}`, `onRowClick`, `rowClassName`, `mobileLayout: cards \| scroll`, `renderMobileCard`, `dense`, `footer`, `caption`. |
| `Pagination` | `page` (0-based), `totalPages`, `totalElements`, `size`, `onPageChange`, `disabled`. `pageInfo(data)` arma las props desde un `PageResponse`. |
| `Tabs<V>` | `tabs: {value,label,icon,count,disabled}[]`, `value`, `onChange`, `variant: underline \| pills`, `ariaLabel`, `idPrefix`. |
| `PageHeader` | `title`, `description`, `icon`, `actions`, `back: {to,label}`, `children` (filtros/tabs debajo). |
| `StatCard` | `label`, `value`, `icon`, `tone: brand \| orange \| red \| amber \| sky \| violet \| slate`, `hint`, `trend: {value,label,invert}`, `loading`, `to`. |
| `EmptyState` | `icon`, `title`, `description`, `action`, `size: sm \| md`, `bordered`. |
| `ErrorState` | `error` (se traduce), `message`, `title`, `onRetry`, `retrying`, `size`. |
| `Spinner`, `PageSpinner`, `Skeleton` | Cargas en línea, de sección y esqueletos (`<Skeleton className="h-4 w-32" />`). |
| `AuthImage` | `src` (ruta `/api/...` protegida), `alt`, `fallback`, `placeholderClassName` + props de `<img>`. |
| `Avatar` | `name`, `size: sm \| md \| lg \| xl`. |
| `useDropdown`, `DropdownPanel`, `DropdownItem`, `DropdownSeparator`, `DropdownLabel` | Menús desplegables accesibles (click afuera, ESC, flechas). |
| `SecureContextWarning` | Aviso de cámara en HTTP (ver §10). |

Ejemplo de tabla responsive:

```tsx
const columns: Array<TableColumn<LotRow> | null> = [
  { id: 'product', header: 'Producto', mobile: 'title', cell: (r) => r.productName },
  useBranchColumn<LotRow>(),
  { id: 'expiry', header: 'Vencimiento', cell: (r) => formatDate(r.expiryDate) },
  { id: 'qty', header: 'Cantidad', align: 'right', cell: (r) => formatNumber(r.quantity) },
  {
    id: 'bucket', header: 'Estado', mobile: 'aside',
    cell: (r) => <Badge tone={expiryBucketTone(r.bucket)} dot>{EXPIRY_BUCKET_LABELS[r.bucket]}</Badge>,
  },
];

<Card padding="none">
  <Table
    columns={columns}
    data={query.data?.content}
    rowKey={(r) => r.lotId}
    loading={query.isPending}
    error={query.error}
    onRetry={() => query.refetch()}
    empty={{ icon: CalendarCheck, title: 'No hay vencimientos próximos' }}
    footer={<Pagination {...pageInfo(query.data)} onPageChange={setPage} />}
  />
</Card>
```

Si una fila tiene `onRowClick` y además botones, frená la propagación en los botones (`event.stopPropagation()`).

---

## 8. Formato (`@/lib/format`)

Siempre es-AR y zona `America/Argentina/Buenos_Aires`. Valores nulos → `—`.

| Función | Ejemplo |
|---|---|
| `formatMoney(8450000)` | `$ 8.450.000` (sin decimales si es entero; `$ 1.234,50` si no) |
| `formatNumber(1234.5)` · `formatNumber(x, { decimals: 1 })` | `1.234,5` |
| `formatPercent(37.5)` (0..100) · `formatRatio(0.78)` (0..1) | `37,5%` · `78%` |
| `formatDate('2026-09-25')` / instante | `25/09/2026` (los `LocalDate` no se corren por UTC) |
| `formatDateTime(instante)` · `formatTime(instante)` | `25/09/2026 14:30` · `14:30` |
| `formatLongDate()` | `Jueves, 17 de septiembre de 2026` |
| `formatShortDate('2026-09-17')` | `17 sep` (ejes de gráficos) |
| `formatRelative(instante)` | `recién`, `hace 5 min`, `hace 2 h`, `ayer`, `hace 3 días`, luego la fecha |
| `daysUntil('2026-09-25')` · `formatDaysLeft(8)` | `8` · `Vence en 8 días` / `Vence hoy` / `Venció hace 3 días` |
| `todayLocalDate()` · `toLocalDateString(date)` | `2026-09-17` (para la API y `<input type="date">`) |
| `pluralize(3, 'producto')` · `formatBytes(n)` · `initials(nombre)` · `capitalize(s)` | `3 productos` · `1,2 MB` · `LG` |

Otros: `cn(...)` combina clases de Tailwind; `useDebounce(value, 300)`.

---

## 9. Layout y mobile

- `AppShell` ya pone sidebar, topbar y el contenedor (`max-w-7xl`, padding responsive). La página solo renderiza su contenido:
  `PageHeader` + secciones. No agreguen `min-h-screen`, fondos de página ni otro contenedor centrado.
- Rutas que comparten página (`/support` y `/support/tickets/:id`, `/app/support` y `/app/support/:id`) no se remontan al
  navegar entre ellas: el estado de la lista se conserva y el detalle se decide con `useParams()`. Si una página falla al
  renderizar, `AppShell` muestra un error recuperable y lo descarta al cambiar de ruta.
- Breakpoints: sidebar fija desde `lg` (1024 px), drawer debajo. Diseñá **mobile first** (empleados usan el celular):
  - Tablas: `Table` pasa a tarjetas debajo de `md`; definí `mobile: 'title'` en la columna principal.
  - Grillas: `grid gap-4 sm:grid-cols-2 xl:grid-cols-4` para `StatCard`.
  - Acciones de formulario: `flex flex-col-reverse gap-2 sm:flex-row sm:justify-end`; en la carga con cámara usá `size="lg"`/`"xl"` y `fullWidth`.
  - Filtros: apilados en mobile (`flex flex-col gap-3 md:flex-row`).
  - Dejá aire abajo: el `SupportWidget` flota abajo a la derecha (el shell ya agrega `pb-24` en mobile).
- Estilo: tarjetas blancas `rounded-2xl border border-slate-200/70 shadow-sm` (`Card`), verde `brand-*` para acciones,
  ámbar/naranja por vencer/bajo, rojo crítico/vencido/recall, celeste informativo. Íconos de `lucide-react` con `aria-hidden`.
- Todas las pantallas muestran estados de carga, error (con reintento) y vacío con textos en español.
- Accesibilidad: botones solo-ícono con `aria-label`, `Field` para etiquetas, foco visible (ya incluido en los componentes).
- Navegación del sidebar: `src/config/navigation.ts` (lo mantiene la fundación). El buscador del topbar navega a `/app/inventory?q=<texto>`:
  la página de inventario debe leer `q` de `useSearchParams`.

---

## 10. Cámara y contexto seguro

Los navegadores solo permiten la cámara en HTTPS o `localhost`. En pantallas con escáner/OCR:

```tsx
import { SecureContextWarning } from '@/components/ui';
import { isCameraSupported } from '@/lib/secureContext';

<SecureContextWarning className="mb-4" />            {/* solo aparece si hace falta */}
{isCameraSupported() ? <Scanner /> : <ManualBarcodeInput />}
```

`getSecureUrl()` devuelve la URL equivalente en `https://<host>:<VITE_HTTPS_PORT|8443>`; `LOCAL_CA_CERT_PATH` es el certificado de la CA local que sirve nginx.

---

## 11. Checklist de una página nueva

1. Tipos en `features/<modulo>/types.ts` y funciones en `features/<modulo>/api.ts` usando los helpers de `@/api/client`.
2. Queries con keys por sucursal (`useBranchQueryKey`) si los datos son por sucursal; columna "Sucursal" con `useBranchColumn`.
3. Escrituras por sucursal con `useWriteBranch` + `BranchPicker` y `branchId` en el body.
4. `PageHeader` + estados de carga / error / vacío.
5. Acciones visibles según rol (`hasRole` / `ROLE_GROUPS`), toasts de éxito, errores con `getErrorMessage`.
6. Probar en 375 px de ancho y en escritorio. `npm run build` sin errores.
