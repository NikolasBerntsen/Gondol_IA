# Módulo D — Avisos y alertas de recall

Backend `com.gondolia.announcements`, frontend `frontend/src/features/announcements`.
Implementa SPEC §6.7 (avisos y recalls), §5.3 (matching de recalls, que aporta la fundación) y §7 (push de
`/user/queue/security-alerts`). Complementa `docs/api-foundation.md` §12.4.

- Base `/api`. JSON camelCase, instantes ISO-8601 UTC, fechas `"2026-09-17"`.
- Sucursal: los endpoints de coincidencias respetan el encabezado `X-Branch-Id` (§3.5).
- **Avisos y recalls están "siempre incluidos"** (SPEC §14.1): ningún endpoint de este módulo lleva
  `@RequiresModule`; funcionan aunque el comercio no tenga POS ni multi-sucursal.

---

## 1. Consola de dueños (`PLATFORM_OWNER`)

Prefijo `/api/platform/announcements`. Todo el controlador es `@PreAuthorize(Roles.OWNER)`: un usuario de comercio
recibe 403 `FORBIDDEN` (además del filtro por prefijo de `SecurityConfig`).

**Regla de privacidad (SPEC §3.4.3):** ninguna respuesta dice *qué* comercios coinciden con un recall, solo **cuántos**.

### 1.1 `GET /api/platform/announcements?kind=&status=&page=0&size=20`

Más nuevos primero (`publishedAt` desc, `createdAt` desc, `id` desc). `kind` = `GENERAL | RECALL`,
`status` = `DRAFT | PUBLISHED | ARCHIVED`; ambos opcionales.

```json
{"content":[{
  "id": 2, "kind": "RECALL", "severity": "CRITICAL", "status": "PUBLISHED",
  "title": "Retiro: Yogur bebible frutilla Vaquita 1 L",
  "body": "Retiro voluntario de dos lotes por posible falla en la cadena de frío.",
  "targetBusinessTypes": [], "publishedAt": "2026-09-18T00:27:16.384513Z",
  "createdAt": "2026-09-18T00:27:16.385520Z", "createdByName": "Dueño GondolIA",
  "recipientsCount": 5, "affectedTenantsCount": 1,
  "recall": {"productName":"Yogur bebible frutilla Vaquita 1 L","brand":"Vaquita","barcode":"7791234000029",
             "lotNumbers":["yv-0925","YV0926"],"allLots":false,"expiryFrom":null,"expiryTo":null,
             "reason":"Posible corte de la cadena de frío durante el transporte.",
             "instructions":"Retirá el producto de la góndola, separalo del resto y esperá el retiro del proveedor."}
}],"page":0,"size":20,"totalElements":3,"totalPages":1}
```

`recall` es `null` en los avisos generales. `targetBusinessTypes` vacío = todos los rubros.

### 1.2 `GET /api/platform/announcements/{id}`

Agrega las estadísticas agregadas de SPEC §6.7:

```json
{"id":2, "...": "...",
 "updatedAt":"2026-09-18T00:27:16.420762Z",
 "recipientsCount":5, "readCount":0, "affectedTenantsCount":1,
 "matchesOpen":0, "matchesAcknowledged":1, "matchesResolved":1}
```

| Campo | Qué cuenta |
|---|---|
| `recipientsCount` | Usuarios que recibieron la notificación al publicarlo (se guarda en el alta). |
| `readCount` | Filas de `announcement_reads` (usuarios que abrieron el aviso). |
| `affectedTenantsCount` | Comercios distintos con al menos una coincidencia (lo mantiene `RecallMatchingService`). |
| `matchesOpen` / `matchesAcknowledged` / `matchesResolved` | Coincidencias por estado. En un aviso general los tres son 0. |

404 `NOT_FOUND` ("El aviso no existe") si el id no existe.

### 1.3 `POST /api/platform/announcements` → 201

Publica en el acto (no hay borradores en el prototipo: nace `PUBLISHED` con `publishedAt = now`).

```json
{"kind":"RECALL","severity":"CRITICAL",
 "title":"Retiro: Leche entera La Pradera 1 L",
 "body":"El fabricante retira el lote LP2409A por un posible defecto en el sellado del envase.",
 "targetBusinessTypes":null,
 "recall":{"productName":"Leche entera La Pradera 1 L","brand":"La Pradera","barcode":"7791234000012",
           "lotNumbers":["LP2409A"],"allLots":false,"expiryFrom":null,"expiryTo":null,
           "reason":"Posible defecto en el sellado del envase detectado por el fabricante.",
           "instructions":"Retirá el producto de la góndola, separalo del resto y esperá el retiro del proveedor."}}
```

