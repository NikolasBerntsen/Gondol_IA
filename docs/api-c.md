# API del módulo C — Consola de dueños (`com.gondolia.platform`)

Endpoints de la consola con la que **los dueños de GondolIA** administran su negocio: métricas de la plataforma,
comercios clientes, módulos por cliente y equipo interno. Implementa SPEC.md §6.6 y §14.3 y se apoya en la fundación
(`docs/api-foundation.md`).

- Base: `/api/platform`. Todo requiere **rol `PLATFORM_OWNER`** (`@PreAuthorize(Roles.OWNER)`): un usuario de comercio
  o un pedido sin token reciben `403 FORBIDDEN` / `401`.
- **Soporte** (`SUPPORT_AGENT`, `Roles.PLATFORM_ANY`) usa Clientes y Módulos por cliente para resolver tickets:
  `GET /tenants`, `GET /tenants/{id}`, `PUT /tenants/{id}` (con el plan actual: un plan distinto responde `403`),
  `POST /tenants/{id}/reset-admin-password`, `GET /tenant-modules`, `GET /tenants/{id}/modules` y
  `PUT /tenants/{id}/modules/{module}`. El resto (métricas, catálogo con adopción, alta, deshabilitar, habilitar, baja,
  reactivar, eliminar, avisos y equipo) le responde `403`. Son dos barreras: la regla por URL y método de
  `SecurityConfig` y el `@PreAuthorize` de cada endpoint. Lo que cambia soporte queda en el historial a su nombre.
- JSON en camelCase, instantes ISO-8601 UTC, importes en ARS. Paginación con `PageResponse<T>`
  (`content`, `page` 0-based, `size`, `totalElements`, `totalPages`).
- Pantallas: `/owner` (métricas), `/owner/tenants`, `/owner/tenants/new`, `/owner/tenants/:id`,
  `/owner/tenants/:id/edit`, `/owner/modules`, `/owner/team` — todas en `frontend/src/features/platform`.

## Privacidad: qué **no** devuelve esta API

Es un requisito duro (SPEC §3.4.3). Ningún endpoint de `/api/platform` expone productos, stock, lotes, ventas,
alertas, recomendaciones de IA, chats de soporte ni **qué** comercios coinciden con un recall. De las sucursales solo
se devuelve nombre, código, ciudad, estado y fecha de alta — nunca su API key del POS ni su inventario. De los recalls
y del soporte solo se devuelven **cantidades**.

Del otro lado, un dueño tampoco puede entrar a los datos de un comercio: `GET /api/tenant/**` con un token
`PLATFORM_OWNER` responde `403`. Lo cubre `PlatformApiIsolationIntegrationTest`.

```bash
curl -s -o /dev/null -w '%{http_code}\n' localhost:18104/api/tenant/products -H "Authorization: Bearer $OWNER"
# 403
```

---

## 1. Métricas · `GET /api/platform/metrics`

Solo agregados. Las ventanas de 7 y 30 días se cuentan contra el reloj de negocio del núcleo.

```json
{
  "tenants": {"total":6,"active":4,"disabled":1,"cancelled":1,"newLast30d":6,"cancelledLast30d":1},
  "branches": {"total":7,"active":5,"avgPerActiveTenant":1.3,"multiBranchTenants":1},
  "tenantsByPlan": {"FREEMIUM":2,"BASICO":3,"PROFESIONAL":1},
  "tenantsByBusinessType": {"KIOSCO":2,"ALMACEN":2,"DIETETICA":1,"MINIMERCADO":1,"FARMACIA":0,"OTRO":0},
  "engagement": {"activeTenants7d":2,"activeTenants30d":2,"activeUsers7d":3},
  "users": {"total":17,"byRole":{"TENANT_BOSS":5,"TENANT_ADMIN":6,"TENANT_EMPLOYEE":5,"TENANT_CASHIER":1}},
  "revenue": {"estimatedMrr":210000.00,"currency":"ARS","freemiumToPaidConversionPct":75.0},
  "growth": [{"month":"2026-09","newTenants":6,"cancelled":1,"activeAtEndOfMonth":4}],
  "support": {"openTickets":0,"unassignedTickets":0,"avgFirstResponseMinutes":null,"resolvedLast30d":0,"avgRating":null},
  "recalls": {"activeRecalls":0,"affectedTenantsTotal":0},
  "modules": {"POS_GONDOLIA":{"tenants":3,"pct":75.0},"POS_INTEGRATION":{"tenants":3,"pct":75.0},
              "MULTI_BRANCH":{"tenants":2,"pct":50.0}}
}
```

