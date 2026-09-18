# Módulo H — POS GondolIA (`com.gondolia.pos` · `features/pos`)

API y decisiones del punto de venta propio de GondolIA (SPEC §15, §4.2, §14). Complementa
`docs/api-foundation.md` (núcleo) y `docs/frontend-guide.md` (pantallas).

- Base: `/api/tenant/pos`. JSON en camelCase, instantes ISO-8601 UTC, fechas `aaaa-mm-dd`.
- **Todos** los endpoints requieren el módulo `POS_GONDOLIA` (`@RequiresModule`): sin él responden
  403 `MODULE_DISABLED`.
- Roles: `Roles.TENANT_POS` (TENANT_ADMIN + TENANT_EMPLOYEE + TENANT_CASHIER), salvo lo que indique
  "solo ADMIN". El jefe (`TENANT_BOSS`) **no** entra al POS.
- Sucursal: siempre del alcance del usuario (`BranchAccessService`). El `tenantId` sale siempre de
  `CurrentUser`, nunca del request.

---

## 1. Permisos (espejo de SPEC §3.3)

| Acción | BOSS | ADMIN | EMPLOYEE | CASHIER |
|---|:-:|:-:|:-:|:-:|
| Listar cajas de su alcance | ✘ | ✔ | ✔ | ✔ |
| Crear / editar cajas | ✘ | ✔ | ✘ | ✘ |
| Abrir su turno, mover efectivo, cerrar con arqueo | ✘ | ✔ | ✔ | ✔ |
| Operar el turno de otro (cobrar, mover efectivo, cerrar) | ✘ | ✔ | ✘ | ✘ |
| Ver todos los turnos y todas las ventas del alcance | ✘ | ✔ | ✘ | ✘ |
| Ver sus propios turnos y las ventas de sus turnos | ✘ | ✔ | ✔ | ✔ |
| Anular cualquier venta del alcance | ✘ | ✔ | ✘ | ✘ |
| Anular ventas **de su turno abierto** | ✘ | ✔ | ✔ | ✔ |
| Estadísticas del POS | ✘ | ✔ | ✘ | ✘ |

El empleado y el cajero solo trabajan en las sucursales de `user_branches`; con `X-Branch-Id` de otra
sucursal reciben 403 `BRANCH_FORBIDDEN`, y una caja, turno o venta de otro comercio siempre da
404 `NOT_FOUND` (nunca se revela que existe).

---

## 2. Códigos de error propios

Los compartidos están en `ErrorCodes` (`REGISTER_BUSY`, `SESSION_ALREADY_OPEN`, `PAYMENT_INSUFFICIENT`,
`ALREADY_VOIDED`, `INSUFFICIENT_STOCK`, `MODULE_DISABLED`, `BRANCH_REQUIRED`, `BRANCH_FORBIDDEN`).
`com.gondolia.pos.PosErrorCodes` agrega:

| Estado | `code` | Cuándo |
|---|---|---|
| 409 | `SESSION_NOT_OPEN` | Se intentó cobrar, mover efectivo o cerrar un turno ya cerrado |
| 409 | `REGISTER_INACTIVE` | Se quiso abrir un turno en una caja desactivada |
| 409 | `REGISTER_NAME_TAKEN` | Ya hay otra caja con ese nombre en la sucursal |
| 409 | `REGISTER_IN_USE` | Se quiso desactivar o mudar de sucursal una caja con turno abierto |
| 409 | `PRODUCT_RECALLED` | El producto tiene stock en cuarentena por un recall: no se vende |
| 400 | `CHANGE_NOT_ALLOWED` | El excedente a devolver no llegó en efectivo |

`POST /sales` con falta de stock devuelve el formato de error estándar **más** un campo `details`
(lo agrega `PosExceptionHandler`, solo para este paquete):

```json
{"timestamp":"2026-09-17T13:02:11.044Z","status":409,"error":"Conflict","code":"INSUFFICIENT_STOCK",
 "message":"No hay stock suficiente de Yogur bebible frutilla Vaquita 1 L: quedan 3 u. en esta sucursal.",
 "path":"/api/tenant/pos/sales","fieldErrors":[],
 "details":[{"productId":42,"productName":"Yogur bebible frutilla Vaquita 1 L","requested":5,"available":3}]}
```

---

## 3. Cajas — `/api/tenant/pos/registers`

