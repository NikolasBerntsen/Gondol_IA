# API de la fundación (backend)

Documentación de los endpoints y contratos transversales que implementa la fundación del backend
(`com.gondolia.auth`, `security`, `common`, `realtime`, `notification`, `stock`, `recall`, `storage`, `ai`,
`bootstrap`). Complementa SPEC.md §4.2, §5, §6.1, §6.2 y §7. La sección 12 es la guía de uso del núcleo para los módulos.

- Base: `/api`. JSON en camelCase. Instantes en ISO-8601 UTC (`"2026-09-17T13:00:00Z"`), fechas `"2026-09-17"`.
- Swagger UI: `/api/swagger-ui.html` · OpenAPI: `/api/docs` (esquema `bearerAuth`; en `/api/tenant/**` se documenta
  el encabezado opcional `X-Branch-Id`).
- Salud: `GET /actuator/health` → `{"status":"UP"}` (público).

---

## 1. Autenticación

Autenticación stateless con JWT HS256 en el encabezado `Authorization: Bearer <token>`.
Claims: `sub` = id de usuario, `email`, `role`, `tid` = id del comercio (ausente/`null` para roles de plataforma),
`tv` = versión de token, `iss` = `gondolia`, `iat`, `exp`. Vida: `JWT_EXPIRATION_HOURS` (12 h por defecto).

En **cada request** autenticado se vuelve a validar al usuario contra la base (`UserAccessValidator`): que exista,
que esté activo, que `tv` coincida con `users.token_version` y, si es usuario de comercio, que el tenant esté `ACTIVE`.
Por eso cambiar la contraseña, desactivar un usuario o bloquear un comercio corta las sesiones abiertas al instante.

### 1.1 `POST /api/auth/login` (público)

Request:
```json
{"email": "Admin@ElSol.com", "password": "Demo2026!"}
```
El email se normaliza (recortado y en minúsculas). Validaciones: `email` obligatorio (≤150), `password` obligatoria (≤72).