Respuesta: el mismo `AnnouncementDetailDto` de §1.2 (con las estadísticas ya calculadas).

**Qué pasa al publicar**

1. `NotificationService.notifyAllActiveTenants` reparte una notificación `ANNOUNCEMENT` (severidad del aviso,
   link `/app/notices`, `referenceType = "ANNOUNCEMENT"`, `referenceId` = id del aviso, cuerpo recortado a 300
   caracteres) a **todos los usuarios de comercios `ACTIVE`**; el resultado queda en `recipientsCount`.
   - `GENERAL`: se filtra por `targetBusinessTypes` (vacío o `null` = todos los rubros).
   - `RECALL`: llega a **todos los rubros**; `targetBusinessTypes` se ignora y se guarda vacío.
2. Si es `RECALL`, se ejecuta `RecallMatchingService.matchAnnouncement(id)` (fundación, §12.4): pone los lotes
   alcanzados en `RECALLED`, crea `recall_matches`, abre la alerta `RECALL_MATCH` CRITICAL de cada sucursal,
   notifica `RECALL_ALERT` y hace push de `RecallAlertMessage` a `/user/queue/security-alerts` de cada usuario con
   acceso a esa sucursal, y actualiza `affected_tenants_count`.

**Validaciones**

| Campo | Regla | Error |
|---|---|---|
| `kind` | obligatorio (`GENERAL` / `RECALL`) | 400 `VALIDATION_ERROR` |
| `title` | obligatorio, ≤ 200 | 400 con `fieldErrors` |
| `body` | obligatorio, ≤ 4000 | 400 con `fieldErrors` |
| `severity` | opcional; por defecto `INFO` en general y `CRITICAL` en recall | — |
| `recall` | obligatorio si `kind=RECALL`; **prohibido** si es general | 400 "Los datos de retiro son solo para los recalls…" |
| `recall.productName`, `reason`, `instructions` | obligatorios | 400 con `fieldErrors` |
| `recall.barcode` | obligatorio, alfanumérico ≤ 32; si es **numérico** de 8/12/13 dígitos se valida el dígito verificador (EAN-8, UPC-A, EAN-13) | 400 "El dígito verificador del código de barras no es correcto: revisá los números" |
| `recall.lotNumbers` | hasta 50; se normalizan con `LotNumbers` (se guarda el original y la forma normalizada) y se descartan repetidos y vacíos | 400 si supera el máximo |
| `allLots` / `lotNumbers` | si `allLots=false` tiene que quedar al menos un lote válido | 400 "Indicá al menos un número de lote o marcá que el recall alcanza a todos los lotes" |
| `expiryFrom` / `expiryTo` | opcionales; `from ≤ to` | 400 "El rango de vencimiento está invertido…" |

### 1.4 `POST /api/platform/announcements/{id}/archive`

Pasa el aviso a `ARCHIVED` y devuelve el detalle. El aviso deja de verse en los comercios; **las coincidencias y la
cuarentena que ya se aplicaron no se tocan** (la mercadería sigue bloqueada hasta que el comercio la retire).
409 `CONFLICT` ("El aviso ya está archivado") si ya lo estaba.

### 1.5 `POST /api/platform/announcements/recall-preview`

Alcance estimado **sin efectos**: no crea coincidencias ni pone lotes en cuarentena. Usa la misma regla de
coincidencia que `RecallMatchingService` sobre los lotes `ACTIVE`/`RECALLED` con remanente de comercios `ACTIVE`.

```json
{"barcode":"7791234000029","lotNumbers":["l 2409 a"],"allLots":false,"expiryFrom":null,"expiryTo":null}
```
```json
{"affectedTenantsCount": 1, "affectedLotsCount": 2, "affectedUnits": 25}
```

`affectedUnits` es el remanente total de esos lotes (extra sobre SPEC, para que el dueño dimensione el retiro).
Con `allLots=false` y sin lotes válidos devuelve ceros. 400 `VALIDATION_ERROR` si el código está vacío, no es
alfanumérico o el rango está invertido.

