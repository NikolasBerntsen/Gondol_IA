# Módulo E — Soporte con chat en vivo (`com.gondolia.support`)

Tickets y chat en vivo entre los comercios y el equipo de soporte de GondolIA (SPEC §6.8 y §7).
Backend: `com.gondolia.support`. Frontend: `frontend/src/features/support`.

Dos lados de la misma conversación:

| Lado | Base | Rol | Qué ve |
|---|---|---|---|
| Comercio | `/api/tenant/support/tickets` | `TENANT_ANY` (jefe, administrador, empleado y cajero) | Solo los tickets de **su** comercio |
| Soporte | `/api/support` | `SUPPORT_AGENT` | Los tickets de todos los comercios |

**Soporte no es un módulo opcional** (SPEC §14.1): está siempre disponible, sin `@RequiresModule`.
Tampoco depende de la sucursal: no usa `X-Branch-Id` ni `BranchAccessService` (una consulta es del comercio, no de un local).

## 1. Aislamiento y permisos

- El `tenantId` sale siempre de `CurrentUser`, nunca del request. Todo acceso por id usa
  `findByIdAndTenantId(id, tenantId)` → **404 `NOT_FOUND`** si el ticket es de otro comercio (no se revela que existe).
- `SUPPORT_AGENT` no entra a `/api/tenant/**` y los usuarios de comercio no entran a `/api/support/**` → **403 `FORBIDDEN`**
  (lo aplican `SecurityConfig` por prefijo y `@PreAuthorize` en cada controlador). `PLATFORM_OWNER` tampoco entra a la
  consola de soporte.
- Los adjuntos van por el núcleo (`GET /api/attachments/{id}`): los lee el agente o un usuario del mismo comercio; el
  resto recibe 404.
- El agente ve del comercio solo datos **administrativos** (`tenantName`, `tenantPlan`, `tenantBusinessType`) y de la
  persona (`createdByName`, `createdByRole`): nunca stock, ventas ni alertas (SPEC §3.4).

## 2. DTOs

```jsonc
// TicketSummary
{
  "id": 1, "tenantId": 1, "tenantName": "Comercio de Prueba",
  "tenantPlan": "BASICO", "tenantBusinessType": "ALMACEN",     // extras para el panel del agente (ver §7)
  "subject": "El escáner no lee los códigos EAN-13",
  "category": "TECNICO", "priority": "ALTA", "status": "IN_PROGRESS", "channel": "CHAT",
  "createdById": 3, "createdByName": "Ana Rodríguez", "createdByRole": "TENANT_ADMIN",
  "assignedToId": 6, "assignedToName": "Sofía Martínez",
  "lastMessageAt": "2026-09-17T23:47:11.402Z",
  "lastMessagePreview": "Listo, probamos y te confirmo en un rato.",
  "unreadCount": 1,                                            // depende de quién mira (ver §3)
  "createdAt": "2026-09-17T23:47:02.118Z", "resolvedAt": null, "rating": null
}

// MessageDto
{
  "id": 10, "ticketId": 1, "senderId": 6, "senderName": "Sofía Martínez",
  "senderType": "AGENT",                                       // CUSTOMER | AGENT | SYSTEM
  "body": "Probá recalibrando el lector.",                     // puede ser null si solo hay imagen
  "attachment": { "id": 3, "url": "/api/attachments/3", "contentType": "image/png",
                  "originalName": "captura.png", "sizeBytes": 2418 },
  "createdAt": "2026-09-17T23:47:11.402Z"
}

// TicketDetail = TicketSummary + { messages: [MessageDto], ratingComment, firstResponseAt }
```

Enums (SPEC §4.1): `TicketCategory` TECNICO · USO · FACTURACION · SUGERENCIA · OTRO ·
`TicketPriority` BAJA · MEDIA · ALTA · URGENTE · `TicketStatus` OPEN · IN_PROGRESS · WAITING_CUSTOMER · RESOLVED · CLOSED ·
`TicketChannel` TICKET · CHAT · `MessageSenderType` CUSTOMER · AGENT · SYSTEM.

## 3. Mensajes sin leer (`unreadCount`)

Se calcula **según quién mira**, con las dos marcas de lectura del ticket:

