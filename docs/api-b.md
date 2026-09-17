# API del módulo B — Inicio, estadísticas, alertas e IA

Endpoints de `com.gondolia.analytics`, `com.gondolia.alerts` y `com.gondolia.insights` (SPEC §6.5, §8).
Complementa `docs/api-foundation.md`: el formato de error, la paginación, el encabezado `X-Branch-Id` y la
autenticación son los del núcleo.

- Base `/api`. JSON camelCase. Fechas `"2026-09-17"`, instantes ISO-8601 UTC, dinero como número.
- **Roles**: leer = `TENANT_DASHBOARD` (jefe y administrador). **Decidir** (gestionar alertas, aceptar o descartar
  recomendaciones, recalcular la IA) = `TENANT_ADMIN`. El empleado y el cajero reciben 403 `FORBIDDEN` en todo el
  módulo.
- **Alcance de sucursales** (SPEC §3.5): cada lectura opera sobre las sucursales del encabezado `X-Branch-Id`
  (una, o todas las accesibles si falta o es `all`). Toda fila por sucursal trae `branchId` y `branchName`.
  El `tenantId` **siempre** sale del token. Una sucursal de otro comercio da 403 `BRANCH_FORBIDDEN`; un id de otro
  comercio en la ruta da 404 `NOT_FOUND`.
- **Ventas netas**: todas las métricas de venta de este módulo descuentan los movimientos `SALE_VOID`
  (anulaciones del POS, SPEC §15.1). Los faltantes (`SALE` con `lotId` null) **cuentan como venta** en la
  facturación y además se informan aparte como "ventas perdidas".

---

## 1. Inicio — `/api/tenant/dashboard`

### 1.1 `GET /summary`

```json
{"scope":"ALL","branchCount":2,"productsCount":10,"expiringSoonCount":11,"expiredCount":1,
 "lowStockCount":3,"outOfStockCount":1,"inventoryCostValue":910150.00,"inventorySaleValue":1542700.00,
 "openAlertsCount":24,"pendingRecommendationsCount":21,"openRecallMatchesCount":0,
 "todaySalesUnits":46,"todaySalesAmount":155670.00,"lastAiRunAt":"2026-09-17T22:32:51.279031Z",
 "today":"2026-09-17","asOf":"2026-09-17T22:34:10.231Z"}
```

| Campo | Cómo se calcula |
|---|---|
| `scope` | `"ALL"` si el encabezado no pidió una sucursal puntual, `"BRANCH"` si sí. |
| `productsCount` | Productos activos **del comercio**: el catálogo es por tenant (SPEC §1.1), no por sucursal. |
| `expiringSoonCount` | Lotes `ACTIVE` con remanente que vencen entre hoy y hoy + `expiryWarningDays`. |
| `expiredCount` | Lotes `ACTIVE` con remanente ya vencidos (hay que descartarlos). |
| `lowStockCount` / `outOfStockCount` | Combinaciones producto × sucursal con stock vendible ≤ `min_stock` / = 0. El segundo es un subconjunto del primero. |
| `inventoryCostValue` / `inventorySaleValue` | Stock **físico** (`ACTIVE` + `RECALLED`) × `lots.cost_price` (o el costo del producto) / × `products.sale_price`. |
| `todaySalesUnits` / `todaySalesAmount` | Ventas netas del día de negocio (`America/Argentina/Buenos_Aires`). |
| `lastAiRunAt` | Fin del último `ai_runs` en `OK` del alcance. |

Sin sucursales accesibles devuelve todo en cero (no falla).

### 1.2 `GET /sales-stock-trend?days=30`

`days` entre 1 y 180 (400 `VALIDATION_ERROR` fuera de rango). Una fila **por día**, incluso sin movimientos:

```json
[{"date":"2026-08-19","salesUnits":27,"salesAmount":95500.00,"stockUnits":1620}]
```

`stockUnits` es el stock físico al cierre de cada día, reconstruido hacia atrás desde el stock actual con los
movimientos del período.

### 1.3 `GET /branch-comparison?days=30`

Una fila por sucursal del alcance (útil con "Todas las sucursales"):

```json
[{"branchId":1,"branchName":"Sucursal Centro","salesUnits":896,"salesAmount":2967450.00,
  "inventoryCostValue":612300.00,"expiringSoonCount":7,"lowStockCount":1,"wasteValue":39400.00,
  "openAlertsCount":15}]
```