---

## 2. Bandeja de avisos del comercio (`TENANT_ANY`, incluido el cajero)

Prefijo `/api/tenant/announcements`.

### 2.1 `GET /api/tenant/announcements?page=0&size=20`

Avisos `PUBLISHED` que le corresponden al comercio, más nuevos primero. Los archivados no se muestran.
Visibilidad: los **recalls** llegan a todos los comercios; un **aviso general** solo si su lista de rubros está vacía
o incluye el `businessType` del comercio.

```json
{"content":[{
  "id": 3, "kind": "RECALL", "severity": "CRITICAL",
  "title": "Retiro: Leche entera La Pradera 1 L",
  "body": "El fabricante retira el lote LP2409A por un posible defecto en el sellado del envase.",
  "publishedAt": "2026-09-18T00:36:02.114Z",
  "read": false, "affectsMe": true, "myMatchesCount": 1, "myOpenMatchesCount": 1,
  "recall": {"productName":"Leche entera La Pradera 1 L","brand":"La Pradera","barcode":"7791234000012",
             "lotNumbers":["LP2409A"],"allLots":false,"expiryFrom":null,"expiryTo":null,
             "reason":"…","instructions":"…"}
}],"page":0,"size":20,"totalElements":3,"totalPages":1}
```

- `read`: hay una fila en `announcement_reads` para **ese usuario**.
- `affectsMe`: el recall alcanzó al menos un lote de una sucursal **accesible por el usuario** (SPEC §3.4.6): un
  empleado o cajero de Centro no ve como "te afecta" un recall que solo pegó en Norte.
- `myMatchesCount` / `myOpenMatchesCount`: coincidencias totales y sin resolver de esas sucursales (extra sobre SPEC,
  para la línea "Tenés N lotes pendientes de retirar").

### 2.2 `GET /api/tenant/announcements/unread-count` → `{"count": 2}`

Avisos visibles menos los que el usuario ya leyó.

### 2.3 `POST /api/tenant/announcements/{id}/read` → 204

Idempotente (si ya estaba leído no hace nada; una condición de carrera entre dos pestañas se absorbe).
404 `NOT_FOUND` si el aviso no existe o **no le corresponde al comercio** (por ejemplo un aviso general dirigido a
otro rubro): así no se puede sondear qué avisos existen.

---

## 3. Seguridad alimentaria: coincidencias de recall

Prefijo `/api/tenant/recall-matches`. El alcance de sucursales sale de `BranchAccessService.scopeBranchIds()`
(encabezado `X-Branch-Id`; ausente o `all` = todas las accesibles).

### 3.1 `GET /api/tenant/recall-matches?status=ACTIVE` (`TENANT_ANY`)

`status` = `ACTIVE` (por defecto: `OPEN` + `ACKNOWLEDGED`) · `OPEN` · `ACKNOWLEDGED` · `RESOLVED` · `ALL`.
Más recientes primero.

```json
[{
  "id": 3, "announcementId": 3, "branchId": 1, "branchName": "Sucursal Centro",
  "title": "Retiro: Leche entera La Pradera 1 L", "severity": "CRITICAL",
  "reason": "Posible defecto en el sellado del envase detectado por el fabricante.",
  "instructions": "Retirá el producto de la góndola, separalo del resto y esperá el retiro del proveedor.",
  "productId": 1, "productName": "Leche entera La Pradera 1 L", "barcode": "7791234000012",
  "lotId": 1, "lotNumber": "LP2409A", "expiryDate": "2026-10-07",
  "quantityAtMatch": 24, "currentQuantity": 24,
  "status": "OPEN", "matchedAt": "2026-09-18T00:36:02.196Z",
  "acknowledgedAt": null, "acknowledgedByName": null,
  "resolvedAt": null, "resolvedByName": null, "resolution": null, "resolutionNote": null
}]
```

`quantityAtMatch` es el remanente cuando se detectó la coincidencia y `currentQuantity` el que queda hoy en
cuarentena (0 después de resolverla). Un valor de `status` inválido → 400 `VALIDATION_ERROR`.

### 3.2 `POST /api/tenant/recall-matches/{id}/acknowledge` (`TENANT_ANY`)

