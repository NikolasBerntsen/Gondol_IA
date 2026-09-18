# API del módulo A2 — Ventas, movimientos, vencimientos, transferencias e integración POS

Paquete `com.gondolia.movements` · frontend `features/movements`. Implementa SPEC §6.4 sobre el núcleo
documentado en `docs/api-foundation.md` (§12.3 `StockService`, §3 `BranchAccessService`, §12.5 `ModuleService`).

- Base `/api`. JSON camelCase. Instantes ISO-8601 UTC, fechas `"2026-09-17"`, dinero como número.
- El `tenantId` **siempre** sale de `CurrentUser`; nunca viaja en el request.
- La sucursal va en el encabezado `X-Branch-Id` (id numérico o `all`). Las escrituras aceptan además `branchId`
  en el cuerpo y el backend lo prioriza sobre el encabezado.
- Formato de error, paginación y códigos: los del núcleo (§4 y §5 de `api-foundation.md`).

## Índice de endpoints

| Método y ruta | Rol | Módulo |
|---|---|---|
| `POST /api/tenant/sales` | ADMIN | — |
| `GET /api/tenant/sales` | ADMIN | — |
| `GET /api/tenant/sales/{batchRef}` | ADMIN | — |
| `GET /api/tenant/sales/import/template` | ADMIN | `POS_INTEGRATION` |
| `POST /api/tenant/sales/import` | ADMIN | `POS_INTEGRATION` |
| `GET /api/tenant/movements` | ADMIN | — |
| `POST /api/tenant/movements/adjustments` | ADMIN + EMPLEADO | — |
| `GET /api/tenant/expirations` | ADMIN + EMPLEADO | — |
| `GET /api/tenant/expirations/summary` | ADMIN + EMPLEADO | — |
| `POST /api/tenant/expirations/{lotId}/discard` | ADMIN + EMPLEADO | — |
| `POST /api/tenant/expirations/discard-expired` | ADMIN + EMPLEADO | — |
| `POST /api/tenant/transfers` | ADMIN | `MULTI_BRANCH` |
| `GET /api/tenant/transfers` | ADMIN | `MULTI_BRANCH` |
| `GET /api/tenant/transfers/{batchRef}` | ADMIN | `MULTI_BRANCH` |
| `GET /api/tenant/transfers/available-lots` | ADMIN | `MULTI_BRANCH` |
| `GET /api/tenant/integrations/pos` | ADMIN | `POS_INTEGRATION` |
| `POST /api/tenant/integrations/pos/{branchId}/key` | ADMIN | `POS_INTEGRATION` |
| `POST /api/tenant/integrations/pos/{branchId}/simulate` | ADMIN | `POS_INTEGRATION` |
| `POST /api/integrations/pos/sales` | API key (sin JWT) | `POS_INTEGRATION` |

Las ventas manuales, el historial de ventas, los movimientos y los vencimientos son **del núcleo**: están siempre
disponibles (SPEC §6.4). Solo la integración con el POS propio y las transferencias están detrás de un módulo.

---

## 1. Ventas

### 1.1 `POST /api/tenant/sales` — venta manual (TENANT_ADMIN)

Descuenta stock con `StockService.registerSale`, **una llamada por línea ordenadas por `productId`** (para no
bloquearse con otra venta concurrente) y todas con el mismo `batchRef`. El consumo sigue la rotación del comercio:
**lotes en liquidación primero** y dentro de cada grupo FIFO o FEFO (SPEC §4.2).

```bash
curl -X POST http://localhost:8080/api/tenant/sales \
  -H "Authorization: Bearer $TOKEN" -H "X-Branch-Id: 1" -H 'Content-Type: application/json' \
  -d '{"branchId":1,"items":[{"productId":2,"quantity":3},{"productId":1,"quantity":2}]}'
```

| Campo | Regla |
|---|---|
| `branchId` | Opcional; si falta se toma de `X-Branch-Id`. Con alcance "todas" y más de una sucursal → 400 `BRANCH_REQUIRED`. |
| `items` | 1 a 100 líneas. Un producto no puede repetirse (400 `VALIDATION_ERROR`). |
| `items[].quantity` | Entero de 1 a 100.000. |
| `items[].unitPrice` | Opcional (≥ 0). Si falta se usa `products.sale_price`. |
| `occurredAt` | Opcional; `null` = ahora. **No puede ser futura** (400 `VALIDATION_ERROR`). |

