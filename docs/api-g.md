# Módulo G — Datos de demostración (`com.gondolia.seed`)

El módulo G **no expone endpoints REST propios**: su trabajo es poblar la base con un mundo creíble para demos,
pruebas manuales y la presentación del proyecto (SPEC §11). Los datos se consultan y se usan con la API de los demás
módulos (catálogo, movimientos, POS, tableros, IA, avisos, soporte, consola de dueños). Qué hay y cómo mostrarlo está
en [`datos-demo.md`](datos-demo.md); este documento cuenta cómo funciona el seeder, cómo se configura y qué se decidió.

## 1. Cuándo corre

| Condición | Comportamiento |
|---|---|
| `APP_SEED_DEMO=true` (default en `docker compose`) y la tabla `tenants` está vacía | Siembra todo el mundo demo al arrancar |
| `APP_SEED_DEMO=true` y ya hay comercios (demo sembrada antes, datos reales o el fixture de desarrollo) | No hace nada (log `la base ya tiene N comercios`) → **idempotente** |
| `APP_SEED_DEMO=false` | El bean ni se crea |

- Es un `ApplicationRunner` con `@Order(30)`: corre después del dueño inicial (`BootstrapRunner`, 10) y del fixture
  de desarrollo (`DevFixtureRunner`, 20). Con `APP_DEV_FIXTURE=true` el fixture crea "Comercio de Prueba" antes, así que
  la demo no se siembra (son mundos alternativos: fixture para desarrollo, demo para presentar).
- Todo se escribe en **una sola transacción** (timeout 10 min): si algo falla no queda un mundo a medias. El error se
  registra en el log (`No se pudieron cargar los datos demo…`) y la aplicación arranca igual, vacía.
- `./start.sh reset` (borra la base y vuelve a sembrar) es la forma de "regenerar" la demo.

## 2. Qué hace (en orden)

1. **Plataforma**: completa el dueño inicial (nombre "Federico Almada", sin tocar su contraseña) y crea
   `socia@gondolia.app`, `soporte@gondolia.app` y `soporte2@gondolia.app` si no existen.
2. **Avisos**: 4 avisos generales publicados (uno segmentado por rubro, uno de mantenimiento con la fecha del próximo
   domingo), uno archivado, un borrador y el **recall histórico** (Dulce de leche Dulce Valle 400 g, lote `DV2603B`).
3. **Comercios** (17): datos administrativos, `tenant_settings` (FIFO/FEFO), sucursales (con API key de demo en las que
   usan POS propio), usuarios por rol con sus sucursales, módulos habilitados y `tenant_events` fechados (altas, módulos,
   cambios de plan, deshabilitaciones, rehabilitación y bajas) para que crecimiento y churn tengan sentido.
4. **Catálogo** por rubro desde un catálogo maestro de 130 productos con marcas ficticias y **EAN-13 válidos** `779…`,
   categorías y proveedores (con demora de entrega).
5. **Simulación día a día** (`StoreSimulator`, en memoria): 180 días en los comercios ricos y hasta 45 en los livianos
   (ver §4).
6. **Escritura en bloque** de lotes, movimientos, turnos, tickets, ítems, pagos y movimientos de caja con `COPY`.
7. **Historias**: recomendaciones de la IA ya decididas (descuentos aceptados con resultado medido, reposiciones
   aceptadas y descartadas), coincidencias del recall histórico resueltas (con su alerta resuelta y notificaciones),
   la importación inicial APLICADA de El Sol con sus filas, notificaciones y lecturas de los avisos y los tickets de
   soporte con conversación (uno con **foto adjunta real** guardada con `AttachmentStorageService`).

**No se siembran** alertas ni recomendaciones pendientes ni resultados de IA: al terminar el arranque el motor de
alertas y el análisis de IA (módulo B) los generan solos sobre estos datos (en la prueba local: 134 alertas abiertas y
~190 recomendaciones pendientes en las 18 sucursales activas, en unos segundos).

## 3. Rendimiento

| Medición (Windows 11, Postgres 16 en Docker) | Valor |
|---|---|
| Siembra completa (base vacía → mundo listo) | **≈ 26 s** |
| Filas escritas en bloque | ≈ 361.000 (7.300 lotes, 160.000 movimientos, 46.000 tickets del POS con ítems y pagos, 1.276 turnos) |
| Simulación en memoria (sin base) | ≈ 1 s |

Muy por debajo de la ventana de salud del backend (`start_period: 240s`). La prueba de integración falla si la siembra
tarda más de 240 s.