Response `200`:
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "expiresAt": "2026-09-18T01:00:00Z",
  "user": {
    "id": 5, "email": "admin@elsol.com", "fullName": "Laura Gómez", "role": "TENANT_ADMIN", "mustChangePassword": false,
    "tenant": {"id": 2, "name": "Minimercado El Sol", "plan": "PROFESIONAL", "businessType": "MINIMERCADO",
               "currency": "ARS", "stockRotation": "FIFO", "maxBranches": 10},
    "branches": [{"id": 3, "name": "Sucursal Centro", "code": "CEN"}, {"id": 4, "name": "Sucursal Fisherton", "code": "FIS"}]
  }
}
```
Actualiza `users.last_login_at`.

Errores:
| Estado | `code` | Cuándo |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Falta email o contraseña (con `fieldErrors`) o JSON inválido |
| 401 | `BAD_CREDENTIALS` | Email inexistente o contraseña incorrecta ("El email o la contraseña son incorrectos") |
| 401 | `USER_DISABLED` | Usuario desactivado |
| 403 | `TENANT_DISABLED` | "El acceso de tu comercio está deshabilitado. Comunicate con GondolIA." |
| 403 | `TENANT_CANCELLED` | Comercio dado de baja |

Un token viejo o inválido en el encabezado no afecta al login (la ruta es pública y lo ignora).

### 1.2 `GET /api/auth/me` (autenticado)

Response `200`: el mismo `MeDto` que `user` en el login.
- Roles de plataforma (`PLATFORM_OWNER`, `SUPPORT_AGENT`): `tenant` = `null`, `branches` = `[]`.
- Usuarios de comercio: `tenant.maxBranches` sale del plan (FREEMIUM 1, BASICO 3, PROFESIONAL 10),
  `tenant.currency` y `tenant.stockRotation` de `tenant_settings`, y `branches` son las **sucursales activas
  accesibles**, ordenadas por nombre: todas las del tenant para `TENANT_ADMIN`/`TENANT_BOSS`; solo las asignadas en
  `user_branches` para `TENANT_EMPLOYEE`.

### 1.3 `POST /api/auth/change-password` (autenticado)

Request:
```json
{"currentPassword": "Demo2026!", "newPassword": "OtraClave2026!"}
```
Response `200`:
```json
{"token": "eyJhbGciOiJIUzI1NiJ9...", "expiresAt": "2026-09-18T01:05:00Z"}
```
Guarda el hash BCrypt, pone `must_change_password = false` e incrementa `token_version`: **todos los tokens anteriores
(incluido el usado en el request) dejan de funcionar** (401 `UNAUTHORIZED`); el frontend debe reemplazar el token
guardado por el devuelto.

Errores: 400 `VALIDATION_ERROR` (`newPassword` entre 8 y 72 caracteres), 400 `INVALID_CURRENT_PASSWORD`
("La contraseña actual no es correcta"), 400 `SAME_PASSWORD` (la nueva es igual a la actual).

### 1.4 Errores de sesión en cualquier endpoint protegido

| Estado | `code` | Cuándo |
|---|---|---|
| 401 | `UNAUTHORIZED` | Sin token, token mal formado, vencido, con firma inválida, usuario inexistente o token revocado |
| 401 | `USER_DISABLED` | El usuario fue desactivado |
| 403 | `TENANT_DISABLED` / `TENANT_CANCELLED` | El comercio fue deshabilitado o dado de baja |
| 403 | `FORBIDDEN` | El rol no puede usar ese prefijo o endpoint |

---

## 2. Reglas de acceso por prefijo

| Ruta | Acceso |
|---|---|
| `/api/auth/login`, `/api/integrations/pos/**`, `/actuator/health`, `/api/docs/**`, `/api/swagger-ui/**`, `/ws/**` | Público (el WebSocket autentica en el CONNECT de STOMP; el webhook POS con `X-API-Key`) |
| `/api/platform/**` | `PLATFORM_OWNER` |
| `/api/support/**` | `SUPPORT_AGENT` |
| `/api/tenant/**` | `TENANT_BOSS`, `TENANT_ADMIN`, `TENANT_EMPLOYEE` |
| resto de `/api/**` (`/api/auth/me`, `/api/notifications/**`, `/api/presence/**`, `/api/attachments/**`) | cualquier usuario autenticado |

El detalle fino se declara en cada controlador con `@PreAuthorize(Roles.X)` (`Roles.OWNER`, `SUPPORT`, `TENANT_ANY`,
`TENANT_ADMIN`, `TENANT_DASHBOARD`, `TENANT_INVENTORY`). Una denegación por método responde 403 `FORBIDDEN` con el
formato estándar.

CORS (solo desarrollo con Vite): origen `http://localhost:5173` (configurable con `APP_CORS_ALLOWED_ORIGINS`), métodos
`GET, POST, PUT, PATCH, DELETE, OPTIONS`, encabezados `Authorization`, `Content-Type`, `Accept`, `Accept-Language`,
`Cache-Control`, `X-Requested-With`, `X-Branch-Id`, `X-API-Key`; expone `Content-Disposition`.

---

## 3. Sucursales: encabezado `X-Branch-Id` y `BranchAccessService`

El frontend manda la sucursal elegida en `X-Branch-Id`:
- id numérico → esa sucursal;
- ausente, vacío o `all` (sin distinguir mayúsculas) → **todas las sucursales accesibles** (vista consolidada);
- cualquier otro valor → 400 `VALIDATION_ERROR`.

Acceso: `TENANT_ADMIN` y `TENANT_BOSS` acceden a todas las sucursales **activas** del tenant; `TENANT_EMPLOYEE` solo a
las activas asignadas en `user_branches`.

Servicio `com.gondolia.security.BranchAccessService` (lo usan los controladores de los módulos):

| Método | Comportamiento |
|---|---|
| `List<BranchRef> accessibleBranches()` | Sucursales activas accesibles por el usuario actual, por nombre. `[]` sin usuario de comercio. |
| `List<BranchRef> accessibleBranches(AuthUser user)` | Lo mismo para un usuario dado (fuera del contexto de seguridad). |
| `List<Long> scopeBranchIds()` | Lecturas. `[id]` si el encabezado trae un id accesible; todas las accesibles si falta o es `all`. |
| `Long requireSingleBranch(Long explicitBranchId)` | Escrituras. Explícito (p. ej. `branchId` del body) > encabezado > la única accesible. Si el alcance es "todas" y hay más de una → 400 `BRANCH_REQUIRED` ("Elegí una sucursal para esta operación"). Valida acceso. |
| `void assertAccess(Long branchId)` | 404 `NOT_FOUND` si la sucursal no es del tenant; 403 `BRANCH_FORBIDDEN` si está desactivada o el empleado no la tiene asignada; 400 `BRANCH_REQUIRED` si es `null`. |
| `boolean canAccess(Long userId, Long branchId)` | Usuario activo de comercio con acceso a esa sucursal activa de su tenant. |
| `List<Long> userIdsWithAccess(Long tenantId, Long branchId)` | Usuarios activos con acceso: jefes y administradores del tenant + empleados asignados. `[]` si la sucursal no es del tenant o está desactivada. |
| `Map<Long,String> branchNames(Long tenantId)` | Id → nombre de todas las sucursales del tenant (incluidas las desactivadas), por nombre. |
| `Optional<Long> requestedBranchId()` | Id crudo del encabezado, sin validar acceso (vacío si falta o es `all`). Útil para informar `scope: "ALL" \| "BRANCH"`. |

Errores del alcance:
| Estado | `code` | Cuándo |
|---|---|---|
| 400 | `BRANCH_REQUIRED` | Escritura con alcance "todas" y más de una sucursal accesible |
| 403 | `BRANCH_FORBIDDEN` | Sucursal no asignada al empleado, sucursal desactivada o usuario sin sucursales |
| 404 | `NOT_FOUND` | La sucursal no existe o es de otro comercio |
| 403 | `NO_TENANT` | Un usuario de plataforma llamó a una operación que requiere comercio |

Fuera de un request HTTP (tareas `@Scheduled`, listeners `@Async`, STOMP) no hay encabezado ni usuario:
`accessibleBranches()` y `scopeBranchIds()` devuelven `[]` sin fallar; `canAccess`, `userIdsWithAccess` y `branchNames`
funcionan igual; `requireSingleBranch` y `assertAccess` requieren un usuario autenticado (401 `UNAUTHORIZED` si no hay).

---

## 4. Formato de error

Todas las respuestas de error (controladores, filtro de seguridad y errores del contenedor) usan:
```json
{"timestamp":"2026-09-17T12:00:00.123Z","status":400,"error":"Bad Request","code":"VALIDATION_ERROR",
 "message":"Revisá los datos ingresados","path":"/api/tenant/products","fieldErrors":[{"field":"name","message":"es obligatorio"}]}
```
`fieldErrors` siempre está presente (lista vacía si no aplica).

Mapeo de `GlobalExceptionHandler`:
| Excepción | Respuesta |
|---|---|
| `ApiException` y subclases (`NotFoundException`, `BadRequestException`, `ForbiddenException`, `ConflictException`) | su estado, `code` y mensaje |
| Validación de `@Valid` / `@Validated` / parámetros | 400 `VALIDATION_ERROR` con `fieldErrors` |
| Tipo de parámetro inválido, parámetro/parte/encabezado faltante, JSON ilegible, `sort` inválido | 400 `VALIDATION_ERROR` |
| Archivo mayor al límite (15 MB por request) o multipart ilegible | 400 `INVALID_FILE` |
| `AccessDeniedException` | 403 `FORBIDDEN` |
| Ruta inexistente | 404 `NOT_FOUND` |
| Método no permitido / media type no soportado | 405 `METHOD_NOT_ALLOWED` / 415 `UNSUPPORTED_MEDIA_TYPE` |
| `DataIntegrityViolationException` por restricción (única, FK, check), bloqueo optimista/pesimista | 409 `CONFLICT` |
| Dato que no entra en su columna (SQLState `22…`: texto demasiado largo, número fuera de rango) | 400 `VALIDATION_ERROR` |
| Cualquier otra | 500 `INTERNAL_ERROR` (se registra en el log) |

Códigos comunes en `com.gondolia.common.error.ErrorCodes`: `VALIDATION_ERROR`, `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`,
`BAD_CREDENTIALS`, `TENANT_DISABLED`, `TENANT_CANCELLED`, `USER_DISABLED`, `NO_TENANT`, `CONFLICT`, `INSUFFICIENT_STOCK`,
`AI_UNAVAILABLE`, `BRANCH_REQUIRED`, `BRANCH_FORBIDDEN`, `BRANCH_LIMIT_REACHED`, `BRANCH_HAS_STOCK`,
`LOT_NOT_TRANSFERABLE`, `INVALID_FILE`, `INVALID_API_KEY`, `INTERNAL_ERROR`.

---

## 5. Paginación

`com.gondolia.common.PageResponse<T>`:
```json
{"content":[],"page":0,"size":20,"totalElements":123,"totalPages":7}
```
Parámetros `page` (desde 0), `size` (máximo 100) y `sort` (`campo,asc|desc`). Fábricas: `PageResponse.of(page)`,
`PageResponse.of(page, mapper)`, `PageResponse.of(content, page, size, totalElements)` (consultas JDBC) y
`PageResponse.empty(page, size)`.

---

## 6. API keys del POS (`ApiKeyService`)

Una key por sucursal: `gk_` + 40 caracteres base62. En `branches` se guardan solo `pos_api_key_hash` (SHA-256 hex) y
`pos_api_key_prefix` (primeros 10 caracteres). `resolveBranch(rawKey)` devuelve `PosBranch(tenantId, branchId)` solo si
la sucursal está activa y el comercio `ACTIVE`. El encabezado del webhook es `X-API-Key` (`ApiKeyService.HEADER`).

---

## 7. Dueño inicial

Al arrancar, `BootstrapRunner` crea el `PLATFORM_OWNER` con `APP_BOOTSTRAP_OWNER_EMAIL` / `APP_BOOTSTRAP_OWNER_PASSWORD`
(por defecto `dueno@gondolia.app` / `Gondolia2026!`) si todavía no existe ningún dueño.

---

## 8. Notificaciones (cualquier usuario autenticado)

`NotificationDto`:
```json
{"id":41,"type":"RECALL_ALERT","severity":"CRITICAL","title":"Alerta de recall: Sopa de tomate La Huerta 340 g",
 "body":"Sucursal Centro: el lote L2409A de Sopa de tomate La Huerta 340 g (vence 25/09/2026) está alcanzado por el recall ...",
 "link":"/app/recalls","referenceType":"RECALL_MATCH","referenceId":7,"read":false,"createdAt":"2026-09-17T14:10:00.123456Z"}
```
`type`: `ANNOUNCEMENT`, `RECALL_ALERT`, `ALERT`, `RECOMMENDATION`, `TICKET_MESSAGE`, `TICKET_STATUS`, `SYSTEM`.
`severity`: `INFO`, `WARNING`, `CRITICAL`. `link` es una ruta del frontend (puede ser `null`).

| Método y ruta | Respuesta |
|---|---|
| `GET /api/notifications?unreadOnly=false&page=0&size=20` | `PageResponse<NotificationDto>`, más recientes primero. `size` entre 1 y 100 (si no, 400 `VALIDATION_ERROR`). |
| `GET /api/notifications/unread-count` | `{"count":3}` |
| `POST /api/notifications/{id}/read` | 204. Idempotente. 404 `NOT_FOUND` si no existe o es de otro usuario. |
| `POST /api/notifications/read-all` | 204 |

Cada notificación nueva llega también en vivo por `/user/queue/notifications` (mismo `NotificationDto`), recién
cuando confirma la transacción que la creó.

## 9. Presencia de soporte

`GET /api/presence/support` (cualquier autenticado) → `{"agentsOnline":1}`: agentes `SUPPORT_AGENT` distintos con una
sesión STOMP abierta (un agente con varias pestañas cuenta una vez). Cada cambio se publica en
`/topic/support/presence` con el mismo cuerpo.

## 10. Archivos adjuntos

`GET /api/attachments/{id}` (autenticado) → los bytes de la imagen con su `Content-Type`,
`Content-Disposition: inline; filename="..."` y `Cache-Control: max-age=3600, private`.

| `purpose` | Pueden leerlo |
|---|---|
| `SUPPORT` | `SUPPORT_AGENT` y usuarios del comercio dueño del adjunto |
| `OCR` | usuarios del comercio dueño del adjunto |

En cualquier otro caso (otro comercio, dueño de la plataforma, id inexistente o archivo faltante en disco) responde
**404 `NOT_FOUND`**, sin revelar si existe. El frontend lo pide con el token (componente `AuthImage`).

Subida (la usan los módulos, p. ej. soporte): `AttachmentStorageService.store(file, purpose, tenantId, uploadedBy)`.
Solo PNG, JPEG, WEBP y GIF de hasta 10 MB; el tipo real se verifica por la firma del archivo (magic bytes), no solo por
el content-type declarado. Se guarda en `${APP_STORAGE_DIR}/yyyy/MM/<uuid>.<ext>` (`attachments.storage_key` es la ruta
relativa) y el archivo se borra si la transacción se revierte. Errores: 400 `INVALID_FILE` ("Adjuntá una imagen",
"La imagen supera el máximo de 10 MB", "Solo se aceptan imágenes PNG, JPG, WEBP o GIF").

## 11. WebSocket (STOMP)

- Endpoint **`/ws`**, WebSocket nativo (sin SockJS). Broker simple en memoria: prefijos `/topic` y `/queue`; destino de
  aplicación `/app`; destinos de usuario `/user`. Heartbeats de 10 s en ambos sentidos.
- Handshake público (mismo origen o `APP_CORS_ALLOWED_ORIGINS`). La autenticación va en el frame **CONNECT**:
  `Authorization: Bearer <jwt>`. Se validan firma, vigencia, usuario activo, versión de token y comercio habilitado.
  El `Principal` de la sesión es un `Authentication` cuyo principal es `AuthUser` (nombre = id del usuario).
- Un frame rechazado recibe `ERROR` con encabezados `message` (en español) y `code`, y **se cierra la conexión**:

| `code` | Cuándo |
|---|---|
| `UNAUTHORIZED` | CONNECT sin token ("Necesitás iniciar sesión para conectarte"), token inválido/vencido/revocado, o frame sin CONNECT previo |
| `USER_DISABLED` / `TENANT_DISABLED` / `TENANT_CANCELLED` | Usuario desactivado o comercio bloqueado (en CONNECT, o al revalidar) |
| `FORBIDDEN` | SUBSCRIBE o SEND a un destino no permitido |

- En SUBSCRIBE y SEND el usuario se vuelve a validar contra la base si pasaron más de 30 s desde la última validación.

Destinos (SPEC §7). Cualquier otro destino se rechaza, incluidas colas de otros usuarios (`/user/{id}/...`):

| SUBSCRIBE | Quién | Payload |
|---|---|---|
| `/user/queue/notifications` | cualquiera | `NotificationDto` |
| `/user/queue/security-alerts` | cualquiera | `RecallAlertMessage` |
| `/user/queue/session` | cualquiera | `{"type":"FORCE_LOGOUT","code":"TENANT_DISABLED","message":"..."}` |
| `/topic/support/presence` | cualquier autenticado | `{"agentsOnline":2}` |
| `/topic/support/queue` | `SUPPORT_AGENT` | `{"event":"TICKET_CREATED"\|"TICKET_UPDATED","ticket":TicketSummary}` (lo publica soporte) |
| `/topic/tickets/{ticketId}` | `SUPPORT_AGENT` o usuario del comercio dueño del ticket | eventos del chat (los publica soporte) |

| SEND | Quién |
|---|---|
| `/app/tickets/{ticketId}/typing` `{"typing":true}` | misma regla que `/topic/tickets/{ticketId}` (el `@MessageMapping` lo implementa soporte) |

Nunca se acepta SEND directo a `/topic`, `/queue` o `/user`.

`RecallAlertMessage`:
```json
{"matchId":7,"announcementId":3,"branchId":4,"branchName":"Sucursal Fisherton","title":"Retiro preventivo de sopa de tomate",
 "severity":"CRITICAL","reason":"Posible contaminación","instructions":"Retirá el producto de la góndola",
 "productId":10,"productName":"Sopa de tomate La Huerta 340 g","barcode":"7791234500012","lotId":55,"lotNumber":"L2409A",
 "expiryDate":"2026-12-01","quantity":12,"matchedAt":"2026-09-17T14:10:00.123456Z"}
```

Ejemplo de cliente (`@stomp/stompjs`):
```ts
const client = new Client({
  brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
  connectHeaders: { Authorization: `Bearer ${token}` },
  onConnect: () => client.subscribe('/user/queue/notifications', (m) => console.log(JSON.parse(m.body))),
  onStompError: (frame) => console.warn(frame.headers.code, frame.headers.message),
});
client.activate();
```

---

## 12. Guía del núcleo para los módulos

Los módulos **usan** estos servicios; no los modifican (SPEC §5.3).

### 12.1 Tiempo real: `RealtimePublisher`, `SessionTerminationService`, `PresenceTracker` (`com.gondolia.realtime`)

- `toUser(userId, queue, payload)`, `toUsers(userIds, queue, payload)`, `toEachUser(queue, Map<userId,payload>)` y
  `toTopic(destination, payload)`. `queue` va **sin** `/user` (constantes en `Destinations`: `QUEUE_NOTIFICATIONS`,
  `QUEUE_SECURITY_ALERTS`, `QUEUE_SESSION`, `TOPIC_SUPPORT_PRESENCE`, `TOPIC_SUPPORT_QUEUE`, `ticketTopic(id)`).
- Dentro de una transacción el envío ocurre **cuando confirma** (si se revierte no se envía); fuera, en el momento.
  `afterCommit(Runnable)` sirve para otras acciones posteriores al commit. Los errores de envío solo se registran.
- `SessionTerminationService.forceLogoutTenant(tenantId, code, message)` y `forceLogoutUser(userId, code, message)`:
  envían `FORCE_LOGOUT` a `/user/queue/session` de las sesiones abiertas y las cierran 1,5 s después. Usalo al deshabilitar
  o dar de baja un comercio, desactivar un usuario o resetear su contraseña (junto con `user.incrementTokenVersion()`).
- `PresenceTracker`: `agentsOnline()`, `isOnline(userId)`, `onlineAgentIds()` (p. ej. para `GET /api/support/agents`).

### 12.2 `NotificationService` (`com.gondolia.notification`)

```java
NotificationDraft draft = new NotificationDraft(NotificationType.ALERT, Severity.CRITICAL,
        "Sin stock: Leche entera", "Sucursal Centro se quedó sin stock", "/app/alerts", "ALERT", alertId);
notificationService.notifyUser(userId, draft);                                   // NotificationDto o null si está inactivo
notificationService.notifyUsers(List.of(id1, id2), draft);                       // usuarios activos de la lista
notificationService.notifyTenantUsers(tenantId, Set.of(Role.TENANT_ADMIN), draft); // null = todos los roles de comercio
notificationService.notifyBranchUsers(tenantId, branchId, null, draft);          // admins + jefes + empleados asignados
notificationService.notifyAllActiveTenants(draft, Set.of(BusinessType.KIOSCO));  // null = todos los rubros
notificationService.notifyPlatformRole(Role.SUPPORT_AGENT, draft);
List<Long> ids = notificationService.branchRecipients(tenantId, branchId, null); // mismos destinatarios que notifyBranchUsers
```
- Solo usuarios activos; los envíos por tenant, sucursal o rubro solo alcanzan comercios `ACTIVE`. Cada método devuelve
  cuántos recibieron. Todo se inserta con un único `INSERT ... SELECT` y el push sale al confirmar.
- `NotificationDraft`: `severity` null = `INFO`; el título se recorta a 200 caracteres; `link` ≤ 300; `referenceType` ≤ 30.
- Los métodos se unen a la transacción actual. Desde un `@TransactionalEventListener(phase = AFTER_COMMIT)` llamalos en
  un método `@Transactional(propagation = Propagation.REQUIRES_NEW)` (la transacción original ya confirmó).

### 12.3 `StockService` (`com.gondolia.stock`)

Los comandos y resultados son records anidados: `import com.gondolia.stock.StockService.ReceiveLotCommand;` etc.
El servicio **no** valida permisos del usuario (usá `BranchAccessService` en el controlador) pero sí que sucursal, producto,
lote y proveedor sean del `tenantId`. Todas las escrituras exigen sucursal activa (403 `BRANCH_FORBIDDEN` si no).

```java
// Carga de mercadería: SIEMPRE crea un lote nuevo + movimiento ENTRY y chequea recalls
ReceiveLotResult r = stockService.receiveLot(new ReceiveLotCommand(tenantId, branchId, productId, "L2409A",
        LocalDate.parse("2026-10-01"), 24, new BigDecimal("950"), supplierId, null /* ahora */, MovementSource.SCAN,
        CurrentUser.id(), null));