Respuesta `200`:
```json
{
  "batchRef": "S-20260917203144-69T5XK",
  "branchId": 1, "branchName": "Sucursal Centro",
  "occurredAt": "2026-09-17T23:31:44.626681Z",
  "lines": [
    {"productId": 1, "productName": "Leche entera La Pradera 1 L", "barcode": "7791234000012",
     "quantity": 2, "unitPrice": 1400.0, "discountPct": null, "total": 2800.0, "shortage": 0,
     "lots": [{"lotId": 2, "lotNumber": "LP2410B", "expiryDate": "2026-10-22", "quantity": 2,
               "unitPrice": 1400.0, "discountPct": null}]},
    {"productId": 2, "productName": "Yogur bebible frutilla Vaquita 1 L", "barcode": "7791234000029",
     "quantity": 3, "unitPrice": 2100.0, "discountPct": null, "total": 6300.0, "shortage": 3, "lots": []}
  ],
  "total": 9100.0, "units": 5, "shortageUnits": 3
}
```

- `lots` muestra de qué lote salió cada unidad, en el orden de rotación. Un lote con `discountPct` cobra
  `precio × (1 − pct/100)` y lo informa en su línea.
- `shortage` / `shortageUnits`: unidades vendidas **sin stock**. El núcleo las registra como `SALE` con
  `lotId` null y abre la alerta `SALE_WITHOUT_STOCK` (SPEC §4.2). No es un error: la venta se registra igual.

Errores: 400 `VALIDATION_ERROR`, 400 `BRANCH_REQUIRED`, 403 `BRANCH_FORBIDDEN`, 403 `FORBIDDEN` (no es admin),
404 `NOT_FOUND` (producto inexistente o de otro comercio), 409 `CONFLICT` (producto dado de baja).

### 1.2 `GET /api/tenant/sales` — historial de todas las fuentes (TENANT_ADMIN)

Agrupa `stock_movements` por `batch_ref` y **netea las anulaciones**: `units` y `total` son `SALE − SALE_VOID`
(SPEC §15.1). Una venta anulada por completo queda en 0 y se marca con `voided`.

Parámetros: `from`, `to` (fechas de negocio, inclusive), `source` (`MANUAL`, `POS_GONDOLIA`, `POS`, `CSV`,
`IMPORT`…), `q` (venta, producto o código de barras), `branchId`, `page`, `size` (≤ 100).

```bash
curl "http://localhost:8080/api/tenant/sales?source=POS_GONDOLIA&from=2026-09-01" \
  -H "Authorization: Bearer $TOKEN" -H "X-Branch-Id: all"
```

```json
{"content": [{
  "batchRef": "P-VERIF-127", "branchId": 1, "branchName": "Sucursal Centro",
  "occurredAt": "2026-09-17T23:34:12.000Z",
  "itemsCount": 1, "units": 0, "total": 0,
  "source": "POS_GONDOLIA", "sourceLabel": "POS GondolIA", "userName": "Ana Rodríguez",
  "voided": true, "voidedUnits": 2,
  "ticketCode": "0001-00000127", "posSaleId": 1
}], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
```

- **Origen**: `sourceLabel` es la etiqueta en español de `MovementLabels.source` — "Manual", "POS externo",
  "POS GondolIA", "Importación CSV", "Importación masiva", "Escáner", "Lectura de etiqueta", "Datos demo", "Sistema".
- **Ticket del POS GondolIA**: para los `batchRef` que empiezan con `P-` se busca la fila de `pos_sales` por
  `(tenant_id, batch_ref)` y se devuelven `ticketCode` y `posSaleId`; el frontend linkea a
  `/app/pos/sales/{posSaleId}/ticket`. En el resto de las fuentes ambos son `null`.
- El alcance es el de `X-Branch-Id`: un empleado nunca ve ventas de sucursales que no tiene asignadas.

### 1.3 `GET /api/tenant/sales/{batchRef}` — detalle (TENANT_ADMIN)

Cabecera neteada (`sale`) más las líneas con sus lotes, el total **bruto** y el motivo de la anulación.

