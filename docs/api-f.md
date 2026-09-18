# API del módulo F — Administración del comercio

Endpoints de `com.gondolia.tenantadmin` (SPEC §6.9, §3.3, §3.5, §14) y pantallas de
`frontend/src/features/tenantadmin`: **Usuarios**, **Sucursales** y **Configuración**.

- Base: `/api`. JSON en camelCase, instantes ISO-8601 UTC, fechas `"2026-09-17"`.
- Autenticación: `Authorization: Bearer <jwt>`. El `tenantId` **siempre** sale del usuario autenticado
  (`CurrentUser.tenantId()`): nunca viaja en el cuerpo ni en la URL. Un id de otro comercio devuelve **404**.
- Formato de error: el estándar de la fundación (`docs/api-foundation.md` §4).
- Ninguno de estos recursos depende de la sucursal elegida, así que el encabezado `X-Branch-Id` **no cambia** las
  respuestas. La única excepción es indirecta: `GET /api/tenant/branches` devuelve las sucursales **accesibles** por
  el usuario (un cajero ve solo las asignadas, mande el encabezado que mande).

## Permisos (SPEC §3.3)

| Endpoint | BOSS | ADMIN | EMPLOYEE | CASHIER |
|---|:-:|:-:|:-:|:-:|
| `/api/tenant/users/**` | ✘ | ✔ | ✘ | ✘ |
| `GET /api/tenant/branches`, `GET /api/tenant/branches/limits` | ✔ | ✔ | ✔ | ✔ |
| `POST/PUT /api/tenant/branches`, `/activate`, `/deactivate` | ✘ | ✔ | ✘ | ✘ |
| `GET/PUT /api/tenant/settings` | ✘ | ✔ | ✘ | ✘ |
| `GET /api/tenant/account` | ✔ | ✔ | ✘ | ✘ |

Ningún endpoint del módulo lleva `@RequiresModule`: usuarios, sucursales y configuración son del núcleo y están
**siempre disponibles** (SPEC §14.1). El módulo `MULTI_BRANCH` no habilita ni deshabilita endpoints: solo cambia el
**máximo efectivo de sucursales activas**.

## Códigos de error propios

| Estado | `code` | Cuándo |
|---|---|---|
| 409 | `DUPLICATE_EMAIL` | Ya existe una cuenta con ese email (el email es único en toda la plataforma) |
| 409 | `DUPLICATE_BRANCH_NAME` | Ya hay una sucursal con ese nombre en el comercio (sin distinguir mayúsculas) |
| 409 | `SELF_UPDATE_FORBIDDEN` | El administrador quiso cambiarse el rol, desactivarse o resetear su propia contraseña |
| 409 | `LAST_ACTIVE_ADMIN` | El cambio dejaría al comercio sin ningún administrador activo |
| 409 | `LAST_ACTIVE_BRANCH` | Es la única sucursal activa del comercio |
| 409 | `BRANCH_LIMIT_REACHED` | Se llegó al máximo efectivo de sucursales activas (núcleo) |
| 409 | `BRANCH_HAS_STOCK` | La sucursal tiene lotes `ACTIVE` o `RECALLED` con remanente (núcleo) |
| 400 | `VALIDATION_ERROR` | Campos inválidos (con `fieldErrors`), rol de plataforma, sucursal ajena o desactivada, empleado o cajero sin sucursal, umbrales de vencimiento incoherentes |

---

## 1. Usuarios del comercio

### 1.1 `GET /api/tenant/users`

Todos los usuarios del comercio (activos y desactivados), ordenados por nombre.

```json
[
  {"id": 3, "fullName": "Ana Rodríguez", "email": "admin@prueba.com", "role": "TENANT_ADMIN",
   "active": true, "mustChangePassword": false, "lastLoginAt": "2026-09-18T00:28:26.550Z",
   "createdAt": "2026-09-18T00:28:18.635Z", "branches": []},
  {"id": 5, "fullName": "Carla Giménez", "email": "cajero@prueba.com", "role": "TENANT_CASHIER",
   "active": true, "mustChangePassword": false, "lastLoginAt": "2026-09-18T00:28:27.090Z",
   "createdAt": "2026-09-18T00:28:18.658Z",
   "branches": [{"id": 1, "name": "Sucursal Centro", "code": "CEN", "active": true}]}
]
```

