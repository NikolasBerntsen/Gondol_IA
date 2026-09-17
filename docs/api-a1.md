# Módulo A1 — Catálogo y carga de mercadería

Backend: `com.gondolia.catalog` · Frontend: `frontend/src/features/catalog` · Contrato: SPEC §6.3.

Todo lo de acá vive bajo `/api/tenant/**`, necesita `Authorization: Bearer <jwt>` y respeta el alcance de
sucursales del encabezado `X-Branch-Id` (SPEC §3.5). El **catálogo (productos, categorías, proveedores, precios)
es único por comercio**; el **stock y los lotes son por sucursal**.

---

## 1. Roles y permisos

| Acción | JEFE | ADMIN | EMPLEADO | CAJERO |
|---|:-:|:-:|:-:|:-:|
| Listar categorías (`GET /tenant/categories`) | ✔ | ✔ | ✔ | ✔ |
| Listar/ver productos, listar proveedores y lotes | ✘ | ✔ | ✔ | ✘ |
| Crear y editar productos, crear categoría (incluida la creación inline) | ✘ | ✔ | ✔ | ✘ |
| Cargar y corregir lotes, consulta pública por código, chequeo de recall, OCR | ✘ | ✔ | ✔ | ✘ |
| Eliminar productos · editar/eliminar categorías · ABM de proveedores | ✘ | ✔ | ✘ | ✘ |

Se implementa con `@PreAuthorize(Roles.TENANT_INVENTORY | Roles.TENANT_ADMIN | Roles.TENANT_ANY)`.
Un rol sin permiso recibe **403 `FORBIDDEN`**; sin token, **401 `UNAUTHORIZED`**.

### Aislamiento (SPEC §3.4)
- El `tenantId` sale **siempre** de `CurrentUser.tenantId()`; nunca del request. Todo acceso por id usa
  `findByIdAndTenantId` → **404 `NOT_FOUND`** si la entidad es de otro comercio (no 403: no se filtra la existencia).
- Las lecturas de stock usan `BranchAccessService.scopeBranchIds()`: una sucursal si el encabezado trae un id
  accesible, todas las accesibles si viene `all` o falta. Una sucursal que el usuario no tiene asignada →
  **403 `BRANCH_FORBIDDEN`**.
- Las escrituras (carga y corrección de lotes) usan `requireSingleBranch(body.branchId)`: **el `branchId` del cuerpo
  manda sobre el encabezado**; si no se puede determinar una sola sucursal → **400 `BRANCH_REQUIRED`**
  ("Elegí una sucursal para esta operación").
- Un empleado asignado solo a Centro no ve ni modifica stock, lotes ni movimientos de Norte.

---

## 2. Categorías

### `GET /api/tenant/categories` — TENANT_ANY
```json
[{"id":1,"name":"Lácteos","productCount":2},{"id":2,"name":"Almacén","productCount":1}]
```
Ordenadas por nombre. `productCount` cuenta los productos activos e inactivos del comercio.

### `POST /api/tenant/categories` — ADMIN o EMPLEADO → 201
`{"name":"Conservas"}` → `CategoryDto`. Nombre obligatorio (≤ 80). Nombre repetido dentro del comercio →
**409 `CONFLICT`**. Dos comercios distintos sí pueden tener la misma categoría.

### `PUT /api/tenant/categories/{id}` — ADMIN
Mismo cuerpo. **404** si no es del comercio.

### `DELETE /api/tenant/categories/{id}` — ADMIN → 204
Con productos asociados → **409 `CONFLICT`**: *"No podés eliminar una categoría con productos. Cambiá primero la
categoría de esos productos."*

---

## 3. Proveedores

### `GET /api/tenant/suppliers?includeInactive=true` — ADMIN o EMPLEADO
```json
[{"id":1,"name":"Distribuidora La Pampa","contactName":"Marta Suárez","phone":"11 4555-0101",
  "email":"pedidos@lapampa.example","leadTimeDays":3,"notes":null,"active":true,"productCount":3}]
```
`includeInactive=false` deja afuera los dados de baja (lo usa el selector de la carga de mercadería).