Decisiones de cálculo:

- **MRR** (SPEC §14.1) = Σ de los comercios `ACTIVE`: `sucursales activas × (precio del plan + Σ adicionales de sus
  módulos habilitados)`. Los comercios `DISABLED` y `CANCELLED` **no facturan**: suman 0 y su `monthlyFee` es 0.
  Con el fixture: `75.000 + 90.000 + 37.000 + 8.000 = 210.000`.
- `freemiumToPaidConversionPct` = comercios `ACTIVE` con plan pago sobre el total de `ACTIVE`.
- `engagement`: "activo" = **algún** usuario del comercio inició sesión dentro de la ventana (`users.last_login_at`).
- `branches`: `total` = todas las sucursales registradas. `active`, `avgPerActiveTenant` y `multiBranchTenants` miran
  la misma población que el MRR: las **sucursales activas de comercios `ACTIVE`** (las que facturan). Las de un
  comercio deshabilitado o dado de baja no cuentan como activas.
- `tenantsByPlan` y `tenantsByBusinessType` cuentan **todos** los comercios, en cualquier estado (suman `total`).
- **Altas y bajas** (`growth`, `newLast30d`, `cancelledLast30d`) salen de una historia por comercio armada con
  `tenant_events` (`CREATED`, `DISABLED`, `ENABLED`, `CANCELLED`, `REACTIVATED`), así las series cuadran entre sí:
  - alta = comercio creado en el período (`tenants.created_at`; si se eliminó, su evento `CREATED`);
  - baja = comercio cuya **última baja o reactivación del período fue una baja**: cada comercio cuenta una sola vez
    (baja → reactivación → baja es una baja) y una baja que se revierte dentro del período no cuenta;
  - `activeAtEndOfMonth` = estado reconstruido al cierre de cada mes (el mes en curso, a hoy).
  - Los comercios **eliminados** siguen contando su alta, su baja y los meses en que estuvieron activos: al
    eliminarlos, su historial guarda el id en `tenant_events.deleted_tenant_id` (V250). Los eventos de comercios
    eliminados antes de V250 no se pueden atribuir y no cuentan (ni el alta ni la baja).
- `growth`: 12 meses terminando en el actual.
- `modules`: adopción entre los comercios `ACTIVE` (mismo denominador que `GET /api/platform/modules`).
- `recalls`: `activeRecalls` = recalls **en curso** (publicados y con al menos una coincidencia `OPEN` o
  `ACKNOWLEDGED`; uno con todas sus coincidencias resueltas ya no cuenta). `affectedTenantsTotal` = comercios
  distintos alcanzados por esos recalls. Solo cantidades.
- `support` y `recalls` son cantidades agregadas de los módulos D y E; si todavía no hay datos, los promedios vienen
  `null` y el frontend muestra `—` / "Sin puntajes".

---

## 2. Clientes · `/api/platform/tenants`

### 2.1 `GET /api/platform/tenants`

Query: `q` (nombre, razón social, contacto, email de contacto, ciudad o CUIT; sin distinguir mayúsculas ni tildes
—`almacen` encuentra "Almacén", `cordoba` encuentra "Córdoba"— y con `%` y `_` como texto, no comodines),
`status`, `plan`, `businessType`, `module`,
`sort` (`name` por defecto · `createdAt` · `lastActivityAt` · `status` · `plan` · `city` · `activeBranchCount` ·
`userCount`), `page` (≥ 0), `size` (1..100, 20 por defecto). → `PageResponse<TenantSummary>`.