`branches` son las sucursales asignadas (`user_branches`): siempre vacío para jefe y administrador, que acceden a
todas. Incluye las sucursales **desactivadas** después de la asignación, con `active: false`, para que la pantalla
las muestre en ámbar.

### 1.2 `POST /api/tenant/users` → `201`

```json
{"fullName": "Laura Gómez", "email": "laura@prueba.com", "password": "Demo2026!",
 "role": "TENANT_CASHIER", "branchIds": [1]}
```

- `role`: solo roles de comercio (`TENANT_BOSS`, `TENANT_ADMIN`, `TENANT_EMPLOYEE`, `TENANT_CASHIER`). Un rol de
  plataforma devuelve 400.
- `branchIds`: **obligatorio (≥ 1)** para `TENANT_EMPLOYEE` y `TENANT_CASHIER`
  (`Role.worksInAssignedBranches()`); se **ignora** para jefe y administrador. Las sucursales tienen que ser del
  comercio y estar activas.
- El email se normaliza (recortado y en minúsculas). `password`: 8 a 72 caracteres.
- El usuario se crea activo y con `mustChangePassword: true`: tiene que cambiar la contraseña al entrar.

La respuesta es el mismo `TenantUserDto` del listado.

| Caso | Respuesta |
|---|---|
| Email repetido | 409 `DUPLICATE_EMAIL` — "Ya hay una cuenta con el email laura@prueba.com" |
| Empleado o cajero sin sucursales | 400 `VALIDATION_ERROR` — "Elegí al menos una sucursal para el empleado o cajero." |
| Sucursal de otro comercio o inexistente | 400 `VALIDATION_ERROR` — "Alguna de las sucursales elegidas no existe en tu comercio." |
| Sucursal desactivada | 400 `VALIDATION_ERROR` — "No podés asignar sucursales desactivadas: Sucursal Oeste." |
| Campos inválidos | 400 `VALIDATION_ERROR` con `fieldErrors` (`fullName`, `email`, `password`, `role`) |

### 1.3 `PUT /api/tenant/users/{id}`

```json
{"fullName": "Laura Gómez", "role": "TENANT_EMPLOYEE", "active": true, "branchIds": [1, 2]}
```

El email no se edita (es la identidad de la cuenta) y la contraseña se cambia con el reseteo. `branchIds`
**reemplaza** las asignaciones actuales.

Reglas:

1. **Nunca contra uno mismo**: si `id` es el del usuario autenticado y cambia el rol o `active` es `false` →
   409 `SELF_UPDATE_FORBIDDEN` ("No podés cambiar tu propio rol ni desactivar tu usuario. Pedíselo a otro
   administrador."). Renombrarse sí está permitido.
2. **Siempre queda un administrador activo**: si el usuario era administrador activo y deja de serlo (cambio de rol
   o baja) y no hay otro → 409 `LAST_ACTIVE_ADMIN`.
3. **Cambio de rol o desactivación cierran la sesión**: se incrementa `token_version` (los JWT viejos dejan de
   valer, porque el rol viaja en el token) y se llama a `SessionTerminationService.forceLogoutUser`, así las
   pestañas abiertas reciben `FORCE_LOGOUT` por `/user/queue/session` con el código `USER_DISABLED` o
   `ROLE_CHANGED`. Cambiar solo el nombre o las sucursales **no** corta la sesión: el acceso por sucursal se
   resuelve contra la base en cada request.

### 1.4 `POST /api/tenant/users/{id}/reset-password`

```json
{"newPassword": "OtraClave2026!"}
```

`newPassword` es **opcional**: si viene vacío (o el cuerpo es `{}`), GondolIA genera una contraseña temporal legible
(`Gondolia-` + 8 caracteres sin ambigüedades tipo `0/O`, `1/l`).

```json
{"userId": 8, "email": "laura@prueba.com", "fullName": "Laura Gómez",
 "temporaryPassword": "Gondolia-bEFqUVv3", "generated": true}
```

La contraseña se devuelve **una sola vez** (la pantalla la muestra con un botón "Copiar" y avisa que no se vuelve a
ver). Siempre: `must_change_password = true`, `token_version + 1` y cierre de las sesiones abiertas del usuario.

Resetear la propia contraseña devuelve 409 `SELF_UPDATE_FORBIDDEN` ("Para cambiar tu propia contraseña entrá a tu
perfil."): el administrador usa `POST /api/auth/change-password`.

---

## 2. Sucursales

### 2.1 `GET /api/tenant/branches?includeInactive=false`

```json
[
  {"id": 1, "name": "Sucursal Centro", "code": "CEN", "address": "Av. Corrientes 1234", "city": "CABA",
   "province": "Buenos Aires", "phone": null, "active": true, "employeeCount": 2, "hasStock": true,
   "createdAt": "2026-09-18T00:28:18.534Z"}
]
```

- Alcance (SPEC §3.5): jefe y administrador ven **todas** las sucursales activas; empleados y cajeros, solo las
  asignadas. `includeInactive=true` solo lo respeta el **administrador**; para los demás roles se ignora (nunca ven
  sucursales desactivadas).
- `employeeCount`: empleados y cajeros **activos** asignados a esa sucursal.
- `hasStock`: hay lotes `ACTIVE` o `RECALLED` con remanente (`LotRepository.hasPhysicalStock`). La pantalla lo usa
  para explicar por qué el botón "Desactivar" está apagado antes de que el usuario lo intente.

### 2.2 `GET /api/tenant/branches/limits`

```json
{"plan": "BASICO", "maxBranches": 3, "planMaxBranches": 3, "multiBranchEnabled": true,
 "activeBranches": 2, "totalBranches": 3}
```

`maxBranches` es el **máximo efectivo** (`ModuleService.effectiveMaxBranches`): el del plan si `MULTI_BRANCH` está
habilitado y **1** si no lo está. `planMaxBranches` es el del plan a secas, para explicarle al comercio cuánto
ganaría activando Multi-sucursal. Sin el módulo, un comercio PROFESIONAL ve `{"maxBranches": 1, "planMaxBranches": 10,
"multiBranchEnabled": false}`.

### 2.3 `POST /api/tenant/branches` → `201` · `PUT /api/tenant/branches/{id}`

```json
{"name": "Sucursal Sur", "code": "sur", "address": "San Martín 980", "city": "Rosario",
 "province": "Santa Fe", "phone": "341 555-0100"}
```

Solo `name` es obligatorio (≤ 100). `code` se guarda en mayúsculas (`"sur"` → `"SUR"`); los campos vacíos se guardan
como `null`. La sucursal nueva nace **activa**; el estado no se cambia por `PUT`.

| Caso | Respuesta |
|---|---|
| Se llegó al máximo | 409 `BRANCH_LIMIT_REACHED` — "Tu plan permite hasta 3 sucursales activas…" o, sin `MULTI_BRANCH`, "Tu comercio no tiene habilitado el módulo Multi-sucursal: solo podés tener una sucursal activa. Contactá a GondolIA para activarlo." |
| Nombre repetido | 409 `DUPLICATE_BRANCH_NAME` |
| Sucursal de otro comercio | 404 `NOT_FOUND` — "La sucursal no existe" |

### 2.4 `POST /api/tenant/branches/{id}/deactivate`

Devuelve la sucursal ya desactivada. Es **idempotente**: si ya estaba desactivada, responde 200 sin cambios.

| Caso | Respuesta |
|---|---|
| Es la única activa | 409 `LAST_ACTIVE_BRANCH` — "No podés desactivar la única sucursal activa del comercio." |
| Tiene stock físico | 409 `BRANCH_HAS_STOCK` — "La sucursal todavía tiene stock. Transferilo a otra sucursal o descartalo antes de desactivarla." |

Se chequea primero que quede al menos una sucursal activa y después el stock. Las asignaciones de empleados y
cajeros **no se borran**: si volvés a activarla, siguen valiendo. Mientras esté desactivada, esos usuarios dejan de
tener acceso a ella (`BranchAccessService`), así que la pantalla avisa cuántas personas trabajan ahí antes de
confirmar.

### 2.5 `POST /api/tenant/branches/{id}/activate`

Vuelve a activar la sucursal respetando el máximo efectivo (409 `BRANCH_LIMIT_REACHED`). También es idempotente.

---

## 3. Configuración del comercio

### 3.1 `GET /api/tenant/settings`

```json
{"currency": "ARS", "stockRotation": "FIFO", "expiryWarningDays": 15, "expiryCriticalDays": 5,
 "defaultLeadTimeDays": 3, "targetCoverageDays": 14, "serviceLevel": 0.950, "maxDiscountPct": 40,
 "updatedAt": "2026-09-18T00:40:15.611Z"}
```

Si el comercio todavía no tiene fila en `tenant_settings`, se crea con los valores por defecto del esquema.

### 3.2 `PUT /api/tenant/settings`

Mismo cuerpo sin `updatedAt`. Rangos validados:

| Campo | Rango | Qué controla |
|---|---|---|
| `stockRotation` | `FIFO` \| `FEFO` | Orden de salida del stock en ventas y bajas sin lote indicado (SPEC §4.2) |
| `expiryCriticalDays` | 1 a 60 | Bucket `CRITICAL` de vencimientos y alertas urgentes |
| `expiryWarningDays` | 1 a 180 | Bucket `WARNING`. **Tiene que ser mayor que `expiryCriticalDays`** |
| `defaultLeadTimeDays` | 0 a 60 | Demora del proveedor cuando el proveedor no tiene la suya |
| `targetCoverageDays` | 1 a 180 | Días de venta que se quieren cubrir al reponer |
| `serviceLevel` | 0,500 a 0,999 (3 decimales) | Stock de seguridad que calcula la IA |
| `maxDiscountPct` | 0 a 80 | Tope del descuento que puede sugerir la IA |
| `currency` | 3 letras, opcional | Si viene vacío se conserva la actual (el prototipo usa ARS) |

Errores: 400 `VALIDATION_ERROR` con `fieldErrors` por campo fuera de rango, y 400 `VALIDATION_ERROR` con el mensaje
"Los días de aviso tienen que ser más que los días críticos: primero avisás y después es crítico." cuando
`expiryCriticalDays >= expiryWarningDays`.

**Rotación de stock (SPEC §4.2)** — es el cambio con más consecuencias, por eso la UI lo explica con dos tarjetas:

- `FIFO`: cada venta descuenta del lote más viejo por `received_at`. Si cargás un lote nuevo que vence antes que uno
  más viejo con stock, la carga devuelve `rotationWarning`.
- `FEFO`: cada venta descuenta del lote con el vencimiento más cercano (`expiry_date`), sin importar cuándo entró.

En los dos casos, los lotes **en liquidación** (`discount_pct`) salen primero y los lotes **vencidos nunca se
venden**. El cambio afecta a las ventas nuevas; los movimientos ya registrados no se tocan.

### 3.3 `GET /api/tenant/account`

Datos administrativos del comercio, **solo lectura** (los cambia GondolIA desde su consola).

```json
{"id": 1, "name": "Comercio de Prueba", "legalName": null, "taxId": null, "businessType": "ALMACEN",
 "plan": "BASICO", "status": "ACTIVE", "contactName": "Ana Rodríguez", "contactEmail": "admin@prueba.com",
 "contactPhone": null, "address": null, "city": "CABA", "province": "Buenos Aires",
 "createdAt": "2026-09-18T00:28:18.493Z", "modules": ["POS_GONDOLIA", "POS_INTEGRATION", "MULTI_BRANCH"],
 "activeBranches": 3, "maxBranches": 3, "estimatedMonthlyFee": 135000}
```

`estimatedMonthlyFee` = `ModuleCatalog.monthlyFee(plan, módulos, sucursales activas)`: (precio del plan + adicionales
de los módulos habilitados) × sucursales activas (SPEC §14.1). `maxBranches` es el máximo efectivo.

---

## 4. Pantallas (`frontend/src/features/tenantadmin`)

| Ruta | Página | Qué hace |
|---|---|---|
| `/app/users` | `UsersPage` | Listado con rol, sucursales asignadas, último ingreso y estado; filtro por estado con contadores y búsqueda; diálogo de alta/edición; reseteo de contraseña |
| `/app/branches` | `BranchesPage` | Indicador "2 de 3 sucursales activas" con el plan, aviso cuando falta `MULTI_BRANCH`, listado con equipo y stock, alta/edición y baja/reactivación |
| `/app/settings` | `SettingsPage` | Pestañas **Alertas y vencimientos**, **Reposición e IA**, **Rotación de stock** y **Datos del comercio** |

Detalles de interfaz:

- **Alta de usuario**: el selector múltiple de sucursales aparece **solo** para Empleado y Cajero; para Jefe y
  Administrador se muestra un aviso "Acceso a todas las sucursales". La contraseña inicial tiene un botón "Generar"
  y se puede mostrar u ocultar.
- **Protecciones del propio usuario**: la fila del administrador logueado lleva la etiqueta "Vos", el rol y el
  interruptor "Usuario activo" quedan deshabilitados con el motivo escrito, y no se le ofrece "Restablecer
  contraseña" (para eso está Mi perfil).
- **Reseteo**: por defecto genera la contraseña; con un interruptor se puede escribir una. El resultado se muestra
  en monoespaciada con "Copiar" y el aviso de que se cerraron las sesiones abiertas.
- **Baja de sucursal**: el botón queda deshabilitado **con el motivo visible** ("No se puede: todavía tiene
  mercadería" / "es tu única sucursal activa"); cuando se puede, la confirmación explica el efecto y avisa si hay
  gente asignada.
- **Rotación**: dos tarjetas de radio con la consecuencia real de cada opción, para quién conviene y un ejemplo;
  debajo, el recordatorio de que los lotes en liquidación salen primero.
- **Vencimientos**: los valores se previsualizan con `ExpiryChip` (Vencido / Crítico / Por vencer / Próximo) usando
  los días que el usuario está escribiendo.
- **Datos del comercio**: solo lectura, con el plan, el abono estimado, los módulos habilitados y un aviso con link
  a Soporte ("El nombre, el plan, el CUIT y los módulos los administra GondolIA").

Las query keys no llevan el segmento de sucursal (`['tenant-users', …]`, `['tenant-branches', …]`,
`['tenant-settings', …]`, `['tenant-account', …]`) porque ninguno de estos recursos depende de la sucursal elegida.

---

## 5. Pruebas

| Clase | Qué cubre |
|---|---|
| `TenantUserServiceIntegrationTest` | Alta con sucursales obligatorias para empleado y cajero, sucursales ajenas o desactivadas, email repetido, roles de plataforma, autoprotección del administrador, último administrador activo, cierre de sesiones al cambiar rol o desactivar, reseteo generado y explícito, aislamiento entre comercios |
| `TenantBranchServiceIntegrationTest` | Máximo efectivo con y sin `MULTI_BRANCH`, nombre repetido, baja bloqueada por stock y por última activa, reactivación respetando el límite, alcance del listado por rol, conteo de empleados, aislamiento entre comercios |
| `TenantConfigServiceIntegrationTest` | Valores por defecto, guardado de rotación y umbrales (verificado con SQL), umbrales incoherentes, abono estimado y máximo efectivo en `account` |
| `TenantAdminApiIntegrationTest` | Los endpoints sobre HTTP: 403 por rol (empleado y cajero), 404 con ids de otro comercio, el cajero solo ve su sucursal aunque mande `X-Branch-Id` de otra, y `fieldErrors` de validación |

```bash
cd backend && mvn test -Dgondolia.it=true
```