### `GET /registers?includeInactive=false`
Cajas del alcance de sucursales, por sucursal y nombre. Cada una informa su turno abierto.

```json
[{"id":1,"branchId":3,"branchName":"Sucursal Centro","name":"Caja 1","active":true,
  "createdAt":"2026-09-01T12:00:00Z",
  "openSession":{"id":14,"openedById":7,"openedByName":"Carla Gómez","openedAt":"2026-09-17T11:02:00Z","mine":true}},
 {"id":2,"branchId":3,"branchName":"Sucursal Centro","name":"Caja 2","active":true,
  "createdAt":"2026-09-01T12:00:00Z","openSession":null}]
```

### `GET /registers/{id}` · una caja (404 si es de otro comercio, 403 si la sucursal no es accesible).

### `POST /registers` (ADMIN) → 201
`{"branchId":3,"name":"Caja 2","active":true}` · `branchId` sigue la regla de escritura de SPEC §3.5:
body > `X-Branch-Id` > única sucursal accesible; si no alcanza, 400 `BRANCH_REQUIRED`.
Nombre repetido en la sucursal → 409 `REGISTER_NAME_TAKEN`.

### `PUT /registers/{id}` (ADMIN)
`{"branchId":3,"name":"Caja 2","active":false}`. Con un turno abierto no se puede desactivar ni cambiar
de sucursal → 409 `REGISTER_IN_USE`.

---

## 4. Turnos de caja — `/api/tenant/pos/sessions`

### `GET /sessions/current`
Turno `OPEN` del usuario, o `null` si no tiene ninguno. Devuelve el reporte completo (§4.5).

### `POST /sessions/open`
```json
{"registerId":1,"openingCash":20000}
```
- 409 `SESSION_ALREADY_OPEN` — el usuario ya tiene un turno abierto (se chequea antes que la caja).
- 409 `REGISTER_BUSY` — esa caja ya tiene un turno abierto de otra persona.
- 409 `REGISTER_INACTIVE` — la caja está desactivada.
- 403 `BRANCH_FORBIDDEN` — la caja es de una sucursal que el usuario no tiene asignada.

La base además garantiza la unicidad con índices parciales; el chequeo previo existe para devolver el
código correcto en lugar de un 409 `CONFLICT` genérico.

### `POST /sessions/{id}/cash-movements`
```json
{"type":"CASH_OUT","amount":15000,"reason":"Pago a proveedor"}
```
`type`: `CASH_IN` | `CASH_OUT`. Devuelve el reporte actualizado. Solo sobre un turno `OPEN` que el
usuario pueda operar (propio, o cualquiera del alcance si es ADMIN).

### `POST /sessions/{id}/close`
```json
{"countedCash":184350,"note":"Faltó vuelto chico toda la tarde"}
```
Devuelve el reporte Z con `expectedCash`, `countedCash` y `difference` ya guardados en el turno.

**Fórmula del arqueo (SPEC §15.2), implementada en `PosSessionService.arqueo`:**

```
expectedCash = openingCash
             + Σ pagos CASH (de todas las ventas del turno, anuladas incluidas)
             − Σ vuelto      (de todas las ventas del turno, anuladas incluidas)
             + Σ CASH_IN
             − Σ CASH_OUT
             − efectivo neto de las ventas anuladas   (Σ (pagos CASH − vuelto) de las VOIDED)
```

Las anuladas entran y salen: así el efectivo de una venta anulada se neutraliza exactamente, sin
depender del orden en que se anuló. `changeGiven` del reporte informa solo el vuelto de las ventas
**vigentes** (es lo que le interesa al cajero), mientras que el cálculo usa el vuelto total.

Una vez cerrado, el turno conserva el `expectedCash` del momento del cierre: si después se anula una
venta, el reporte sigue mostrando el arqueo con el que se cerró la caja (`difference` no se reescribe
sola).

### `GET /sessions?status=&from=&to=&mine=&page=&size=`
`PageResponse<PosSessionSummaryDto>` ordenado por apertura descendente. El ADMIN ve todos los del
alcance (o solo los suyos con `mine=true`); el empleado y el cajero, **siempre** los propios.
`from`/`to` son fechas inclusive sobre `openedAt` en hora de negocio.

### `GET /sessions/{id}` · reporte Z