```json
{"sale": { "...": "SaleSummaryDto, igual que en el listado" },
 "lines": [{"productId": 1, "productName": "Leche entera La Pradera 1 L", "quantity": 2, "unitPrice": 1400.0,
            "total": 2800.0, "shortage": 0,
            "lots": [{"lotId": 2, "lotNumber": "LP2410B", "expiryDate": "2026-10-22", "quantity": 2,
                      "unitPrice": 1400.0, "discountPct": null}]}],
 "grossTotal": 2800.0, "reason": "Error de cobro"}
```

404 `NOT_FOUND` si el `batchRef` no existe o es de otro comercio; 403 `BRANCH_FORBIDDEN` si la venta es de una
sucursal que el usuario no tiene asignada.

### 1.4 `GET /api/tenant/sales/import/template` — plantilla CSV [POS_INTEGRATION]

`text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="ventas-plantilla.csv"`:

```csv
fecha,codigo_barras,cantidad,precio_unitario
2026-09-15,7791234000012,3,1850.00
2026-09-15,7791234000029,1,2100.50
15/09/2026,7791234000036,2,980
```

### 1.5 `POST /api/tenant/sales/import` — importar ventas históricas [POS_INTEGRATION]

`multipart/form-data` con la parte `file` y el parámetro opcional `branchId`.

```bash
curl -X POST "http://localhost:8080/api/tenant/sales/import?branchId=1" \
  -H "Authorization: Bearer $TOKEN" -F "file=@ventas.csv;type=text/csv"
```

```json
{"imported": 3, "skipped": 3, "units": 6, "total": 8510.0,
 "batchRefs": ["S-20260917203412-D4DKDN", "S-20260917203412-7F82ZC"],
 "errors": [{"line": 5, "message": "Fecha inválida: «31/02/2026». Usá aaaa-mm-dd o dd/mm/aaaa"},
            {"line": 6, "message": "No encontramos ningún producto con el código 0000000000000"},
            {"line": 7, "message": "La cantidad tiene que ser mayor a cero"}]}
```

Parseo (`SalesCsvService`):
- Hasta **5 MB** y **5.000 filas** (si no, 400 `INVALID_FILE` / `VALIDATION_ERROR`); hasta 100 errores informados.
- Separador `,` o `;` detectado del encabezado; celdas entrecomilladas al estilo Excel (`""` escapa una comilla);
  se ignora el BOM. Si hay encabezado el orden de las columnas no importa.
- Fechas `aaaa-mm-dd`, `dd/mm/aaaa` o `dd-mm-aaaa` con resolución **estricta**: una fecha que no existe
  (`31/02/2026`) se informa como error de la línea en vez de ajustarse sola al 28/02.
- Importes con punto o coma decimal, con separador de miles y con `$` (`"$ 1.850,50"` → `1850.50`).
- **Una venta (`batchRef`) por cada fecha del archivo**; dentro de cada una las líneas se registran ordenadas por
  `productId`. Las ventas históricas se fechan al **mediodía** de ese día (nunca en el futuro), así el núcleo
  evalúa el vencimiento de los lotes a la fecha de la venta.
- No es transaccional: una fila rechazada (código desconocido, producto de baja) se informa y las demás siguen.

---

## 2. Movimientos

### 2.1 `GET /api/tenant/movements` (TENANT_ADMIN)

Historial de `stock_movements` del alcance, más reciente primero.
Parámetros: `productId`, `type`, `source`, `q` (producto, código, lote o referencia), `from`, `to`, `branchId`,
`page`, `size`.

```json
{"content": [{
  "id": 57, "branchId": 1, "branchName": "Sucursal Centro",
  "productId": 1, "productName": "Leche entera La Pradera 1 L", "barcode": "7791234000012",
  "lotId": 15, "lotNumber": "LP-VENC2", "expiryDate": "2026-09-11",
  "type": "WASTE_EXPIRED", "typeLabel": "Baja por vencimiento",
  "quantity": 5, "signedQuantity": -5,
  "unitPrice": 700.0, "discountPct": null, "totalAmount": 3500.0,
  "source": "MANUAL", "sourceLabel": "Manual",
  "batchRef": "A-20260917203553-FEVVDV", "reason": "Vencido en góndola",
  "userName": "Ana Rodríguez", "occurredAt": "2026-09-17T23:35:53.543677Z"
}], "page": 0, "size": 20, "totalElements": 58, "totalPages": 3}
```

