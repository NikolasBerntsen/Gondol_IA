# GondolIA — Especificación técnica del prototipo

> Documento contrato. Todo módulo (backend, frontend, IA, infraestructura) DEBE respetarlo.
> Si algo no está definido acá, elegí la opción más simple y coherente con este documento y
> dejalo anotado en el reporte final. Nunca cambies un contrato compartido sin avisar.

Idioma: **código en inglés**, **textos de interfaz, mensajes de error y documentación en español rioplatense**
("Iniciá sesión", "Cargá el producto"). Zona horaria de negocio: `America/Argentina/Buenos_Aires`. Moneda: ARS.

---

## 1. Qué es GondolIA

SaaS multi-tenant de gestión inteligente de inventario para comercios pequeños (kioscos, almacenes,
dietéticas, minimercados, farmacias). Controla productos, lotes, vencimientos, stock mínimo, ventas;
genera alertas y **recomendaciones con IA** (reposición, descuentos por vencimiento, anomalías,
patrones de venta). Los dueños de GondolIA administran los tenants (comercios clientes) sin ver sus
datos, publican avisos y **alertas de recall**; un equipo de soporte atiende tickets y chat en vivo.

**Multi-sucursal**: un cliente (tenant = empresa) puede tener **varios supermercados/locales (sucursales)**.
El catálogo (productos, categorías, proveedores, precios) es único por tenant; el **stock, los lotes, las ventas,
las transferencias, las alertas, la IA y los recalls son por sucursal**. Jefe y administrador ven todas las
sucursales (consolidado o una por una); cada empleado trabaja solo en las sucursales que tiene asignadas.

**Varios lotes y vencimientos por producto**: cada ingreso de mercadería crea su propio lote con su fecha de
vencimiento y número de lote, aunque el producto ya tenga stock con otras fechas. Las ventas descuentan
**primero lo que entró antes (FIFO)**, configurable por comercio a FEFO (primero lo que vence antes).
Los lotes vencidos nunca se venden.

### 1.1 Mockup de referencia (Inicio del comercio)
Sidebar verde oscuro con logo "GondolIA — Tu negocio siempre a tiempo" y frase al pie "Productos de hoy,
clientes de siempre". Topbar: buscador "Buscar productos, categorías o códigos…", **selector de sucursal**
(nombre del comercio + sucursal elegida, desplegable con "Todas las sucursales" y cada sucursal), campana, avatar. Título "Resumen del negocio", subtítulo
"¡Hola! Aquí tienes un vistazo general de tu negocio.", fecha larga a la derecha.
4 tarjetas: **Productos** (registrados, verde), **Por vencer** (naranja), **Stock bajo** (rojo),
**Valor inventario** (verde, `$8.450.000`). Gráfico de líneas "Ventas y stock — Tendencia de ventas e
inventario (últimos 30 días)" con series Ventas (unidades) y Stock total (unidades). Tabla "Próximos
vencimientos" (Producto, Fecha de vencimiento, Días restantes, Estado: Por vencer/Próximo). Tabla
"Artículos a reponer" (Producto, Stock actual, Stock mínimo, Sugerencia "Comprar 20", Estado
Crítico/Bajo). Banner verde "Mantén tu negocio siempre fresco".

---

## 2. Arquitectura

```
                 ┌───────────────────────── docker compose (proyecto "gondolia") ─────────────────────────┐
 navegador PC ──►│ web (nginx + React build)  :80 → host ${HTTP_PORT:-8080}                                 │
 celular WiFi ──►│   TLS autofirmado          :443 → host ${HTTPS_PORT:-8443}                               │
                 │   /api/** , /ws  ──proxy──► backend (Spring Boot, :8080 interno)                          │
                 │                                 │  JDBC            │ HTTP                                 │
                 │                                 ▼                  ▼                                      │
                 │                          db (PostgreSQL 16)   ai (FastAPI :8000 interno)                  │
                 └──────────────────────────────────────────────────────────────────────────────────────────┘
```

### 2.1 Estructura del repositorio
```
gondolia/
├── start.sh                  # ./start.sh [start|stop [--borrar]|restart|logs|status|reset|help]
├── docker-compose.yml
├── .env.example              # start.sh crea .env si no existe
├── README.md                 # guía en español para el equipo
├── SPEC.md                   # este documento
├── certs/                    # (generado, ignorado por git) CA local + certificado del servidor
├── docs/                     # documentación por módulo (api-*.md, decisiones)
├── backend/                  # Spring Boot 3.5.x · Java 21 · Maven
├── ai-service/               # Python 3.12 · FastAPI
└── frontend/                 # React 18 · TypeScript · Vite 5 · Tailwind 3 (+ nginx para servirlo)
```

### 2.2 Variables de entorno (.env)
| Variable | Default | Uso |
|---|---|---|
| `HTTP_PORT` | 8080 | Puerto host HTTP (localhost) |
| `HTTPS_PORT` | 8443 | Puerto host HTTPS (celular/LAN) |
| `DB_PORT` | 5433 | Puerto host de Postgres (para herramientas) |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | gondolia / gondolia / gondolia | DB |
| `JWT_SECRET` | (start.sh genera 64+ chars aleatorios) | Firma HS256 |
| `JWT_EXPIRATION_HOURS` | 12 | Vida del token |
| `APP_SEED_DEMO` | true | Carga datos demo si no hay tenants |
| `APP_DEV_FIXTURE` | false | Fixture mínimo para desarrollo local |
| `APP_BOOTSTRAP_OWNER_EMAIL` / `..._PASSWORD` | dueno@gondolia.app / Gondolia2026! | Dueño inicial |
| `APP_OPENFOODFACTS_ENABLED` | true | Autocompletar productos por código de barras (internet) |
| `APP_TIMEZONE` | America/Argentina/Buenos_Aires | Zona de negocio |
| `LAN_IPS` | (start.sh detecta) | IPs para el certificado y URLs impresas |
| `CERT_HOSTNAMES` | (vacío) | Hostnames extra para el certificado |

Backend (Spring) las lee como: `spring.datasource.url=jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT_INTERNAL:5432}/${POSTGRES_DB:gondolia}`,
`app.jwt.secret=${JWT_SECRET}`, `app.ai.base-url=${AI_SERVICE_URL:http://localhost:8000}`,
`app.storage.dir=${APP_STORAGE_DIR:./data/uploads}`, `server.port=${SERVER_PORT:8080}`.
(Se permite sobreescribir con `SPRING_DATASOURCE_URL`, etc.)

---

## 3. Roles, tenants y permisos

### 3.1 Roles (`Role` enum, valores exactos)
| Rol | Ámbito | Etiqueta UI | Propósito |
|---|---|---|---|
| `PLATFORM_OWNER` | plataforma (tenant_id NULL) | Dueño GondolIA | Alta/baja/habilitación de tenants, métricas agregadas, avisos y recalls, equipo interno. **Nunca ve datos de negocio de un tenant** (productos, stock, ventas, chats). |
| `SUPPORT_AGENT` | plataforma | Soporte | Atiende tickets y chat en vivo de todos los tenants. Ve solo lo que el cliente envía en el ticket (texto e imágenes) + nombre de comercio/usuario. |
| `TENANT_BOSS` | tenant | Jefe | Toma de decisiones: **solo dashboards** (Inicio, Estadísticas, Inteligencia IA, Alertas) en modo lectura + Avisos, Seguridad alimentaria, Soporte. |
| `TENANT_ADMIN` | tenant | Administrador | Máximo nivel dentro del tenant: todo. |
| `TENANT_EMPLOYEE` | tenant | Empleado | Carga de inventario y productos con vencimiento/lote (escáner y OCR), vencimientos y **cobro en el POS GondolIA** (si el módulo está activo) + Avisos, Seguridad alimentaria, Soporte. |
| `TENANT_CASHIER` | tenant | Cajero | **Solo el POS GondolIA** (abrir/cerrar su caja, cobrar, anular ventas de su turno) + Avisos, Seguridad alimentaria, Soporte. |

Un tenant se crea con (al menos) un jefe, un administrador y un empleado. El admin puede crear más usuarios (incluidos cajeros).

### 3.2 Estados de tenant (`TenantStatus`)
- `ACTIVE`: acceso normal.
- `DISABLED`: **deshabilitado** por los dueños → todos sus usuarios quedan bloqueados (login y cada request
  devuelven 403 `TENANT_DISABLED`; sesiones abiertas reciben `FORCE_LOGOUT` por WebSocket). Reversible.
- `CANCELLED`: **baja** del servicio → bloqueado (403 `TENANT_CANCELLED`), cuenta como churn. Reversible
  (reactivar) o eliminable definitivamente.

### 3.3 Matriz de permisos (tenant)
Las filas marcadas con [módulo] además requieren que ese módulo esté habilitado para el tenant (§14).
| Funcionalidad | BOSS | ADMIN | EMPLOYEE | CASHIER |
|---|:-:|:-:|:-:|:-:|
| Inicio / Estadísticas / Inteligencia IA / Alertas (ver) | ✔ | ✔ | ✘ | ✘ |
| Aceptar/descartar recomendaciones, gestionar alertas, recalcular IA | ✘ | ✔ | ✘ | ✘ |
| Inventario (listar/ver productos), crear/editar productos, categorías inline | ✘ | ✔ | ✔ | ✘ |
| Eliminar productos, CRUD categorías/proveedores | ✘ | ✔ | ✘ | ✘ |
| Carga de mercadería (lotes, escáner, OCR) — en sus sucursales | ✘ | ✔ | ✔ | ✘ |
| **Importación masiva Excel/CSV y exportación del catálogo** (§16) | ✘ | ✔ | ✘ | ✘ |
| Ver consolidado de todas las sucursales / cambiar de sucursal | ✔ | ✔ | solo asignadas | solo asignadas |
| Gestionar sucursales (alta, edición, baja) y asignar empleados/cajeros | ✘ | ✔ | ✘ | ✘ |
| Transferencias de stock entre sucursales [MULTI_BRANCH] | ✘ | ✔ | ✘ | ✘ |
| Vencimientos (ver) y descartar vencidos/dañados | ✘ | ✔ | ✔ | ✘ |
| **POS GondolIA: abrir/cerrar caja, cobrar, anular ventas del propio turno** [POS_GONDOLIA] (§15) | ✘ | ✔ | ✔ | ✔ |
| POS GondolIA: administrar cajas, ver todos los turnos, anular cualquier venta [POS_GONDOLIA] | ✘ | ✔ | ✘ | ✘ |
| Integración con POS propio: API keys, importación CSV de ventas, simulador [POS_INTEGRATION] | ✘ | ✔ | ✘ | ✘ |
| Historial de ventas (todas las fuentes), venta manual, ajustes, historial de movimientos | ✘ | ✔ | ✘ | ✘ |
| Usuarios del comercio y Configuración | ✘ | ✔ | ✘ | ✘ |
| Avisos (ver), Seguridad alimentaria (ver + "Entendido") | ✔ | ✔ | ✔ | ✔ |
| Resolver recall (retirar de stock) | ✘ | ✔ | ✔ | ✘ |
| Soporte (tickets + chat en vivo) | ✔ | ✔ | ✔ | ✔ |
| Perfil / cambiar contraseña, notificaciones | ✔ | ✔ | ✔ | ✔ |

### 3.4 Reglas de aislamiento (obligatorias)
1. El `tenantId` **siempre** sale del usuario autenticado (`CurrentUser.tenantId()`), **nunca** del request.
2. Todo acceso a una entidad por id en `/api/tenant/**` usa `findByIdAndTenantId(id, tenantId)` → 404 si no coincide.
3. `/api/platform/**` solo devuelve agregados o datos administrativos del tenant (nombre, contacto, plan,
   estado, cantidad de usuarios, última actividad). Prohibido exponer productos, stock, ventas, alertas,
   chats o qué tenants coinciden con un recall (solo **cantidades**).
4. `SUPPORT_AGENT` no accede a `/api/tenant/**`. Los tenant users no acceden a `/api/support/**` ni `/api/platform/**`.
5. Suscripciones STOMP autorizadas por destino (§7).
6. **Sucursales**: toda operación sobre datos por sucursal valida que la sucursal pertenezca al tenant y que el usuario
   tenga acceso (`BranchAccessService`, §5.3). Un empleado nunca ve ni modifica stock, lotes, ventas, alertas o recalls
   de sucursales no asignadas (404/403). El catálogo de productos sí es visible para todos los usuarios de inventario del tenant.

### 3.5 Sucursales y alcance (scope)
- `TENANT_ADMIN` y `TENANT_BOSS`: acceso a **todas** las sucursales activas del tenant. `TENANT_EMPLOYEE` y `TENANT_CASHIER`:
  solo las de `user_branches` (al menos una; el admin las asigna).
- El frontend envía la sucursal elegida en el header **`X-Branch-Id`** (id numérico) en todos los requests. Sin header
  o con `X-Branch-Id: all` = **todas las sucursales accesibles** (vista consolidada).
- Lecturas por sucursal (dashboards, stock, vencimientos, ventas, alertas, IA, recalls) operan sobre el scope
  (una o todas las accesibles) y cada fila incluye `branchId` + `branchName`.
- Escrituras por sucursal (carga de lotes, ventas, ajustes, descarte, resolución de recall, simulador POS) requieren
  **una** sucursal: se toma de `branchId` del body si existe, si no del header; si el scope es "todas" y el usuario tiene
  más de una sucursal → 400 `BRANCH_REQUIRED` ("Elegí una sucursal para esta operación").
- Límite de sucursales por plan: FREEMIUM 1, BASICO 3, PROFESIONAL 10 (409 `BRANCH_LIMIT_REACHED`), **y solo si el módulo
  `MULTI_BRANCH` está habilitado; si no, el máximo efectivo es 1** (§14). La suscripción se cobra **por sucursal activa**.
- Todo tenant tiene al menos una sucursal activa (se crea "Sucursal Principal" junto con el tenant). No se puede desactivar
  la última sucursal activa ni una con stock físico > 0 (409 `BRANCH_HAS_STOCK`).

---

## 4. Modelo de datos

Fuente de verdad: `backend/src/main/resources/db/migration/V1__schema.sql`. Hibernate corre con
`ddl-auto: validate`. Nuevas migraciones: `V{n}__descripcion.sql` con rangos por módulo (§12).