### 1.4 `GET /upcoming-expirations?limit=8`

Lotes vendibles que vencen dentro de los próximos 30 días, del más cercano al más lejano:

```json
[{"lotId":24,"branchId":1,"branchName":"Sucursal Centro","productId":6,
  "productName":"Queso cremoso La Pradera 500 g","lotNumber":"B-6-R","expiryDate":"2026-09-19",
  "daysLeft":2,"quantity":26,"bucket":"CRITICAL"}]
```

`bucket`: `EXPIRED | CRITICAL | WARNING | UPCOMING | OK` según `tenant_settings` (SPEC §4.2).

### 1.5 `GET /reorder?limit=8`

Una fila **por producto y sucursal** con stock vendible por debajo del mínimo:

```json
[{"productId":5,"productName":"Papas fritas Crocantes 150 g","brand":"Crocantes","branchId":2,
  "branchName":"Sucursal Norte","sellableStock":0,"minStock":18,"suggestedQuantity":58,
  "status":"SIN_STOCK","predictedStockoutDate":null}]
```

- `status`: `SIN_STOCK` (0), `CRITICO` (≤ 50 % del mínimo), `BAJO` (≤ mínimo).
- `suggestedQuantity`: cubrir `targetCoverageDays + leadTimeDays` con la demanda de los últimos 28 días, menos el
  stock actual; nunca menos que lo que falta para llegar al mínimo.
- `predictedStockoutDate`: el del análisis de IA de ese producto en esa sucursal, si existe.

---

## 2. Estadísticas — `GET /api/tenant/statistics/overview?days=90`

`days` entre 7 y 365. Devuelve cuatro bloques: `sales`, `products`, `losses` y `ai`.

```json
{"scope":"BRANCH","branchCount":1,"days":30,"from":"2026-08-19","to":"2026-09-17",
 "sales":{"units":896,"amount":2967450.0,"cost":1738000.0,"margin":1229450.0,"marginPct":41.4,
   "avgDailyUnits":29.9,"avgDailyAmount":98915.0,"bestDayAmount":172500.0,"bestDay":"2026-09-13",
   "byDay":[{"date":"2026-08-19","units":27,"amount":95500.0}],
   "byWeek":[{"weekStart":"2026-08-17","label":"lun 17/08","units":159,"amount":496200.0}],
   "byCategory":[{"categoryId":2,"categoryName":"Almacén","units":608,"amount":1984050.0,"sharePct":66.9}],
   "byBranch":[{"branchId":1,"branchName":"Sucursal Centro","units":896,"amount":2967450.0,"sharePct":100.0}],
   "bySource":[{"source":"POS_GONDOLIA","units":896,"amount":2967450.0}]},
 "products":{
   "top":[{"productId":9,"productName":"Yerba mate Serrana 1 kg","brand":"Serrana","categoryName":"Almacén",
           "units":191,"amount":1184200.0,"margin":458400.0,"abcClass":"A"}],
   "bottom":[{"productId":3,"productName":"Galletitas de agua Crocantes 200 g","brand":"Crocantes",
              "categoryName":"Almacén","units":0,"amount":0.0,"margin":0.0,"abcClass":"C"}],
   "abc":[{"abcClass":"A","products":3,"units":537,"amount":2306800.0,"sharePct":77.7}],
   "rotation":[{"productId":4,"productName":"Gaseosa cola Refrescante 2,25 L","units":226,
                "avgDailySales":7.533,"sellableStock":40,"daysOfCover":5.3,"turnoverRatio":5.65,
                "pattern":"ESTACIONAL_SEMANAL"}],
   "withoutSales":3},
 "losses":{"wasteUnits":28,"wasteValue":39400.0,
   "wasteByMonth":[{"month":"2026-08","units":21,"value":34500.0}],
   "wasteByReason":[{"type":"WASTE_DAMAGED","units":21,"value":34500.0}],
   "lostSales":{"units":12,"estimatedAmount":74400.0,"events":4},
   "expiredLots":0,"expiredUnits":0,"expiredValue":0.0,"expiringRiskValue":381600.0},
 "ai":{"recommendations":{"total":18,"pending":14,"accepted":3,"discarded":1,"expired":0,
         "acceptanceRatePct":75.0,"expectedImpactAccepted":681355.29},
       "recoveredSales":{"units":0,"amount":0.0,"costValue":0.0,"avgDiscountPct":0.0,"lots":0},
       "lastRunAt":"2026-09-17T22:34:50.141515Z","productsWithInsights":10}}
```