boolean quarantined = r.lot().getStatus() == LotStatus.RECALLED;       // r.recallMatches() no vacía
List<RecallInfo> recalls = recallMatchingService.toRecallInfos(r.recallMatches());
String warning = r.rotationWarning();                                  // null o el aviso de FIFO

// Venta (una llamada por producto; reusá batchRef para las líneas del mismo ticket)
SaleResult sale = stockService.registerSale(new SaleCommand(tenantId, branchId, productId, 3, null, null,
        MovementSource.MANUAL, CurrentUser.id(), batchRef /* null = se genera S-... */));
String ref = sale.movements().getFirst().getBatchRef();

// Ajuste / merma / retiro por recall
StockMovement m = stockService.adjust(new AdjustCommand(tenantId, lotId, MovementType.WASTE_EXPIRED, 5,
        "Vencido", MovementSource.MANUAL, CurrentUser.id()));

// Transferencia
TransferResult t = stockService.transfer(new TransferCommand(tenantId, fromBranchId, toBranchId,
        List.of(new TransferItem(lotId, 4)), "Reparto semanal", CurrentUser.id()));
```

Reglas (SPEC §4.2):
- **Vendible** = `ACTIVE`, `quantity > 0` y sin vencer. Rotación del comercio (`rotationFor(tenantId)`): FIFO
  `received_at, id`; FEFO `expiry_date NULLS LAST, received_at, id`. `lotsInRotationOrder(...)` devuelve los lotes en el
  orden en que se venderán (el primero es "Se vende primero").
- **Ingreso**: `receivedAt` null = ahora; `source` null = `MANUAL`; producto dado de baja → 409 `CONFLICT`; número de
  lote de más de 60 caracteres (`Lot.MAX_LOT_NUMBER_LENGTH`) → 400 `VALIDATION_ERROR`. Con FIFO,
  `rotationWarning` = "Este lote vence antes que mercadería que ingresó antes: con FIFO se venderá después. Revisalo o
  aplicá un descuento." si hay lotes vendibles que ingresaron antes y vencen después (o no vencen). Si el lote coincide
  con un recall queda `RECALLED` y no se informa el aviso.
- **Venta**: bloquea los lotes vendibles (`SELECT ... FOR UPDATE`, siempre en orden de id, igual que ajustes,
  transferencias y recalls) y los consume en el orden de rotación. Si una operación vende varios productos en la misma
  transacción, llamá a `registerSale` ordenando las líneas por `productId` para no bloquearte con otra venta
  concurrente. `unitPrice` null = `products.sale_price`;
  un lote con `discount_pct` vende a `precio × (1 − pct/100)` y el movimiento guarda `discount_pct`. El vencimiento se
  evalúa a la **fecha de la venta** (`occurredAt` en hora de negocio), así las ventas históricas (CSV, demo) usan los lotes
  que eran vendibles ese día. Si falta stock, el faltante es un `SALE` con `lotId` null y se abre la alerta
  `SALE_WITHOUT_STOCK` (WARNING, `dedupe_key = SALE_WITHOUT_STOCK:{branchId}:{productId}`). `SaleResult.totalAmount`
  incluye el faltante.
- **Ajustes**: tipos `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `WASTE_EXPIRED`, `WASTE_DAMAGED`, `RECALL_REMOVAL` (otro → 400).
  Una salida mayor al remanente → 409 `INSUFFICIENT_STOCK`. Estados: a 0 → `DEPLETED`; por `WASTE_EXPIRED` →
  `EXPIRED_DISCARDED`; `RECALLED` sigue `RECALLED`. `ADJUSTMENT_IN` sobre un lote `DEPLETED` o `EXPIRED_DISCARDED` lo
  vuelve a `ACTIVE`. `batchRef` = `A-...`.