- Comercio: mensajes `AGENT` posteriores a `customer_last_read_at`.
- Agente: mensajes `CUSTOMER` posteriores a `agent_last_read_at`.
- Los mensajes `SYSTEM` (asignación, cambio de estado, cierre, calificación) no cuentan para nadie.
- **Escribir implica leer**: al enviar un mensaje se actualiza la marca de lectura del que escribe.
- En los eventos de `/topic/tickets/{id}` viaja en **0**, porque ese destino lo comparten las dos partes; cada cliente
  conserva el suyo (el frontend ignora ese campo en los eventos del tópico).

`lastMessagePreview` es el último mensaje con los espacios colapsados y recortado a 140 caracteres; si el mensaje solo
tiene imagen, es `"Imagen adjunta"`.

## 4. Endpoints del comercio (`TENANT_ANY`)

### `GET /api/tenant/support/tickets?status=`
`status` acepta `ALL` (por defecto), `ACTIVE` (abierto, en curso o esperando respuesta) o un estado puntual.
No distingue mayúsculas (`?status=active`). Devuelve `[TicketSummary]`, del más reciente al más viejo.

```bash
curl -s "$API/tenant/support/tickets?status=active" -H "Authorization: Bearer $ADMIN"
```

### `POST /api/tenant/support/tickets` → 201 `TicketDetail`
```json
{"subject":"El escáner no lee los códigos EAN-13","category":"TECNICO","priority":"ALTA","channel":"CHAT",
 "message":"Desde ayer el lector de la caja no toma ningún código."}
```
`category` (defecto `USO`), `priority` (`MEDIA`) y `channel` (`TICKET`) son opcionales. `subject` ≤ 200 y `message` ≤ 4000,
ambos obligatorios → 400 `VALIDATION_ERROR` con `fieldErrors`. Crea el ticket en `OPEN` con su primer mensaje, notifica a
todo el equipo de soporte y publica `TICKET_CREATED` en la bandeja.

### `GET /api/tenant/support/tickets/{id}` → `TicketDetail` · 404 si es de otro comercio

### `POST /api/tenant/support/tickets/{id}/messages` (multipart) → `MessageDto`
Campos: `body` (texto, opcional, ≤ 4000) y `file` (imagen PNG/JPG/WEBP/GIF de hasta 10 MB, opcional).
**Tiene que venir al menos uno** → si no, 400 `EMPTY_MESSAGE` ("Escribí un mensaje o adjuntá una imagen.").
La imagen se guarda con `AttachmentStorageService` (`AttachmentPurpose.SUPPORT`, validada por la firma del archivo;
400 `INVALID_FILE` si no es una imagen aceptada).

```bash
curl -s -X POST "$API/tenant/support/tickets/1/messages" -H "Authorization: Bearer $ADMIN" \
  -F 'body=Te paso la pantalla que me tira' -F 'file=@captura.png;type=image/png'
```

Efectos: `WAITING_CUSTOMER` o `RESOLVED` vuelven a `IN_PROGRESS`; con el ticket `CLOSED` responde
**409 `TICKET_CLOSED`**. Notifica al agente asignado (o a todo el equipo si no hay) y publica `MESSAGE` y
`TICKET_UPDATED`.

### `POST /api/tenant/support/tickets/{id}/read` → 204
Marca como leídos los mensajes del agente y publica `READ`.

### `POST /api/tenant/support/tickets/{id}/close` → `TicketDetail`
Pasa a `CLOSED`, fija `resolvedAt` si faltaba y deja un mensaje `SYSTEM` en la conversación. Es idempotente.

### `POST /api/tenant/support/tickets/{id}/rate` → `TicketDetail`
```json
{"rating": 5, "comment": "Rapidísimos, gracias"}
```
`rating` 1..5 obligatorio (400 `VALIDATION_ERROR` fuera de rango), `comment` ≤ 500. Solo con el ticket `RESOLVED` o
`CLOSED` → si no, **409 `TICKET_NOT_RATEABLE`**. Se puede corregir una calificación ya puesta.

## 5. Endpoints del equipo de soporte (`SUPPORT_AGENT`)