```json
{"id":1,"name":"Comercio de Prueba","businessType":"ALMACEN","plan":"BASICO","status":"ACTIVE",
 "city":"CABA","province":"Buenos Aires","contactName":"Ana Rodríguez","contactEmail":"admin@prueba.com",
 "contactPhone":null,"userCount":4,"branchCount":2,"activeBranchCount":2,
 "modules":["POS_GONDOLIA","POS_INTEGRATION","MULTI_BRANCH"],"monthlyFee":90000,
 "lastActivityAt":"2026-09-17T23:28:06.432310Z","createdAt":"2026-09-17T20:03:47.111483Z",
 "statusChangedAt":null,"statusReason":null}
```

`GET /api/platform/tenants?module=MULTI_BRANCH` devuelve solo los comercios que tienen ese módulo habilitado
(lo usa el link "clientes con este módulo" de la matriz).

### 2.2 `GET /api/platform/tenants/{id}` → `TenantDetail`

`TenantSummary` + `legalName`, `taxId`, `address`, `notes`, `maxBranches` (máximo **efectivo**: el del plan solo si
tiene `MULTI_BRANCH`, si no 1), `stockRotation`, `usersByRole`, `branches[]`, `users[]` y `events[]`.
`404 NOT_FOUND` si no existe.

```json
{"…":"…",
 "maxBranches":3,"stockRotation":"FIFO","usersByRole":{"TENANT_BOSS":1,"TENANT_ADMIN":1,"TENANT_EMPLOYEE":1,"TENANT_CASHIER":1},
 "branches":[{"id":1,"name":"Sucursal Centro","code":"CEN","city":"CABA","active":true,"createdAt":"2026-09-17T20:03:47.203142Z"}],
 "users":[{"id":3,"fullName":"Ana Rodríguez","email":"admin@prueba.com","role":"TENANT_ADMIN","active":true,
           "lastLoginAt":"2026-09-17T23:28:06.432310Z","createdAt":"2026-09-17T20:03:47.3Z"}],
 "events":[{"id":12,"type":"MODULE_ENABLED","fromValue":"POS_GONDOLIA","toValue":"true","reason":null,
            "actorName":"Dueño GondolIA","createdAt":"2026-09-17T20:05:12Z"}]}
```

`TenantEventType`: `CREATED`, `PLAN_CHANGED`, `DISABLED`, `ENABLED`, `CANCELLED`, `REACTIVATED`, `DELETED`,
`MODULE_ENABLED`, `MODULE_DISABLED`. En los eventos de módulo, `fromValue` es el módulo y `toValue` el nuevo estado.
`actorName` es `null` cuando el cambio lo hizo el sistema (el frontend muestra "Sistema").

### 2.3 `POST /api/platform/tenants` → `201` + `TenantDetail`

Crea, en una transacción: el comercio, su `tenant_settings`, la primera sucursal, los **tres usuarios**
(jefe, administrador y empleado —este último asignado a esa sucursal—) y los módulos indicados, o el **preset del plan**
si no se manda `modules` (FREEMIUM → `POS_GONDOLIA`; BASICO y PROFESIONAL → los tres). En el historial el alta
(`CREATED`) va primero y los `MODULE_ENABLED` iniciales quedan a nombre del dueño que creó el comercio.

```json
{"name":"Almacén Don Pepe","legalName":"Pepe Fernández SRL","taxId":"30-71234567-4",
 "businessType":"ALMACEN","plan":"PROFESIONAL","contactName":"José Fernández",
 "contactEmail":"pepe@donpepe.com.ar","contactPhone":"11 4567-8900","address":"Av. San Martín 2140",
 "city":"San Isidro","province":"Buenos Aires","notes":"Llegó por recomendación.",
 "firstBranch":{"name":"Casa Central","code":"CEN","city":"San Isidro","province":"Buenos Aires"},
 "boss":{"fullName":"José Fernández","email":"jefe@donpepe.com.ar","password":"Gnd-KRTM-4821"},
 "admin":{"fullName":"Marta Fernández","email":"admin@donpepe.com.ar","password":"Gnd-QWPL-7734"},
 "employee":{"fullName":"Nicolás Sosa","email":"deposito@donpepe.com.ar","password":"Gnd-ZXCV-9912"},
 "modules":["POS_GONDOLIA","POS_INTEGRATION","MULTI_BRANCH"],"stockRotation":"FIFO"}
```