- **Transferencias**: origen ≠ destino (400), ambas sucursales activas del tenant, lotes de la sucursal de origen (400),
  sin lotes repetidos (400). Lote `RECALLED` o vencido → 409 `LOT_NOT_TRANSFERABLE`; remanente insuficiente → 409
  `INSUFFICIENT_STOCK`. Se valida todo antes de modificar. El lote destino copia número, vencimiento, costo, proveedor y
  el `received_at` original, con `origin_lot_id`; `TRANSFER_OUT` y `TRANSFER_IN` comparten `batchRef` `T-...` y la nota
  como `reason`. Cada lote destino pasa por `checkLot`.
- **Importes de los movimientos**: en `SALE`, `unitPrice`/`totalAmount` son el precio cobrado; en el resto, el costo
  unitario (`lots.cost_price` o, si es null, `products.cost_price`) y el valor a costo.
- **Consultas agregadas**: `sellableStock(tenantId, branchId, productId)`, `sellableStockByProduct(tenantId, branchIds)`
  (suma del scope) y `sellableStockByBranchAndProduct(tenantId, branchIds)` (`branchId → productId → stock`); solo
  incluyen combinaciones con stock (ausente = 0).
- **Eventos**: `StockChangedEvent(tenantId, branchId, productId)` por cada sucursal/producto modificado y
  `LotReceivedEvent` por cada lote creado (ingreso o destino de transferencia).