### `POST|PUT /api/tenant/suppliers[/{id}]` — ADMIN (201 / 200)
```json
{"name":"Distribuidora Paraná","contactName":"Julián Ferrer","phone":"341 555-1234",
 "email":"ventas@parana.com.ar","leadTimeDays":3,"notes":"Entrega martes y jueves.","active":true}
```
`name` obligatorio; `email` con formato válido; `leadTimeDays` ≥ 0. Nombre repetido → **409 `CONFLICT`**.

### `DELETE /api/tenant/suppliers/{id}` — ADMIN
→ `{"deactivated":true}` si el proveedor ya se usa en productos o lotes (**baja lógica**, se conserva el historial);
`{"deactivated":false}` si nunca se usó (se elimina de verdad). La pantalla muestra el mensaje que corresponde.

---

## 4. Productos

### `GET /api/tenant/products` — ADMIN o EMPLEADO
| Parámetro | Valores | Notas |
|---|---|---|
| `q` | texto | Busca en **nombre, marca y código de barras** (sin distinguir mayúsculas). |
| `categoryId` | id | Categoría exacta. |
| `stockStatus` | `ALL` · `OK` · `LOW` · `OUT` · `EXPIRING` | Otro valor → 400 `VALIDATION_ERROR`. |
| `active` | `true` · `false` · vacío | Vacío = activos y dados de baja. |
| `page` · `size` · `sort` | 0-based · ≤ 100 · `campo,asc\|desc` | Campos ordenables: `name`, `barcode`, `brand`, `categoryName`, `salePrice`, `costPrice`, `minStock`, `sellableStock`, `nextExpiryDate`, `stockStatus`, `createdAt`. Otro campo → 400. |

`EXPIRING` devuelve los productos con stock vencido pendiente **o** con el próximo vencimiento dentro de
`tenant_settings.expiry_warning_days`.

Respuesta `PageResponse<ProductListItem>`:
```json
{"content":[{"id":1,"barcode":"7791234000012","name":"Leche entera La Pradera 1 L","brand":"La Pradera",
  "categoryId":1,"categoryName":"Lácteos","unit":"UNIDAD","costPrice":950.0,"salePrice":1400.0,"minStock":12,
  "perishable":true,"active":true,"sellableStock":92,"expiredStock":0,"quarantinedStock":0,
  "nextExpiryDate":"2026-10-05","lotsCount":4,"stockStatus":"OK",
  "stockByBranch":[{"branchId":1,"branchName":"Sucursal Centro","sellableStock":72,"stockStatus":"OK"},
                   {"branchId":2,"branchName":"Sucursal Norte","sellableStock":20,"stockStatus":"OK"}]}],
 "page":0,"size":20,"totalElements":4,"totalPages":1}
```
- Los totales están **sumados sobre el alcance** y `stockByBranch` detalla cada sucursal accesible con stock.
- `stockStatus` consolidado = el **peor** de las sucursales (`OUT` > `LOW` > `OK`), con `OUT` si el vendible es 0 y
  `LOW` si no supera `minStock` (SPEC §4.2).
- `sellableStock` excluye vencidos y lotes en cuarentena; esos van en `expiredStock` y `quarantinedStock`.

### `GET /api/tenant/products/{id}` y `GET /api/tenant/products/by-barcode/{barcode}`
`ProductDetail` = `ProductListItem` + `description`, `supplierId`, `supplierName`, `createdAt`, `updatedAt` y `lots`.
Los lotes vienen **agrupados por sucursal y, dentro de cada una, en orden de rotación**:
```json
{"id":5,"branchId":1,"branchName":"Sucursal Centro","productId":1,"productName":"Leche entera La Pradera 1 L",
 "lotNumber":"LP2409A","expiryDate":"2026-10-07","daysToExpiry":20,"initialQuantity":24,"quantity":24,
 "costPrice":950.0,"receivedAt":"2026-09-11T12:00:00Z","status":"ACTIVE","source":"SEED","discountPct":null,
 "supplierId":1,"supplierName":"Distribuidora La Pampa","expiryBucket":"UPCOMING","originLotId":null,
 "rotationRank":1}
```
`rotationRank` = posición de salida **dentro de su sucursal** (1 = "1º sale"); es `null` para los lotes que ya no
son vendibles (vencidos, agotados o en cuarentena). El orden respeta `tenant_settings.stock_rotation`
(FIFO o FEFO) y pone primero los lotes con `discountPct` (SPEC §4.2). Código inexistente → **404 `NOT_FOUND`**.