## 4. La simulación

Por comercio, día por día y sucursal por sucursal, con eventos ordenados por hora:

- **Demanda** por producto con su patrón (SPEC §11): estable, semanal suave, **finde fuerte**, **intermitente** (pocos
  días con venta y compras de a varios), **creciente**, **decreciente**, **sin movimiento** y **picos anómalos**
  armados; escala por sucursal y ruido realista. Los precios históricos se "desinflan" 2,1 % por mes (el precio actual
  es el del catálogo) y los costos de cada lote varían ±2 %.
- **Canales**: POS GondolIA (turnos mañana/tarde en Caja 1, Caja 2 algunos días, tickets de 1 a 7 productos, pagos
  efectivo/débito/crédito/transferencia/QR, pagos mixtos, vuelto, anulaciones con `SALE_VOID`, retiros e ingresos de
  efectivo, arqueo con diferencias y nota), **POS externo** por API (`source = POS`, `S-EXT-...`, con faltantes si el
  POS vende sin stock registrado), **CSV** diario (los primeros 60 días de Echesortu) y **venta manual**.
- **Consumo de lotes** con las mismas reglas que `StockService` (§4.2): FIFO `received_at, id` o FEFO
  `expiry_date NULLS LAST, received_at, id`, lotes en liquidación primero, vencidos nunca (vencimiento evaluado en la
  fecha de la venta), lotes en cuarentena nunca; el faltante es un `SALE` sin lote con la razón "Venta sin stock
  registrado". Los lotes quedan con estado final coherente (`ACTIVE`, `DEPLETED`, `EXPIRED_DISCARDED`, `RECALLED`).
- **Reposición**: punto de pedido y cobertura por producto según la demora del proveedor; **cada ingreso es un lote
  nuevo** con su número (formato por marca), vencimiento y costo; compras de oportunidad que dejan 2–4 lotes vivos y,
  en perecederos, riesgo de merma. Origen `SCAN`, `OCR` o `MANUAL` con "Remito …".
- **Mermas**: los vencidos se descartan (`WASTE_EXPIRED`, "Descarte por vencimiento") 1 a 6 días después; algunos
  quedan pendientes a propósito. Roturas (`WASTE_DAMAGED`) y ajustes de recuento (`ADJUSTMENT_IN/OUT`, `A-...`).
- **Transferencias** quincenales entre sucursales (El Sol y Vida Sana): `TRANSFER_OUT` + lote destino con el mismo
  número, vencimiento, costo, proveedor y `received_at` original, `origin_lot_id` y `TRANSFER_IN` con `T-...`.
- **Hoy** se simula hasta la hora actual: ventas del día, turnos en curso abiertos (siempre al menos el de
  `cajero@elsol.com` en Caja 1 de El Sol Centro) y nada en el futuro.
- **Escenarios armados** (`DemoScenarios`): lote `L2409A` del recall en vivo, recall histórico, caso "lote nuevo que
  vence antes que uno viejo" en cada sucursal FIFO (y el equivalente con FEFO en Vida Sana), sobrestock por vencer con
  descuento aceptado, una liquidación en curso, productos sin stock/bajo mínimo (proveedor que deja de entregar),
  vencidos pendientes y decisiones sobre recomendaciones.

La simulación es **determinística por día** (semilla = comercio + fecha): el mismo día siembra los mismos datos.

## 5. Permisos y aislamiento

El seeder corre sin usuario (arranque) y escribe con SQL directo, siempre con el `tenant_id` y el `branch_id`
correctos: cada lote, movimiento, turno, ticket, alerta, recomendación, notificación y ticket de soporte pertenece a su
comercio y sucursal. Las asignaciones `user_branches` respetan §3.5 (empleados y cajeros solo en sus sucursales). La
prueba de integración lo verifica con la API real sobre los datos sembrados: empleado de Echesortu → Centro = 403
`BRANCH_FORBIDDEN`; administrador de Vida Sana → producto de El Sol = 404; cajero → tablero = 403; adjunto de soporte de
Don Pepe → otro comercio = 404; comercios deshabilitados o dados de baja → login 403 `TENANT_DISABLED` /
`TENANT_CANCELLED`.

## 6. Decisiones