"Entendido": deja `ACKNOWLEDGED` con `acknowledgedAt`/`acknowledgedBy` y pasa la alerta `RECALL_MATCH` del tablero
a `ACKNOWLEDGED`. Es **idempotente** (repetirlo no cambia la marca) y no toca las ya resueltas.
Devuelve el `RecallMatchDto` actualizado.

### 3.3 `POST /api/tenant/recall-matches/{id}/resolve` (`TENANT_INVENTORY`: admin y empleado)

```json
{"resolution": "RETURNED_TO_SUPPLIER", "note": "Lo retira el distribuidor el jueves"}
```

Retira del stock **todo el remanente en cuarentena** con `StockService.adjust` (origen `MANUAL`, usuario actual) y
deja la coincidencia `RESOLVED`. Si nadie la había confirmado, resolver también la confirma. La alerta
`RECALL_MATCH` queda `RESOLVED`.

| `resolution` | Movimiento | Motivo que queda en el movimiento |
|---|---|---|
| `REMOVED_FROM_STOCK` | `RECALL_REMOVAL` | `Retiro por recall #{id}` |
| `RETURNED_TO_SUPPLIER` | `ADJUSTMENT_OUT` | `Devolución al proveedor por recall #{id}` |
| `NOT_FOUND_IN_STORE` | `ADJUSTMENT_OUT` | `Recall #{id}: no se encontró en el local` |

La `note` (≤ 300) se agrega al motivo después de ` · `. El lote queda con `quantity = 0` y **sigue en `RECALLED`**
(SPEC §4.2: un lote en cuarentena no pasa a `DEPLETED`). Si el lote ya estaba en 0 no se genera movimiento.

`resolution` es obligatoria → 400 `VALIDATION_ERROR`. Reintentar sobre una resuelta → 409 `CONFLICT`
("Esta alerta ya está resuelta"). El jefe (`TENANT_BOSS`) y el cajero reciben 403 `FORBIDDEN` (matriz §3.3).

### 3.4 Errores de alcance y aislamiento

| Caso | Respuesta |
|---|---|
| Coincidencia de otro comercio | 404 `NOT_FOUND` ("La alerta de recall no existe") |
| Coincidencia de una sucursal que el usuario no tiene asignada | 403 `BRANCH_FORBIDDEN` |
| `X-Branch-Id` de una sucursal no accesible | 403 `BRANCH_FORBIDDEN` |
| Usuario de comercio sin sucursales accesibles | listado vacío |

---

## 4. Tiempo real

Este módulo **no publica** en `/user/queue/security-alerts`: lo hace `RecallMatchingService` (fundación) cuando un
recall alcanza un lote, ya sea al publicarlo (§1.3), al cargar mercadería, al recibir una transferencia o al
rehabilitar un comercio. El frontend lo consume en `SecurityAlertHost` con el payload `RecallAlertMessage` de SPEC §7.

---

## 5. Frontend (`features/announcements`)

| Archivo | Qué es |
|---|---|
| `pages/OwnerAnnouncementsPage.tsx` | `/owner/announcements`. Adopción arriba (avisos, clientes alcanzados, destinatarios), filtro por tipo, tarjetas con franja de severidad, detalle desplegable con la ficha del recall y el desglose de coincidencias, y archivado con diálogo que explica el efecto. |
| `pages/AnnouncementFormPage.tsx` | `/owner/announcements/new`. Segmentado aviso general / recall; el general elige rubros, el recall pide producto, marca, **código de barras con validación de EAN en vivo** (dibuja el `BarcodeDigits` cuando es válido), lotes múltiples o "Todos los lotes", rango de vencimiento, motivo e instrucciones. El panel lateral calcula la **vista previa** ("N clientes afectados", lotes y unidades) y publicar exige una **confirmación explícita** que repite esos números. |
| `pages/NoticesPage.tsx` | `/app/notices`. Filtros Todos / Sin leer / Te afectan, tarjetas con severidad, píldoras "Te afecta" y "Sin leer", detalle desplegable (abrirlo marca el aviso como leído) con la ficha del retiro y link a Seguridad alimentaria. |
| `pages/RecallsPage.tsx` | `/app/recalls`. KPI (sin confirmar, confirmados sin retirar, unidades en cuarentena), filtros Para resolver / Resueltos / Todos, una tarjeta por coincidencia con sucursal, lote, vencimiento, cantidad y estado, "Entendido" y el diálogo "Retirar del lote del stock" (resolución + nota). Con `?match=<id>` resalta la fila que vino de la alerta. |
| `components/SecurityAlertHost.tsx` | Montado por `AppShell` para usuarios de comercio. Al montar pide las coincidencias `OPEN`, se suscribe a `/user/queue/security-alerts` y muestra un **diálogo rojo bloqueante** (`alertdialog`, sin cierre por fondo ni Esc) con producto, lote, vencimiento, código, sucursal, unidades, motivo y pasos numerados, con sonido y vibración. "Entendido" confirma y muestra la siguiente de la cola; "Ver detalle y retirar del stock" navega a `/app/recalls?match=<id>`. |
| `components/RecallSummary.tsx` | Ficha del producto retirado (compartida por avisos y consola de dueños). |
| `api.ts` · `types.ts` | Llamadas con los helpers de `@/api/client` y query keys (`announcementKeys`). Las coincidencias usan `useBranchQueryKey` porque son datos por sucursal; los avisos no. |