```json
{"id":14,"branchId":3,"branchName":"Sucursal Centro","registerId":1,"registerName":"Caja 1",
 "status":"CLOSED","openedById":7,"openedByName":"Carla Gómez","closedByName":"Carla Gómez",
 "openedAt":"2026-09-17T11:02:00Z","closedAt":"2026-09-17T21:05:00Z","openingCash":20000.00,
 "totalsByMethod":{"CASH":179350.00,"DEBIT":42000.00,"CREDIT":0.00,"TRANSFER":0.00,"QR":8500.00},
 "cashIn":0.00,"cashOut":15000.00,"changeGiven":0.00,"expectedCash":184350.00,
 "countedCash":184350.00,"difference":0.00,"salesCount":38,"salesTotal":229850.00,"units":96,
 "voidedCount":1,"voidedTotal":3200.00,
 "topProducts":[{"productId":11,"productName":"Leche entera La Pradera 1 L","units":24,"total":33600.00}],
 "cashMovements":[{"id":3,"type":"CASH_OUT","amount":15000.00,"reason":"Pago a proveedor",
                   "userName":"Carla Gómez","createdAt":"2026-09-17T18:40:00Z"}],
 "mine":true,"closingNote":null}
```

`mine` le sirve al frontend para marcar "(vos)" y habilitar la anulación sin repetir la regla de permisos.

---

## 5. Productos para vender — `/api/tenant/pos/products`

Siempre sobre **una** sucursal: `branchId` del query, si no `X-Branch-Id`, si no la única accesible
(400 `BRANCH_REQUIRED` si el alcance es "todas" y hay más de una). El mostrador manda la sucursal del
turno abierto.

### `GET /products/lookup?code=7791234000012`
Código de barras exacto (normalizado con `Barcodes.normalize`). 404 si no existe o el producto está
dado de baja.

### `GET /products/search?q=&categoryId=&limit=20`
Hasta 20 resultados por nombre, marca o código (sin acentos ni mayúsculas). Sin `q` devuelve los
primeros por nombre: así el mostrador arranca con mosaicos para tocar.

```json
{"productId":42,"barcode":"7791234000029","name":"Yogur bebible frutilla Vaquita 1 L",
 "brand":"Vaquita","categoryId":5,"categoryName":"Lácteos","unit":"UNIDAD","listPrice":2100.00,
 "sellableStock":10,
 "nextLot":{"lotId":88,"lotNumber":"YV0925","expiryDate":"2026-09-25","discountPct":20.00,"unitPrice":1680.00},
 "hasRecalledStock":false,"hasExpiredStock":true,"outOfStock":false,
 "branchId":3,"branchName":"Sucursal Centro"}
```

- `sellableStock`: lotes `ACTIVE` sin vencer (SPEC §4.2). Los vencidos nunca se venden.
- `nextLot`: **el primer lote que va a salir**, con el mismo orden que usa `StockService.registerSale`
  — primero los lotes en liquidación (`discount_pct` activo) y dentro de cada grupo la rotación del
  comercio (FIFO o FEFO). `unitPrice` ya trae el descuento aplicado, así el mosaico y el carrito
  muestran el precio que se va a cobrar.
- `hasRecalledStock`: hay lotes `RECALLED` con remanente → el frontend bloquea el producto y el backend
  rechaza el cobro con 409 `PRODUCT_RECALLED`.
- `hasExpiredStock`: hay lotes vencidos pendientes de descarte (aviso para el encargado, no bloquea).

### `GET /products/categories`
Categorías con al menos un producto con stock vendible en la sucursal, para los filtros rápidos del
mostrador: `[{"id":5,"name":"Lácteos","productCount":12}]`.

---

## 6. Ventas — `/api/tenant/pos/sales`

### `POST /sales` → 201

```json
{"sessionId":14,
 "items":[{"productId":42,"quantity":2},{"productId":11,"quantity":1}],
 "payments":[{"method":"CASH","amount":10000},{"method":"DEBIT","amount":2000,"reference":"lote 003"}],
 "customerName":"Marta","customerDoc":"20123456","allowShortage":false}
```

Validaciones, en orden:

1. Turno `OPEN` que el usuario pueda operar (409 `SESSION_NOT_OPEN`, 403 si es de otro cajero y no es ADMIN).
2. Productos del comercio y activos (404 / 409 `CONFLICT`).
3. Ningún producto con stock en cuarentena → 409 `PRODUCT_RECALLED`.
4. Stock vendible suficiente → 409 `INSUFFICIENT_STOCK` con `details`, **salvo** `allowShortage:true`.
5. Σ pagos ≥ total → 400 `PAYMENT_INSUFFICIENT`.
6. El excedente no puede superar el efectivo recibido → 400 `CHANGE_NOT_ALLOWED` (el vuelto solo sale
   del efectivo).

Registro:

- Una llamada a `StockService.registerSale` **por producto, ordenadas por `productId`** (evita el
  interbloqueo entre dos cajas que venden los mismos productos en distinto orden), con
  `source = POS_GONDOLIA` y un `batchRef` `P-...` compartido por todas las líneas del ticket.
- El precio de cada unidad lo decide el núcleo según el lote consumido (`precio × (1 − pct/100)`), así
  que el total de la venta es la suma de los movimientos, no un cálculo propio del POS.
- Con `allowShortage`, el faltante entra como movimiento `SALE` con `lot_id NULL` y el núcleo abre la
  alerta `SALE_WITHOUT_STOCK`; la venta queda con `hasShortage:true`.
- Numeración: `insert ... on conflict do nothing` sobre `pos_branch_counters` y después
  `PosBranchCounterRepository.lockByBranchId` (`SELECT ... FOR UPDATE`) →
  `ticketCode` `0003-00000127` (`PosBranchCounter.ticketCode(branchId, number)`).
- Se guardan `pos_sales`, `pos_sale_items` (con los lotes consumidos en el JSONB `lots`) y
  `pos_payments`, y se actualizan los contadores del turno con la fila bloqueada
  (`PESSIMISTIC_WRITE`), para no perder ventas concurrentes de la misma caja.

Respuesta: `PosSaleDto` completo (ticket, totales, ítems con lotes y descuentos, pagos, vuelto).

### `GET /sales?sessionId=&status=&from=&to=&q=&mine=&page=&size=`
`PageResponse<PosSaleSummaryDto>`, más nuevas primero. `q` busca por código de ticket o nombre del
cliente. El empleado y el cajero solo ven las ventas de **sus** turnos.

### `GET /sales/{id}` · `PosSaleDto`.

### `GET /sales/{id}/ticket`
Datos listos para imprimir el comprobante de 80 mm: comercio y CUIT, sucursal y dirección, caja,
cajero, fecha, código, ítems (con precio de lista tachado, lote, vencimiento y `discountPct` cuando
hubo descuento), totales, pagos, vuelto y la leyenda **"Comprobante no válido como factura"**.

### `POST /sales/{id}/void`
```json
{"reason":"El cliente se arrepintió"}
```
- ADMIN: cualquier venta de su alcance.
- EMPLOYEE / CASHIER: solo ventas de **su** turno y mientras siga `OPEN` (si ya cerró, la anula el admin).
- Llama a `StockService.voidSale` (SPEC §15.1): devuelve las unidades a los mismos lotes y deja la
  auditoría en `SALE_VOID`.
- 409 `ALREADY_VOIDED` si ya estaba anulada.
- Los contadores del turno se ajustan (`salesCount`/`salesTotal` bajan, `voidedCount`/`voidedTotal` suben).

---

## 7. Estadísticas — `GET /api/tenant/pos/stats?days=30` (solo ADMIN)

Sobre el alcance de sucursales y **descontando las ventas anuladas** (SPEC §4.2):

```json
{"days":30,"from":"2026-08-18","to":"2026-09-17","salesCount":812,"salesTotal":4820500.00,
 "units":2104,"averageTicket":5936.58,"voidedCount":9,"voidedTotal":41200.00,
 "byMethod":[{"method":"CASH","label":"Efectivo","total":2890000.00,"sales":540,"sharePct":59.95}],
 "byHour":[{"hour":10,"sales":74,"total":402100.00}],
 "byCashier":[{"userId":7,"name":"Carla Gómez","sales":410,"total":2410000.00,
               "averageTicket":5878.05,"voided":4}],
 "topProducts":[{"productId":11,"productName":"Leche entera La Pradera 1 L","units":420,"total":588000.00}]}
```

`byHour` usa la hora de negocio (`America/Argentina/Buenos_Aires`), no UTC.

---

## 8. Frontend (`features/pos`)