### `GET /api/support/tickets?status=&assigned=&q=&page=&size=` → `PageResponse<TicketSummary>`
- `status`: igual que arriba (`ALL`, `ACTIVE` o un estado).
- `assigned`: `all` (defecto), `me`, `unassigned`. Valor inválido → 400 `VALIDATION_ERROR`
  ("El filtro de asignación tiene que ser all, me o unassigned.").
- `q`: texto libre en asunto, nombre del comercio o nombre de quien escribió (sin distinguir mayúsculas ni acentos del
  patrón; `%` y `_` se escapan).
- Orden: último mensaje primero. `size` ≤ 100.

### `GET /api/support/tickets/{id}` → `TicketDetail`

### `POST /api/support/tickets/{id}/assign` → `TicketSummary`
Cuerpo `{"agentId": 6}` o `{}` / sin cuerpo para asignárselo a quien lo pide. El destinatario tiene que ser un
`SUPPORT_AGENT` activo → si no, 400 `AGENT_NOT_FOUND`. Deja un mensaje `SYSTEM` y, si el agente no es quien lo asignó,
le manda una notificación.

### `POST /api/support/tickets/{id}/status` `{"status":"RESOLVED"}` → `TicketSummary`
`RESOLVED`/`CLOSED` fijan `resolvedAt`; volver a un estado activo lo limpia. Deja un mensaje `SYSTEM` y notifica al
comercio (`TICKET_STATUS`).

### `PATCH /api/support/tickets/{id}` `{"priority":"MEDIA","category":"USO"}` → `TicketSummary`
Los campos nulos no se tocan; si vienen los dos nulos → 400 `VALIDATION_ERROR`.

### `POST /api/support/tickets/{id}/messages` (multipart) → `MessageDto`
Mismos campos y validaciones que del lado del comercio. Además:
- Si el ticket no tenía dueño, queda asignado a quien responde.
- **La primera respuesta de un agente fija `first_response_at`** y pasa el ticket a `IN_PROGRESS` (también lo reabre si
  estaba `RESOLVED`).
- Notifica a quien abrió el ticket (`TICKET_MESSAGE`, link `/app/support/{id}`).

### `POST /api/support/tickets/{id}/read` → 204

### `GET /api/support/stats` → tablero de la bandeja
```json
{"open":2,"inProgress":1,"waitingCustomer":1,"unassigned":2,"mine":2,"resolvedToday":1,
 "avgFirstResponseMinutes":12.4}
```
`mine` son los tickets activos del agente que consulta; `resolvedToday` usa el día de `America/Argentina/Buenos_Aires`
(bean `Clock`); `avgFirstResponseMinutes` es el promedio de primera respuesta de los últimos **30 días** con un decimal,
o `null` si todavía no hubo ninguna.

### `GET /api/support/agents` → `[{ "id": 6, "fullName": "Sofía Martínez", "online": true }]`
Agentes activos; `online` sale de `PresenceTracker` (sesiones STOMP abiertas).

### Presencia para el comercio (núcleo)
`GET /api/presence/support` → `{"agentsOnline":1}` — lo usa el widget flotante para mostrar si hay alguien.

## 6. Tiempo real (SPEC §7)

| Destino | Quién | Carga |
|---|---|---|
| `/topic/tickets/{id}` | agente o usuario del comercio dueño | `{"event":"MESSAGE","message":MessageDto}` · `{"event":"TICKET_UPDATED","ticket":TicketSummary}` · `{"event":"TYPING","userId","name","senderType","typing"}` · `{"event":"READ","senderType","readAt"}` |
| `/topic/support/queue` | solo `SUPPORT_AGENT` | `{"event":"TICKET_CREATED"\|"TICKET_UPDATED","ticket":TicketSummary}` (con el `unreadCount` del agente) |
| `/topic/support/presence` | cualquier autenticado | `{"agentsOnline":2}` (lo publica el núcleo) |

Cliente → servidor: `/app/tickets/{id}/typing` con `{"typing":true}`. Lo atiende
`SupportChatController` (`@MessageMapping`), que traduce el rol a `senderType` y reenvía el aviso al tópico. La
autorización del destino ya la resolvió el interceptor STOMP del núcleo; el emisor ignora su propio eco por `userId`.