`signedQuantity` es la cantidad con signo según `MovementType.isInbound()`: positiva para `ENTRY`, `SALE_VOID`,
`ADJUSTMENT_IN` y `TRANSFER_IN`; negativa para el resto. `typeLabel` y `sourceLabel` son las etiquetas en español
de `MovementLabels` (el frontend las espeja en `MOVEMENT_TYPE_LABELS_EXT` / `MOVEMENT_SOURCE_LABELS_EXT`).

En `SALE` los importes son el precio cobrado; en el resto, el costo unitario y el valor a costo (núcleo §12.3).

### 2.2 `POST /api/tenant/movements/adjustments` (TENANT_ADMIN + TENANT_EMPLOYEE)

```bash
curl -X POST http://localhost:8080/api/tenant/movements/adjustments \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"lotId":2,"type":"WASTE_DAMAGED","quantity":1,"reason":"Envase roto"}'
```

| Rol | Tipos permitidos |
|---|---|
| `TENANT_ADMIN` | `ADJUSTMENT_IN`, `ADJUSTMENT_OUT`, `WASTE_EXPIRED`, `WASTE_DAMAGED`, `RECALL_REMOVAL` |
| `TENANT_EMPLOYEE` | solo `WASTE_EXPIRED` y `WASTE_DAMAGED`, y solo en sus sucursales (SPEC §3.3) |

Devuelve el `MovementDto` del ajuste. Valida en este orden: tipo válido (400 `VALIDATION_ERROR` si no es un tipo de
ajuste) → rol (403 `FORBIDDEN`) → lote del comercio (404 `NOT_FOUND`) → acceso a la sucursal del lote
(403 `BRANCH_FORBIDDEN`) → `StockService.adjust` (409 `INSUFFICIENT_STOCK` si la salida supera el remanente).

Estados que aplica el núcleo: a 0 → `DEPLETED`; por `WASTE_EXPIRED` → `EXPIRED_DISCARDED`; `RECALLED` sigue
`RECALLED`; `ADJUSTMENT_IN` sobre un lote `DEPLETED` o `EXPIRED_DISCARDED` lo reactiva. `batchRef` = `A-...`.

---

## 3. Vencimientos (TENANT_ADMIN + TENANT_EMPLOYEE)

Buckets de SPEC §4.2 con los umbrales de `tenant_settings` (`expiry_critical_days`, `expiry_warning_days`) y un
horizonte fijo de 30 días para `UPCOMING`:

| Bucket | Etiqueta UI | Días restantes |
|---|---|---|
| `EXPIRED` | Vencido | < 0 |
| `CRITICAL` | Crítico | 0 … `criticalDays` |
| `WARNING` | Por vencer | … `warningDays` |
| `UPCOMING` | Próximo | … 30 |

### 3.1 `GET /api/tenant/expirations`

Parámetros: `bucket` (`ALL` por defecto), `q`, `branchId`, `page`, `size`. Orden: por vencimiento.

```json
{"content": [{
  "lotId": 6, "branchId": 2, "branchName": "Sucursal Norte",
  "productId": 2, "productName": "Yogur bebible frutilla Vaquita 1 L", "barcode": "7791234000029",
  "categoryName": "Lácteos", "lotNumber": "YV0926", "expiryDate": "2026-09-29", "daysLeft": 12,
  "quantity": 10, "receivedAt": "2026-09-10T12:00:00Z", "rotationRank": 1,
  "costValue": 14000.0, "saleValue": 21000.0, "bucket": "WARNING", "discountPct": null, "status": "ACTIVE"
}], "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
```

`rotationRank` es la posición del lote en la cola de salida **de su producto en su sucursal** (1 = el próximo que
se vende; `null` si no es vendible, p. ej. vencido o en cuarentena). Se calcula con el mismo orden que
`StockService`: `(discount_pct inactivo) ASC, <FIFO|FEFO>`.

### 3.2 `GET /api/tenant/expirations/summary`

```json
{"expired":  {"lots": 0, "units": 0,  "costValue": 0,     "saleValue": 0},
 "critical": {"lots": 0, "units": 0,  "costValue": 0,     "saleValue": 0},
 "warning":  {"lots": 1, "units": 10, "costValue": 14000, "saleValue": 21000},
 "upcoming": {"lots": 1, "units": 20, "costValue": 19000, "saleValue": 28000},
 "criticalDays": 5, "warningDays": 15, "upcomingDays": 30, "asOf": "2026-09-17"}
```