### `GET /api/tenant/products/{id}/movements?limit=12`
Vista acotada y de solo lectura de los últimos movimientos del producto **en el alcance**, para la ficha
(el historial completo con filtros es del módulo A2). `limit` 1..100, por defecto 12.
```json
[{"id":9,"branchId":1,"branchName":"Sucursal Centro","lotId":10,"lotNumber":"LP2409C","type":"ENTRY",
  "quantity":18,"unitPrice":null,"discountPct":null,"totalAmount":null,"source":"SCAN","batchRef":null,
  "reason":null,"userName":"Emiliano Gómez","occurredAt":"2026-09-17T22:32:26Z"}]
```

### `POST /api/tenant/products` (201) · `PUT /api/tenant/products/{id}`
```json
{"barcode":"7791234500017","name":"Sopa de tomate en lata La Huerta 340 g","brand":"La Huerta",
 "description":null,"categoryId":null,"categoryName":"Conservas","supplierId":null,"unit":"UNIDAD",
 "costPrice":1380,"salePrice":2100,"minStock":8,"perishable":true,"active":true}
```
- `name` obligatorio; `costPrice`/`salePrice`/`minStock` no negativos; `unit` del enum `ProductUnit`.
- `barcode` se normaliza con `Barcodes.normalize` (trim y sin espacios, alfanumérico ≤ 32) y es **único por
  comercio**: repetido → **409 `DUPLICATE_BARCODE`** ("Ya tenés un producto con ese código de barras.").
  Dos comercios distintos sí pueden usar el mismo código.
- **Categoría inline**: si no viene `categoryId` y sí `categoryName`, se reutiliza la categoría con ese nombre o se
  crea una nueva. Así el empleado da de alta el producto sin salir del formulario.
- La respuesta es el `ProductDetail` completo (con el stock del alcance).

### `DELETE /api/tenant/products/{id}` — ADMIN
→ `{"deactivated":true}` si el producto tiene lotes o movimientos: queda `active=false`, sigue en el historial y ya
no se puede vender ni cargar. `{"deactivated":false}` si nunca se usó (se elimina).

---

## 5. Lotes (carga de mercadería)

### `POST /api/tenant/lots` — ADMIN o EMPLEADO → 201
```json
{"branchId":1,"productId":1,"lotNumber":"lp-2409/c","expiryDate":"2026-09-30","quantity":18,
 "costPrice":980,"supplierId":1,"source":"SCAN"}
```
`productId` y `quantity` (> 0) obligatorios; `source` ∈ `MANUAL|SCAN|OCR` (por defecto `MANUAL`).
Usa `StockService.receiveLot`, así que **siempre crea un lote nuevo** con su `ENTRY`, aunque el producto ya tenga
stock con otra fecha, y pasa por `RecallMatchingService.checkLot`.

```json
{"lot":{ ...LotDto... },
 "quarantined":false,
 "recalls":[],
 "rotationWarning":"Este lote vence antes que mercadería que ingresó antes: con FIFO se venderá después. Revisalo o aplicá un descuento.",
 "existingLots":[{"rotationRank":1,"lotNumber":"LP2409A","expiryDate":"2026-10-07","quantity":24, "...":"..."}]}
```
- `existingLots`: los otros lotes vendibles del producto **en esa sucursal**, en orden de rotación, para mostrar
  "ya tenés X u. con vencimiento …".
- `rotationWarning`: solo con FIFO, cuando el lote nuevo vence antes que mercadería que entró antes. **No bloquea.**
- `quarantined: true` + `recalls`: el lote coincidió con un recall publicado y quedó en `RECALLED` (no vendible).