Roles en pantalla: "Retirar del stock" solo se muestra a administrador y empleado (el jefe y el cajero ven la
frase "Solo el administrador o un empleado pueden retirarlo"). Todas las pantallas tienen estado de carga
(esqueletos), error con reintento y vacío, y funcionan desde 390 px sin scroll horizontal.

---

## 6. Decisiones

1. **Publicación inmediata, sin borradores.** SPEC §6.7 solo pide crear+publicar y archivar; `DRAFT` queda en el
   enum pero el alta siempre nace `PUBLISHED`. Así el recall bloquea la mercadería en el acto, que es el punto.
2. **Los recalls ignoran el filtro por rubro.** Un retiro de seguridad alimentaria tiene que llegar a todos los
   comercios: el campo se acepta pero se guarda vacío y se documenta en la UI.
3. **Severidad por defecto según el tipo**: `CRITICAL` para recalls, `INFO` para avisos generales. En el formulario
   el selector de importancia solo aparece en los avisos generales.
4. **`affectsMe` y las coincidencias se limitan a las sucursales accesibles** (SPEC §3.4.6), no a todo el comercio.
5. **`NOT_FOUND_IN_STORE` también descuenta el stock** con `ADJUSTMENT_OUT`: si la mercadería no está en el local,
   el sistema no puede seguir contándola. Queda el motivo explícito en el movimiento.
6. **Resolver cierra la alerta del tablero** (`RECALL_MATCH` → `RESOLVED`) y confirmar la pasa a `ACKNOWLEDGED`,
   usando el `dedupe_key` `RECALL:{announcementId}:{lotId}` que define la fundación. Sin esto quedarían alertas
   CRITICAL abiertas para siempre en el módulo B.
7. **`affectedUnits` en la vista previa** (además de comercios y lotes): sigue siendo un agregado, nunca dice de
   quién es la mercadería.
8. **La vista previa reimplementa la regla de coincidencia** dentro del módulo en lugar de tocar el núcleo:
   `RecallMatchingService` no expone un método "solo contar" y no se puede modificar (SPEC §12).
9. **Paginación de la bandeja del comercio en memoria**: la visibilidad depende del rubro y de los avisos
   publicados (pocos, a nivel plataforma), así que se filtra y se pagina sobre la lista. La consola de dueños sí
   pagina en la base con una `Specification`.

---

## 7. Pruebas

`backend/src/test/java/com/gondolia/announcements/AnnouncementServiceIntegrationTest.java`
(12 pruebas contra PostgreSQL real, `mvn test -Dgondolia.it=true`, cada una se revierte):
publicación de un recall con cuarentena y agregados, filtro por rubro de los avisos generales, vista previa sin
efectos, validaciones (EAN inválido, rango invertido, lotes faltantes, recall sin datos), archivado, bandeja del
comercio con lectura por usuario y `affectsMe` por sucursal, 404 al marcar leído un aviso de otro rubro,
**aislamiento por sucursal** (el empleado solo ve Centro y recibe 403 `BRANCH_FORBIDDEN` en Norte),
**aislamiento entre comercios** (404 en las coincidencias ajenas), confirmar + resolver con `RECALL_REMOVAL` y
alerta resuelta, `RETURNED_TO_SUPPLIER` con `ADJUSTMENT_OUT`, y el alcance del encabezado `X-Branch-Id`.