- `firstBranch` es opcional: sin él se crea "Sucursal Principal" con la dirección del comercio.
- `stockRotation` por defecto `FIFO`.
- Errores: `400 VALIDATION_ERROR` con `fieldErrors` anidados (`boss.email`, `admin.password`, `firstBranch.name`…) ·
  `409 EMAIL_TAKEN` si alguno de los tres emails ya existe o se repite entre ellos.

```json
{"status":400,"code":"VALIDATION_ERROR","message":"Revisá los datos ingresados",
 "fieldErrors":[{"field":"name","message":"es obligatorio"},
                {"field":"boss.email","message":"no es un email válido"},
                {"field":"boss.password","message":"tiene que tener entre 8 y 72 caracteres"}]}
```

### 2.4 `PUT /api/platform/tenants/{id}` → `TenantDetail`

Datos administrativos, rubro, rotación y **plan**. Si el plan cambia se registra `PLAN_CHANGED`
(con `planChangeReason` en el historial). Los módulos **no** se tocan acá: se cambian en §4.

Bajar a un plan con menos sucursales que las activas:

```json
{"status":409,"code":"BRANCH_LIMIT_REACHED",
 "message":"El plan Freemium permite 1 sucursal activa y el comercio tiene 2: desactivá las sucursales que sobran antes de bajar el plan"}
```

### 2.5 Estado de la cuenta

| Endpoint | Cuerpo | Efecto |
|---|---|---|
| `POST /{id}/disable` | `{"reason":"…"}` **obligatorio** | `ACTIVE → DISABLED`. Evento `DISABLED`, `TenantStatusChangedEvent` y `SessionTerminationService.forceLogoutTenant`: sus usuarios reciben `FORCE_LOGOUT` y sus pedidos siguientes dan `403 TENANT_DISABLED`. |
| `POST /{id}/enable` | `{"reason":"…"}` opcional (o sin cuerpo) | `DISABLED → ACTIVE`. Evento `ENABLED`. |
| `POST /{id}/cancel` | `{"reason":"…"}` **obligatorio** | → `CANCELLED` (cuenta como baja en las métricas, deja de facturar, cierra sesiones). |
| `POST /{id}/reactivate` | `{"reason":"…"}` opcional | `CANCELLED → ACTIVE`. Evento `REACTIVATED`. |

Todos devuelven el `TenantSummary` actualizado. El motivo queda en `tenants.status_reason` y en el historial.
Una transición imposible (habilitar algo que ya está activo, reactivar algo que no está dado de baja) responde
`409 INVALID_STATUS_CHANGE`; un motivo vacío en `disable`/`cancel`, `400 VALIDATION_ERROR`
(`{"field":"reason","message":"contá el motivo"}`).

### 2.6 `DELETE /api/platform/tenants/{id}?confirmName=<nombre exacto>` → `204`

Borrado definitivo: usuarios, sucursales y datos del comercio. Solo comercios `CANCELLED`.

- No está dado de baja → `409 INVALID_STATUS_CHANGE` ("Solo se puede eliminar un comercio dado de baja…").
- Nombre distinto → `400 CONFIRM_NAME_MISMATCH` ("Escribí el nombre exacto del comercio («…») para confirmar").
- El historial de `tenant_events` queda sin comercio asociado (`tenant_id` NULL) pero con su id en
  `deleted_tenant_id`, para que su alta y su baja sigan contando una vez cada una en las métricas.

### 2.7 `POST /api/platform/tenants/{id}/reset-admin-password`

Genera una contraseña temporal para el **administrador** del comercio, corta sus sesiones y lo obliga a cambiarla al
entrar. Se devuelve **una sola vez**: no se guarda en claro.

```json
{"email":"admin@semilla.com.ar","fullName":"Iván Ríos","temporaryPassword":"Gnd-TKDE-2344"}
```

Formato `Gnd-XXXX-9999`, sin caracteres que se confundan (`0/O`, `1/l/I`), pensado para dictarla por teléfono.
Si el comercio no tiene un administrador activo → `409`.

---