Definiciones que conviene tener a mano:

| Métrica | Definición |
|---|---|
| `sales.cost` / `margin` / `marginPct` | Costo de lo vendido (costo del lote, o del producto si el lote no tiene) y margen sobre la venta neta. |
| `products.abc` | Clases por facturación acumulada del período: A hasta el 80 %, B hasta el 95 %, C el resto. |
| `rotation.daysOfCover` | Stock vendible ÷ venta diaria promedio del período. `null` si no vendió nada. |
| `rotation.turnoverRatio` | Unidades vendidas ÷ stock vendible actual (cuántas veces se dio vuelta el stock). |
| `losses.lostSales` | Movimientos `SALE` sin lote (faltantes): unidades, valor al precio de lista y cantidad de eventos. |
| `losses.expiringRiskValue` | Valor a costo del stock que vence dentro del aviso de vencimiento. |
| `ai.recommendations.acceptanceRatePct` | `accepted ÷ (accepted + discarded)` × 100 (las `PENDING` y `EXPIRED` no cuentan). |
| `ai.recoveredSales` | Ventas de lotes con `discount_pct` activo: lo que se recuperó gracias a los descuentos por vencimiento. |

---

## 3. Alertas — `/api/tenant/alerts`

### 3.1 `GET /api/tenant/alerts`

Parámetros: `status` (`OPEN` por defecto; `ACKNOWLEDGED`, `RESOLVED`, `DISMISSED` o `ALL`), `type`, `severity`,
`productId`, `q` (título o mensaje), `page`, `size` (≤ 100). Un valor inválido → 400 `VALIDATION_ERROR`.

```json
{"content":[{"id":17,"branchId":2,"branchName":"Sucursal Norte","type":"OUT_OF_STOCK","severity":"CRITICAL",
  "status":"OPEN","productId":5,"productName":"Papas fritas Crocantes 150 g","lotId":null,"lotNumber":null,
  "announcementId":null,"title":"Sin stock: Papas fritas Crocantes 150 g",
  "message":"Sucursal Norte: no queda stock vendible de Papas fritas Crocantes 150 g (el mínimo es 18 u.). Reponelo.",
  "createdAt":"2026-09-17T22:32:49.0Z","updatedAt":"2026-09-17T22:32:49.0Z","handledByName":null,
  "resolvedAt":null}],
 "page":0,"size":20,"totalElements":24,"totalPages":2}
```

Ordena por severidad (crítica primero) y después por fecha. Incluye las alertas sin sucursal (`branchId` null).

### 3.2 `GET /api/tenant/alerts/counts`

```json
{"open":24,"acknowledged":0,"resolved":1,"dismissed":0,"critical":7,"warning":13,"info":4,
 "byType":{"EXPIRING_SOON":11,"LOW_STOCK":2,"OUT_OF_STOCK":1,"STOCKOUT_PREDICTED":6,"ANOMALY":4}}
```

`critical`/`warning`/`info` y `byType` cuentan solo las que siguen abiertas (`OPEN` + `ACKNOWLEDGED`).

### 3.3 Cambios de estado (TENANT_ADMIN)

`POST /{id}/acknowledge` · `POST /{id}/resolve` · `POST /{id}/dismiss` → devuelven la alerta actualizada.
Guardan `handled_by` y, salvo en "vista", `resolved_at`. Alerta de otro comercio → 404; de una sucursal sin acceso
→ 403 `BRANCH_FORBIDDEN`.

### 3.4 Motor de alertas

`AlertEngine` reconcilia el estado deseado de cada sucursal:

- **Cuándo corre**: al arrancar la aplicación, cada 10 minutos, y ante `StockChangedEvent`.
- **Ráfagas**: los eventos de stock llegan de a cientos (una importación, el seeder, una venta múltiple). El
  listener **agrupa por sucursal** y recién evalúa cuando pasaron 5 s sin novedades, o 60 s desde el primer evento.
  Así una importación de 10.000 filas produce **una sola** pasada por sucursal.
- **Tipos que administra**: `EXPIRED`, `EXPIRING_SOON`, `LOW_STOCK`, `OUT_OF_STOCK`, `STOCKOUT_PREDICTED`,
  `ANOMALY`. `RECALL_MATCH` (módulo D) y `SALE_WITHOUT_STOCK` (núcleo) solo se cierran a mano.