- `BatchRefs.sale(clock)` / `adjustment(clock)` / `transfer(clock)` generan referencias
  `S-20260917143012-K3F9QZ` si el módulo necesita una antes de llamar al servicio.
- **Rechazos y transacciones**: toda validación lanza `ApiException` antes de escribir, y esas excepciones no marcan
  como rollback-only la transacción del que llama. Un proceso por lotes (importación CSV, webhook o simulador POS) puede
  capturar el rechazo de una línea dentro de su `@Transactional` y seguir con las demás (lo mismo vale para
  `BranchAccessService`). Cualquier otra excepción sí revierte.

### 12.4 `RecallMatchingService` (`com.gondolia.recall`)

- `findActiveRecalls(barcode, lotNumber, expiryDate)` → `List<RecallInfo>` sin efectos (chequeo previo a la carga).
- `matchAnnouncement(announcementId)`: barre lotes `ACTIVE`/`RECALLED` con remanente de tenants `ACTIVE`. Llamalo al
  publicar un recall. Si el aviso no es un recall `PUBLISHED`, devuelve `[]`.
- `checkLot(tenantId, lotId)`: contra recalls `PUBLISHED`; lo llaman `receiveLot` y `transfer`, y debe llamarse al
  corregir número o vencimiento de un lote.