### 4.1 Enums (valores exactos, persistidos como STRING)
```
Role: PLATFORM_OWNER, SUPPORT_AGENT, TENANT_BOSS, TENANT_ADMIN, TENANT_EMPLOYEE, TENANT_CASHIER
TenantStatus: ACTIVE, DISABLED, CANCELLED
TenantModule: POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH                         (§14)
PosSessionStatus: OPEN, CLOSED · PosSaleStatus: COMPLETED, VOIDED                   (§15)
PaymentMethod: CASH, DEBIT, CREDIT, TRANSFER, QR · CashMovementType: CASH_IN, CASH_OUT (§15)
ImportType: PRODUCTS · ImportStatus: UPLOADED, VALIDATED, APPLYING, APPLIED, FAILED, CANCELLED (§16)
ImportFileFormat: XLSX, XLS, CSV · ImportRowStatus: PENDING, VALID, WARNING, ERROR, SKIPPED, IMPORTED, FAILED · ImportRowAction: CREATE, UPDATE, SKIP (§16)
TenantPlan: FREEMIUM, BASICO, PROFESIONAL            (precio mensual ARS POR SUCURSAL activa: 0, 25000, 55000; máx. sucursales: 1, 3, 10)
StockRotation: FIFO, FEFO                             (default FIFO)
BusinessType: KIOSCO, ALMACEN, DIETETICA, MINIMERCADO, FARMACIA, OTRO
TenantEventType: CREATED, PLAN_CHANGED, DISABLED, ENABLED, CANCELLED, REACTIVATED, DELETED, MODULE_ENABLED, MODULE_DISABLED
ProductUnit: UNIDAD, KG, LITRO, PAQUETE, CAJA
LotStatus: ACTIVE, DEPLETED, EXPIRED_DISCARDED, RECALLED
MovementType: ENTRY, SALE, SALE_VOID, ADJUSTMENT_IN, ADJUSTMENT_OUT, WASTE_EXPIRED, WASTE_DAMAGED, RECALL_REMOVAL, TRANSFER_OUT, TRANSFER_IN
MovementSource: MANUAL, SCAN, OCR, CSV, POS, POS_GONDOLIA, IMPORT, SEED, SYSTEM   (POS = POS externo del cliente; POS_GONDOLIA = nuestro POS)
AlertType: EXPIRING_SOON, EXPIRED, LOW_STOCK, OUT_OF_STOCK, RECALL_MATCH, ANOMALY, STOCKOUT_PREDICTED, SALE_WITHOUT_STOCK
Severity: INFO, WARNING, CRITICAL
AlertStatus: OPEN, ACKNOWLEDGED, RESOLVED, DISMISSED
AiRunStatus: RUNNING, OK, ERROR
AiRunTrigger: SCHEDULED, MANUAL, STARTUP
SalesPattern: ALTA_ROTACION_ESTABLE, ESTACIONAL_SEMANAL, INTERMITENTE, EN_CRECIMIENTO, EN_DECLIVE, BAJA_ROTACION, SIN_MOVIMIENTO, DATOS_INSUFICIENTES
RecommendationType: REORDER, DISCOUNT, REMOVE_EXPIRED, REVIEW_ANOMALY, REDUCE_PURCHASE
RecommendationStatus: PENDING, ACCEPTED, DISCARDED, EXPIRED
AnnouncementKind: GENERAL, RECALL
AnnouncementStatus: DRAFT, PUBLISHED, ARCHIVED
RecallMatchStatus: OPEN, ACKNOWLEDGED, RESOLVED
RecallResolution: REMOVED_FROM_STOCK, RETURNED_TO_SUPPLIER, NOT_FOUND_IN_STORE
NotificationType: ANNOUNCEMENT, RECALL_ALERT, ALERT, RECOMMENDATION, TICKET_MESSAGE, TICKET_STATUS, SYSTEM
AttachmentPurpose: SUPPORT, OCR
TicketCategory: TECNICO, USO, FACTURACION, SUGERENCIA, OTRO
TicketPriority: BAJA, MEDIA, ALTA, URGENTE
TicketStatus: OPEN, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED, CLOSED
TicketChannel: TICKET, CHAT
MessageSenderType: CUSTOMER, AGENT, SYSTEM
```

### 4.2 Reglas de stock
Todo el stock es **por sucursal** (`lots.branch_id`, `stock_movements.branch_id`). Los totales de un scope de varias
sucursales son la suma de cada una.
- **Stock vendible** de un producto en una sucursal = Σ `lots.quantity` con `status='ACTIVE'` y (`expiry_date IS NULL` o `expiry_date >= hoy`).
- **Stock vencido pendiente** = lotes `ACTIVE` con `expiry_date < hoy` y `quantity > 0` (se descartan con `WASTE_EXPIRED`).
- **Stock en cuarentena** = lotes `RECALLED` con `quantity > 0` (no vendible).
- **Stock físico** (para "Stock total" y valor de inventario) = Σ quantity de lotes `ACTIVE` + `RECALLED`.
- **Cada ingreso crea un lote nuevo** (lot_number y expiry_date pueden ser NULL, y puede repetir número/fecha de un lote
  existente): así un mismo producto convive con varios lotes y vencimientos, y el orden de salida es exacto.
  `received_at` = instante del ingreso (TIMESTAMPTZ).
- **Rotación** (`tenant_settings.stock_rotation`) para ventas y bajas sin lote indicado, siempre sobre lotes vendibles
  (los vencidos NUNCA se venden):
  - `FIFO` (default, "primero sale lo que entró antes"): `received_at ASC, id ASC`.
  - `FEFO` ("primero sale lo que vence antes"): `expiry_date ASC NULLS LAST, received_at ASC, id ASC`.
  - **Lotes en liquidación primero**: los lotes con `discount_pct` activo (recomendación DISCOUNT aceptada) se venden antes que
    el resto; dentro de cada grupo rige FIFO/FEFO. Orden completo: `(discount_pct IS NULL) ASC, <orden FIFO|FEFO>`. Así el
    descuento por vencimiento cercano realmente acelera la salida de ese lote. La IA simula el consumo con este mismo orden.
  El próximo lote a consumir se muestra en la UI con la etiqueta "Se vende primero" (o "En liquidación · sale primero").
  Las ventas netas descuentan los movimientos `SALE_VOID` (anulaciones del POS, §15).
  Si no alcanza, el faltante se registra como movimiento `SALE` con `lot_id NULL` y alerta `SALE_WITHOUT_STOCK`.