- **Idempotencia**: cada alerta lleva un `dedupe_key` que incluye la sucursal (p. ej. `LOW_STOCK:{branchId}:{productId}`,
  `EXPIRING_SOON:{branchId}:{lotId}`) y se inserta con `OpenAlertWriter.openIfAbsent`. Lo que ya no aplica pasa a
  `RESOLVED` solo.
- **Descartes**: una alerta `DISMISSED` no se vuelve a abrir por 7 días aunque la condición siga.
- **Notificaciones**: al abrirse una `CRITICAL` nueva se notifica a los administradores; las de vencimiento
  (`EXPIRED`, `EXPIRING_SOON`) también a los empleados de esa sucursal (`notifyBranchUsers`, link `/app/alerts`).

---

## 4. Inteligencia IA — `/api/tenant/insights`

### 4.1 `GET /summary`

```json
{"scope":"ALL","branchCount":2,"productsAnalyzed":20,"lastRunAt":"2026-09-17T22:32:51.279031Z",
 "running":false,"aiAvailable":true,
 "patternCounts":{"ALTA_ROTACION_ESTABLE":4,"ESTACIONAL_SEMANAL":4,"EN_CRECIMIENTO":2,"EN_DECLIVE":2,
                  "BAJA_ROTACION":2,"DATOS_INSUFICIENTES":6},
 "abcCounts":{"A":8,"B":4,"C":8},
 "pendingRecommendations":22,
 "recommendationsByType":{"DISCOUNT":11,"REORDER":6,"REVIEW_ANOMALY":4,"REMOVE_EXPIRED":1},
 "atRiskValue":522350.00,"predictedStockouts7d":9,"anomalies7d":4,
 "topRisks":[{"branchId":1,"branchName":"Sucursal Centro","productId":6,
   "productName":"Queso cremoso La Pradera 500 g","lotId":14,"lotNumber":"B-6-1","expiryDate":"2026-09-20",
   "daysToExpiry":3,"quantity":40,"unitsAtRisk":28,"riskLevel":"HIGH","recommendedDiscountPct":40,
   "valueAtRisk":89600.00}],
 "runs":[ /* igual que /runs/latest */ ]}
```

`aiAvailable` es el `/health` del servicio de IA, con 30 s de caché. `running` es `true` mientras haya un
`ai_runs` en `RUNNING` en el alcance (el frontend refresca cada 4 s mientras dure).

### 4.2 `GET /products?pattern=&abc=&q=&page=&size=`

`PageResponse<ProductInsightRow>`: una fila **por producto y sucursal**, ordenada por quiebre previsto más cercano.

```json
{"productId":6,"productName":"Queso cremoso La Pradera 500 g","brand":"La Pradera","categoryName":"Lácteos",
 "branchId":1,"branchName":"Sucursal Centro","pattern":"ALTA_ROTACION_ESTABLE",
 "patternDescription":"Venta estable de 4 u./día","abcClass":"A","xyzClass":"X","avgDailySales":4.0,
 "trendPct":0.0,"daysOfCover":17.5,"predictedStockoutDate":"2026-09-21","reorderPoint":10,"safetyStock":3,
 "suggestedOrderQty":38,"sellableStock":66,"minStock":10,"anomaliesCount":0,"lotsAtRisk":2,"unitsAtRisk":54,
 "updatedAt":"2026-09-17T22:32:50.4Z"}
```

`pattern` inválido → 400; `abc` fuera de A/B/C → 400. `ALL` (o vacío) desactiva el filtro.

### 4.3 `GET /products/{productId}?branchId=`