### 3.3 `POST /api/tenant/expirations/{lotId}/discard`

Cuerpo `{"quantity": 5, "reason": "Vencido en góndola"}`; ambos opcionales. Sin `quantity` descarta **todo el
remanente**; sin `reason` usa "Descarte por vencimiento". Registra un `WASTE_EXPIRED` y devuelve su `MovementDto`.

409 `INSUFFICIENT_STOCK` si el lote ya está en cero o si la cantidad supera el remanente; 404 `NOT_FOUND` si el
lote es de otro comercio; 403 `BRANCH_FORBIDDEN` si es de una sucursal ajena al usuario.

### 3.4 `POST /api/tenant/expirations/discard-expired` — descarte masivo

Cuerpo opcional `{"branchId": 1, "reason": "..."}`. Da de baja **todos** los lotes `ACTIVE` con `expiry_date < hoy`
y remanente > 0 del alcance (una sucursal o todas las accesibles).

```json
{"lots": 2, "units": 10, "costValue": 7000.0, "failed": 0}
```

Cada lote se descarta con su propia llamada a `StockService.adjust`: si uno falla (por concurrencia, por ejemplo)
suma a `failed` y los demás siguen. Un empleado solo alcanza sus sucursales.

---

## 4. Transferencias [MULTI_BRANCH] (TENANT_ADMIN)

### 4.1 `GET /api/tenant/transfers/available-lots?branchId=1&q=&page=&size=`

Lotes **vendibles** de la sucursal de origen en el orden en que se venderían. Los vencidos y los `RECALLED` no
aparecen: no se pueden transferir.

```json
{"content": [{"lotId": 7, "branchId": 1, "branchName": "Sucursal Centro", "productId": 3,
  "productName": "Galletitas de agua Crocantes 200 g", "barcode": "7791234000036",
  "lotNumber": "GC2603", "expiryDate": "2027-01-15", "daysLeft": 120, "quantity": 27,
  "receivedAt": "2026-08-18T12:00:00Z", "rotationRank": 1, "costPrice": 650.0, "discountPct": null}],
 "page": 0, "size": 50, "totalElements": 3, "totalPages": 1}
```

### 4.2 `POST /api/tenant/transfers`

```bash
curl -X POST http://localhost:8080/api/tenant/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"fromBranchId":1,"toBranchId":2,"items":[{"lotId":7,"quantity":5}],"note":"Reparto semanal"}'
```

```json
{"batchRef": "T-20260917203321-ETJVCH",
 "fromBranchId": 1, "fromBranchName": "Sucursal Centro",
 "toBranchId": 2, "toBranchName": "Sucursal Norte",
 "occurredAt": "2026-09-17T23:33:21.278709Z",
 "items": [{"lotId": 7, "destinationLotId": 13, "productId": 3,
            "productName": "Galletitas de agua Crocantes 200 g", "barcode": "7791234000036",
            "lotNumber": "GC2603", "expiryDate": "2027-01-15", "quantity": 5,
            "costPrice": 650.0, "quarantined": false}],
 "units": 5, "costValue": 3250.0, "note": "Reparto semanal", "userName": "Ana Rodríguez", "recalls": []}
```

El lote destino conserva número, vencimiento, costo, proveedor y **el `received_at` original** (así no pierde
antigüedad para FIFO) y guarda `origin_lot_id`. `TRANSFER_OUT` y `TRANSFER_IN` comparten el `batchRef` `T-...`.
Cada lote destino pasa por el chequeo de recall: si coincide queda en cuarentena (`quarantined: true`) y el aviso
aparece en `recalls`.

Errores: 400 `VALIDATION_ERROR` (origen = destino, lote repetido, lote que no es de la sucursal de origen),
404 `NOT_FOUND` (sucursal o lote de otro comercio), 403 `BRANCH_FORBIDDEN`, 403 `MODULE_DISABLED`,
409 `LOT_NOT_TRANSFERABLE` (lote vencido o en cuarentena), 409 `INSUFFICIENT_STOCK`.

### 4.3 `GET /api/tenant/transfers` y `GET /api/tenant/transfers/{batchRef}`

El listado (`from`, `to`, `branchId`, `page`, `size`) trae las transferencias en las que participa alguna sucursal
del alcance, como **origen o destino**:

```json
{"content": [{"batchRef": "T-20260917203321-ETJVCH",
  "fromBranchId": 1, "fromBranchName": "Sucursal Centro",
  "toBranchId": 2, "toBranchName": "Sucursal Norte",
  "occurredAt": "2026-09-17T23:33:21.278709Z",
  "itemsCount": 1, "units": 5, "costValue": 3250.0, "userName": "Ana Rodríguez", "note": "Reparto semanal"}],
 "page": 0, "size": 20, "totalElements": 1, "totalPages": 1}
```

El detalle devuelve el mismo `TransferDto` del alta.

---

## 5. Integración con el POS propio [POS_INTEGRATION]

### 5.1 `GET /api/tenant/integrations/pos` (TENANT_ADMIN)

Una fila por sucursal accesible, con la actividad de las últimas 24 h.

```json
[{"branchId": 1, "branchName": "Sucursal Centro", "branchCode": "CEN", "configured": true,
  "prefix": "gk_Wo5Syqv", "createdAt": "2026-09-17T20:09:05.047388Z",
  "lastSaleAt": "2026-09-17T20:11:21.503146Z", "salesLast24h": 12, "unitsLast24h": 634},
 {"branchId": 2, "branchName": "Sucursal Norte", "branchCode": "NOR", "configured": false,
  "prefix": null, "createdAt": null, "lastSaleAt": null, "salesLast24h": 0, "unitsLast24h": 0}]
```

### 5.2 `POST /api/tenant/integrations/pos/{branchId}/key` (TENANT_ADMIN)

Genera la API key de la sucursal con `ApiKeyService`. **La clave en claro se devuelve una sola vez**: después solo
queda el prefijo (en `branches` se guardan `pos_api_key_hash` SHA-256 y `pos_api_key_prefix`). Volver a generarla
invalida la anterior de inmediato.

```json
{"branchId": 1, "branchName": "Sucursal Centro", "apiKey": "gk_Nskrw174doN…",
 "prefix": "gk_Nskrw17", "createdAt": "2026-09-17T23:33:48.000Z"}
```

### 5.3 `POST /api/tenant/integrations/pos/{branchId}/simulate` (TENANT_ADMIN)

Cuerpo `{"sales": 10}` (1 a 50). Genera ventas aleatorias realistas **por el mismo camino que el webhook**, para
probar la integración sin conectar el POS.

```json
{"branchId": 1, "branchName": "Sucursal Centro", "sales": 5, "lines": 7, "units": 16,
 "total": 19600.0, "shortages": 0,
 "batchRefs": ["S-20260917203412-6T5UXB", "…"]}
```

### 5.4 `POST /api/integrations/pos/sales` — webhook público

Se autentica con el encabezado **`X-API-Key`** de la sucursal, **sin JWT**.

```bash
curl -X POST http://localhost:8080/api/integrations/pos/sales \
  -H "X-API-Key: gk_…" -H 'Content-Type: application/json' \
  -d '{"externalId":"TCK-7001","items":[{"barcode":"7791234000012","quantity":2},
                                        {"barcode":"0000000000000","quantity":1}]}'
```

```json
{"batchRef": "S-EXT-TCK-7001", "processed": 2, "units": 101, "total": 210700.0,
 "unknownBarcodes": ["0000000000000"],
 "shortages": [{"barcode": "7791234000029", "productName": "Yogur bebible frutilla Vaquita 1 L", "quantity": 99}]}
```

- `externalId` (opcional) arma el `batchRef` `S-EXT-{externalId}`; sin él se genera un `S-...`.
- `occurredAt` (opcional) fecha la venta; el núcleo evalúa el vencimiento de los lotes a esa fecha.
- Los códigos que no existen en el catálogo se informan en `unknownBarcodes` y **no frenan** el resto del ticket.
- Las líneas sin stock suficiente se registran igual (faltante + alerta `SALE_WITHOUT_STOCK`) y se informan en
  `shortages`.
- 401 `INVALID_API_KEY` si la key falta, no existe, la sucursal está desactivada o el comercio no está `ACTIVE`.
- **403 `MODULE_DISABLED`** si el comercio no tiene `POS_INTEGRATION`. Como la ruta no tiene usuario autenticado,
  el interceptor de `@RequiresModule` no puede resolver el comercio: el controlador lo chequea **a mano** con
  `moduleService.require(branch.tenantId(), POS_INTEGRATION)` después de `ApiKeyService.resolveBranch`
  (`api-foundation.md` §12.5).