Errores: **400 `BRANCH_REQUIRED`** (alcance "todas" y más de una sucursal accesible, sin `branchId` en el cuerpo) ·
**403 `BRANCH_FORBIDDEN`** (sucursal no asignada) · **404 `NOT_FOUND`** (producto o sucursal de otro comercio) ·
**400 `VALIDATION_ERROR`** (cantidad ≤ 0, producto dado de baja: *"El producto está dado de baja: activalo antes de
cargar mercadería."*).

### `PUT /api/tenant/lots/{id}` — ADMIN o EMPLEADO
`{"lotNumber":"LP2409C","expiryDate":"2026-11-20"}` → `LotDto`. Corrige una carga: recalcula
`lot_number_normalized`, `daysToExpiry` y `expiryBucket`, y **vuelve a chequear el recall** si cambió el lote o la
fecha. Solo sobre lotes de sucursales accesibles (si no, 403 `BRANCH_FORBIDDEN` / 404).

### `GET /api/tenant/lots?productId=&includeEmpty=false`
Lotes del producto **en el alcance**, por sucursal y en orden de rotación. `includeEmpty=true` agrega los agotados.

---

## 6. Ayudas de la carga

### `GET /api/tenant/catalog/lookup/{barcode}` — autocompletar un producto nuevo
Consulta **Open Food Facts** (`app.openfoodfacts-enabled`, por defecto `true`; timeout **4 s**).
**Nunca falla el request**: si está deshabilitado, no hay internet, tarda de más o el código no existe,
devuelve `found: false` y la pantalla ofrece cargar los datos a mano.
```json
{"found":true,"source":"OPEN_FOOD_FACTS","barcode":"3017620422003","name":"Nutella","brand":"Nutella",
 "quantity":"400 g e","categoryHint":"Nuttela","imageUrl":"https://images.openfoodfacts.org/..."}
```

### `GET /api/tenant/recalls/check?barcode=&lotNumber=&expiryDate=`
Chequeo **previo** a la carga, sin efectos: `RecallMatchingService.findActiveRecalls`. El número de lote se
normaliza igual que en la carga (`l-2409/a` coincide con `L2409A`).
```json
{"recalled":true,"recalls":[{"announcementId":1,"title":"Retiro de Sopa de tomate La Huerta 340 g",
  "reason":"Posible presencia de cuerpos extraños en el lote L2409A.",
  "instructions":"Retirá el producto de la góndola y no lo vendas…","allLots":false}]}
```
Sin `barcode` devuelve `{"recalled":false,"recalls":[]}`.

### OCR — `POST /api/tenant/ocr/label` y `POST /api/tenant/ocr/barcode` (multipart `file`)
Proxy de `AiClient` (SPEC §8.3/§8.4). **La foto se procesa y se descarta**: no se guarda ningún adjunto.
`/label` agrega `matchedProduct` con el `ProductListItem` del catálogo si alguno de los códigos detectados existe
(`null` si no).
```json
{"text":"…","lines":["LOTE: L2409A","VTO 25/09/2026"],
 "expiryDates":[{"value":"2026-09-25","raw":"VTO 25/09/2026","confidence":0.92}],
 "lotNumbers":[{"value":"L2409A","raw":"LOTE: L2409A","confidence":0.85}],
 "manufactureDates":[],"barcodes":[{"value":"7791234500017","format":"EAN_13"}],
 "productNameCandidates":["SOPA DE TOMATE LA HUERTA"],"processingMs":840,"matchedProduct":{"id":4,"…":"…"}}
```
Si el servicio de IA no está disponible: **503 `AI_UNAVAILABLE`** con mensaje accionable en español
(*"No pudimos leer la etiqueta: el servicio de IA no está disponible. Cargá el vencimiento y el lote a mano."*).
La pantalla lo muestra como aviso y deja seguir a mano: **la cámara nunca es un requisito**.

---

## 7. Frontend (`features/catalog`)

| Ruta | Página | Qué hace |
|---|---|---|
| `/app/inventory` | `InventoryPage` | Búsqueda (lee `?q=` del buscador de la barra superior), filtro por categoría y segmentado de estado de stock. Con "Todas las sucursales" agrega **una columna por sucursal**. Acciones rápidas por fila (cargar mercadería, editar), link "Importar Excel/CSV" (solo admin) y estado vacío de comercio nuevo. |
| `/app/products/:id` | `ProductDetailPage` | KPI (vendible, próximo vencimiento, vencido pendiente, precio), **lotes en orden de salida** con `LotRankChip` + `ExpiryChip` (los no vendibles muestran su estado en vez del orden), datos, stock por sucursal, movimientos recientes y el resumen de IA si el módulo B lo devuelve. Avisos de cuarentena y de producto dado de baja. |
| `/app/products/new` · `/:id/edit` | `ProductFormPage` | Alta y edición con campo de código de barras + botón de escáner, autocompletado con la base pública, **creación de categoría inline**, precios, stock mínimo y "tiene vencimiento". |
| `/app/intake` | `IntakePage` | La pantalla móvil de carga: `BarcodeScanner`, código a mano y lector USB (`useBarcodeWedge`); producto encontrado → tarjeta, desconocido → datos de Open Food Facts y "Crear el producto"; "Leer vencimiento y lote con la cámara" → `CameraCapture` → chips de OCR con su confianza; stepper de cantidad, costo, proveedor, `BranchPicker` cuando el alcance es "todas"; lotes existentes en orden de salida con el lote nuevo resaltado; **panel rojo que bloquea si hay recall** y aviso de FIFO que no bloquea; toast de éxito y lista "Cargaste hoy". |
| `/app/categories` | `CategoriesPage` | ABM con diálogo; el borrado avisa cuando la categoría tiene productos. |
| `/app/suppliers` | `SuppliersPage` | ABM con diálogo y **link de WhatsApp** armado desde el teléfono (`wa.me`, agrega el código de país argentino si falta). |

Convenciones seguidas: query keys por sucursal con `useBranchQueryKey`, escrituras con `useWriteBranch` +
`BranchPicker` mandando `branchId` en el cuerpo, componentes firma de Góndola UI (`ExpiryChip`, `LotRankChip`,
`StockStatusPill`, `BarcodeDigits`, `PriceTag` indirecto vía KPI), estados de carga / vacío / error en todas las
pantallas y textos en voseo rioplatense.

---

## 8. Decisiones tomadas

1. **`GET /tenant/products/{id}/movements` no está en el SPEC**: la ficha del producto necesitaba los movimientos
   recientes y `GET /tenant/movements` (módulo A2) es solo para el administrador. Se agregó este endpoint acotado
   (solo ese producto, solo el alcance, sin filtros, máximo 100 filas) con el permiso de inventario. No pisa el
   endpoint de A2.
2. **`DELETE` de productos y proveedores devuelve `{"deactivated":boolean}`** en vez de 204, para que la pantalla
   diga si el registro se eliminó o quedó desactivado (el SPEC define la regla pero no cómo informarla).
3. **`GET /tenant/suppliers` acepta `includeInactive`** (por defecto `true`): la carga de mercadería solo ofrece
   proveedores activos y las pantallas de administración necesitan ver los dados de baja.
4. **Editar un producto lo puede hacer el empleado** (SPEC §3.3 *"Inventario … crear/editar productos"* con ✔ para
   EMPLOYEE). En la ficha solo se oculta "Dar de baja", que es exclusivo del administrador.
5. **El resumen de IA de la ficha es una lectura opcional** de `GET /tenant/insights/products/{id}` (módulo B): se
   pide solo para jefe y administrador, sin reintentos, y si no llega la tarjeta no se dibuja. El tipo del frontend
   tiene todos los campos opcionales para no acoplarse al contrato de ese módulo mientras se construye en paralelo.
6. **`lot_number` guarda lo que se escribió** y la normalización va en `lot_number_normalized` (SPEC §4.2), que es
   lo que usa el recall. La pantalla de carga normaliza mientras se tipea, así que en la práctica se guarda en
   mayúsculas y sin separadores.
7. **El filtro `EXPIRING`** usa `expiry_warning_days` del comercio e incluye los productos con stock vencido
   pendiente (el SPEC no define el corte exacto).
8. **No hizo falta ninguna migración**: el módulo usa las tablas de `V1__schema.sql` (`products`, `categories`,
   `suppliers`, `lots`, `stock_movements`). El rango V100–V149 quedó libre.