- `toRecallInfos(matches)`: recalls distintos de un conjunto de coincidencias (para las respuestas `recalls:[RecallInfo]`).
- Coincidencia: mismo código de barras y (`recall_all_lots` o número de lote normalizado en la lista) y, si hay rango,
  vencimiento dentro (solo "desde" o solo "hasta" también se respetan; lote sin vencimiento no coincide con un rango).
- Devuelven las coincidencias vigentes (nuevas y previas). Solo para las **nuevas**: `recall_matches` con `branch_id`
  (idempotente por `(announcement_id, lot_id)`), lote en `RECALLED`, alerta `RECALL_MATCH` CRITICAL con `branch_id`
  (`dedupe_key = RECALL:{announcementId}:{lotId}`), notificación `RECALL_ALERT` CRITICAL (link `/app/recalls`,
  `referenceType = RECALL_MATCH`, `referenceId` = id de la coincidencia) y push `RecallAlertMessage` a los usuarios con
  acceso a esa sucursal, `announcements.affected_tenants_count` actualizado, `StockChangedEvent` si el lote pasó a
  cuarentena y un `RecallMatchedEvent(tenantId, announcementId, matchIds)` por tenant.

### 12.5 Alertas idempotentes: `OpenAlertWriter` (`com.gondolia.domain.alert`)