- **SQL directo + `COPY` en lugar de `StockService` por venta**: 160.000 movimientos pasando por el servicio (bloqueos,
  eventos, alertas) tardarían muchos minutos. La simulación replica las reglas del núcleo y las pruebas lo verifican
  reproduciendo cada movimiento. El driver de PostgreSQL es `runtime` en el `pom.xml`, así que la API de `COPY` se usa
  por reflexión (`PGConnection.getCopyAPI().copyIn`) a través de `JdbcTemplate`, en la misma transacción.
- **Ids reservados de las secuencias** (`nextval` en bloque) para lotes, turnos y tickets: así los ids respetan el
  orden de creación (desempate FIFO por id, como el núcleo) y las referencias se resuelven antes de escribir.
- **Fechas hacia atrás**: todo se escribe ya con su fecha histórica (`created_at`, `occurred_at`, `received_at`,
  `opened_at`…), incluida la fecha del adjunto de soporte (se actualiza después de `store`).
- **Orígenes realistas** (`SCAN`, `OCR`, `MANUAL`, `IMPORT`, `POS`, `POS_GONDOLIA`, `CSV`) en lugar de `SEED`: el origen
  `SEED` se muestra como "Datos demo" en la interfaz y rompería la ilusión en una presentación.
- **API keys de demo conocidas** para las sucursales con POS propio (documentadas en `datos-demo.md`) para poder mostrar
  el webhook en vivo. Son solo para la demo: si la instancia queda expuesta, regenerarlas desde Integración POS.
- **Foto de soporte generada**: un PNG de la etiqueta con el EAN real de la sopa, dibujado píxel a píxel y codificado a
  mano (sin `java.awt`, que en la imagen Alpine no tiene fuentes).
- **Recall histórico publicado** (no archivado) para que los comercios lo vean en Avisos con su resolución; el recall
  en vivo **no** se siembra: lo publica el dueño durante la demo.
- **Recomendaciones decididas con `run_id` nulo**: son anteriores a los análisis de este arranque; su `outcome` tiene
  los mismos campos que mide el job diario (`unitsBefore7d`, `unitsAfter7d`, `lift`, `lotUnitsSold`,
  `lotUnitsRemaining`, `appliedDiscountPct`, `lotUnitsAtAccept`, `measuredAt`) y viaja como `feedback` a la IA.
- **Usuarios extra** además de los de SPEC §11: `empleado.cerro@vidasana.com` (Cerro de las Rosas) y
  `cajera.tarde@elsol.com` (turno tarde de Fisherton), para cubrir los turnos sin superponer personas.

## 7. Archivos

| Archivo | Qué tiene |
|---|---|
| `DemoDataSeeder` | `ApplicationRunner`: condición, transacción, log y orquestación |
| `DemoWorld` | Comercios, sucursales, usuarios, módulos y eventos (SPEC §11) |
| `DemoCatalog` / `Ean13` | Catálogo maestro, proveedores y códigos EAN-13 |
| `DemoScenarios` | Escenarios armados (recalls, FIFO/FEFO, descuentos, faltantes, picos, vencidos) |
| `StoreSimulator`, `SimModel`, `SimOutput`, `SeedRandom` | Simulación en memoria |
| `DemoWorldBuilder` | Altas de comercios y catálogos, escritura en bloque de la simulación |
| `DemoStories` | Avisos, recalls, recomendaciones, importación, notificaciones y soporte |
| `SeedJdbc` | `INSERT ... RETURNING`, reserva de ids y `COPY` |
| `DemoLabelImage`, `SeedMultipartFile` | Foto adjunta del ticket de soporte |

## 8. Pruebas

- `DemoCatalogAndWorldTest` (sin base): EAN válidos y únicos, mundo según SPEC §11, usuarios y sucursales coherentes,
  escenarios que apuntan a productos y sucursales existentes, formato de las API keys, PNG válido.
- `StoreSimulatorTest` (sin base): simula los 17 comercios y **reproduce cada movimiento** verificando que cada venta
  salga del lote correcto según FIFO/FEFO y liquidación, que nunca se venda un vencido o un lote en cuarentena, que
  ningún lote quede negativo, que los arqueos cierren, un turno abierto por usuario, recall en vivo solo en Don Pepe y
  El Sol Fisherton, resultados de descuentos, casos FIFO, varios lotes vivos, vencidos pendientes y faltantes.
- `DemoDataSeederIntegrationTest` (`mvn test -Dgondolia.it=true`): crea la base `gondolia_it_seed`, arranca la
  aplicación con la demo y verifica volumen, tiempo, idempotencia, invariantes de stock y de caja, historias, logins,
  aislamiento con la API real y el recall en vivo con `RecallMatchingService.matchAnnouncement`.