| Ruta | Página | Qué hace |
|---|---|---|
| `/app/pos` | `PosTerminalPage` | Mostrador. Sin turno abierto muestra `OpenSessionPanel` (elegir caja libre + efectivo inicial). Con turno: franja de caja, buscador siempre enfocado (lector USB con Enter + botón de cámara con `BarcodeScanner`), mosaicos con filtros por categoría, carrito con chip de lote/vencimiento y etiqueta "-20% VTO CERCANO", `PriceTag` del total, hoja de cobro, ingresos y retiros de efectivo, y cierre con arqueo. |
| `/app/pos/sessions` | `PosSessionsPage` | "Mis turnos" / "Todos" (ADMIN), filtro por estado, reporte Z del turno elegido y sus ventas con anulación. |
| `/app/pos/registers` | `PosRegistersPage` | CRUD de cajas (solo ADMIN). |
| `/app/pos/sales/:id/ticket` | `PosTicketPage` | Fuera del AppShell: `Ticket80mm` + `window.print()`. Con `?print=1` imprime al abrir. |

Atajos del mostrador: **F2** buscar · **F4** cobrar · **F8** quitar ítem · **Esc** limpiar ·
**Enter** agrega el primer resultado (y confirma el cobro dentro de la hoja). Todo se puede hacer
también con el dedo: los mosaicos, el `QtyStepper` de 44 px y la barra fija de mobile con el total y
"Cobrar" al alcance del pulgar.

Query keys: `['pos', ...]` con el segmento de sucursal de `useBranchQueryKey` en lo que depende de la
sucursal (cajas, búsqueda, categorías, turnos, ventas). Después de cobrar o anular se invalidan
`pos`, `products`, `expirations`, `movements` y `dashboard`.

---

## 9. Decisiones

1. **El total lo calcula el núcleo.** El POS no vuelve a aplicar descuentos: suma los
   `totalAmount` de los movimientos que devuelve `registerSale`. Si mañana cambia la regla de
   liquidación (SPEC §4.2), el ticket sigue cuadrando sin tocar este módulo.
2. **Las anuladas entran y salen del arqueo.** Sumar los pagos de todas las ventas y restar después el
   neto de las anuladas da el mismo número que ignorarlas, pero deja los dos importes visibles en el
   reporte (`voidedCount`, `voidedTotal`) para que el encargado vea qué pasó en el turno.
3. **El arqueo se congela al cerrar.** Anular una venta de un turno cerrado no reescribe su
   `expectedCash`: el reporte Z es lo que se contó ese día.
4. **`details` en el 409 de stock.** El formato de error del núcleo no tiene un campo para el detalle
   por producto, así que un `@RestControllerAdvice` acotado a `com.gondolia.pos` agrega `details`
   manteniendo el resto del contrato. No se tocó `GlobalExceptionHandler`.
5. **Un lote vencido nunca se vende** (regla del núcleo). Si el producto solo tiene lotes vencidos, el
   POS lo muestra `outOfStock` con `hasExpiredStock:true`: el cajero ve "Sin stock" y el encargado sabe
   que hay mercadería para descartar.
6. **Cuarentena por recall = bloqueo duro.** Se valida en el mostrador (mosaico bloqueado) y otra vez
   en el backend, porque entre la búsqueda y el cobro puede publicarse un recall.
7. **`allowShortage` es explícito.** El cajero tiene que confirmar "vender igual" en el aviso; el
   frontend nunca lo manda solo.

---

## 10. Pruebas

`backend/src/test/java/com/gondolia/pos/`:

- `PosMoneyTest` — redondeo a dos decimales y precio con descuento del lote.
- `PosServiceIntegrationTest` (extiende `PostgresIntegrationTest`, `mvn test -Dgondolia.it=true`):
  cobro consumiendo primero el lote en liquidación y numeración por sucursal; rechazo por falta de
  stock con `details`; bloqueo por recall y validación de pagos; anulación que devuelve el stock y
  ajusta los contadores; solo el admin anula ventas de otro cajero; la fórmula del arqueo de §15.2;
  409 `REGISTER_BUSY` / `SESSION_ALREADY_OPEN`; **aislamiento entre comercios y sucursales** (el cajero
  no abre la caja de una sucursal no asignada, y otro comercio recibe 404 en caja, turno, venta y
  ticket, con listados vacíos); y que el lookup solo ve el stock de su propia sucursal.