---

## 6. Decisiones de diseño

1. **El neteo de `SALE_VOID` se hace en la consulta, no en memoria.** El historial agrupa por `batch_ref` con
   `sum(...) filter (where type = 'SALE') − sum(...) filter (where type = 'SALE_VOID')`, así la paginación y los
   totales son correctos sin traer todos los movimientos. El filtro por fecha, origen y texto se aplica en el
   `HAVING` sobre los movimientos `SALE` (los de la venta original), no sobre las anulaciones.

2. **El origen de una venta es el de su primer movimiento `SALE`** (`array_agg(... order by id)[1]`): una venta
   tiene un solo origen y así una anulación posterior no lo cambia.

3. **El `ticketCode` se resuelve con una sola consulta extra por página**, y solo para los `batchRef` que empiezan
   con `P-` (los del POS GondolIA). Evita un join con `pos_sales` en la consulta agrupada, que es del módulo H.

4. **Fechas del CSV con `ResolverStyle.STRICT`.** Con el resolutor SMART que trae `DateTimeFormatter` por defecto,
   `31/02/2026` se convierte en silencio al 28/02 y la venta se importa con la fecha equivocada. En estricto se
   informa como error de la línea. Requiere los patrones con `uuuu` en lugar de `yyyy`.

5. **El descarte masivo no es una sola transacción.** Cada lote se da de baja con su propia llamada a
   `StockService.adjust`, que valida y rechaza con `ApiException` sin marcar la transacción del llamador como
   rollback-only (`api-foundation.md` §12.3). Así un lote que cambió entre la consulta y el descarte no anula el
   trabajo de los demás; se cuenta en `failed`.

6. **Las líneas de una venta se registran ordenadas por `productId`** (venta manual, CSV, webhook y simulador),
   como pide el núcleo, para que dos ventas concurrentes del mismo par de productos no se bloqueen entre sí. La
   respuesta de la venta manual, en cambio, devuelve las líneas **en el orden en que las cargó el usuario**.

7. **Las ventas históricas del CSV se fechan al mediodía** del día del archivo (y nunca en el futuro): así quedan
   dentro del día de negocio sin importar la zona horaria y el núcleo elige los lotes que eran vendibles ese día.

8. **`MovementLabels` es la única fuente de las etiquetas en español** y el frontend la espeja en
   `features/movements/types.ts`. El tipo compartido `MovementSource` de `@/api/types` todavía no incluye
   `POS_GONDOLIA` ni `IMPORT`, y `MovementType` no incluye `SALE_VOID`: el módulo los agrega en sus propios tipos
   (`MovementSourceExt`, `MovementTypeExt`) **sin tocar la fundación**. Conviene unificarlo al integrar.

---

## 7. Pantallas (`frontend/src/features/movements`)

| Ruta | Pantalla | Qué hace |
|---|---|---|
| `/app/sales` | `SalesPage` | Pestañas "Registrar venta" (buscador con escáner, carrito con cantidad y precio editable, `BranchPicker`, total en `PriceTag`) e "Historial" (filtros por texto, origen y fechas; cajón de detalle con los lotes y el link al ticket). |
| `/app/movements` | `MovementsPage` | Historial con filtros de tipo, origen, texto y fechas; diálogo de ajuste (producto → lote → tipo → cantidad → motivo) limitado por rol. |
| `/app/expirations` | `ExpirationsPage` | Buckets con contadores y KPIs, tabla con `ExpiryChip` + `LotRankChip` + columna de sucursal, descarte por lote con confirmación y "descartar todos los vencidos". |
| `/app/transfers` | `TransfersPage` | Origen y destino, lotes con su remanente y contador por lote, resumen con nota, e historial. |
| `/app/integrations` | `IntegrationsPage` | API key por sucursal (se muestra una sola vez, con copiar y ejemplo `curl`), importación CSV con plantilla y reporte, y simulador. |

Convenciones seguidas: query keys por sucursal con `useBranchQueryKey`, columna "Sucursal" con `useBranchColumn`,
escrituras con `useWriteBranch` + `BranchPicker` y `branchId` en el cuerpo, estados de carga / error / vacío en las
cinco pantallas, y componentes de Góndola UI sin estilos ad hoc.