- Con FIFO, si al cargar un lote su vencimiento es **anterior** al de lotes más viejos con stock en la misma sucursal,
  la respuesta de la carga incluye `rotationWarning` ("Este lote vence antes que mercadería que ingresó antes: con FIFO se
  venderá después. Revisalo o aplicá un descuento.") y la IA lo considera en el riesgo por lote.
- **Transferencias**: `TRANSFER_OUT` descuenta del lote origen y crea en la sucursal destino un lote nuevo con el mismo
  `lot_number`, `expiry_date`, `cost_price`, `supplier_id` y **el mismo `received_at` original** (conserva su antigüedad para
  FIFO), `origin_lot_id` = lote origen, más un movimiento `TRANSFER_IN`. Ambos movimientos comparten `batch_ref` (`T-...`).
  No se pueden transferir lotes `RECALLED` ni vencidos (409 `LOT_NOT_TRANSFERABLE`). El lote destino pasa por el chequeo de recall.
- Lote que llega a quantity 0 → `DEPLETED` (salvo `RECALLED`, que queda `RECALLED`; y si el último movimiento
  fue `WASTE_EXPIRED` → `EXPIRED_DISCARDED`). `ADJUSTMENT_IN` sobre un lote `DEPLETED` lo vuelve a `ACTIVE`.
- Un lote con `discount_pct` aplica ese descuento al precio de las unidades vendidas de ese lote
  (`unit_price = sale_price * (1 - pct/100)`, se guarda `discount_pct` en el movimiento).
- Valor de inventario (costo) = Σ `quantity * COALESCE(lots.cost_price, products.cost_price)` (stock físico).
- Buckets de vencimiento (con `tenant_settings`): `EXPIRED` (<0 días), `CRITICAL` (0..critical_days),
  `WARNING` (..warning_days), `UPCOMING` (..30 días), resto `OK`. Etiquetas UI: Vencido / Crítico / Por vencer / Próximo.
- Estado de stock de producto **por sucursal**: `OUT` (vendible = 0), `LOW` (vendible ≤ min_stock), `OK`. `min_stock` es
  por producto y aplica a cada sucursal. En un scope de varias sucursales el estado es el **peor** entre ellas y se
  informa el detalle `stockByBranch`. En "Artículos a reponer" (una fila por producto+sucursal): `SIN_STOCK` (0),
  `CRITICO` (≤ 50% del mínimo), `BAJO` (≤ mínimo).
- **Normalización de lote** `LotNumbers.normalize(raw)`: `null` si vacío; mayúsculas; eliminar todo carácter
  que no sea `[A-Z0-9]` ("l-2409/a " → "L2409A"). Se guarda en `lot_number_normalized`.
- **Código de barras** `Barcodes.normalize(raw)`: trim, eliminar espacios; se aceptan EAN-13/EAN-8/UPC-A/Code128 (alfanumérico ≤ 32).

---

## 5. Backend (Spring Boot)

### 5.1 Stack
Spring Boot **3.5.x**, Java 21, Maven. Dependencias (todas declaradas en el `pom.xml` desde el inicio):
web, security, data-jpa, validation, websocket, actuator, flyway-core + flyway-database-postgresql,
postgresql, jjwt 0.12.x (api/impl/jackson), springdoc-openapi-starter-webmvc-ui 2.8.x, lombok,
spring-boot-starter-test, spring-security-test. `artifactId=gondolia-backend`, jar final `target/gondolia-backend.jar`
(`<finalName>gondolia-backend</finalName>`). Paquete raíz **`com.gondolia`**, clase `GondoliaApplication`
(`@EnableScheduling`, `@EnableAsync`).

### 5.2 Convenciones
- **Entidades**: `com.gondolia.domain.<area>` (areas: `tenant` (incluye `Branch`, `TenantSettings`), `user` (incluye `UserBranch`), `inventory`, `alert`, `ai`,
  `announcement`, `notification`, `support`). Lombok `@Getter @Setter @NoArgsConstructor`. **Sin asociaciones JPA**:
  las FK se mapean como `Long` (`private Long categoryId;`). Enums `@Enumerated(EnumType.STRING)`.
  Timestamps `Instant` con `@CreationTimestamp`/`@UpdateTimestamp`. Fechas `LocalDate`. Dinero `BigDecimal`.
  JSONB: `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private JsonNode campo;`.
  Enums en `com.gondolia.domain.<area>` junto a su entidad (Role en `domain.user`, Severity en `domain.common`).
- **Repositorios**: Spring Data en el mismo paquete que la entidad (`ProductRepository`). Para tenant:
  `Optional<X> findByIdAndTenantId(Long id, Long tenantId)`. Analítica compleja: `JdbcTemplate`/`NamedParameterJdbcTemplate`.
- **DTOs**: Java `record`s en el paquete del módulo (`com.gondolia.catalog.dto`). JSON camelCase. Nunca exponer entidades.
- **Validación**: `jakarta.validation` en requests + mensajes en español.
- **Seguridad por método**: `@PreAuthorize(Roles.TENANT_ADMIN)` etc. usando las constantes de `com.gondolia.security.Roles`:
  ```java
  public static final String OWNER = "hasRole('PLATFORM_OWNER')";
  public static final String SUPPORT = "hasRole('SUPPORT_AGENT')";
  public static final String TENANT_ANY = "hasAnyRole('TENANT_BOSS','TENANT_ADMIN','TENANT_EMPLOYEE','TENANT_CASHIER')";
  public static final String TENANT_POS = "hasAnyRole('TENANT_ADMIN','TENANT_EMPLOYEE','TENANT_CASHIER')";
  public static final String TENANT_ADMIN = "hasRole('TENANT_ADMIN')";
  public static final String TENANT_DASHBOARD = "hasAnyRole('TENANT_BOSS','TENANT_ADMIN')";
  public static final String TENANT_INVENTORY = "hasAnyRole('TENANT_ADMIN','TENANT_EMPLOYEE')";
  ```
  Además `SecurityConfig` protege por prefijo: `/api/auth/login`, `/api/integrations/pos/**`, `/actuator/health`,
  `/api/docs/**`, `/api/swagger-ui/**`, `/ws/**` → permitAll (el WS autentica en CONNECT; POS con API key);
  `/api/platform/**` → PLATFORM_OWNER; `/api/support/**` → SUPPORT_AGENT; `/api/tenant/**` → roles tenant;
  resto `/api/**` → authenticated. Sesión STATELESS, CSRF deshabilitado, CORS permitido para `http://localhost:5173` (dev).
- **Errores**: `com.gondolia.common.error`: `ApiException(HttpStatus status, String code, String message)` y
  `NotFoundException(String msg)` (404 `NOT_FOUND`), `BadRequestException(String code, String msg)` (400),
  `ForbiddenException(String code, String msg)` (403), `ConflictException(String code, String msg)` (409).
  `GlobalExceptionHandler` devuelve siempre:
  ```json
  {"timestamp":"2026-09-17T12:00:00Z","status":400,"error":"Bad Request","code":"VALIDATION_ERROR",
   "message":"Revisá los datos ingresados","path":"/api/tenant/products","fieldErrors":[{"field":"name","message":"es obligatorio"}]}
  ```
  Códigos comunes: `VALIDATION_ERROR`, `NOT_FOUND`, `UNAUTHORIZED`, `FORBIDDEN`, `BAD_CREDENTIALS`,
  `TENANT_DISABLED`, `TENANT_CANCELLED`, `USER_DISABLED`, `CONFLICT`, `INSUFFICIENT_STOCK`, `AI_UNAVAILABLE`,
  `BRANCH_REQUIRED`, `BRANCH_FORBIDDEN`, `BRANCH_LIMIT_REACHED`, `BRANCH_HAS_STOCK`, `LOT_NOT_TRANSFERABLE`, `INTERNAL_ERROR`.
  401/403 del filtro de seguridad también usan este formato.
- **Paginación**: `com.gondolia.common.PageResponse<T>` →
  `{"content":[...],"page":0,"size":20,"totalElements":123,"totalPages":7}`; params `page` (0-based), `size` (≤100), `sort` (`campo,asc|desc`).
- **Tiempo**: bean `java.time.Clock` con zona `app.timezone`. "Hoy" = `LocalDate.now(clock)`. Nunca `LocalDate.now()` sin clock.
- **Eventos de dominio** (Spring `ApplicationEventPublisher`, paquete `com.gondolia.common.events`), records:
  `StockChangedEvent(Long tenantId, Long branchId, Long productId)`, `LotReceivedEvent(Long tenantId, Long branchId, Long productId, Long lotId)`,
  `RecallMatchedEvent(Long tenantId, Long announcementId, List<Long> matchIds)`,
  `TenantStatusChangedEvent(Long tenantId, TenantStatus from, TenantStatus to)`.
  Los listeners usan `@TransactionalEventListener(phase = AFTER_COMMIT)` (+ `@Async` si son costosos).
- Logs en español o inglés, sin datos sensibles. Swagger en `/api/swagger-ui.html`, docs en `/api/docs`.

### 5.3 Núcleo compartido (lo implementa la fundación; los módulos lo USAN, no lo modifican)

**Seguridad** (`com.gondolia.security`)
- `record AuthUser(Long id, String email, String fullName, Role role, Long tenantId) implements java.security.Principal` — `getName()` devuelve `String.valueOf(id)`.
- `final class CurrentUser`: `static AuthUser get()`, `static Long id()`, `static Role role()`,
  `static Long tenantId()` (lanza `ForbiddenException("NO_TENANT", ...)` si no es usuario de tenant), `static Optional<AuthUser> optional()`.
- `JwtService`: `String issue(User u)` (claims: `sub`=id, `email`, `role`, `tid`=tenantId, `tv`=tokenVersion), `Optional<JwtClaims> parse(String token)`.
- `UserAccessValidator`: `AuthUser validate(Long userId, int tokenVersion)` — carga user, verifica `active`, `tokenVersion`,
  y si es de tenant verifica tenant `ACTIVE` (lanza ApiException 401 `USER_DISABLED` / 403 `TENANT_DISABLED` / `TENANT_CANCELLED`). Lo usan el filtro HTTP y el interceptor STOMP.
- `ApiKeyService`: `GeneratedApiKey generate()` → `record GeneratedApiKey(String rawKey, String prefix, String hash)`
  (formato `gk_` + 40 chars base62; prefix = primeros 10 chars; hash = SHA-256 hex), `String hash(String raw)`,
  `Optional<PosBranch> resolveBranch(String rawKey)` → `record PosBranch(Long tenantId, Long branchId)` (la API key es
  **por sucursal**, en `branches.pos_api_key_hash`; sucursal activa y tenant ACTIVE) y `PosBranch authenticate(String rawKey)`
  para el webhook (401 `INVALID_API_KEY` si la key no sirve; 403 `TENANT_DISABLED` / `TENANT_CANCELLED` si es de un comercio bloqueado).
- `BranchAccessService` (lee el header `X-Branch-Id` del request actual vía `RequestContextHolder`):
  - `record BranchRef(Long id, String name, String code)`
  - `List<BranchRef> accessibleBranches()` — sucursales activas accesibles por el usuario actual (ADMIN/BOSS: todas; EMPLOYEE: asignadas), orden por nombre.
  - `List<Long> scopeBranchIds()` — `[id]` si el header trae un id accesible (si no es accesible → 403 `BRANCH_FORBIDDEN`), o todas las accesibles si falta/`all`.
  - `Long requireSingleBranch(Long explicitBranchId /* nullable, p.ej. del body */)` — explícito > header > única accesible; si no se puede determinar → 400 `BRANCH_REQUIRED`; valida acceso.
  - `void assertAccess(Long branchId)` — 404 `NOT_FOUND` si no es del tenant, 403 `BRANCH_FORBIDDEN` si el usuario no tiene acceso.
  - `boolean canAccess(Long userId, Long branchId)` y `List<Long> userIdsWithAccess(Long tenantId, Long branchId)` (usuarios activos con acceso: admins + jefes + empleados asignados).
  - `Map<Long,String> branchNames(Long tenantId)`.
- `PasswordEncoder` = BCrypt.

**Auth** (`com.gondolia.auth`) — ver §6.1.

**Tiempo real** (`com.gondolia.realtime`)
- `RealtimePublisher`: `void toUser(Long userId, String queue, Object payload)` (queue p.ej. `"/queue/notifications"`),
  `void toTopic(String destination, Object payload)`. Si hay transacción activa, envía **después del commit**.
- `SessionTerminationService`: `void forceLogoutTenant(Long tenantId, String code, String message)`,
  `void forceLogoutUser(Long userId, String code, String message)` → envía `/user/queue/session` `{"type":"FORCE_LOGOUT","code":"TENANT_DISABLED","message":"..."}`.
- `PresenceTracker`: cuenta sesiones STOMP de `SUPPORT_AGENT`; publica `/topic/support/presence` `{"agentsOnline":2}` al cambiar.
- Config STOMP, interceptor de CONNECT (JWT) y autorización de SUBSCRIBE (§7).

**Notificaciones** (`com.gondolia.notification`)
- `record NotificationDraft(NotificationType type, Severity severity, String title, String body, String link, String referenceType, Long referenceId)`
- `NotificationService`:
  - `NotificationDto notifyUser(Long userId, NotificationDraft d)`
  - `int notifyTenantUsers(Long tenantId, Set<Role> roles /* null = todos los roles de tenant */, NotificationDraft d)`
  - `int notifyBranchUsers(Long tenantId, Long branchId, Set<Role> roles /* null = todos */, NotificationDraft d)` (solo usuarios con acceso a esa sucursal)
  - `int notifyAllActiveTenants(NotificationDraft d, Set<BusinessType> businessTypes /* null = todos */)`
  - `int notifyPlatformRole(Role role, NotificationDraft d)`
  Persiste en `notifications` (solo usuarios activos) y hace push `/user/queue/notifications` con `NotificationDto`.
- `NotificationController` (§6.2).

**Stock** (`com.gondolia.stock`) — `StockService` (transaccional, publica `StockChangedEvent(tenantId, branchId, productId)`).
Los comandos NO validan permisos de usuario (eso lo hace el controller con `BranchAccessService`), pero sí que
producto, lote y sucursal pertenezcan al `tenantId`.
```java
record ReceiveLotCommand(Long tenantId, Long branchId, Long productId, String lotNumber, LocalDate expiryDate, int quantity,
                         BigDecimal costPrice, Long supplierId, Instant receivedAt /* null = now */, MovementSource source,
                         Long userId, String reason) {}
record ReceiveLotResult(Lot lot, StockMovement movement, List<RecallMatch> recallMatches, String rotationWarning /* null si no aplica */) {}
ReceiveLotResult receiveLot(ReceiveLotCommand cmd);   // SIEMPRE crea un lote nuevo + ENTRY; llama RecallMatchingService.checkLot → si coincide queda RECALLED

record SaleCommand(Long tenantId, Long branchId, Long productId, int quantity, BigDecimal unitPrice /* null = sale_price */,
                   Instant occurredAt /* null = now */, MovementSource source, Long userId, String batchRef) {}
record SaleResult(List<StockMovement> movements, int shortageQuantity, BigDecimal totalAmount) {}
SaleResult registerSale(SaleCommand cmd);             // rotación FIFO/FEFO del tenant, descuentos por lote, faltante → alerta SALE_WITHOUT_STOCK

record AdjustCommand(Long tenantId, Long lotId, MovementType type /* ADJUSTMENT_IN|ADJUSTMENT_OUT|WASTE_EXPIRED|WASTE_DAMAGED|RECALL_REMOVAL */,
                     int quantity, String reason, MovementSource source, Long userId) {}
StockMovement adjust(AdjustCommand cmd);              // la sucursal es la del lote; valida que no quede negativo → 409 INSUFFICIENT_STOCK

record TransferItem(Long lotId, int quantity) {}
record TransferCommand(Long tenantId, Long fromBranchId, Long toBranchId, List<TransferItem> items, String note, Long userId) {}
record TransferResult(String batchRef, List<StockMovement> outMovements, List<Lot> destinationLots, List<RecallMatch> recallMatches) {}
TransferResult transfer(TransferCommand cmd);         // reglas §4.2; from != to; lotes deben ser de fromBranchId

List<Lot> lotsInRotationOrder(Long tenantId, Long branchId, Long productId); // lotes vendibles en el orden en que se venderán
int sellableStock(Long tenantId, Long branchId, Long productId);
Map<Long, Integer> sellableStockByProduct(Long tenantId, Collection<Long> branchIds);          // suma del scope
Map<Long, Map<Long, Integer>> sellableStockByBranchAndProduct(Long tenantId, Collection<Long> branchIds); // branchId → (productId → stock)
StockRotation rotationFor(Long tenantId);
```
**Recall** (`com.gondolia.recall`) — `RecallMatchingService`:
```java
record RecallInfo(Long announcementId, String title, String reason, String instructions, boolean allLots) {}
List<RecallInfo> findActiveRecalls(String barcode, String lotNumber, LocalDate expiryDate); // sin efectos
List<RecallMatch> matchAnnouncement(Long announcementId);  // barre lotes (ACTIVE/RECALLED con quantity>0) de tenants ACTIVE
List<RecallMatch> checkLot(Long tenantId, Long lotId);     // contra recalls PUBLISHED
```
Coincidencia: `products.barcode = recall_barcode` Y (`recall_all_lots` O `lots.lot_number_normalized IN recall lots`)
Y (si hay rango) `expiry_date BETWEEN recall_expiry_from AND recall_expiry_to`. Por cada lote nuevo coincidente:
crea `recall_matches` (idempotente por `(announcement_id, lot_id)`, con `branch_id` del lote), pone el lote en `RECALLED`
(cuarentena), crea alerta `RECALL_MATCH` CRITICAL con `branch_id` (`dedupe_key = "RECALL:"+announcementId+":"+lotId`),
notifica a **todos los usuarios con acceso a esa sucursal** (`notifyBranchUsers`, `RECALL_ALERT`, CRITICAL, link `/app/recalls`)
y hace push `/user/queue/security-alerts` con `RecallAlertMessage` (§7) a cada uno, actualiza
`announcements.affected_tenants_count` (tenants distintos con matches) y publica `RecallMatchedEvent`.

**Archivos** (`com.gondolia.storage`) — `AttachmentStorageService`:
`Attachment store(MultipartFile file, AttachmentPurpose purpose, Long tenantId, Long uploadedBy)` (solo
`image/png|jpeg|webp|gif`, ≤ 10 MB, 400 `INVALID_FILE`; guarda en `${app.storage.dir}/yyyy/MM/<uuid>.<ext>`),
`Resource load(Attachment a)`. `AttachmentController`: `GET /api/attachments/{id}` → bytes con content-type;
acceso: `SUPPORT` → `SUPPORT_AGENT` o usuario del mismo tenant; `OCR` → mismo tenant. Si no → 404.

**Cliente IA** (`com.gondolia.ai`) — `AiClient` (RestClient, base `app.ai.base-url`, timeouts: connect 3s, analyze 120s, ocr 60s):
`boolean isHealthy()`, `AnalyzeResponse analyze(AnalyzeRequest req)`, `OcrResponse ocr(byte[] bytes, String filename, String contentType)`,
`BarcodeResponse barcode(byte[] bytes, String filename, String contentType)`. DTOs records en `com.gondolia.ai.dto`
que espejan EXACTAMENTE §8. Error de red → `ApiException(503, "AI_UNAVAILABLE", "El servicio de IA no está disponible")`.

**Bootstrap** (`com.gondolia.bootstrap`)
- `BootstrapRunner` (`@Order(10)`): si no existe ningún `PLATFORM_OWNER`, lo crea con `APP_BOOTSTRAP_OWNER_*`.
- `DevFixtureRunner` (`@Order(20)`, solo si `app.dev-fixture=true`): crea (si no existen) tenant "Comercio de Prueba"
  (ALMACEN, BASICO) + settings + sucursales "Sucursal Centro" y "Sucursal Norte" + `jefe@prueba.com`, `admin@prueba.com`,
  `empleado@prueba.com` (asignado solo a Centro), `soporte@gondolia.app`, todos con contraseña `Demo2026!`; un segundo
  tenant "Otro Comercio" con `admin@otro.com` (para probar aislamiento); y 3 productos con 2 lotes cada uno (distintas fechas)
  en Centro y 1 lote en Norte.
- El seeder de demo (`com.gondolia.seed.DemoDataSeeder`, `@Order(30)`, `app.seed-demo=true` y 0 tenants) lo implementa el módulo G.

---

## 6. API REST

Base `/api`. JSON. Auth: `Authorization: Bearer <jwt>`. Sucursal: header `X-Branch-Id` (§3.5). Toda respuesta con datos
por sucursal incluye `branchId` y `branchName` en cada fila. Fechas `LocalDate` → `"2026-09-17"`; instantes → ISO-8601 UTC.
Dinero y decimales → número JSON. En cada módulo documentá los endpoints finales en `docs/api-<modulo>.md`.

### 6.1 Auth (fundación)
- `POST /api/auth/login` `{email, password}` → `{"token":"...","expiresAt":"...","user": MeDto}`.
  Errores: 401 `BAD_CREDENTIALS`, 401 `USER_DISABLED`, 403 `TENANT_DISABLED` ("El acceso de tu comercio está deshabilitado. Comunicate con GondolIA."), 403 `TENANT_CANCELLED`.
  Actualiza `last_login_at`.
- `GET /api/auth/me` → `MeDto`:
  ```json
  {"id":5,"email":"admin@elsol.com","fullName":"Laura Gómez","role":"TENANT_ADMIN","mustChangePassword":false,
   "tenant":{"id":2,"name":"Minimercado El Sol","plan":"PROFESIONAL","businessType":"MINIMERCADO","currency":"ARS","stockRotation":"FIFO","maxBranches":10},
   "branches":[{"id":3,"name":"Sucursal Centro","code":"CEN"},{"id":4,"name":"Sucursal Fisherton","code":"FIS"}]}
  ```
  (`tenant` = null y `branches` = [] para roles de plataforma; `branches` = sucursales accesibles)
- `POST /api/auth/change-password` `{currentPassword, newPassword}` (min 8) → 204; incrementa `token_version`, devuelve nuevo token: `{"token":"...","expiresAt":"..."}` (200).

### 6.2 Notificaciones (fundación) — cualquier usuario autenticado
- `GET /api/notifications?unreadOnly=false&page=0&size=20` → `PageResponse<NotificationDto>`
  `NotificationDto = {id,type,severity,title,body,link,referenceType,referenceId,read,createdAt}`
- `GET /api/notifications/unread-count` → `{"count":3}`
- `POST /api/notifications/{id}/read` → 204 · `POST /api/notifications/read-all` → 204
- `GET /api/presence/support` → `{"agentsOnline":1}`
- `GET /api/attachments/{id}` → imagen

### 6.3 Módulo A1 — Catálogo y carga (`com.gondolia.catalog`) · roles: TENANT_INVENTORY salvo indicación
- Categorías: `GET /api/tenant/categories` → `[{id,name,productCount}]` (TENANT_ANY) · `POST` `{name}` · `PUT /{id}` (ADMIN) · `DELETE /{id}` (ADMIN, 409 si tiene productos).
- Proveedores: `GET /api/tenant/suppliers` → `[{id,name,contactName,phone,email,leadTimeDays,notes,active,productCount}]` · `POST|PUT /{id}|DELETE /{id}` (ADMIN).
- Productos:
  - `GET /api/tenant/products?q=&categoryId=&stockStatus=ALL|OK|LOW|OUT|EXPIRING&active=true&page=&size=&sort=name,asc`
    → `PageResponse<ProductListItem>`; `ProductListItem = {id,barcode,name,brand,categoryId,categoryName,unit,costPrice,salePrice,minStock,perishable,active,sellableStock,expiredStock,quarantinedStock,nextExpiryDate,lotsCount,stockStatus:"OK|LOW|OUT",stockByBranch:[{branchId,branchName,sellableStock,stockStatus}]}`
    (stock sumado sobre el scope de sucursales; `q` busca en nombre, marca y código)
  - `GET /api/tenant/products/{id}` → `ProductDetail = ProductListItem + {description,supplierId,supplierName,createdAt,updatedAt,lots:[LotDto]}`
    (lotes del scope con `quantity > 0` o recientes, ordenados por sucursal y luego en **orden de rotación**)
    `LotDto = {id,branchId,branchName,productId,lotNumber,expiryDate,daysToExpiry,initialQuantity,quantity,costPrice,receivedAt,status,source,discountPct,supplierId,supplierName,expiryBucket,originLotId,rotationRank /* 1 = se vende primero en su sucursal; null si no vendible */}`
  - `GET /api/tenant/products/by-barcode/{barcode}` → `ProductDetail` | 404
  - `POST /api/tenant/products` `{barcode,name,brand,description,categoryId,categoryName? (crea si no existe),supplierId,unit,costPrice,salePrice,minStock,perishable}` → `ProductDetail` (409 `DUPLICATE_BARCODE`)
  - `PUT /api/tenant/products/{id}` (mismo body) · `DELETE /api/tenant/products/{id}` (ADMIN; si tiene movimientos → `active=false`)
- `GET /api/tenant/catalog/lookup/{barcode}` → `{"found":true,"source":"OPEN_FOOD_FACTS","barcode":"...","name":"...","brand":"...","quantity":"340 g","categoryHint":"Sopas","imageUrl":"..."}` (timeout 4s; `found:false` si falla o está deshabilitado)
- Lotes (carga de mercadería):
  - `POST /api/tenant/lots` `{branchId?,productId,lotNumber,expiryDate,quantity,costPrice,supplierId,source:"MANUAL|SCAN|OCR"}`
    → `{"lot":LotDto,"quarantined":false,"recalls":[RecallInfo],"rotationWarning":null,"existingLots":[LotDto]}` (usa `StockService.receiveLot`;
    `existingLots` = otros lotes vendibles del producto en esa sucursal en orden de rotación, para mostrar "ya tenés X u. con vencimiento …")
  - `PUT /api/tenant/lots/{id}` `{lotNumber,expiryDate}` → `LotDto` (re-chequea recall si cambió)
  - `GET /api/tenant/lots?productId=&includeEmpty=false` → `[LotDto]` (scope de sucursales, orden de rotación)
- `GET /api/tenant/recalls/check?barcode=&lotNumber=&expiryDate=` → `{"recalled":true,"recalls":[RecallInfo]}`
- OCR: `POST /api/tenant/ocr/label` multipart `file` → `OcrResponse` (§8.3) + `"matchedProduct": ProductListItem|null` (si detectó un código existente). No se persiste la imagen.
- `POST /api/tenant/ocr/barcode` multipart `file` → `{"barcodes":[{"value":"...","format":"EAN_13"}]}`

### 6.4 Módulo A2 — Movimientos, ventas, transferencias, vencimientos, POS (`com.gondolia.movements`)
- Ventas (ADMIN): `POST /api/tenant/sales` `{branchId?,items:[{productId,quantity,unitPrice?}],occurredAt?}` →
  `{"batchRef":"S-...","branchId":3,"branchName":"...","occurredAt":"...","lines":[{productId,productName,quantity,unitPrice,discountPct,total,shortage,lots:[{lotId,lotNumber,expiryDate,quantity}]}],"total":1234.5}`
  (`lots` muestra de qué lotes salió cada unidad según la rotación)
  · `GET /api/tenant/sales?from=&to=&page=` → `PageResponse<{batchRef,branchId,branchName,occurredAt,itemsCount,units,total,source,userName}>`
  · `GET /api/tenant/sales/{batchRef}` · `POST /api/tenant/sales/import` multipart csv (`fecha,codigo_barras,cantidad,precio_unitario`; fecha `YYYY-MM-DD` o `DD/MM/YYYY`) → `{imported,skipped,errors:[{line,message}]}`
  · `GET /api/tenant/sales/import/template` → CSV
- Movimientos (ADMIN): `GET /api/tenant/movements?productId=&type=&from=&to=&page=` → `PageResponse<{id,branchId,branchName,productId,productName,lotId,lotNumber,type,quantity,unitPrice,discountPct,totalAmount,source,batchRef,reason,userName,occurredAt}>`
  · `POST /api/tenant/movements/adjustments` `{lotId,type,quantity,reason}` (ADMIN todos los tipos; EMPLOYEE solo `WASTE_EXPIRED|WASTE_DAMAGED` y en sus sucursales)
- Transferencias (ADMIN): `POST /api/tenant/transfers` `{fromBranchId,toBranchId,items:[{lotId,quantity}],note}` → `{batchRef,fromBranchId,fromBranchName,toBranchId,toBranchName,occurredAt,items:[{lotId,destinationLotId,productId,productName,lotNumber,expiryDate,quantity}],recalls:[RecallInfo]}`
  · `GET /api/tenant/transfers?page=` → `PageResponse<{batchRef,fromBranchId,fromBranchName,toBranchId,toBranchName,occurredAt,itemsCount,units,userName,note}>` · `GET /api/tenant/transfers/{batchRef}`
- Vencimientos (TENANT_INVENTORY): `GET /api/tenant/expirations?bucket=ALL|EXPIRED|CRITICAL|WARNING|UPCOMING&q=&page=` →
  `PageResponse<{lotId,branchId,branchName,productId,productName,barcode,categoryName,lotNumber,expiryDate,daysLeft,quantity,receivedAt,rotationRank,costValue,saleValue,bucket,discountPct,status}>`
  · `GET /api/tenant/expirations/summary` → `{"expired":{lots,units,costValue},"critical":{...},"warning":{...},"upcoming":{...}}`
  · `POST /api/tenant/expirations/{lotId}/discard` `{quantity?,reason?}` → `WASTE_EXPIRED` (todo el remanente si no se indica)
- **Módulos** (§14): ventas manuales, historial de ventas, movimientos y vencimientos son del núcleo (siempre disponibles).
  API keys POS, simulador, importación CSV de ventas y el webhook requieren `@RequiresModule(POS_INTEGRATION)` y su UI vive en
  `/app/integrations` (IntegrationsPage). Transferencias requieren `MULTI_BRANCH`. El historial de ventas muestra la fuente
  (POS GondolIA con link al ticket vía `pos_sales.batch_ref`, POS externo, CSV, manual, importación) y descuenta `SALE_VOID`.
- POS por sucursal (ADMIN): `GET /api/tenant/integrations/pos` → `[{branchId,branchName,configured,prefix,createdAt,lastSaleAt,salesLast24h}]` ·
  `POST /api/tenant/integrations/pos/{branchId}/key` → `{branchId,apiKey,prefix,createdAt}` (se muestra una sola vez) ·
  `POST /api/tenant/integrations/pos/{branchId}/simulate` `{sales: 10}` → genera ventas aleatorias realistas por el mismo camino que el POS.
- **Webhook POS** (API key de la sucursal, sin JWT): `POST /api/integrations/pos/sales` header `X-API-Key` body
  `{externalId?,occurredAt?,items:[{barcode,quantity,unitPrice?}]}` → `{batchRef,processed,unknownBarcodes:[],shortages:[{barcode,quantity}]}`. 401 `INVALID_API_KEY`
  (sea cual sea el payload); 403 `TENANT_DISABLED` / `TENANT_CANCELLED` si la key es válida pero el comercio está bloqueado (§3.2).

### 6.5 Módulo B — Dashboards, estadísticas, alertas e IA (`com.gondolia.analytics`, `com.gondolia.alerts`, `com.gondolia.insights`)
Roles: lectura TENANT_DASHBOARD; acciones TENANT_ADMIN. Todo respeta el scope de sucursales (§3.5).
- `GET /api/tenant/dashboard/summary` → `{scope:"ALL|BRANCH",branchCount,productsCount,expiringSoonCount,expiredCount,lowStockCount,outOfStockCount,inventoryCostValue,inventorySaleValue,openAlertsCount,pendingRecommendationsCount,openRecallMatchesCount,todaySalesUnits,todaySalesAmount,lastAiRunAt,asOf}`
- `GET /api/tenant/dashboard/sales-stock-trend?days=30` → `[{date,salesUnits,salesAmount,stockUnits}]`
- `GET /api/tenant/dashboard/branch-comparison?days=30` → `[{branchId,branchName,salesUnits,salesAmount,inventoryCostValue,expiringSoonCount,lowStockCount,wasteValue,openAlertsCount}]` (útil en la vista "Todas las sucursales")
- `GET /api/tenant/dashboard/upcoming-expirations?limit=8` → `[{lotId,branchId,branchName,productId,productName,lotNumber,expiryDate,daysLeft,quantity,bucket}]`
- `GET /api/tenant/dashboard/reorder?limit=8` → `[{productId,productName,branchId,branchName,sellableStock,minStock,suggestedQuantity,status:"SIN_STOCK|CRITICO|BAJO",predictedStockoutDate}]`
- `GET /api/tenant/statistics/overview?days=90` → ventas por día/semana, por categoría, por sucursal, top/bottom productos, ABC, rotación, merma por mes (valor), ventas perdidas estimadas por faltantes, tasa de aceptación de recomendaciones, ventas recuperadas con descuentos (estructura libre documentada en `docs/api-analytics.md`).
- Alertas: `GET /api/tenant/alerts?status=OPEN&type=&page=` · `POST /api/tenant/alerts/{id}/acknowledge|resolve|dismiss` (ADMIN).
  Motor: `AlertEngine` programado (cada 10 min + al inicio + listener `StockChangedEvent`) que abre/cierra alertas **por
  sucursal** `EXPIRING_SOON`, `EXPIRED`, `LOW_STOCK`, `OUT_OF_STOCK`, `STOCKOUT_PREDICTED`, `ANOMALY` con `dedupe_key`
  (incluye branchId) y notifica a los ADMIN (y a los EMPLOYEE de esa sucursal solo las de vencimiento) cuando se abre una CRITICAL nueva.
  Filas: `{id,branchId,branchName,type,severity,status,productId,productName,lotId,lotNumber,title,message,createdAt,handledByName,resolvedAt}`.
- IA (**por sucursal**): `GET /api/tenant/insights/summary` · `GET /api/tenant/insights/products?pattern=&abc=&page=` (filas con branchId/branchName) ·
  `GET /api/tenant/insights/products/{productId}?branchId=` (incluye historia diaria 90 días + pronóstico de esa sucursal; si falta branchId y el scope es una sola sucursal, usa esa) ·
  `POST /api/tenant/insights/run` (ADMIN, asincrónico → un `ai_runs` por sucursal del scope) · `GET /api/tenant/insights/runs/latest` → `[{branchId,branchName,status,startedAt,finishedAt,productsAnalyzed,recommendationsCreated,errorMessage}]`.
  `InsightsService` arma un `AnalyzeRequest` (§8.2) **por sucursal** con 180 días de ventas de esa sucursal, persiste `product_insights`,
  upsert de `recommendations` (dedupe por sucursal), expira las PENDING que ya no aplican. Programado diario 03:00 y al arrancar
  (si no hay run OK en 24 h) para todas las sucursales activas de tenants ACTIVE, con reintentos si la IA no está lista.
- Recomendaciones: `GET /api/tenant/recommendations?status=PENDING&type=&page=` →
  `PageResponse<{id,branchId,branchName,type,status,productId,productName,lotId,lotNumber,title,explanation,suggestedQuantity,suggestedDiscountPct,suggestedDate,priority,confidence,expectedImpact,createdAt,decidedAt,decidedByName,decisionNote,outcome}>`
  · `POST /api/tenant/recommendations/{id}/accept` `{note?,quantity?,discountPct?}` (DISCOUNT → setea `lots.discount_pct`; REMOVE_EXPIRED → `WASTE_EXPIRED`)
  · `POST /api/tenant/recommendations/{id}/discard` `{note?}`. Job diario mide `outcome` de DISCOUNT aceptados a los 7 días
  (`{unitsBefore7d,unitsAfter7d,lift,lotUnitsSold,lotUnitsRemaining}`) y lo envía como `feedback` en el próximo análisis.

### 6.6 Módulo C — Consola de dueños (`com.gondolia.platform`) · rol PLATFORM_OWNER
- `GET /api/platform/metrics` →
  ```json
  {"tenants":{"total":18,"active":14,"disabled":2,"cancelled":2,"newLast30d":3,"cancelledLast30d":1},
   "branches":{"total":27,"active":25,"avgPerActiveTenant":1.6,"multiBranchTenants":5},
   "tenantsByPlan":{"FREEMIUM":5,"BASICO":8,"PROFESIONAL":5},"tenantsByBusinessType":{"ALMACEN":6,"...":0},
   "engagement":{"activeTenants7d":11,"activeTenants30d":13,"activeUsers7d":30},
   "users":{"total":60,"byRole":{"TENANT_BOSS":18,"TENANT_ADMIN":20,"TENANT_EMPLOYEE":22}},
   "revenue":{"estimatedMrr":575000,"currency":"ARS","freemiumToPaidConversionPct":37.5},
   "growth":[{"month":"2025-10","newTenants":2,"cancelled":0,"activeAtEndOfMonth":7}],
   "support":{"openTickets":4,"unassignedTickets":1,"avgFirstResponseMinutes":12.4,"resolvedLast30d":22,"avgRating":4.6},
   "recalls":{"activeRecalls":1,"affectedTenantsTotal":2}}
  ```
  (MRR = Σ precio del plan × sucursales activas de tenants ACTIVE, §4.1; "activo 7d" = algún usuario con login en 7 días)
- Tenants: `GET /api/platform/tenants?q=&status=&plan=&businessType=&page=` → `PageResponse<TenantSummary>`
  `TenantSummary = {id,name,businessType,plan,status,city,province,contactName,contactEmail,contactPhone,userCount,branchCount,activeBranchCount,monthlyFee,lastActivityAt,createdAt,statusChangedAt,statusReason}`
  · `GET /{id}` → `TenantSummary + {legalName,taxId,address,notes,maxBranches,usersByRole:{},branches:[{id,name,city,active,createdAt}],events:[{type,fromValue,toValue,reason,createdAt}]}`
    (de las sucursales solo datos administrativos: nombre, ciudad, estado — nunca stock ni ventas)
  · `POST /api/platform/tenants` `{name,legalName,taxId,businessType,plan,contactName,contactEmail,contactPhone,address,city,province,notes,firstBranch:{name,address,city,province},boss:{fullName,email,password},admin:{...},employee:{...}}`
    (crea tenant + settings + primera sucursal + 3 usuarios; el empleado queda asignado a la primera sucursal)
  · `PUT /{id}` (datos y plan; plan distinto → evento PLAN_CHANGED; bajar a un plan con menos sucursales que las activas → 409 `BRANCH_LIMIT_REACHED`)
  · `POST /{id}/disable {reason}` · `POST /{id}/enable` · `POST /{id}/cancel {reason}` · `POST /{id}/reactivate`
    (eventos + `TenantStatusChangedEvent` + `SessionTerminationService.forceLogoutTenant` al bloquear)
  · `DELETE /{id}?confirmName=` (solo CANCELLED; nombre exacto) · `POST /{id}/reset-admin-password` → `{email,temporaryPassword}`
- **Módulos por cliente** (§14): catálogo, matriz tenants × módulos, activar/desactivar por tenant, presets al crear,
  `modules` en TenantSummary, filtro `?module=`, adopción y MRR con adicionales en `/metrics`.
- Equipo: `GET /api/platform/users` → `[{id,fullName,email,role,active,lastLoginAt,createdAt}]` (roles de plataforma) ·
  `POST {fullName,email,password,role}` · `PUT /{id} {fullName,active}` · `POST /{id}/reset-password {newPassword}` (no se puede desactivar a sí mismo).

### 6.7 Módulo D — Avisos y recalls (`com.gondolia.announcements`)
- Dueños (PLATFORM_OWNER): `GET /api/platform/announcements?kind=&status=&page=` · `GET /{id}` (con estadísticas agregadas:
  `recipientsCount,readCount,affectedTenantsCount,matchesOpen,matchesAcknowledged,matchesResolved`) ·
  `POST /api/platform/announcements` `{kind,severity,title,body,targetBusinessTypes?:[],recall?:{productName,brand,barcode,lotNumbers:[],allLots,expiryFrom?,expiryTo?,reason,instructions}}`
  → publica: notifica a todos los usuarios de tenants ACTIVE (`ANNOUNCEMENT`, link `/app/notices`) y, si es RECALL,
  ejecuta `RecallMatchingService.matchAnnouncement`. · `POST /{id}/archive` ·
  `POST /api/platform/announcements/recall-preview` `{barcode,lotNumbers,allLots,expiryFrom,expiryTo}` → `{affectedTenantsCount,affectedLotsCount}`.
- Tenant (TENANT_ANY): `GET /api/tenant/announcements?page=` → `PageResponse<{id,kind,severity,title,body,publishedAt,read,affectsMe,recall:{productName,brand,barcode,lotNumbers,allLots,expiryFrom,expiryTo,reason,instructions}|null}>`
  · `POST /api/tenant/announcements/{id}/read` · `GET /api/tenant/announcements/unread-count` → `{count}`
- Matches (tenant, scope de sucursales accesibles): `GET /api/tenant/recall-matches?status=ACTIVE|OPEN|ACKNOWLEDGED|RESOLVED|ALL` →
  `[{id,announcementId,branchId,branchName,title,severity,reason,instructions,productId,productName,barcode,lotId,lotNumber,expiryDate,quantityAtMatch,currentQuantity,status,matchedAt,acknowledgedAt,acknowledgedByName,resolvedAt,resolvedByName,resolution,resolutionNote}]`
  · `POST /{id}/acknowledge` (TENANT_ANY) · `POST /{id}/resolve {resolution,note}` (TENANT_INVENTORY; retira el remanente con `RECALL_REMOVAL` o `ADJUSTMENT_OUT`).

### 6.8 Módulo E — Soporte (`com.gondolia.support`)
`TicketSummary = {id,tenantId,tenantName,subject,category,priority,status,channel,createdById,createdByName,createdByRole,assignedToId,assignedToName,lastMessageAt,lastMessagePreview,unreadCount,createdAt,resolvedAt,rating}`
`MessageDto = {id,ticketId,senderId,senderName,senderType,body,attachment:{id,url:"/api/attachments/{id}",contentType,originalName,sizeBytes}|null,createdAt}`
`TicketDetail = TicketSummary + {messages:[MessageDto],ratingComment,firstResponseAt}`
- Cliente (TENANT_ANY, solo tickets de su tenant): `GET /api/tenant/support/tickets?status=` → `[TicketSummary]` ·
  `POST /api/tenant/support/tickets` `{subject,category,priority,channel,message}` → `TicketDetail` ·
  `GET /{id}` · `POST /{id}/messages` multipart (`body` texto opcional, `file` imagen opcional; al menos uno) → `MessageDto` ·
  `POST /{id}/read` · `POST /{id}/close` · `POST /{id}/rate {rating,comment}`
- Agente (SUPPORT_AGENT): `GET /api/support/tickets?status=&assigned=all|me|unassigned&q=&page=` → `PageResponse<TicketSummary>` ·
  `GET /{id}` · `POST /{id}/assign {agentId?}` · `POST /{id}/status {status}` · `PATCH /{id} {priority?,category?}` ·
  `POST /{id}/messages` multipart · `POST /{id}/read` · `GET /api/support/stats` → `{open,inProgress,waitingCustomer,unassigned,mine,resolvedToday,avgFirstResponseMinutes}` ·
  `GET /api/support/agents` → `[{id,fullName,online}]`
- Mensaje de cliente → notificación `TICKET_MESSAGE` al agente asignado (o a todos si no hay) + `/topic/support/queue`.
  Mensaje de agente → notificación al creador del ticket; primer mensaje de agente setea `first_response_at` y status `IN_PROGRESS`.

### 6.9 Módulo F — Administración del comercio (`com.gondolia.tenantadmin`) · TENANT_ADMIN
- Usuarios: `GET /api/tenant/users` → `[{id,fullName,email,role,active,lastLoginAt,createdAt,branches:[{id,name}]}]` ·
  `POST {fullName,email,password,role,branchIds:[]}` (EMPLOYEE y CASHIER requieren ≥1 sucursal; para ADMIN/BOSS se ignora) ·
  `PUT /{id} {fullName,role,active,branchIds}` (no puede desactivarse/degradarse a sí mismo; siempre ≥1 ADMIN activo) ·
  `POST /{id}/reset-password {newPassword}` (incrementa token_version y fuerza logout)
- Sucursales: `GET /api/tenant/branches` (TENANT_ANY → solo las accesibles; ADMIN con `?includeInactive=true` ve todas) →
  `[{id,name,code,address,city,province,phone,active,employeeCount,createdAt}]` · `POST {name,code,address,city,province,phone}`
  (límite por plan → 409 `BRANCH_LIMIT_REACHED`) · `PUT /{id}` (mismo body) · `POST /{id}/deactivate` (409 `BRANCH_HAS_STOCK` / última activa) ·
  `POST /{id}/activate` (respeta límite) · `GET /api/tenant/branches/limits` → `{plan,maxBranches,activeBranches}`
- `GET /api/tenant/settings` → `{currency,stockRotation,expiryWarningDays,expiryCriticalDays,defaultLeadTimeDays,targetCoverageDays,serviceLevel,maxDiscountPct}` · `PUT` mismo body (validar rangos)
- `GET /api/tenant/account` → `{name,legalName,taxId,businessType,plan,status,contactName,contactEmail,contactPhone,address,city,province,createdAt}` (TENANT_DASHBOARD)

---

## 7. WebSocket (STOMP)

- Endpoint **`/ws`** (WebSocket nativo, sin SockJS). Cliente `@stomp/stompjs`: `brokerURL = (https? 'wss':'ws') + '://' + location.host + '/ws'`,
  header CONNECT `Authorization: Bearer <jwt>`. Broker simple en memoria, prefijos app `/app`, broker `/topic`, `/queue`, user `/user`.
  El `Principal` de la sesión es `AuthUser` (name = id de usuario). CONNECT inválido → error STOMP y cierre.
- Destinos y autorización de SUBSCRIBE:
| Destino | Quién puede suscribirse | Payload |
|---|---|---|
| `/user/queue/notifications` | cualquiera (propio) | `NotificationDto` |
| `/user/queue/security-alerts` | cualquiera (propio) | `RecallAlertMessage` |
| `/user/queue/session` | cualquiera (propio) | `{type:"FORCE_LOGOUT",code,message}` |
| `/topic/support/presence` | cualquier autenticado | `{agentsOnline}` |
| `/topic/support/queue` | SUPPORT_AGENT | `{event:"TICKET_CREATED"|"TICKET_UPDATED",ticket:TicketSummary}` |
| `/topic/tickets/{ticketId}` | SUPPORT_AGENT o usuario del tenant dueño del ticket | `{event:"MESSAGE",message:MessageDto}` · `{event:"TICKET_UPDATED",ticket:TicketSummary}` · `{event:"TYPING",userId,name,senderType,typing}` · `{event:"READ",senderType,readAt}` |
| cualquier otro | nadie | — |
- Envío cliente→servidor: `/app/tickets/{ticketId}/typing` `{typing:true}` (misma autorización; el módulo E implementa el `@MessageMapping`).
- `RecallAlertMessage = {matchId,announcementId,branchId,branchName,title,severity,reason,instructions,productId,productName,barcode,lotId,lotNumber,expiryDate,quantity,matchedAt}`

---

## 8. Servicio de IA (`ai-service`, FastAPI, puerto 8000, solo red interna)

Python 3.12. Librerías: fastapi, uvicorn, pydantic v2, numpy, pandas, statsmodels, scikit-learn, pytesseract
(+ paquetes `tesseract-ocr tesseract-ocr-spa tesseract-ocr-eng`), opencv-python-headless, pillow, zxing-cpp,
python-multipart, python-dateutil; tests con pytest + httpx. JSON en **camelCase** (usar alias de pydantic).
Stateless: todo el contexto llega en el request.

### 8.1 `GET /health` → `{"status":"ok","version":"1.0.0","modelVersion":"gondolia-ai-1.0","tesseract":true}`

### 8.2 `POST /v1/analyze` — reconocimiento de patrones de stock y recomendaciones
Request:
```json
{"tenantId":2,"branchId":3,"branchName":"Sucursal Centro","asOfDate":"2026-09-17",
 "settings":{"stockRotation":"FIFO","expiryWarningDays":15,"expiryCriticalDays":5,"leadTimeDays":3,"targetCoverageDays":14,"serviceLevel":0.95,"maxDiscountPct":40,"horizonDays":14},
 "products":[{"productId":10,"name":"Yogur bebible frutilla 1L","category":"Lácteos","salePrice":2100.0,"costPrice":1400.0,
   "minStock":10,"sellableStock":25,"perishable":true,"leadTimeDays":3,"createdAt":"2026-03-01",
   "dailySales":[{"date":"2026-09-16","quantity":4,"discountPct":0}],
   "lots":[{"lotId":55,"lotNumber":"L2409A","expiryDate":"2026-09-25","receivedAt":"2026-09-02T13:10:00Z","quantity":12,"discountPct":null,"status":"ACTIVE"}]}],
 "feedback":[{"recommendationId":90,"type":"DISCOUNT","productId":10,"category":"Lácteos","status":"ACCEPTED","discountPct":20,
   "outcome":{"unitsBefore7d":10,"unitsAfter7d":18,"lift":1.8}}]}
```
(`dailySales` y `lots` son **de esa sucursal**; `sellableStock` también. `dailySales` puede venir esparcido: días faltantes = 0
desde `createdAt`/primera venta hasta `asOfDate`. La simulación de consumo por lote usa `settings.stockRotation`: FIFO ordena
por `receivedAt`, FEFO por `expiryDate`; los lotes vencidos no se consumen.)

Response:
```json
{"modelVersion":"gondolia-ai-1.0","generatedAt":"2026-09-17T06:00:00Z",
 "products":[{"productId":10,"pattern":"ESTACIONAL_SEMANAL","patternDescription":"Vende 45% más los fines de semana",
   "abcClass":"A","xyzClass":"Y","avgDailySales":3.2,"trendPct":12.5,"weekdayProfile":[0.8,0.9,0.9,1.0,1.1,1.4,1.2],
   "forecastMethod":"HOLT_WINTERS","forecast":[{"date":"2026-09-18","yhat":3.4,"lo":1.2,"hi":5.6}],
   "daysOfCover":7.8,"predictedStockoutDate":"2026-09-25","reorderPoint":14,"safetyStock":5,"suggestedOrderQty":30,
   "anomalies":[{"date":"2026-09-01","quantity":20,"expected":3.1,"score":4.2,"kind":"SPIKE"}],
   "lotRisks":[{"lotId":55,"daysToExpiry":8,"quantity":12,"expectedSalesBeforeExpiry":6.1,"unitsAtRisk":6,"riskLevel":"HIGH",
     "recommendedDiscountPct":20,"expectedUnitsSoldWithDiscount":11.2}]}],
 "recommendations":[{"type":"REORDER","productId":10,"lotId":null,"priority":85,"confidence":0.78,
   "title":"Reponer Yogur bebible frutilla 1L",
   "explanation":"Al ritmo actual (3,2 u/día, tendencia +12%) el stock alcanza para 7,8 días y el proveedor tarda 3 días. Sugerimos pedir 30 u. antes del 21/09.",
   "suggestedQuantity":30,"suggestedDiscountPct":null,"suggestedDate":"2026-09-21","expectedImpact":6300.0,"dedupeKey":"REORDER:10"}],
 "summary":{"productsAnalyzed":60,"atRiskValue":12345.0,"predictedStockouts7d":4,"anomalies30d":3,
   "patternCounts":{"ESTACIONAL_SEMANAL":10},"elasticityByCategory":{"Lácteos":2.4},"modelNotes":["..."]}}
```
Algoritmos mínimos: relleno de serie diaria; pronóstico Holt-Winters (estacionalidad 7) si ≥ 28 días con ventas,
Croston/SBA si intermitente, media móvil ponderada si hay pocos datos; perfil semanal; tendencia (regresión lineal
últimos 56 días vs promedio); clasificación de patrón por reglas + clustering KMeans sobre features (media, CV,
tendencia, ratio finde, % días sin venta); ABC por facturación (80/15/5) y XYZ por coeficiente de variación
(<0,5 / <1 / ≥1); anomalías por z-score robusto (MAD) sobre residuos del pronóstico in-sample e IsolationForest
como segunda opinión; punto de pedido = demanda en lead time + stock de seguridad (z(serviceLevel)·σ·√LT);
cantidad sugerida = cubrir `targetCoverageDays + leadTimeDays` − stock; riesgo por lote simulando el consumo
pronosticado en el orden de rotación (lotes con `discountPct` primero, luego FIFO/FEFO, §4.2) — con FIFO detecta lotes nuevos
que vencen antes que los viejos y quedarían sin vender; descuento mínimo (escalones 10/15/20/25/30/40, tope `maxDiscountPct`) para vender el lote antes del
vencimiento usando elasticidad por categoría (default 2,0 = +2% ventas por 1% de descuento) ajustada con `feedback`
(promedio ponderado de `(lift-1)/(discountPct/100)`). Recomendaciones: `REORDER` (stock ≤ punto de pedido o quiebre
previsto antes de LT+2 días), `DISCOUNT` (lote con unitsAtRisk>0 y daysToExpiry>0), `REMOVE_EXPIRED` (lote vencido con
stock), `REVIEW_ANOMALY` (anomalía en últimos 7 días), `REDUCE_PURCHASE` (perecedero con daysOfCover > 3×target).
`dedupeKey`: `REORDER:{productId}`, `DISCOUNT:{lotId}`, `REMOVE_EXPIRED:{lotId}`, `REVIEW_ANOMALY:{productId}:{date}`,
`REDUCE_PURCHASE:{productId}`. Explicaciones en español, claras, con números formateados es-AR.

### 8.3 `POST /v1/ocr` (multipart `file`) — lectura de etiqueta
```json
{"text":"texto completo","lines":["LOTE: L2409A","VTO 25/09/2026"],
 "expiryDates":[{"value":"2026-09-25","raw":"VTO 25/09/2026","confidence":0.92}],
 "lotNumbers":[{"value":"L2409A","raw":"LOTE: L2409A","confidence":0.85}],
 "manufactureDates":[{"value":"2026-03-25","raw":"ELAB 25/03/26","confidence":0.8}],
 "barcodes":[{"value":"7791234500017","format":"EAN_13"}],
 "productNameCandidates":["SOPA DE TOMATE LA HUERTA"],"processingMs":840}
```
Preprocesado OpenCV (escala de grises, upscaling, CLAHE, umbral adaptativo, variantes rotadas 0/90/180/270 y se queda
con la de mayor confianza), Tesseract `spa+eng`. Parseo robusto de fechas: `dd/mm/aaaa`, `dd/mm/aa`, `dd-mm-aa`,
`dd.mm.aaaa`, `mm/aaaa` (→ último día del mes), `aaaa-mm-dd`, meses en texto (`25 SEP 2026`, `SEPT/26`), prefijos
`VTO`, `VENC`, `VENCE`, `EXP`, `CAD`, `BB`, `CONSUMIR ANTES DE`; manufactura `ELAB`, `FAB`, `F.ELAB`. Si hay varias
fechas sin prefijo, la más lejana en el futuro es candidata a vencimiento. Lotes: prefijos `LOTE`, `LOT`, `L:`, `L.`,
`LT`, `BATCH`, `PARTIDA`; también códigos alfanuméricos tipo `L2409A`. Confianzas 0..1, ordenados desc.
### 8.4 `POST /v1/barcode` (multipart `file`) → `{"barcodes":[{"value":"...","format":"EAN_13"}]}` (zxing-cpp, con variantes de preprocesado)

---

## 9. Frontend (React)

### 9.1 Stack
React 18.3 + TypeScript 5 (strict) + Vite 5 + Tailwind CSS 3.4 + react-router-dom 6 + @tanstack/react-query 5 +
axios + @stomp/stompjs 7 + recharts 2 + @zxing/browser & @zxing/library + html-to-image + lucide-react +
date-fns 4 (locale `es`) + sonner (toasts) + clsx + tailwind-merge + @fontsource-variable/inter.
**Todas** las dependencias se declaran en la fundación. Build: `npm run build` (= `tsc -b && vite build`).
Dev: `npm run dev` en :5173 con proxy `/api` → `http://localhost:8080` y `/ws` (ws: true).

### 9.2 Estructura
```
frontend/src/
├── main.tsx · App.tsx (router; SOLO fundación) · index.css
├── config/navigation.ts            # menú por rol
├── api/client.ts                   # axios `api` (agrega Authorization y X-Branch-Id), helpers, ApiError, uploadFile()
├── api/types.ts                    # Role, MeDto, BranchRef, PageResponse<T>, ErrorResponse, NotificationDto, enums compartidos
├── auth/ AuthContext.tsx (useAuth), RequireAuth.tsx, RequireRole.tsx, tokenStorage.ts, roleHome.ts
├── branches/ BranchContext.tsx (useBranch: branches, selectedBranchId | 'all', setBranch, currentBranch, isAll, canSelectAll),
│             BranchSelector.tsx (desplegable del topbar), BranchPicker.tsx (selector inline obligatorio para escrituras cuando isAll)
├── realtime/ StompProvider.tsx, useStompSubscription.ts, useStompPublish.ts, useSessionEvents.ts
├── components/ui/                  # Button, Card, Badge, Input, Select, Textarea, Field, Modal, ConfirmDialog, Table,
│                                   # Spinner, EmptyState, PageHeader, StatCard, Tabs, Pagination, SearchInput, Toggle, AuthImage + index.ts
├── components/layout/ AppShell.tsx, Sidebar.tsx, Topbar.tsx, NotificationBell.tsx, UserMenu.tsx, Logo.tsx
├── lib/ format.ts (formatMoney ARS "$ 8.450.000", formatDate "25/09/2026", formatLongDate, formatRelative, formatNumber), cn.ts, useDebounce.ts
├── pages/ LoginPage.tsx, ProfilePage.tsx, NotificationsPage.tsx, NotFoundPage.tsx, ForbiddenPage.tsx
└── features/
    ├── catalog/pages/       InventoryPage, ProductFormPage, ProductDetailPage, IntakePage, CategoriesPage, SuppliersPage   (A1)
    ├── movements/pages/     SalesPage, MovementsPage, ExpirationsPage, TransfersPage                                      (A2)
    ├── analytics/pages/     DashboardPage, StatisticsPage, InsightsPage, AlertsPage                                        (B)
    ├── platform/pages/      OwnerMetricsPage, TenantsPage, TenantFormPage, TenantDetailPage, PlatformTeamPage              (C)
    ├── announcements/pages/ OwnerAnnouncementsPage, AnnouncementFormPage, NoticesPage, RecallsPage                         (D)
    ├── announcements/components/SecurityAlertHost.tsx   (montado globalmente para usuarios de tenant)                      (D)
    ├── support/pages/       SupportConsolePage, TenantSupportPage                                                          (E)
    ├── support/components/SupportWidget.tsx             (botón flotante de chat en páginas de tenant)                      (E)
    └── tenantadmin/pages/   UsersPage, SettingsPage, BranchesPage                                                          (F)
```
Cada página es `export default function NombrePage()`. App.tsx las carga con `React.lazy`. La fundación crea
todos estos archivos como placeholders ("Sección en construcción"); cada módulo reemplaza los suyos y agrega
archivos nuevos solo dentro de `features/<modulo>/` (`api.ts`, `types.ts`, `components/`, `hooks/`).

### 9.3 Rutas
| Ruta | Página | Roles |
|---|---|---|
| `/login` | LoginPage | público |
| `/` | redirección por rol: OWNER→`/owner`, SUPPORT→`/support`, BOSS/ADMIN→`/app/dashboard`, EMPLOYEE→`/app/intake`, CASHIER→`/app/pos` | auth |
| `/profile`, `/notifications` | ProfilePage, NotificationsPage | todos |
| `/owner` · `/owner/tenants` · `/owner/tenants/new` · `/owner/tenants/:id` · `/owner/tenants/:id/edit` · `/owner/announcements` · `/owner/announcements/new` · `/owner/team` | OwnerMetricsPage · TenantsPage · TenantFormPage · TenantDetailPage · TenantFormPage · OwnerAnnouncementsPage · AnnouncementFormPage · PlatformTeamPage | PLATFORM_OWNER |
| `/support` · `/support/tickets/:id` | SupportConsolePage | SUPPORT_AGENT |
| `/app/dashboard` · `/app/statistics` · `/app/insights` · `/app/alerts` | Dashboard · Statistics · Insights · Alerts | BOSS, ADMIN |
| `/app/inventory` · `/app/products/new` · `/app/products/:id` · `/app/products/:id/edit` · `/app/intake` · `/app/expirations` | Inventory · ProductForm · ProductDetail · ProductForm · Intake · Expirations | ADMIN, EMPLOYEE |
| `/app/categories` · `/app/suppliers` · `/app/sales` · `/app/movements` · `/app/transfers` · `/app/users` · `/app/branches` · `/app/settings` | Categories · Suppliers · Sales · Movements · Transfers · Users · Branches · Settings | ADMIN |
| `/app/notices` · `/app/recalls` · `/app/support` · `/app/support/:id` | Notices · Recalls · TenantSupport | BOSS, ADMIN, EMPLOYEE, CASHIER |
| `/app/pos` · `/app/pos/sessions` | PosTerminalPage · PosSessionsPage (§15) | ADMIN, EMPLOYEE, CASHIER + módulo POS_GONDOLIA |
| `/app/pos/registers` | PosRegistersPage (§15) | ADMIN + POS_GONDOLIA |
| `/app/pos/sales/:id/ticket` | PosTicketPage (**fuera del AppShell**, para imprimir) | ADMIN, EMPLOYEE, CASHIER + POS_GONDOLIA |
| `/app/integrations` | IntegrationsPage (A2: POS propio) | ADMIN + POS_INTEGRATION |
| `/app/imports` · `/app/imports/:id` | ImportsPage · ImportWizardPage (§16) | ADMIN |
| `/owner/modules` | ModulesMatrixPage (§14) | PLATFORM_OWNER |
`/app/transfers` requiere además MULTI_BRANCH. Rutas con módulo deshabilitado → `ModuleDisabledPage` (guard `RequireModule`).

### 9.4 Navegación (sidebar, íconos lucide)
Cada ítem puede declarar `module`; si el tenant no lo tiene habilitado, el ítem no se muestra.
- OWNER: Métricas (BarChart3) · Clientes (Store) · Módulos por cliente (Blocks) · Avisos y recalls (Megaphone) · Equipo GondolIA (Users)
- SUPPORT: Bandeja de soporte (Headset)
- BOSS: Inicio (Home) · Estadísticas (BarChart3) · Inteligencia IA (Sparkles) · Alertas (Bell) ‖ Avisos (Megaphone) · Seguridad alimentaria (ShieldAlert) · Soporte (LifeBuoy)
- ADMIN: Inicio · Punto de venta (MonitorSmartphone)[POS_GONDOLIA] · Inventario (Package) · Carga de mercadería (ScanBarcode) · Importar Excel/CSV (FileSpreadsheet) · Vencimientos (CalendarClock) · Ventas (ShoppingCart) · Transferencias (ArrowLeftRight)[MULTI_BRANCH] · Movimientos (History) · Estadísticas · Inteligencia IA · Alertas · Proveedores (Truck) · Categorías (Tags) ‖ Sucursales (Building2) · Usuarios (UserCog) · Cajas y turnos (Calculator)[POS_GONDOLIA] · Integración POS (Plug)[POS_INTEGRATION] · Configuración (Settings) ‖ Avisos · Seguridad alimentaria · Soporte
- EMPLOYEE: Punto de venta[POS_GONDOLIA] · Mis turnos de caja (Calculator)[POS_GONDOLIA] · Carga de mercadería · Inventario · Vencimientos ‖ Avisos · Seguridad alimentaria · Soporte
- CASHIER: Punto de venta[POS_GONDOLIA] · Mis turnos de caja[POS_GONDOLIA] ‖ Avisos · Seguridad alimentaria · Soporte

### 9.5 Diseño — "Góndola UI"
**Fuente de verdad visual: `docs/design-system.md` y el prototipo `design/gondola-ui/`** (hechos con el skill
web-artifacts-builder: React + shadcn/ui). Resumen: componentes **shadcn/ui** (Radix) en `components/ui/`; tokens CSS
claro/oscuro (fondo `#F3F5F1`, tinta `#15231A`, verde góndola `#1E6A42`, rail `#0F3A25`, acento amarillo etiqueta de precio
`#F4C542` usado con moderación, semánticos ok/warn/crit/info); tipografías **Bricolage Grotesque** (display), **Figtree** (UI) y
**JetBrains Mono** (códigos, lotes, vencimientos, ticket) empaquetadas con `@fontsource-variable` (sin CDN) — **no usar Inter**;
radios por rol (controles 8px, paneles 12px, diálogos 16px, tablas sin radio, píldoras solo para estados); bordes antes que sombras.
Componentes firma compartidos en `components/gondola/`: `PriceTag`, `ExpiryChip`, `LotRankChip`, `StockStatusPill`,
`SeverityRow`, `Ticket80mm`, `BarcodeDigits`. Evitar el look genérico de IA: sin degradados violeta, sin todo centrado, sin emoji
como marcadores, sin radios uniformes, sin barras de acento en tarjetas redondeadas.
**Mobile first** para empleados: sidebar como drawer < `lg`, tablas → tarjetas en pantallas chicas, botones grandes
en la carga con cámara. Estados vacíos, loaders y errores con mensajes en español en todas las pantallas.
Si `!window.isSecureContext` y no es localhost, mostrar aviso "Para usar la cámara abrí https://…".

### 9.6 Comportamiento transversal (fundación)
- Token en `localStorage` (`gondolia.token`). Interceptor: 401 → logout → `/login?motivo=sesion`; 403 con code
  `TENANT_DISABLED|TENANT_CANCELLED|USER_DISABLED` → logout con mensaje. `mustChangePassword` → forzar `/profile`.
- `StompProvider` conecta al autenticarse; `useStompSubscription(dest, handler, enabled?)` re-suscribe al reconectar;
  `useSessionEvents` escucha `/user/queue/session` → logout + toast.
- `NotificationBell`: contador (`/api/notifications/unread-count`), dropdown con últimas 10, push en vivo
  (`/user/queue/notifications`) con toast; severidad CRITICAL con estilo rojo; click → navega a `link`.
- `AppShell` monta `<SecurityAlertHost/>` y `<SupportWidget/>` solo para roles de tenant.
- **Sucursal**: `BranchProvider` (dentro de AuthProvider) toma `me.branches`; la selección se guarda en `localStorage`
  (`gondolia.branch.<userId>`), default: ADMIN/BOSS con >1 sucursal → `'all'`; con una sola (o EMPLOYEE con una) → esa.
  "Todas las sucursales" solo si hay >1 accesible. El interceptor de axios agrega `X-Branch-Id` (id o `all`).
  Al cambiar de sucursal se invalidan todas las queries de React Query (`queryClient.invalidateQueries()`) y las query keys
  de datos por sucursal incluyen `selectedBranchId`. `BranchSelector` en el topbar (oculto para roles de plataforma; solo texto
  si hay una sola sucursal). `BranchPicker` se usa en formularios de escritura cuando `isAll` (carga, ventas, ajustes).
  En tablas/listados con `isAll` se muestra la columna "Sucursal".
- React Query: `staleTime` 30s, invalidación tras mutaciones; `api/client.ts` exporta `api` (axios) y `getErrorMessage(err)`.

---

## 10. Infraestructura

- `docker-compose.yml` (name `gondolia`): `db` (postgres:16-alpine, healthcheck `pg_isready`, volumen `pgdata`),
  `ai` (build `./ai-service`, healthcheck `/health`), `backend` (build `./backend`, depends_on db+ai healthy,
  volumen `uploads:/data/uploads`, healthcheck `/actuator/health`), `web` (build `./frontend`, depends_on backend
  healthy, puertos `${HTTP_PORT}:80` y `${HTTPS_PORT}:443`, bind `./certs:/etc/nginx/certs`). `restart: unless-stopped`. `TZ` en todos.
- `frontend/Dockerfile`: build Node 22 → nginx 1.27-alpine + openssl; `docker-entrypoint.d` script que genera una
  **CA local** (`certs/gondolia-ca.crt/.key`, 10 años) y un certificado de servidor firmado por la CA con SAN
  `localhost`, `127.0.0.1`, `LAN_IPS`, `CERT_HOSTNAMES` (se regenera si cambian los SAN; validez 825 días).
  nginx: SPA fallback, gzip, cache de assets, `/api/` → `http://backend:8080` (`client_max_body_size 15m`,
  `proxy_read_timeout 180s`), `/ws` con upgrade, `/gondolia-ca.crt` descargable, en :80 redirigir a HTTPS si el host
  no es localhost/127.0.0.1, header `Permissions-Policy: camera=(self)`.
- `backend/Dockerfile`: maven:3.9-eclipse-temurin-21 (cache de dependencias) → eclipse-temurin:21-jre-alpine, usuario no root.
- `ai-service/Dockerfile`: python:3.12-slim + tesseract (spa, eng) + requirements; usuario no root.
- `start.sh` (bash, LF, funciona en Git Bash de Windows, Linux y macOS):
  `./start.sh` | `start` (verifica Docker, crea `.env` con JWT aleatorio, detecta IPs LAN, `docker compose up -d --build`,
  espera salud, imprime URLs y credenciales) · `stop` (apaga, conserva datos) · `stop --borrar` / `stop --purge`
  (apaga y borra contenedores, volúmenes, imágenes del proyecto y certificados; pide confirmación salvo `-y`) ·
  `restart` · `logs [servicio]` · `status` · `reset` (borra la base y vuelve a sembrar demo) · `help`.
- `.gitattributes` fuerza LF en `*.sh`, `Dockerfile`, `*.conf`.

---

## 11. Datos demo (módulo G) y credenciales
Contraseñas: plataforma `Gondolia2026!`, comercios `Demo2026!`.
- Dueños: `dueno@gondolia.app`, `socia@gondolia.app` · Soporte: `soporte@gondolia.app`, `soporte2@gondolia.app`
- Tenants con datos ricos (180 días de historia de ventas simulada día a día **por sucursal** con la rotación del
  comercio, reposiciones que generan varios lotes con distintas fechas por producto, transferencias, mermas):
  - **Almacén Don Pepe** (ALMACEN, BASICO, CABA, 1 sucursal "Sucursal Principal"): `jefe@donpepe.com`, `admin@donpepe.com`, `empleado@donpepe.com`
  - **Dietética Vida Sana** (DIETETICA, PROFESIONAL, Córdoba, 2 sucursales "Nueva Córdoba" y "Cerro de las Rosas", FEFO):
    `jefe@vidasana.com`, `admin@vidasana.com`, `empleado@vidasana.com` (solo Nueva Córdoba)
  - **Minimercado El Sol** (MINIMERCADO, PROFESIONAL, Rosario, 3 sucursales "Centro", "Fisherton" y "Echesortu", FIFO):
    `jefe@elsol.com`, `admin@elsol.com`, `empleado@elsol.com` (Centro y Fisherton), `empleado.echesortu@elsol.com` (solo Echesortu)
- **Módulos y cajeros** (§14, §15): Don Pepe {POS_GONDOLIA} con `cajero@donpepe.com`; Vida Sana {POS_INTEGRATION, MULTI_BRANCH}
  (ventas históricas con source POS externo, API key por sucursal); El Sol {los 3 módulos} con `cajero@elsol.com` (Centro) y
  `cajero.fisherton@elsol.com` (Fisherton), POS GondolIA en Centro y Fisherton y POS externo en Echesortu. Cajas "Caja 1"/"Caja 2"
  por sucursal con POS, turnos históricos cerrados con arqueo (algunos con diferencia), ventas con pagos mixtos, algunas anuladas,
  y un turno abierto hoy. Una importación histórica APLICADA en El Sol (migración inicial, §16).
- **Kiosco La Esquina** (KIOSCO, FREEMIUM, DISABLED, {POS_GONDOLIA}) `admin@laesquina.com` (para demostrar el bloqueo) + ~12 tenants
  livianos distribuidos en 12 meses (varios planes/estados, 2 CANCELLED) para métricas de crecimiento.
- Escenario recall listo para demo en vivo: "Sopa de tomate en lata La Huerta 340 g", EAN `7791234500017`, lote
  `L2409A` presente en stock de Don Pepe y de El Sol **solo en la sucursal Fisherton** (no en Vida Sana). Marcas ficticias en todos los productos.
- Escenario varios lotes: en cada sucursal, varios productos con 2–4 lotes vivos con distintos `received_at` y vencimientos
  (incluido al menos un caso donde un lote más nuevo vence antes que uno más viejo, para mostrar el aviso de FIFO).
- Patrones sembrados: finde fuerte (bebidas, snacks), estable (leche, pan), intermitente (especias), creciente,
  decreciente, sin movimiento, picos anómalos, lotes por vencer con sobrestock, productos bajo mínimo, algunos
  vencidos pendientes; tickets de soporte con conversación (incluida una imagen), avisos generales y un recall histórico resuelto.

---

## 12. Propiedad de archivos (desarrollo en paralelo)
| Módulo | Backend | Frontend | Migraciones |
|---|---|---|---|
| Fundación (+ extensión núcleo) | `pom.xml`, `application*.yml`, `GondoliaApplication`, `config`, `security` (incl. `BranchAccessService`), `auth`, `common`, `domain` (incl. `domain.pos`, `domain.imports`), `realtime`, `notification`, `stock`, `recall`, `storage`, `ai`, `bootstrap`, `modules` | todo salvo `features/*/pages/*` y los 2 componentes globales (incl. `branches/`, `modules/`, `components/ui` shadcn, `components/gondola`, `components/scanner`) | V1, V2 |
| A1 Catálogo | `catalog` | `features/catalog` | V100–V149 |
| A2 Movimientos | `movements` | `features/movements` | V150–V199 |
| B Analítica/IA | `analytics`, `alerts`, `insights` | `features/analytics` | V200–V249 |
| C Plataforma | `platform` | `features/platform` | V250–V299 |
| D Avisos/recalls | `announcements` | `features/announcements` | V300–V349 |
| E Soporte | `support` | `features/support` | V350–V399 |
| F Admin comercio | `tenantadmin` | `features/tenantadmin` | V400–V449 |
| G Seed demo | `seed` | — | V450–V499 |
| H POS GondolIA | `pos` | `features/pos` | V500–V549 |
| I Importación masiva | `imports` | `features/imports` | V550–V599 |
| Infra | Dockerfiles, compose, start.sh, nginx, README | `frontend/Dockerfile`, `frontend/nginx/` | — |

No modificar archivos de otro módulo. Si el núcleo tiene un bug o falta algo imprescindible, hacé el cambio mínimo,
compatible hacia atrás, y reportalo explícitamente. Nuevas dependencias: evitarlas; si son imprescindibles, reportarlas.

## 13. Definición de terminado (cada módulo)
1. `cd backend && mvn -q -DskipTests package` compila sin errores; tests unitarios propios pasan (`mvn -q test`).
2. `cd frontend && npm run build` sin errores de TypeScript.
3. Probado en ejecución contra Postgres real (endpoints principales con curl, incluidos casos de permiso denegado y aislamiento entre tenants).
4. Endpoints documentados en `docs/api-<modulo>.md`. Textos de UI en español. Sin TODOs sin resolver en funcionalidades pedidas.

---

## 14. Módulos por tenant (plataforma modular)

Los clientes pueden usar GondolIA **con su propio POS** (integración) **o con el POS GondolIA**, o ambos. Los dueños de
GondolIA gestionan desde su consola qué módulos tiene habilitados cada tenant. Esquema: `V2__modulos_cajero_pos_importacion.sql`.

### 14.1 Catálogo (`com.gondolia.modules.ModuleCatalog`)
| `TenantModule` | Nombre UI | Qué habilita | Adicional mensual |
|---|---|---|---|
| `POS_GONDOLIA` | Punto de venta GondolIA | Cajas por sucursal, cobro con escáner, medios de pago, tickets, anulaciones, cierre de caja (§15) | ARS 12.000 por sucursal activa |
| `POS_INTEGRATION` | Integración con POS propio | API key por sucursal (webhook de ventas), importación CSV de ventas, simulador (§6.4) | ARS 8.000 por sucursal activa |
| `MULTI_BRANCH` | Multi-sucursal | Más de una sucursal (hasta el límite del plan), transferencias, vista consolidada | sin adicional |

**Siempre incluido** (no son módulos): inventario, lotes y vencimientos, carga con escáner/OCR, **importación masiva de
productos (§16)**, alertas, IA, avisos y recalls, soporte, ventas manuales e historial, usuarios y configuración.

- Presets al crear un tenant (si no se envía `modules`): FREEMIUM {POS_GONDOLIA}; BASICO {POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH}; PROFESIONAL {los 3}.
- Máximo efectivo de sucursales = `MULTI_BRANCH` ? límite del plan : 1.
- MRR = Σ tenants ACTIVE: sucursales activas × (precio del plan + Σ adicionales de sus módulos habilitados).
- Deshabilitar `MULTI_BRANCH` con más de 1 sucursal activa → 409 `MODULE_IN_USE` ("El cliente tiene N sucursales activas: debe desactivar las sucursales extra antes").
- Módulo deshabilitado → 403 `MODULE_DISABLED` ("Esta función no está habilitada para tu comercio. Contactá a GondolIA para activarla.") en endpoints y webhook; el frontend oculta el menú y muestra `ModuleDisabledPage` si se entra por URL.
- Cambio de módulos → `tenant_events` `MODULE_ENABLED`/`MODULE_DISABLED` (`from_value` = módulo) + push `/user/queue/session`
  `{"type":"MODULES_CHANGED"}` a todos los usuarios del tenant → el frontend hace `refreshMe()` + toast "Se actualizaron las funciones habilitadas".

### 14.2 Núcleo (extensión de la fundación)
- `ModuleService`: `Set<TenantModule> enabledModules(Long tenantId)`, `boolean isEnabled(Long tenantId, TenantModule m)`,
  `void require(TenantModule m)` (tenant del usuario actual; 403 `MODULE_DISABLED`), `int effectiveMaxBranches(Long tenantId)`,
  `List<TenantModuleStatus> statuses(Long tenantId)`, `TenantModuleStatus setEnabled(Long tenantId, TenantModule m, boolean enabled, Long actorUserId)`
  (valida, registra evento, hace push), `void applyPlanPreset(Long tenantId, TenantPlan plan)`.
  `record TenantModuleStatus(TenantModule module, String name, String description, BigDecimal monthlyPricePerBranch, boolean enabled, Instant updatedAt, String updatedByName)`.
- Anotación `@RequiresModule(TenantModule.X)` en clase o método de controller, aplicada por un `HandlerInterceptor`.
- `MeDto.tenant` agrega `modules: ["POS_GONDOLIA", ...]` y `maxBranches` pasa a ser el máximo efectivo.
- Entidad `com.gondolia.domain.tenant.TenantModuleConfig` (tabla `tenant_modules`) y enum `TenantModule`.

### 14.3 Consola de dueños (módulo C)
- `GET /api/platform/modules` → `[{module,name,description,monthlyPricePerBranch,enabledTenants,adoptionPct}]`
- `GET /api/platform/tenant-modules?q=&status=&plan=&page=` → `PageResponse<{tenantId,tenantName,plan,status,activeBranchCount,modules:{POS_GONDOLIA:true,POS_INTEGRATION:false,MULTI_BRANCH:true},estimatedMonthlyFee}>` (matriz)
- `GET /api/platform/tenants/{id}/modules` → `[TenantModuleStatus]` · `PUT /api/platform/tenants/{id}/modules/{module}` `{enabled}` → `TenantModuleStatus`
- `POST /api/platform/tenants` acepta `modules?: [TenantModule]`; `TenantSummary` agrega `modules`; `GET /api/platform/tenants?module=`.
- `GET /api/platform/metrics` agrega `modules: {POS_GONDOLIA:{tenants,pct}, ...}` y el MRR incluye adicionales.
- Frontend: `/owner/modules` **ModulesMatrixPage** "Módulos por cliente": adopción arriba, buscador y filtros, tabla clientes × módulos
  con switches, plan, estado, sucursales activas y cuota estimada; confirmación al desactivar explicando el efecto; mensaje claro
  para `MODULE_IN_USE`. Sección "Módulos" con switches en TenantDetailPage y checkboxes (con preset por plan) en TenantFormPage.

## 15. POS GondolIA (módulo H · `com.gondolia.pos` · `features/pos`) — requiere `POS_GONDOLIA`

Lo usan ADMIN, EMPLOYEE y CASHIER (`Roles.TENANT_POS`) en sus sucursales. Comprobante: **ticket no fiscal**
("Comprobante no válido como factura"; la factura electrónica con ARCA queda para una etapa futura).
Entidades (extensión núcleo, `com.gondolia.domain.pos`): `PosRegister`, `PosSession`, `PosBranchCounter`, `PosSale`, `PosSaleItem`, `PosPayment`, `PosCashMovement`.

### 15.1 Núcleo: anulación en StockService
`record VoidSaleCommand(Long tenantId, String batchRef, Long userId, String reason)`;
`List<StockMovement> voidSale(VoidSaleCommand cmd)`: por cada movimiento `SALE` con `lot_id` del batch crea `SALE_VOID` (misma
cantidad, lote y precio) y devuelve el stock a ese lote (`DEPLETED` → `ACTIVE`; lotes `RECALLED` o vencidos quedan como están);
los faltantes (`lot_id NULL`) no devuelven stock; 409 `ALREADY_VOIDED` si el batch ya tiene `SALE_VOID`. Publica `StockChangedEvent`.

### 15.2 API
- **Cajas**: `GET /api/tenant/pos/registers` (scope; `openSession:{id,openedByName,openedAt}|null`) · `POST` / `PUT /{id}` (ADMIN) `{branchId,name,active}`.
- **Turnos**: `GET /api/tenant/pos/sessions/current` → turno OPEN del usuario o `null` ·
  `POST /api/tenant/pos/sessions/open` `{registerId,openingCash}` (409 `REGISTER_BUSY` / `SESSION_ALREADY_OPEN`) ·
  `POST /api/tenant/pos/sessions/{id}/cash-movements` `{type,amount,reason}` · `POST /api/tenant/pos/sessions/{id}/close` `{countedCash,note}` → reporte ·
  `GET /api/tenant/pos/sessions?status=&from=&to=&page=` (ADMIN: todos los del scope; EMPLOYEE/CASHIER: los propios) · `GET /api/tenant/pos/sessions/{id}` → reporte
  `{id,branchId,branchName,registerId,registerName,status,openedByName,closedByName,openedAt,closedAt,openingCash,totalsByMethod:{CASH,DEBIT,CREDIT,TRANSFER,QR},cashIn,cashOut,changeGiven,expectedCash,countedCash,difference,salesCount,salesTotal,voidedCount,voidedTotal,topProducts:[{productName,units,total}],cashMovements:[...]}`.
  `expectedCash = openingCash + Σ pagos CASH − Σ vuelto + CASH_IN − CASH_OUT − efectivo neto de ventas anuladas`.
- **Productos para vender** (sucursal del turno): `GET /api/tenant/pos/products/lookup?code=` (código exacto) y
  `GET /api/tenant/pos/products/search?q=` (top 20) → `{productId,barcode,name,brand,unit,listPrice,sellableStock,nextLot:{lotId,lotNumber,expiryDate,discountPct,unitPrice}|null,hasRecalledStock,hasExpiredStock,outOfStock}`.
- **Ventas**: `POST /api/tenant/pos/sales` `{sessionId,items:[{productId,quantity}],payments:[{method,amount,reference?}],customerName?,customerDoc?,allowShortage:false}`:
  valida turno OPEN del usuario (ADMIN puede operar cualquier turno de su scope); stock suficiente o 409 `INSUFFICIENT_STOCK`
  con `details:[{productId,productName,requested,available}]` salvo `allowShortage`; Σ pagos ≥ total o 400 `PAYMENT_INSUFFICIENT`;
  vuelto solo si hay pago CASH y no mayor que el efectivo recibido. Registra con `StockService.registerSale` por ítem **ordenado por
  productId** (source `POS_GONDOLIA`, batchRef `P-...` compartido), numera con `pos_branch_counters` (`SELECT ... FOR UPDATE`),
  guarda venta/ítems (con lotes consumidos)/pagos y actualiza contadores del turno → `PosSaleDto` completo con `ticketCode`.
  `GET /api/tenant/pos/sales?sessionId=&status=&from=&to=&q=&page=` · `GET /api/tenant/pos/sales/{id}` ·
  `GET /api/tenant/pos/sales/{id}/ticket` → datos para imprimir (comercio, sucursal y dirección, caja, cajero, fecha, código, ítems con
  descuentos por lote, total, pagos, vuelto, leyenda) · `POST /api/tenant/pos/sales/{id}/void` `{reason}`: ADMIN siempre;
  EMPLOYEE/CASHIER solo ventas de su turno OPEN → `StockService.voidSale`.
- `GET /api/tenant/pos/stats?days=30` (ADMIN, scope) → ventas por medio de pago, por hora, ticket promedio, por cajero, anulaciones.

### 15.3 Frontend
- `/app/pos` **PosTerminalPage** (AppShell compacto): sin turno abierto → elegir caja y abrir con efectivo inicial; buscador +
  lector USB siempre enfocado + botón cámara (`BarcodeScanner` compartido); carrito con +/−, precio con descuento por lote y chip
  de lote/vencimiento; productos en cuarentena bloqueados y aviso de sin stock; atajos **F2** buscar, **F4** cobrar, **F8** quitar ítem,
  **Esc** cancelar; modal de cobro con medios combinables, billetes rápidos y vuelto; al confirmar muestra el ticket con Imprimir /
  Nueva venta; retiros e ingresos de efectivo; cerrar caja con arqueo.
- `/app/pos/sessions` **PosSessionsPage** (turnos, ventas, reporte de cierre, anulación) · `/app/pos/registers` **PosRegistersPage** (ADMIN) ·
  `/app/pos/sales/:id/ticket` **PosTicketPage** (sin AppShell, CSS de impresión 80 mm, `window.print()`).

## 16. Importación masiva de productos y stock (módulo I · `com.gondolia.imports` · `features/imports`)

Primera carga rápida / migración desde la planilla o base que el cliente usaba, con revisión y edición antes de confirmar, y
exportación del catálogo para ajustes masivos posteriores. Rol TENANT_ADMIN. Siempre incluido. Entidades (extensión núcleo,
`com.gondolia.domain.imports`): `ImportJob`, `ImportJobRow`. Dependencias en el pom: Apache POI `poi` + `poi-ooxml` 5.3.x, Apache Commons CSV 1.12.x.

### 16.1 Campos (`GET /api/tenant/imports/fields` → `[{key,label,required,type,description,synonyms}]`)
`barcode` (Código de barras), `name`* (Nombre), `brand` (Marca), `category` (Categoría), `supplier` (Proveedor), `unit` (Unidad),
`costPrice` (Precio de costo), `salePrice` (Precio de venta), `minStock` (Stock mínimo), `perishable` (Perecedero), `description`,
`quantity` (Cantidad en stock), `lotNumber` (Número de lote), `expiryDate` (Fecha de vencimiento), `receivedAt` (Fecha de ingreso —
preserva el orden FIFO), `branch` (Sucursal: nombre o código).
Auto-mapeo por sinónimos de encabezado (sin acentos ni mayúsculas): código/cod/ean/código de barras/barcode/cod_barra;
nombre/descripción/producto/artículo; marca; categoría/rubro/familia/sección; proveedor/distribuidor; unidad/u. medida;
costo/precio costo/precio compra; precio/pvp/precio venta/precio público; mínimo/stock mínimo/stock min; perecedero;
stock/cantidad/existencia/unidades; lote/nro lote/n° lote; vencimiento/vto/fecha venc/fecha de vencimiento;
fecha ingreso/ingreso/fecha compra; sucursal/local/tienda.
Una fila = un producto (+ opcionalmente un lote). **Varias filas con el mismo código y distinto lote/vencimiento = un producto con varios lotes.**

### 16.2 Parseo y validación
- XLSX/XLS con POI (fechas nativas o seriales de Excel, fórmulas evaluadas, primera hoja no vacía o `sheetName`); CSV con detección de
  separador `;` `,` o tab y de codificación UTF-8 (con/sin BOM) o Windows-1252 (así exporta Excel en español). Máx. 10 MB y 10.000 filas;
  ignora filas vacías; encabezado = primera fila no vacía.
- Números es-AR y en ("$ 1.234,50", "1234.5"), booleanos (si/sí/no/true/false/1/0/x), fechas DMY por defecto (dd/mm/aaaa, dd-mm-aa, aaaa-mm-dd, serial).
- **ERROR**: falta nombre; precio o cantidad no numérico o negativo; cantidad no entera; fecha inválida; sucursal inexistente; cantidad > 0
  sin sucursal ni sucursal por defecto; categoría/proveedor inexistente con la opción de crear desactivada; texto demasiado largo.
- **WARNING**: EAN con dígito verificador inválido; precio de venta menor al costo; unidad desconocida (usa UNIDAD); producto existente
  (por código, o por nombre exacto si no hay código) → UPDATE si `updateExisting`, si no SKIP de los datos del producto; categoría o
  proveedor nuevo que se va a crear; vencimiento pasado (entra como vencido pendiente de descarte); el producto ya tiene stock en esa
  sucursal (se suma un lote nuevo); filas duplicadas con datos de producto contradictorios (gana la primera).
- Opciones: `{updateExisting:true, createCategories:true, createSuppliers:true, importStock:true, defaultBranchId, dateFormat:"DMY"|"MDY"|"YMD"}`.

### 16.3 API (TENANT_ADMIN)
- `GET /api/tenant/imports/template?format=xlsx|csv` → plantilla con encabezados en español, 4 filas de ejemplo (incluye un producto con 2 lotes) y hoja "Instrucciones".
- `GET /api/tenant/imports/export?format=xlsx|csv&includeStock=true` → catálogo actual (+ lotes con stock del scope) con **las mismas columnas** de la plantilla, para editar en Excel y reimportar como actualización masiva.
- `POST /api/tenant/imports` multipart `file` [+ `sheetName`] → `ImportJobDto {id,status,fileName,fileFormat,sheetName,sheetNames,headers,suggestedMapping,options,totalRows,validRows,warningRows,errorRows,skippedRows,processedRows,result,createdAt,createdByName,appliedAt,sampleRows}`
- `PUT /api/tenant/imports/{id}/mapping` `{columnMapping,options}` → normaliza y valida todas las filas → `ImportJobDto` (VALIDATED) + `preview:{productsToCreate,productsToUpdate,lotsToCreate,unitsToLoad,categoriesToCreate:[],suppliersToCreate:[]}`
- `GET /api/tenant/imports/{id}` · `GET /api/tenant/imports?page=` (historial) · `DELETE /api/tenant/imports/{id}` (cancela si no se aplicó)
- `GET /api/tenant/imports/{id}/rows?status=ALL|ERROR|WARNING|VALID|SKIPPED&q=&page=&size=` → `PageResponse<{id,rowNumber,raw,data,status,action,messages,productId,lotId}>`
- `PATCH /api/tenant/imports/{id}/rows/{rowId}` `{data}` → fila revalidada + contadores del job ·
  `POST /api/tenant/imports/{id}/rows/bulk` `{rowIds:[]|null (null = todas las del filtro), filterStatus?, action:"SKIP"|"UNSKIP"|"SET_FIELD", field?, value?}`
- `GET /api/tenant/imports/{id}/errors.csv` → filas con error/advertencia y sus mensajes
- `POST /api/tenant/imports/{id}/apply` `{ignoreErrors:false}` (409 `IMPORT_HAS_ERRORS` si hay errores sin omitir) → asincrónico (APPLYING,
  `processedRows` para la barra de progreso, bloques de 200 filas por transacción): crea categorías/proveedores, upsert de productos y, si
  `importStock` y cantidad > 0, `StockService.receiveLot` (source `IMPORT`, `receivedAt` = columna o instante de aplicación + rowNumber ms
  para respetar el orden del archivo; el chequeo de recall corre solo). `result = {productsCreated,productsUpdated,lotsCreated,unitsLoaded,categoriesCreated,suppliersCreated,rowsSkipped,rowsFailed,recallMatches}`
  → APPLIED; notificación SYSTEM al admin al terminar.

### 16.4 Frontend
- `/app/imports` **ImportsPage**: historial, "Nueva importación", descargar plantilla (xlsx/csv), exportar catálogo actual.
- `/app/imports/:id` **ImportWizardPage** por pasos: 1 Archivo (arrastrar y soltar, hoja) → 2 Columnas (por cada campo un select de
  encabezados con sugerencia y valores de muestra; opciones) → 3 Revisión (tarjetas válidas/advertencias/errores; grilla paginada filtrable
  con **edición inline** y mensajes por celda; acciones masivas omitir / asignar categoría o sucursal; descargar errores) → 4 Confirmar
  (productos nuevos/actualizados, lotes, unidades, categorías y proveedores nuevos; barra de progreso) → 5 Resultado (contadores, links, aviso de recalls).
- InventoryPage (A1) y el Inicio vacío de un comercio nuevo (B) enlazan a `/app/imports` ("Hacé tu primera carga importando tu planilla").
- `docs/examples/importacion-ejemplo.xlsx` y `.csv` con algunos errores intencionales para la demo.
- Nota para B: las importaciones generan ráfagas de `StockChangedEvent`; el listener del AlertEngine debe agrupar por sucursal (debounce).

## 17. Extensión del núcleo — checklist (antes de los módulos en paralelo)
Backend: V2 (ya escrita), enums/entidades/repositorios nuevos (§4.1, §15, §16), `Role.TENANT_CASHIER` en `Roles`/`BranchAccessService`
(como EMPLOYEE) y en `DevFixtureRunner` (`cajero@prueba.com`, Centro; "Comercio de Prueba" con los 3 módulos; "Otro Comercio" solo
POS_INTEGRATION), `ModuleService` + `@RequiresModule` + interceptor, `MeDto.tenant.modules`/`maxBranches` efectivo,
`StockService.voidSale` y orden de rotación con lotes en liquidación primero (§4.2), dependencias POI + Commons CSV.
Frontend: migración del kit a **shadcn/ui** + tokens/tipografías "Góndola UI" (§9.5), componentes firma `components/gondola/`,
`components/scanner/` (`BarcodeScanner` con zxing y cámara trasera, `useBarcodeWedge` para lectores USB, `CameraCapture` para OCR),
rol Cajero (tipos, etiqueta, home), `useModules()` + `RequireModule` + `ModuleDisabledPage`, navegación con `module`, rutas y
placeholders nuevos (§9.3), `MODULES_CHANGED` en `useSessionEvents`, variante compacta del AppShell para `/app/pos` y layout sin shell
para el ticket.