Ficha del producto **en una sucursal**. Si falta `branchId` y el alcance es una sola sucursal se usa esa; si el
alcance es "todas" y hay más de una → 400 `BRANCH_REQUIRED` ("Elegí una sucursal para ver el detalle del
producto"). Sucursal fuera del alcance → 403 `BRANCH_FORBIDDEN`. Sin análisis de ese producto → 404.

```json
{"insight": { /* ProductInsightRow */ },
 "barcode":"7799990000035","salePrice":5400.00,"costPrice":3200.00,"perishable":true,
 "history":[{"date":"2026-06-20","units":4,"amount":21600.00}],
 "weekdayProfile":[0.8,0.9,0.9,1.0,1.1,1.4,1.2],
 "forecast":[{"date":"2026-09-18","yhat":3.9,"lo":1.9,"hi":5.9}],
 "forecastMethod":"HOLT_WINTERS",
 "anomalies":[{"date":"2026-09-13","quantity":20,"expected":3.1,"score":4.2,"kind":"SPIKE"}],
 "lotRisks":[{"lotId":24,"daysToExpiry":2,"quantity":26,"expectedSalesBeforeExpiry":0.0,"unitsAtRisk":26,
   "riskLevel":"HIGH","recommendedDiscountPct":40,"expectedUnitsSoldWithDiscount":14.3}],
 "lots":[{"lotId":24,"lotNumber":"B-6-R","expiryDate":"2026-09-19","daysToExpiry":2,"quantity":26,
   "discountPct":25.00,"status":"ACTIVE","rotationRank":1,"bucket":"CRITICAL"}],
 "recommendations":[ /* RecommendationDto PENDING y ACCEPTED */ ]}
```

- `history`: 90 días de ventas **netas** de esa sucursal (solo los días con movimiento).
- `lots`: los lotes vivos **en el orden en que se van a vender** (`StockService.lotsInRotationOrder`), así el
  `rotationRank` 1 es el que sale primero (SPEC §4.2: los lotes en liquidación van antes).

### 4.4 `GET /runs/latest`

Último `ai_runs` de cada sucursal del alcance:

```json
[{"id":7,"branchId":1,"branchName":"Sucursal Centro","status":"OK","trigger":"STARTUP",
  "modelVersion":"gondolia-ai-1.0","productsAnalyzed":10,"recommendationsCreated":15,
  "startedAt":"2026-09-17T22:32:49.092437Z","finishedAt":"2026-09-17T22:32:50.442017Z","errorMessage":null}]
```

### 4.5 `POST /run` (TENANT_ADMIN)

Lanza el análisis **asincrónico**, un `ai_runs` por sucursal del alcance. Una sucursal que ya se está analizando se
saltea (no se encola dos veces).

```json
{"message":"Estamos analizando 2 sucursales. Te avisamos cuando terminen.",
 "runs":[{"id":9,"branchId":1,"branchName":"Sucursal Centro","status":"RUNNING","trigger":"MANUAL", "...":null}]}
```

Sin sucursales accesibles → 400 `BRANCH_REQUIRED`.

### 4.6 Cómo se arma el análisis

`InsightsService` → `InsightsRunner` → `InsightsStore`, **una corrida por sucursal**:

1. `buildInput` arma el `AnalyzeRequest` de §8.2 con **180 días** de ventas netas de esa sucursal (`dailySales`),
   los lotes vivos con `receivedAt` y `discountPct`, el stock vendible, `settings` (incluida `stockRotation`,
   `serviceLevel`, `targetCoverageDays` y `maxDiscountPct`) y el `feedback` de las recomendaciones `DISCOUNT` ya
   decididas en los últimos 90 días (con su `outcome` medido).
2. La llamada HTTP a la IA (hasta 120 s) ocurre **fuera de toda transacción**.
3. `applyResults` guarda `product_insights` (uno por producto y sucursal), hace **upsert** de las
   recomendaciones `PENDING` por `(branchId, dedupeKey)` y **expira** las `PENDING` que la IA dejó de sugerir.
   Se descartan las respuestas que mencionan productos o lotes que no estaban en el pedido.
4. `finishRun` cierra el `ai_runs` con el resumen del modelo; un fallo lo deja en `ERROR` con el mensaje.
5. Si hubo recomendaciones nuevas se notifica a los administradores de esa sucursal (`RECOMMENDATION`,
   link `/app/insights`).

**Programación** (`InsightsScheduler`): todos los días a las 03:00 para todas las sucursales activas de comercios
`ACTIVE`; y al arrancar, solo las que no tienen un análisis `OK` en las últimas 24 h. Al arrancar espera a que el
servicio de IA responda el `/health` (hasta 20 reintentos cada 15 s: el contenedor tarda en levantar). También
cierra los `ai_runs` que quedaron `RUNNING` más de una hora por un reinicio.

---

## 5. Recomendaciones — `/api/tenant/recommendations`

### 5.1 `GET /api/tenant/recommendations?status=PENDING&type=&productId=&q=&page=&size=`

`PageResponse<RecommendationDto>`, primero las `PENDING` y dentro de cada grupo por prioridad:

```json
{"id":2,"branchId":1,"branchName":"Sucursal Centro","type":"DISCOUNT","status":"PENDING","productId":6,
 "productName":"Queso cremoso La Pradera 500 g","brand":"La Pradera","lotId":24,"lotNumber":"B-6-R",
 "lotExpiryDate":"2026-09-19","title":"Aplicar 40% de descuento a Queso cremoso La Pradera 500 g (lote B-6-R)",
 "explanation":"El lote B-6-R vence el 19/09/2026 (en 2 días) y tiene 26 u. …",
 "suggestedQuantity":null,"suggestedDiscountPct":40.00,"suggestedDate":"2026-09-17","priority":90,
 "confidence":0.550,"expectedImpact":46352.88,"createdAt":"2026-09-17T22:32:50.2Z","decidedAt":null,
 "decidedByName":null,"decisionNote":null,"outcome":null}
```

### 5.2 `POST /{id}/accept` (TENANT_ADMIN)

Body opcional: `{"note":"…","quantity":40,"discountPct":25}`.

| Tipo | Qué hace de verdad |
|---|---|
| `DISCOUNT` | Pone `lots.discount_pct` (y `discount_started_at`) en el lote: pasa a **venderse primero** (SPEC §4.2) y la caja cobra con el descuento. `discountPct` del body pisa al sugerido; por encima de `tenant_settings.max_discount_pct` → 400 `VALIDATION_ERROR`. Publica `StockChangedEvent`. |
| `REMOVE_EXPIRED` | Descarta el remanente con `StockService.adjust` (`WASTE_EXPIRED`, origen `SYSTEM`). `quantity` del body o todo el lote; más que el remanente → 409 `INSUFFICIENT_STOCK`. |
| `REORDER` | Arma el texto del pedido al proveedor y, si el proveedor tiene teléfono, el link de WhatsApp. `quantity` del body o la sugerida; sin cantidad → 400. |
| `REVIEW_ANOMALY` / `REDUCE_PURCHASE` | Quedan registradas como aceptadas (son decisiones de gestión, no tocan el stock). |

```json
{"recommendation": { /* … status ACCEPTED, decidedByName, outcome */ },
 "message":"Aplicamos 25% de descuento al lote B-6-R: ahora se vende primero.",
 "appliedDiscountPct":25.00,"discardedQuantity":null,"orderedQuantity":null,
 "whatsappText":null,"whatsappUrl":null}
```

Ejemplo de `REORDER`:

```json
{"message":"Anotamos el pedido de 120 unidades a Distribuidora La Pampa.","orderedQuantity":120,
 "whatsappText":"¡Hola Marta Suárez! Te hago un pedido:\n\n• Yerba mate Serrana 1 kg (Serrana) — 120 unidades\n  Código: 7799990000066\n\nLo necesitamos para el 17/09/2026.\nEntrega en Sucursal Centro.\n\n¡Gracias!",
 "whatsappUrl":"https://wa.me/541145550101?text=…"}
```

El teléfono se normaliza para `wa.me`: solo dígitos, sin el 0 de larga distancia y con el código de país `54` si
el proveedor no lo cargó. Si el teléfono no sirve, `whatsappUrl` es `null` y queda solo el texto para copiar.

Errores: 404 `NOT_FOUND` (recomendación de otro comercio), 403 `BRANCH_FORBIDDEN` (sucursal sin acceso),
409 `CONFLICT` ("Esa recomendación ya fue decidida" o "El lote de la recomendación ya no tiene stock").

### 5.3 `POST /{id}/discard` (TENANT_ADMIN)

Body opcional `{"note":"Fue una promo puntual"}`. La deja en `DISCARDED`; la IA la vuelve a evaluar en el próximo
análisis (y el descarte viaja como `feedback`).

### 5.4 Medición del resultado (`RecommendationOutcomeJob`)

Todos los días a las **02:30** (antes del análisis de las 03:00) se miden las recomendaciones `DISCOUNT`
aceptadas hace 7 días o más que todavía no tienen resultado. Se guarda en `recommendations.outcome`:

```json
{"appliedDiscountPct":25.00,"unitsBefore7d":10,"unitsAfter7d":25,"lift":2.500,
 "lotUnitsSold":14,"lotUnitsRemaining":6,"lotUnitsAtAccept":20,"measuredAt":"2026-09-25T05:30:00Z"}
```

- `unitsBefore7d` se fotografía **al aceptar** (ventas netas de los 7 días previos); `unitsAfter7d` son los 7 días
  posteriores a la decisión. `lift = después ÷ antes` (`null` si antes no vendió nada).
- Ese `outcome` es lo que viaja como `feedback` en el próximo `AnalyzeRequest`: con eso la IA ajusta la
  elasticidad por categoría (SPEC §8.2) con lo que realmente pasó en el comercio.

---

## 6. Pantallas (`frontend/src/features/analytics`)

| Ruta | Página | Qué muestra |
|---|---|---|
| `/app/dashboard` | `DashboardPage` | "Para hoy" (lo urgente, por severidad), 4 KPI, "Ventas y stock" de 30 días, comparación de sucursales (solo en consolidado), próximos vencimientos, artículos a reponer con "Comprar N" y las recomendaciones de la IA. Comercio sin productos → estado vacío con links a `/app/imports` y `/app/intake`. |
| `/app/statistics` | `StatisticsPage` | Pestañas **Ventas**, **Rotación y ABC** y **Pérdidas e impacto**, con selector de período. |
| `/app/insights` | `InsightsPage` | Resumen, estado de los análisis por sucursal, lotes con más riesgo, tabla de patrones con filtros y la ficha de cada producto (90 días + pronóstico + anomalías + lotes + recomendaciones). "Recalcular IA" solo para el administrador. |
| `/app/alerts` | `AlertsPage` | Bandeja con filtros por estado, tipo y severidad, franja de severidad por fila y las acciones vista / resolver / descartar. |

El **jefe** ve las cuatro pantallas en modo lectura: sin botones de acción y con un aviso que lo aclara.
Las query keys de datos por sucursal usan `useBranchQueryKey`, así que al cambiar de sucursal se refresca todo.

---

## 7. Decisiones y cosas a saber

1. **Los faltantes cuentan como venta.** Un `SALE` con `lotId` null es una venta que se cobró sin stock
   registrado: suma en la facturación y además se informa como "ventas perdidas" en `losses.lostSales`. Así los
   totales del módulo coinciden con el historial de ventas del módulo A2.
2. **`productsCount` es del comercio, no de la sucursal**: el catálogo es único por tenant (SPEC §1.1), a
   diferencia del stock. Con una sucursal elegida cambian los valores de stock, no el conteo de productos.
3. **Ráfagas de stock**: el motor de alertas agrupa por sucursal con 5 s de silencio (máximo 60 s de espera). Sin
   eso, una importación o el seeder dispararían cientos de evaluaciones seguidas.
4. **La IA se llama por sucursal y fuera de transacción.** Cada paso (crear el run, armar el pedido, guardar los
   resultados) es una transacción corta propia: la llamada HTTP de hasta 120 s nunca mantiene una transacción
   abierta.
5. **Dedupe de recomendaciones por sucursal.** El `dedupeKey` de §8.2 (`DISCOUNT:{lotId}`, `REORDER:{productId}`…)
   es único junto con `branchId` y solo entre las `PENDING`: una recomendación aceptada o descartada no bloquea la
   que genere el próximo análisis si la situación vuelve a darse.
6. **Aceptar un descuento cambia el orden de venta.** Al poner `discount_pct` el lote pasa al frente de la
   rotación (SPEC §4.2), así que el descuento realmente acelera su salida: eso es lo que después mide el job de
   resultados.
7. **`X-Branch-Id` con una sucursal de otro comercio** nunca devuelve datos: `BranchAccessService` responde 403
   `BRANCH_FORBIDDEN` antes de llegar a la consulta, y todas las consultas filtran igual por `tenant_id`.
8. **Sin sucursales accesibles** (un usuario sin asignaciones) las lecturas devuelven listas vacías y ceros en vez
   de fallar.
9. **Compatibilidad con el driver de Postgres**: los parámetros de fecha van como `OffsetDateTime` y los
   `timestamptz` se leen con `AnalyticsSql.instant(rs, col)`. pgjdbc no convierte `Instant` en ninguno de los dos
   sentidos.
10. **El frontend completa dos etiquetas que faltan en el núcleo**: `MOVEMENT_SOURCE_LABELS` de `api/types.ts` no
    tiene `POS_GONDOLIA` ni `IMPORT` (sí están en SPEC §4.1), así que `StatisticsPage` las agrega localmente.