`Optional<Long> openIfAbsent(Alert alert)` inserta la alerta en `OPEN` salvo que ya exista una `OPEN`/`ACKNOWLEDGED` del
tenant con la misma `dedupe_key` (usa `ON CONFLICT` sobre el índice parcial, así nunca falla por concurrencia).
Obligatorios: `tenantId`, `type`, `severity`, `title`, `dedupeKey` (≤ 150). Devuelve el id creado o vacío.

### 12.6 `AiClient` (`com.gondolia.ai`)

`isHealthy()`, `health()`, `analyze(AnalyzeRequest)`, `ocr(bytes, filename, contentType)`,
`barcode(bytes, filename, contentType)`. DTOs en `com.gondolia.ai.dto` (espejo de SPEC §8: `AnalyzeRequest`,
`AnalyzeSettings`, `ProductInput`, `DailySale`, `LotInput`, `FeedbackInput`, `FeedbackOutcome`, `AnalyzeResponse`,
`ProductAnalysis`, `ForecastPoint`, `Anomaly`, `LotRisk`, `RecommendationResult`, `AnalyzeSummary`, `OcrResponse`,
`DateCandidate`, `LotCandidate`, `BarcodeValue`, `BarcodeResponse`, `HealthResponse`). Timeouts: conexión 3 s, analyze
120 s, OCR y códigos 60 s, health 5 s. Servicio caído, error 5xx o respuesta ilegible → 503 `AI_UNAVAILABLE`
("El servicio de IA no está disponible"); imagen rechazada por el servicio (400/413/415/422) → 400 `INVALID_FILE` con su
mensaje.