## 3. Equipo de GondolIA · `/api/platform/users`

Solo cuentas de **plataforma** (`PLATFORM_OWNER` y `SUPPORT_AGENT`), ordenadas por nombre.

| Endpoint | Request | Notas |
|---|---|---|
| `GET /api/platform/users` | — | `[{id,fullName,email,role,active,lastLoginAt,createdAt}]` |
| `POST /api/platform/users` | `{fullName,email,password,role}` | `201`. Rol fuera de los dos de plataforma → `400 VALIDATION_ERROR` ("El equipo de GondolIA solo tiene dueños y agentes de soporte"). Email repetido → `409 EMAIL_TAKEN`. |
| `PUT /api/platform/users/{id}` | `{fullName,active}` | Desactivar corta sus sesiones. Desactivarse a sí mismo → `409 SELF_DEACTIVATION`. |
| `POST /api/platform/users/{id}/reset-password` | `{newPassword}` | Queda obligado a cambiarla al entrar; se cierran sus sesiones. |

---

## 4. Módulos por cliente (SPEC §14.3)

### 4.1 `GET /api/platform/modules`

Catálogo con adopción entre los comercios `ACTIVE`.

```json
[{"module":"POS_GONDOLIA","name":"Punto de venta GondolIA",
  "description":"Cajas por sucursal, cobro con escáner, medios de pago, tickets, anulaciones, cierre de caja",
  "monthlyPricePerBranch":12000,"enabledTenants":3,"adoptionPct":75.0,"activeTenants":4}]
```

`activeTenants` es el denominador, para que el frontend pueda mostrar "3 de 4 clientes activos" sin pedir las métricas.

### 4.2 `GET /api/platform/tenant-modules` → `PageResponse<TenantModulesRow>`

Matriz clientes × módulos. Query: `q`, `status`, `plan`, `module`, `sort`, `page`, `size`.

```json
{"tenantId":1,"tenantName":"Comercio de Prueba","businessType":"ALMACEN","city":"CABA","plan":"BASICO",
 "status":"ACTIVE","activeBranchCount":2,
 "modules":{"POS_GONDOLIA":true,"POS_INTEGRATION":true,"MULTI_BRANCH":true},
 "estimatedMonthlyFee":90000,"lastActivityAt":"2026-09-17T23:28:06Z"}
```

`modules` trae **siempre los tres** con `true`/`false`. `estimatedMonthlyFee` es 0 para los que no facturan.

### 4.3 `GET /api/platform/tenants/{id}/modules` → `[TenantModuleStatus]`

```json
[{"module":"POS_INTEGRATION","name":"Integración con POS propio",
  "description":"API key por sucursal (webhook de ventas), importación CSV de ventas, simulador",
  "monthlyPricePerBranch":8000,"enabled":true,
  "updatedAt":"2026-09-17T23:29:57.685874Z","updatedByName":"Dueño GondolIA"}]
```

### 4.4 `PUT /api/platform/tenants/{id}/modules/{module}` `{"enabled":true|false}` → `TenantModuleStatus`

Delega en `ModuleService.setEnabled` del núcleo, que valida, registra `MODULE_ENABLED`/`MODULE_DISABLED` en
`tenant_events` y empuja `{"type":"MODULES_CHANGED"}` por `/user/queue/session` a **todos** los usuarios del comercio:
el frontend hace `refreshMe()` y actualiza el menú sin recargar.

Deshabilitar `MULTI_BRANCH` con más de una sucursal activa:

```json
{"status":409,"code":"MODULE_IN_USE",
 "message":"El cliente tiene 2 sucursales activas: debe desactivar las sucursales extra antes"}
```

---

## 5. Pantallas (`frontend/src/features/platform`)