Todos los envíos salen por `RealtimePublisher`, o sea **después del commit**: nunca se ve un mensaje que terminó
revertido.

## 7. Decisiones tomadas

1. **`tenantPlan` y `tenantBusinessType` en `TicketSummary`/`TicketDetail`** (agregado sobre SPEC §6.8): el panel derecho
   de la consola necesita el contexto del comercio. Son datos administrativos, permitidos por SPEC §3.4 (lo que está
   prohibido es exponer productos, stock, ventas, alertas o chats).
2. **Mensajes `SYSTEM` en la conversación** cuando alguien toma el ticket, cambia el estado, cierra o califica. Dejan la
   historia entendible para las dos partes y no cuentan como no leídos.
3. **Estado `CLOSED` cierra la conversación** (409 `TICKET_CLOSED` en `/messages`, de los dos lados): cerrar tiene que
   significar algo. Para seguir se abre una consulta nueva. `RESOLVED`, en cambio, se reabre solo si alguien escribe.
4. **`ACTIVE` como valor de `status`** además de los estados del enum: la bandeja y la lista del comercio muestran por
   defecto lo que necesita atención. Los filtros se parsean a mano (`TicketStatusFilter.from`, `AssignedFilter.from`)
   para aceptar `assigned=me` en minúscula como documenta SPEC §6.8 y devolver un 400 con mensaje en español.
5. **Calificar solo con el ticket resuelto o cerrado**, y se puede corregir (la primera calificación deja un mensaje
   `SYSTEM`).
6. **Lecturas con SQL** (`SupportQueryRepository`, SPEC §5.2): los no leídos por lado, la vista previa del último
   mensaje y los nombres de comercio y persona salen en una sola consulta por listado.
7. **No se agregaron migraciones**: `support_tickets`, `support_messages` y `attachments` ya vienen en `V1__schema.sql`
   con las dos marcas de lectura y `first_response_at`. El rango V350–V399 quedó libre.

## 8. Frontend (`features/support`)

| Pantalla | Ruta | Rol |
|---|---|---|
| `SupportConsolePage` | `/support` y `/support/tickets/:id` | `SUPPORT_AGENT` |
| `TenantSupportPage` | `/app/support` y `/app/support/:id` | jefe, administrador, empleado y cajero |
| `SupportWidget` | flotante en todas las pantallas del comercio (lo monta `AppShell`) | ídem |

- **Consola (3 paneles)**: bandeja con búsqueda, filtros de asignación y estado, insignias de no leídos y franja de
  prioridad; conversación con burbujas, imágenes (`AuthImage`) e indicador de escritura; panel derecho con comercio
  (nombre, plan, rubro), persona y rol, y los controles de estado, prioridad, categoría y agente. Debajo de `xl` el
  panel derecho pasa a una hoja lateral ("Datos") y debajo de `lg` la bandeja y la conversación se turnan.
  **Sonido** al entrar una consulta nueva o un mensaje del comercio (Web Audio, sin archivos), con interruptor que se
  recuerda en `localStorage` (`gondolia.support.mute`).
- **Envío instantáneo**: el mensaje aparece al toque como "Enviando" y se reconcilia con el del servidor (por id, así
  el eco de STOMP no lo duplica). Si falla queda en rojo con "Reintentar" y "Descartar".
- **Adjuntos**: selector de archivos, **pegar con Ctrl+V**, arrastrar y soltar, **cámara** en dispositivos táctiles y
  **"Capturar pantalla"** (html-to-image sobre `#root`, excluyendo el propio widget por `data-support-widget`). Siempre
  con vista previa antes de enviar y los mismos límites que el backend (PNG/JPG/WEBP/GIF, 10 MB).
- **Widget**: burbuja con punto de presencia e insignia de no leídos; adentro, mis conversaciones abiertas, el
  formulario de consulta nueva y el chat. Escucha los tópicos de mis tickets abiertos aunque esté cerrado, y no se
  muestra en `/app/support` (ahí ya está la pantalla completa).
- Query keys sin segmento de sucursal (`['support', …]`): las conversaciones no dependen de la sucursal elegida.