---

## 13. Fixture de desarrollo (`APP_DEV_FIXTURE=true`)

`DevFixtureRunner` (idempotente, corre después del dueño inicial). Contraseña de todos: `Demo2026!`.

| Comercio | Sucursales | Usuarios |
|---|---|---|
| Comercio de Prueba (ALMACEN, BASICO, FIFO) | Sucursal Centro (CEN), Sucursal Norte (NOR) | `jefe@prueba.com` (jefe), `admin@prueba.com` (admin), `empleado@prueba.com` (empleado, solo Centro) |
| Otro Comercio (KIOSCO, FREEMIUM) | Sucursal Principal | `admin@otro.com` (admin) |
| — | — | `soporte@gondolia.app` (soporte) |

Catálogo de "Comercio de Prueba": proveedor "Distribuidora La Pampa", categorías Lácteos y Almacén, y 3 productos con 2
lotes en Centro y 1 en Norte (cargados con `StockService.receiveLot`, origen `SEED`): Leche entera La Pradera 1 L
(`7791234000012`), Yogur bebible frutilla Vaquita 1 L (`7791234000029`; en Centro el lote más nuevo vence antes que el
más viejo, caso del aviso de FIFO) y Galletitas de agua Crocantes 200 g (`7791234000036`).

---

## 14. Pruebas

- `mvn test`: pruebas sin base de datos (utilidades, JWT, API keys, `BranchAccessService`, seguridad HTTP con MockMvc,
  interceptor STOMP, `RealtimePublisher`, `PresenceTracker`, almacenamiento de adjuntos, `AiClient` contra un servidor
  HTTP local, `BatchRefs`, `NotificationDraft`). Las pruebas contra PostgreSQL se omiten.
- Pruebas de integración contra PostgreSQL real (`FoundationDatabaseTest`, `StockServiceIntegrationTest`,
  `RecallMatchingServiceIntegrationTest`, `NotificationServiceIntegrationTest` —cada prueba se revierte— y
  `RealtimeEndToEndTest`, que levanta el servidor en un puerto aleatorio y usa clientes STOMP reales; confirma sus datos
  y los borra al final):
  ```bash
  docker exec gondolia-dev-pg createdb -U postgres gondolia_it   # una sola vez
  mvn test -Dgondolia.it=true                                     # o GONDOLIA_IT=true
  # otra base: -Dgondolia.it.db.url=jdbc:postgresql://host:puerto/base -Dgondolia.it.db.user=... -Dgondolia.it.db.password=...
  ```