| Ruta | Página | Qué tiene |
|---|---|---|
| `/owner` | `OwnerMetricsPage` | Fila de KPI (clientes activos, MRR, sucursales, uso 7 días), gráfico de crecimiento de 12 meses (recharts + tabla equivalente para lectores de pantalla), reparto por plan y por rubro, adopción de módulos, usuarios por rol, soporte y recalls. Arriba, la nota "No tenés acceso a los datos de tus clientes". |
| `/owner/tenants` | `TenantsPage` | Píldoras de estado con contadores, buscador con *debounce*, filtros por plan, rubro y **módulo** (todo en la URL), tabla con plan, estado, módulos, sucursales, usuarios, actividad y cuota, y menú de acciones por fila. |
| `/owner/tenants/new` · `/owner/tenants/:id/edit` | `TenantFormPage` | Alta con primera sucursal, los tres usuarios (con "Generar una" contraseña) y los módulos con **preset por plan**; edición de datos y plan, con aviso previo si el plan nuevo no alcanza para las sucursales activas. |
| `/owner/tenants/:id` | `TenantDetailPage` | Datos, plan y facturación, módulos con switches, sucursales y usuarios (solo administrativo), historial de eventos, acciones de estado con motivo obligatorio, eliminación escribiendo el nombre y reseteo de la contraseña del admin mostrando la temporal una sola vez. |
| `/owner/modules` | `ModulesMatrixPage` | Adopción arriba, buscador y filtros, matriz clientes × módulos con switches, MRR estimado **en vivo** de la lista, confirmación al desactivar explicando qué pierde el cliente y el bloqueo de `MULTI_BRANCH` en uso. |
| `/owner/team` | `PlatformTeamPage` | Equipo de GondolIA: alta, edición (sin poder desactivarse a sí mismo) y reseteo de contraseña. |

Notas de implementación:

- `features/platform/api.ts` concentra las llamadas y las *query keys*. Los datos de plataforma **no** dependen de la
  sucursal (los dueños no tienen sucursales), así que las keys no llevan el segmento `{branch}`.
- `moduleMath.ts` recalcula la cuota en el navegador con las constantes de `@/api/types`
  (`PLAN_MONTHLY_PRICE_PER_BRANCH`, `TENANT_MODULE_MONTHLY_PRICE`) para mostrar el "antes → después" de un cambio y el
  total en vivo. **El backend sigue siendo la fuente de verdad**: después de cada cambio se invalidan las queries.
- `useModuleToggle` + `ModuleDisableDialog`: habilitar es directo; deshabilitar siempre pide confirmación y, si es
  `MULTI_BRANCH` con más de una sucursal activa, avisa sin llamar al backend (y si igual llega, se muestra el mensaje
  de `MODULE_IN_USE`).

---

## 6. Pruebas

`backend/src/test/java/com/gondolia/platform` (19 tests, todos sobre Postgres real vía `PostgresIntegrationTest`):

- `PlatformApiIsolationIntegrationTest` — un dueño recibe `403` en `/api/tenant/**`; los usuarios de comercio reciben
  `403` en `/api/platform/**` y el soporte en las métricas, el equipo y las acciones de estado; el detalle y las
  métricas solo traen datos administrativos y agregados.
- `SupportClientAccessIntegrationTest` — soporte lista, abre y edita un cliente (sin cambiar el plan), activa o
  desactiva módulos (el historial lo nombra) y restablece la contraseña del admin; todo lo demás le responde `403`.
  `PlatformConsoleAccessMatrixTest` fija qué `@PreAuthorize` tiene cada endpoint de la consola.
- `PlatformMetricsServiceIntegrationTest` — MRR con plan + módulos por sucursal activa, uso, crecimiento reconstruido
  desde los eventos, agregados de soporte y recalls, conversión a plan pago.
- `TenantAdminServiceIntegrationTest` — alta con preset y con módulos explícitos, emails repetidos, `BRANCH_LIMIT_REACHED`,
  flujo de estados con sus eventos, borrado con `confirmName` y reseteo de la contraseña del admin.

Comprobado además contra el jar (puerto 18104) con curl: camino feliz de cada endpoint, `403` por rol, `401` sin token,
`403` de un dueño sobre `/api/tenant/**`, `400 VALIDATION_ERROR`, `409 EMAIL_TAKEN`, `409 BRANCH_LIMIT_REACHED`,
`409 MODULE_IN_USE`, `409 INVALID_STATUS_CHANGE`, `409 SELF_DEACTIVATION` y `400 CONFIRM_NAME_MISMATCH`.
