# Servicio de IA de GondolIA (`ai-service`)

Microservicio en **Python 3.12 + FastAPI** que concentra la "inteligencia" del producto: reconoce patrones de venta,
pronostica la demanda, calcula cuándo y cuánto reponer, estima qué lotes van a vencer sin venderse (respetando la
rotación **FIFO/FEFO** del comercio), sugiere descuentos que aprenden de los resultados reales, detecta anomalías y
lee etiquetas (OCR) y códigos de barras desde fotos.

Este documento explica **qué hace cada algoritmo y por qué se eligió**, pensado para defender el proyecto.
El contrato JSON exacto está en `SPEC.md §8`; hay un pedido y una respuesta reales en
[`docs/examples/analyze-request.json`](examples/analyze-request.json) y
[`docs/examples/analyze-response.json`](examples/analyze-response.json).

---

## 1. Dónde encaja

```
backend (Spring Boot) ──HTTP interno──► ai (FastAPI :8000)
   InsightsService: 1 pedido /v1/analyze POR SUCURSAL (180 días de ventas + lotes + feedback)
   Carga de mercadería: /v1/ocr y /v1/barcode con la foto que sube el empleado
```

- **Sin estado**: no tiene base de datos. Todo el contexto (ventas, lotes, configuración y resultados de
  recomendaciones anteriores) llega en cada pedido; el backend persiste los resultados (`product_insights`,
  `recommendations`, `ai_runs`).
- **Por sucursal**: cada local tiene su propio patrón de ventas, su stock y sus lotes. El backend manda un pedido
  por sucursal (`branchId`, `branchName`) y el servicio analiza solo esos datos. El catálogo (precios, categorías)
  es común al tenant, así que la elasticidad por categoría usa el feedback que el backend decida enviar.
- **Solo red interna**: no expone CORS ni autenticación; nginx no lo publica.

| Endpoint | Qué hace | Tiempo típico |
|---|---|---|
| `GET /health` | Estado, versión y si Tesseract (spa+eng) está disponible | < 5 ms |
| `POST /v1/analyze` | Patrones, pronóstico, reposición, riesgo por lote, anomalías y recomendaciones | 16 productos: ~0,3 s · 150 productos × 180 días: ~2 s |
| `POST /v1/ocr` | Lee una etiqueta: vencimientos, lotes, elaboración, nombre, códigos | 0,8 s (foto limpia) – 2,2 s (foto con ruido) |
| `POST /v1/barcode` | Lee códigos de barras con zxing-cpp | < 100 ms |

### Cómo correrlo y probarlo

```bash
docker build -t gondolia-ai-dev ai-service
docker run --rm gondolia-ai-dev python -m pytest -q          # 184 tests dentro de la imagen
docker run --rm -p 18000:8000 gondolia-ai-dev                 # API en http://localhost:18000
curl -s -X POST http://localhost:18000/v1/analyze -H "Content-Type: application/json" \
     --data-binary @docs/examples/analyze-request.json
python ai-service/scripts/make_example_request.py            # regenera el pedido de ejemplo (sin dependencias)
```

La imagen usa `python:3.12-slim`, instala `tesseract-ocr` con los idiomas español e inglés, corre con un usuario
sin privilegios (`gondolia`, uid 10001) y levanta `uvicorn` con 2 workers. Las librerías numéricas se limitan a un
hilo por proceso (`OMP_NUM_THREADS=1`, `OMP_THREAD_LIMIT=1`) para que los 2 workers y los hilos del OCR no compitan
por la CPU.

---

## 2. `/v1/analyze` paso a paso

```
ventas diarias ──► serie completa ──► picos extremos acotados ──► features ──► patrón (reglas) ──► KMeans (casos dudosos)
                                          │
                                          ├──► pronóstico (HW / Croston-SBA / media ponderada) ──► anomalías (MAD + Isolation Forest)
                                          │                    │
lotes + rotación FIFO/FEFO ───────────────┴──► simulación de consumo lote por lote ──► riesgo por lote ──► descuento mínimo
                                                               │
                                          punto de pedido, stock de seguridad, cobertura, quiebre previsto, cantidad sugerida
                                                               │
                                    recomendaciones (prioridad, confianza, impacto, dedupeKey, explicación es-AR)
```

El código está en `ai-service/app/analysis/`: `series.py`, `patterns.py`, `abcxyz.py`, `forecasting.py`,
`anomalies.py`, `lots.py`, `inventory.py`, `discounts.py`, `recommendations.py` y `pipeline.py` (orquestación).
Si un producto trae datos inconsistentes y algo falla, se analiza con un método simplificado (media de 28 días)
en lugar de cortar todo el análisis de la sucursal, y queda anotado en `summary.modelNotes`.

### 2.1 Serie diaria (`series.py`)

El backend manda solo los días con ventas. El servicio arma la serie **completa** rellenando con 0 desde el alta del
producto (o la primera venta, si es anterior) hasta **ayer**. El día `asOfDate` se considera **en curso**: el
análisis corre a las 03:00, así que sus ventas están incompletas; no entran en la historia pero se descuentan de la
demanda de hoy. Por eso el pronóstico de la respuesta empieza en `asOfDate + 1`.

**Días sin stock (demanda censurada).** El backend informa en `stockoutDays` los días en que el producto no tuvo
stock vendible ni ventas. Esos ceros no muestran la demanda (no había qué vender): tomarlos como datos haría ver una
caída que no existió (una leche agotada hace 6 días figuraba "Venta estable de 6,9 u/día" con "tendencia −92%" y una
reposición calculada con 3,8 u/día). `impute_stockouts` completa cada tramo con el promedio de los 28 días previos con
stock, ajustado por día de la semana (8 semanas, suavizado hacia 1), **antes** de medir features, patrón, pronóstico
y anomalías. No se completan tramos sin al menos 7 días de historia previa ni más largos que 8 semanas. Si el faltante
llega hasta ayer, la reposición lo dice: "No hay stock vendible desde el 13/09 y la demanda es de 6,9 u/día (los días
sin stock no se toman como una caída de la venta)…", y `summary.modelNotes` cuenta cuántos productos se completaron.

**Picos extremos acotados.** Antes de calcular features, patrón y pronóstico, cada día se compara con su nivel local
(mediana móvil de 15 días, desestacionalizada) y se acota si lo supera en más de 6 desvíos robustos; en demanda
esporádica se compara el tamaño de cada venta con el de las demás ventas. Así una venta mayorista o un error de carga
(por ejemplo 1.000.000 u. en un producto que vende 3 por día) no dispara el pronóstico, el stock de seguridad ni la
cantidad sugerida. Un cambio de nivel sostenido (varias semanas) no se recorta, y la detección de anomalías sigue
usando la serie **original**, así que el pico igual se informa para revisar.

### 2.2 Features de cada producto (`patterns.py`)

| Feature | Cálculo | Para qué sirve |
|---|---|---|
| Promedio reciente | media de los últimos 56 días, con picos extremos acotados (`avgDailySales`) | nivel de venta actual |
| CV diario / semanal | desvío / media de ventas diarias (84 días) y de totales semanales (12 semanas) | estabilidad, XYZ |
| ADI | días de historia / días con venta | intermitencia (umbral de Syntetos-Boylan 1,32) |
| Tamaño medio | unidades promedio los días que vende | distingue "vende poco" de "vende de a mucho, a veces" |
| Perfil semanal | venta media por día de semana / media general (lun..dom, suma 7) | `weekdayProfile` y estacionalidad |
| Estacionalidad | test de **Kruskal-Wallis** entre los 7 días de la semana (p < 0,01) + amplitud ≥ 1,3 o finde ±20% | ¿el día de la semana importa de verdad? |
| Tendencia | regresión lineal de los últimos 56 días (`trendPct`) y de 112 días | crecimiento o declive |

Detalles de la **tendencia** que conviene saber defender:
- `trendPct` = cuánto cambia la recta de regresión a lo largo de las 8 semanas, en % del promedio de esas semanas.
- Se calcula sobre la serie **desestacionalizada** (dividida por el perfil semanal) y con los **picos aislados
  acotados** (mediana + 5 desvíos robustos): así ni los sábados fuertes ni una venta mayorista fabrican tendencias.
- Solo se informa si es **estadísticamente significativa** (p < 0,01) o si la regresión de 16 semanas, más robusta,
  confirma la misma dirección. Si no, se informa 0: con ruido diario, 8 semanas de un producto estable pueden
  mostrar ±30% por pura casualidad, y mostrar eso en pantalla sería engañoso.
- **Coherente con el patrón**: en `EN_CRECIMIENTO` / `EN_DECLIVE` el `trendPct` de la respuesta (y el de las
  explicaciones) es la misma variación que cuenta la descripción del patrón ("pasó de 2,4 a 3,8 u/día (+62%)" →
  `trendPct` 62). Antes podía salir 0 si el alza ya se había estabilizado en las últimas 8 semanas, y la ficha mostraba
  "en alza +62%" junto a "tendencia 0%". Y un `ALTA_ROTACION_ESTABLE` con tendencia de 8 semanas de ±10% o más ya no
  se describe como "Venta estable" sino "Rotación alta: 6,9 u/día, en baja en las últimas 8 semanas (−15%)".

### 2.3 Patrón de ventas: reglas + KMeans

Las **reglas** son interpretables y se evalúan en orden (la primera que se cumple gana):

| Patrón | Regla | Descripción que ve el usuario |
|---|---|---|
| `DATOS_INSUFICIENTES` | menos de 14 días de historia | "Solo hay 9 días de historial…" |
| `SIN_MOVIMIENTO` | sin ventas en los últimos 30 días | "Sin ventas en los últimos 47 días" |
| `EN_CRECIMIENTO` / `EN_DECLIVE` | pendiente de 16 semanas con p < 0,01, variación ≥ +25% / ≤ −20% y nivel del último mes ≥ 1,25× / ≤ 0,8× el primero (más exigente si la demanda es esporádica) | "Ventas en alza: pasó de 1,8 a 3,6 u/día (+106%)" |
| `BAJA_ROTACION` | demanda esporádica (ADI ≥ 1,32) con menos de 1 u/día y de a 1-2 unidades | "Baja rotación: 1,6 u. por semana en promedio" |
| `INTERMITENTE` | vende como mucho la mitad de los días (ADI ≥ 2) pero en tandas | "Ventas esporádicas: vende en el 12% de los días, 4,5 u. por vez" |
| `ESTACIONAL_SEMANAL` | estacionalidad semanal significativa | "Vende 65% más los fines de semana" |
| `ALTA_ROTACION_ESTABLE` | ≥ 1 u/día sin patrón semanal ni tendencia | "Venta estable de 14,1 u/día" |

Cada regla devuelve además una **fuerza** (0..1): qué tan clara es la evidencia.

**KMeans** (`scikit-learn`) agrupa todos los productos de la sucursal por 5 features estandarizadas
(log de la media, CV, tendencia, ratio de fin de semana, % de días sin venta), con k = √(productos/2) acotado a 2..6.
No reemplaza a las reglas: los productos con evidencia **débil** (fuerza < 0,5, por ejemplo "rotación alta pero
irregular") adoptan el patrón dominante de los productos **claros** de su grupo, solo si es consistente con sus
propios datos. Es aprendizaje no supervisado usado como "segunda opinión" sobre casos dudosos.

En los tests hay generadores sintéticos de los 8 patrones: sobre 60 series por patrón el clasificador acierta 480/480.

### 2.4 ABC y XYZ (`abcxyz.py`)

- **ABC** por facturación de los últimos 90 días (unidades × precio × (1 − descuento)): los productos que suman el
  primer 80% son **A**, hasta el 95% **B**, el resto **C**. Principio de Pareto: pocos productos explican casi toda la venta.
- **XYZ** por coeficiente de variación de los totales semanales: **X** < 0,5 (predecible), **Y** < 1, **Z** ≥ 1 o sin
  ventas. Se usa el CV semanal porque el diario de un comercio chico está dominado por el ruido de cada día.

ABC pesa en la prioridad de las recomendaciones (un faltante de un producto A importa más).

### 2.5 Pronóstico (`forecasting.py`)

| Situación | Método | Por qué |
|---|---|---|
| sin ventas | `ZERO` | no hay nada que pronosticar |
| sin ventas hace más de 30 días | `WMA` (28 días) | refleja que hoy no vende |
| demanda esporádica (ADI ≥ 1,32) | `CROSTON_SBA` | los métodos clásicos subestiman/sobreestiman con muchos ceros |
| ≥ 28 días con ventas | `HOLT_WINTERS` | nivel + tendencia + estacionalidad semanal |
| poca historia | `WMA` (14 días) | robusto con pocos datos |

- **Holt-Winters** (`statsmodels`): suavizado exponencial con tendencia aditiva **amortiguada** (evita proyectar
  tendencias al infinito) y estacionalidad aditiva de período 7. Los parámetros se optimizan con la historia
  (hasta 26 semanas). Guardas: se descarta si el pronóstico explota (> 4× el nivel reciente), si colapsa a cero sin
  motivo, o si su error reciente es más de 10% peor que el de la media móvil (así el modelo más complejo tiene que
  "ganarse" su lugar).
- **Croston con corrección SBA** (Syntetos-Boylan Approximation): estima por separado el **tamaño** de cada venta y
  el **intervalo** entre ventas con suavizado exponencial; la demanda diaria es tamaño / intervalo × (1 − α/2)
  (la corrección elimina el sesgo de Croston). α se elige entre 0,05–0,3 por error cuadrático.
- **Media móvil ponderada**: pesos lineales (el día más reciente pesa más), sobre la serie desestacionalizada si el
  producto tiene patrón semanal.
- **Intervalos de predicción del 80%** (`lo`, `hi`): ŷ ± 1,28·σ·√(1 + h·g), donde σ es el error de pronóstico a un
  paso de las últimas 8 semanas y g crece con el horizonte (α² en Holt-Winters). σ se calcula **winsorizando** los
  residuos (mediana ± 3,5 MAD): un pico aislado no infla el stock de seguridad de todo el período. `lo` nunca es negativo.

### 2.6 Anomalías (`anomalies.py`)

1. Para cada día de los últimos 90 se calcula el **residuo** = venta real − pronóstico a un paso (lo que el modelo
   esperaba ese día sin conocer el dato).
2. **z-score robusto**: (residuo − mediana) / (1,4826 · MAD). Usa mediana y desvío absoluto mediano en lugar de media
   y desvío estándar porque las propias anomalías inflarían estos últimos. Umbral 3,5 (criterio de Iglewicz-Hoaglin).
3. Filtros de negocio: un **pico** (`SPIKE`) exige además superar en ≥ 3 u. y en 100% lo esperado, estar 30% por
   encima del percentil 95, **no** haber tenido descuento ese día (una promo no es anomalía) y ser improbable como
   conteo: P(X ≥ venta) < 0,0003 con X ~ Poisson(esperado). Este último filtro evita marcar ruido normal de volúmenes
   bajos (un 7 en un producto que vende 2 por día). Una **caída** (`DROP`) exige esperar ≥ 3 u. y vender ≤ 25%
   (solo se informa el primer día de una racha).
   - **Demanda esporádica** (pronóstico Croston/SBA): los días sin venta son normales y cualquier venta es "grande"
     frente al promedio diario, así que el pico se mide contra el **tamaño típico de las ventas** (mediana y MAD de
     los días con venta; `expected` informa ese tamaño) y no se buscan caídas.
4. **Isolation Forest** como segunda opinión: se entrena con todos los días de todos los productos de la sucursal
   (z robusto, residuo relativo, log-ratio real/esperado) y marca los puntos fáciles de aislar. Un candidato se
   confirma si Isolation Forest coincide **o** si el z es muy extremo (≥ 6). `score` es |z|.

Medido con series sintéticas limpias (150 productos × 90 días por familia): entre 0 y 5 falsos positivos por familia
(sin los filtros de Poisson y de demanda esporádica, 150 productos intermitentes generaban 183 anomalías falsas en
90 días); se siguen detectando todos los picos de 3× en productos estables de 8 u/día.

### 2.7 Reposición (`inventory.py`)

Con la demanda pronosticada d(t) y el lead time LT del proveedor (el del producto o el default del comercio):

- **Stock de seguridad** = ⌈ z(nivel de servicio) · σ · √LT ⌉. Con 95% de servicio z = 1,645.
- **Punto de pedido** = demanda pronosticada durante el LT + stock de seguridad, **nunca menor al stock mínimo**
  configurado (así la IA es coherente con las alertas de stock bajo).
- **Cantidad sugerida** = demanda de (LT + `targetCoverageDays`) + stock de seguridad − stock **utilizable**.
  El stock utilizable descuenta las unidades que la simulación por lotes prevé que **vencen sin venderse** en ese período.
- **Vida útil**: para perecederos se estima con la mediana de (vencimiento − fecha de ingreso) de sus lotes, y la
  cobertura del pedido se acota a esa vida útil (no tiene sentido pedir leche para 14 días si dura 11).
- **`daysOfCover`**: días de venta que representa el stock según el pronóstico (con fracción).
- **`predictedStockoutDate`**: primer día con faltante en la simulación **lote por lote**, que sí considera
  vencimientos (puede ser antes que la cobertura nominal).

Ejemplo real de la respuesta de ejemplo (leche, LT 2 días, vida útil 11 días):
> Al ritmo actual (14,3 u/día) el stock de 21 u. alcanza para 1,4 días y el proveedor tarda 2 días. Sugerimos pedir
> 176 u. hoy. El pedido cubre 11 días en lugar de 14 días porque la vida útil habitual del producto es de 11 días.

### 2.8 Lotes, vencimientos y rotación FIFO/FEFO (`lots.py`)

Cada ingreso de mercadería es un lote propio, así que un producto puede tener varios lotes con distintas fechas.
El servicio **simula día a día** cómo se va a vender la demanda pronosticada, lote por lote, en el mismo orden que usa
el backend para registrar las ventas (`SPEC §4.2`):

- **Lotes en liquidación primero**: los lotes con `discountPct` > 0 (descuento aceptado) se venden antes que el resto,
  con cualquier rotación; dentro de cada grupo rige FIFO o FEFO. Es el orden `(discount_pct IS NULL) ASC, …` del backend.
- **FIFO** (default, "primero sale lo que entró antes"): `receivedAt` ascendente, luego id.
- **FEFO** ("primero sale lo que vence antes"): `expiryDate` ascendente (sin vencimiento al final), luego `receivedAt` e id.
- Un lote **vencido nunca se vende**: al pasar su fecha, lo que queda se cuenta como merma y no atiende demanda.
- Los lotes `RECALLED` (cuarentena) no participan.

Para cada lote con vencimiento se informa en `lotRisks`: días al vencimiento, unidades que se espera vender antes
(`expectedSalesBeforeExpiry`), unidades en riesgo (`unitsAtRisk`) y un nivel: `EXPIRED` (ya vencido), `HIGH`
(≥ 50% en riesgo o vence dentro de los días críticos), `MEDIUM` (≥ 20% o dentro de los días de aviso), `LOW` o `NONE`.

**El caso FIFO que motiva todo esto** (está en el ejemplo): el yogur tiene un lote viejo `L2408B` de 40 u. que vence
el 08/10 y entra un lote **nuevo** `L2409A` de 18 u. que vence **antes**, el 24/09. Con FIFO primero se venden las
40 u. viejas (unos 10 días al ritmo de 3,8 u/día), así que el lote nuevo **vence sin llegar a venderse**. Con FEFO no
pasaría. La IA lo detecta simulando ambas rotaciones y lo explica:

> El lote L2409A vence el 24/09/2026 (en 7 días) y tiene 18 u. Con rotación FIFO primero sale la mercadería que
> entró antes: hay 40 u. de lotes que ingresaron antes y vencen después (hasta el 08/10/2026), así que este lote no
> llegaría a venderse antes de vencer. Al ritmo actual se venderían 0 u. antes del vencimiento y quedan 18 u. en
> riesgo ($ 25.200 a costo). Con 10% de descuento, que lo pasa adelante en la fila de venta, se estima vender 18 u.,
> suficiente para liquidar el lote. Con rotación FEFO (primero lo que vence antes) quedarían 0 u. en riesgo en lugar de 18 u.:
> evaluá cambiarla en Configuración. Elasticidad usada para Lácteos: 2,9, ajustada con 1 resultado medido de descuentos.

### 2.9 Descuentos y aprendizaje con feedback (`lots.py`, `discounts.py`)

**Efecto de un descuento.** Se modela con una **elasticidad** e por categoría: con d% de descuento las ventas se
multiplican por `1 + e · d/100`. Con e = 2 (default: +2% de ventas por cada 1% de descuento), 20% de descuento
vende 1,4 veces más. Aceptar el descuento pone el lote **en liquidación**, y el backend lo vende antes que el resto
(SPEC §4.2): la simulación de cada escalón hace lo mismo, y mientras ese lote se vende la demanda se multiplica por el
factor. Un lote que **ya** está en liquidación se simula primero desde el principio, así que no se lo informa en riesgo
por mercadería más vieja ni se le sugiere otro descuento si a su ritmo se vende a tiempo. Si el lote queda detrás de
otros en liquidación, la explicación lo dice ("Los lotes en liquidación salen primero: hay 40 u. con descuento…").

**Descuento mínimo.** Se prueban los escalones 10, 15, 20, 25, 30 y 40% (sin superar `maxDiscountPct`) simulando
**toda la fila de lotes** y se elige el **primero** con el que se estima vender todo el lote antes de vencer. Si al
pasar primero hace vencer mercadería de otros lotes, la explicación aclara que no alcanza sola ("se demoraría la venta
de otros lotes… conviene además achicar los próximos pedidos"). Si ninguno alcanza, se propone el mayor permitido y la explicación lo aclara
("conviene además exhibirlo… u ofrecerlo en combos"). Si el lote ya tiene un descuento, solo se sugieren escalones
mayores. Si el producto casi no vende (< 0,05 u/día) el modelo no puede estimar la respuesta al precio: la
recomendación se titula "Liquidar … antes del vencimiento", sugiere el máximo permitido, combos o donación, y su
impacto es el valor a costo en riesgo. Si además el vencimiento todavía está lejos, no se recomienda descuento: ahí
corresponde reducir compras.

**Aprendizaje.** El backend mide, 7 días después de aceptar un descuento, las ventas antes y después
(`outcome.unitsBefore7d`, `unitsAfter7d`, `lift`) y las manda como `feedback`. Para cada resultado:

```
elasticidad observada = (lift − 1) / (descuento / 100)          # ej.: lift 1,8 con 20% → 4,0
peso = unidades medidas (antes + después), entre 1 y 150
```

La elasticidad de cada categoría es un **promedio ponderado con un valor previo** ("shrinkage" bayesiano):

```
e(categoría) = (20 · e_previa + Σ peso · e_observada) / (20 + Σ peso)
e_previa     = (60 · 2,0 + Σ peso · e_observada de OTRAS categorías) / (60 + Σ pesos de otras categorías)
```

- Con pocos datos manda el valor previo; con muchos, manda la evidencia propia (no se sobre-reacciona a un caso).
- Lo aprendido en otras categorías se transfiere, pero con más cautela (peso previo 60 en lugar de 20).
- Se acota a 0..8. Un descuento que no movió las ventas baja la elasticidad y la próxima vez se sugiere un escalón
  mayor (o se avisa que el descuento no alcanza); uno exitoso la sube y alcanza con uno menor.

Ejemplo de la respuesta de ejemplo: Lácteos tuvo un descuento de 20% con lift 1,8 (28 u. medidas) y Snacks uno de
25% con lift 1,2 (66 u.). El valor previo para Lácteos es (60·2 + 66·0,8)/126 = 1,37 y entonces
e(Lácteos) = (20·1,37 + 28·4,0)/48 = **2,9**; Snacks baja a **1,23**; las categorías sin datos propios quedan en **1,85**.

Además, la **tasa de aceptación** histórica de cada tipo de recomendación (con al menos 3 decisiones) ajusta
suavemente la confianza: si el comercio descarta casi siempre un tipo, la IA muestra menos seguridad en él.

### 2.10 Recomendaciones (`recommendations.py`)

| Tipo | Cuándo | `dedupeKey` | Impacto esperado (`expectedImpact`, $) |
|---|---|---|---|
| `REMOVE_EXPIRED` | lote vencido con stock | `REMOVE_EXPIRED:{lotId}` | valor a costo de lo vencido |
| `DISCOUNT` | lote con unidades en riesgo y días al vencimiento > 0 | `DISCOUNT:{lotId}` | venta recuperada neta: vendidas con descuento × precio × (1 − d) − ventas que igual iban a ocurrir (si el modelo no puede estimar ventas extra, valor a costo en riesgo) |
| `REORDER` | stock ≤ punto de pedido o quiebre previsto antes de LT + 2 días | `REORDER:{productId}` | ventas que se perderían entre la llegada del pedido y el fin de la cobertura |
| `REVIEW_ANOMALY` | anomalía en los últimos 7 días | `REVIEW_ANOMALY:{productId}:{fecha}` | diferencia con lo esperado × precio |
| `REDUCE_PURCHASE` | perecedero con cobertura > 3 × objetivo (o sin ventas y con stock) | `REDUCE_PURCHASE:{productId}` | capital inmovilizado a costo |

`REORDER` y `REDUCE_PURCHASE` son excluyentes para un mismo producto. El backend usa `dedupeKey` (por sucursal) para
no duplicar una recomendación pendiente y para expirar las que ya no aparecen.

**Prioridad (1..100)** = urgencia + importancia + hasta 4-10 puntos por impacto económico (relativo al mayor impacto
del mismo tipo, en escala logarítmica). Los rangos no se superponen donde importa: **vencidos > reposición y
descuentos > anomalías > sobrestock**.

| Tipo | Base | Rango |
|---|---|---|
| `REMOVE_EXPIRED` | 92 + días desde que venció (hasta 8) | 92–100 — seguridad alimentaria primero |
| `REORDER` | 50 + 30·urgencia (1 sin stock, 0,9 faltante antes del LT, 0,75 faltante cercano, 0,5–0,75 bajo el punto de pedido) + 6·ABC + 4 por impacto | 67–90 |
| `DISCOUNT` | 45 + 28·urgencia (1 si vence en ≤ días críticos, 0,7 en ≤ días de aviso) + 6·% en riesgo + 5·ABC + 6 por impacto (− 8 si es una liquidación sin efecto estimable) | 40–90 |
| `REVIEW_ANOMALY` | 30 + 18·fuerza del z + 8·ABC + 3 si lo confirmó Isolation Forest + 3 si fue hace ≤ 2 días | 30–62 |
| `REDUCE_PURCHASE` | 20 + 15·sobrestock + 10 por impacto | 20–45 |

(ABC: A = 1, B = 0,65, C = 0,35.) Así un lote vencido siempre queda arriba, cualquier reposición necesaria supera a
una anomalía y el sobrestock queda como tarea de fondo.

**Confianza (0..1)**: combina historia disponible (hasta 90 días), error relativo del pronóstico (σ / demanda) y la
fuerza del patrón; se multiplica por la cantidad de resultados medidos de la categoría (descuentos), por la tasa de
aceptación histórica del tipo y se reduce si el descuento no alcanza a liquidar el lote o si el producto se analizó
con el método simplificado. `REMOVE_EXPIRED` tiene 0,99: el vencimiento es un hecho, no una predicción.

**Explicaciones**: siempre en español rioplatense, con números en formato es-AR (`3,5 u/día`, `$ 25.200`,
`24/09/2026`) y con el "por qué" y el "qué hacer": ritmo de venta, tendencia, cobertura, lead time, rotación, elasticidad usada.

### 2.11 Resumen (`summary`)

- `productsAnalyzed`, `patternCounts` (los 8 patrones, con 0 si no hay).
- `atRiskValue`: valor a costo de las unidades en riesgo de vencer sin venderse + las ya vencidas.
- `predictedStockouts7d`: productos con quiebre previsto dentro de los próximos 7 días (hoy incluido).
- `anomalies30d`: anomalías detectadas en los últimos 30 días.
- `elasticityByCategory`: elasticidad usada por categoría (las del pedido y las que tienen feedback).
- `modelNotes`: notas legibles del análisis (métodos usados, grupos de KMeans, lotes afectados por FIFO, vencidos…).

---

## 3. OCR de etiquetas (`/v1/ocr`, `app/ocr/`)

### 3.1 Lectura de la imagen (`preprocess.py`, `engine.py`)

1. **Decodificación** con Pillow (PNG, JPEG, WEBP, GIF, BMP) respetando la orientación **EXIF** de las fotos del
   celular; las transparencias se pintan sobre blanco. Máximo 15 MB; los JPEG de cámaras de 50-200 MP se decodifican
   ya reducidos (lado mayor ≥ 4000 px) y el resto de los formatos admite hasta 60 MP.
2. **Escala**: Tesseract lee mejor letras de 25-40 px, así que las imágenes chicas se agrandan (hasta 1600 px de
   lado mayor) y las enormes se achican (2400 px).
3. **Rotación**: se prueba 0/90/180/270° sobre una versión reducida y se elige la de mejor puntaje.
4. **Variantes OpenCV** sobre la imagen bien orientada: escala de grises (invertida si el fondo es oscuro), **CLAHE**
   (mejora el contraste local en fotos con sombras), **umbral adaptativo** (iluminación despareja) y **Otsu**.
5. **Tesseract `spa+eng`** (`--oem 1`, red LSTM): todas las variantes con segmentación automática (`--psm 3`) y las dos
   mejores también en modo texto disperso (`--psm 11`, útil para etiquetas con datos sueltos). Corren en paralelo
   (4 hilos) con un tiempo máximo por pasada.
6. **Puntaje de cada pasada** con `image_to_data`: suma los caracteres de palabras con confianza ≥ 60 (ponderados por
   su confianza) y **resta** los caracteres dudosos. Así una lectura limpia le gana a una variante que "ve" cientos de
   palabras basura en el ruido. La mejor pasada da `text` y `lines`; las siguientes aportan candidatos extra con 10%
   menos de confianza (un dato puede leerse bien solo en una variante).

### 3.2 Extracción de datos (`parse.py`)

Se normaliza cada línea (mayúsculas, sin acentos, **misma longitud** para que `raw` sea un fragmento literal) y se
corrigen confusiones típicas del OCR **solo dentro de fechas numéricas** (`1O/O3/2O27` → `10/03/2027`).

**Fechas** (orden argentino día/mes/año; años de 2 dígitos = 20xx; se validan fechas imposibles como 31/02):

| Formato | Ejemplos | Resultado |
|---|---|---|
| dd/mm/aaaa, dd-mm-aa, dd.mm.aaaa | `VTO 25/09/2026`, `F. VTO 07-12-26`, `Vence: 05.11.2026` | fecha exacta |
| aaaa-mm-dd | `EXP 2027-01-15` | fecha exacta |
| día + mes en texto | `CONSUMIR ANTES DE 25 SEP 2026`, `3 de octubre de 2026`, `14-SEP-2026` | fecha exacta |
| mes + año | `VENCE 03-2027`, `BB 01/2027`, `VTO SEPT/26`, `EXPIRA ENE 2027` | **último día del mes** |
| aaaa/mm | `EXP. 2027/03` | **último día del mes** |
| mm/aa, ddmmaa o aaaammdd pegados, o con espacios | `VTO 10/26`, `VTO 121026`, `EXP 20271012`, `VTO 10 03 27`, `BEST BEFORE END 03 2027` | solo si hay palabra clave justo antes (impresiones de fechador) |
| mes/día (etiquetas importadas) | `EXP 12/31/2026` | solo si leída día/mes es imposible; confianza −0,15 |

- **Palabras clave de vencimiento**: `VTO`, `VENC`, `VENCE`, `VENCIMIENTO`, `EXP`, `CAD`, `BB`, `BEST BEFORE (END)`,
  `CONSUMIR (PREFERENTEMENTE) ANTES DE`, `FV`/`F.V.`, `V:`… **De elaboración**: `ELAB`, `F.ELAB`, `FAB`, `ENVASADO`,
  `MFG`, `PROD`, `FE`/`F.E.`, `FP`…
  Cada fecha toma la palabra clave más cercana que la precede; también funciona si la palabra clave está sola en la
  línea anterior (`VENCIMIENTO:` / `25/09/2026`) y con encabezados combinados (`ELAB/VTO: 10/09/26 10/03/27`).
- **Fechas sin prefijo**: la más lejana es candidata a vencimiento y la más antigua a elaboración, con menos confianza.
- **Lotes**: prefijos `LOTE`, `LOT`, `Nº LOTE`, `LT`, `BATCH`, `PARTIDA` (se toma el código que sigue); `L.`/`L:`/`L-`
  y `L ` con espacio (`L.2409A` o `L 2409A` → `L2409A`, y como alternativa `2409A`; `1 L 2409` no, porque esa L son
  litros); códigos sueltos tipo `L2409A`; y, con baja confianza, códigos alfanuméricos mezclados. El valor se
  normaliza igual que `LotNumbers.normalize` del backend (solo `[A-Z0-9]`), así coincide con los recalls. Las medidas
  sueltas (`340 G`, `1L`) nunca son lotes; si aparecen justo después de `LOTE` (`LOTE 500G`) quedan con confianza
  baja, y se ignoran las líneas de RNPA, CUIT y teléfonos.
- **Nombre del producto**: líneas mayormente de letras, sin datos ni textos legales ("INDUSTRIA ARGENTINA",
  "CONT. NETO", ingredientes…), priorizando **letra grande** (altura medida por Tesseract), confianza y posición;
  también se prueba unir dos líneas consecutivas de igual tamaño ("SOPA DE TOMATE" + "LA HUERTA").
- **Códigos impresos**: números de 13 dígitos (EAN-13) o UPC-A válidos leídos como texto se suman a los que lee zxing.
- **Confianza** (0..1): base según el formato (fecha completa 0,9; mes/año 0,75), +0,05 con palabra clave, × 0,65 sin
  palabra clave, penalización por fechas implausibles (vencimiento de hace más de un año, elaboración futura) y
  × (0,6 + 0,4 · confianza OCR de la línea). Todas las listas vienen ordenadas de mayor a menor confianza.

## 4. Códigos de barras (`/v1/barcode`, `app/barcode.py`)

**zxing-cpp** busca EAN-13, EAN-8, UPC-A, UPC-E, Code 128, Code 39, Code 93, QR y DataMatrix con rotación automática.
Si la imagen original no da resultados prueba, en orden y hasta encontrar algo: CLAHE, reescalado, enfoque (unsharp
mask), Otsu, umbral adaptativo, imagen invertida (barras claras sobre fondo oscuro) y rotaciones de ±45°, cada una
con dos binarizadores. ITF, Codabar y DataBar se excluyen a propósito: en fotos con ruido producían lecturas falsas y
no identifican productos de góndola. Los formatos se informan con los nombres de ZXing que usa el frontend (`EAN_13`, `CODE_128`, `QR_CODE`…).

## 5. Errores y logs

Errores con formato `{"code","message","fieldErrors"?}` en español:

| HTTP | `code` | Cuándo |
|---|---|---|
| 422 | `VALIDATION_ERROR` | JSON inválido; `fieldErrors` indica campo y motivo ("es obligatorio", "debe ser uno de: FIFO, FEFO"; `body` si el JSON está mal formado) |
| 400 | `INVALID_FILE` | el archivo no es una imagen válida o está vacío |
| 413 | `FILE_TOO_LARGE` | imagen de más de 15 MB |
| 503 | `OCR_UNAVAILABLE` | Tesseract no instalado |
| 500 | `INTERNAL_ERROR` | error inesperado (queda en el log con el stack trace) |

**Tolerancia del pedido**: `settings`, `feedback`, `products`, `dailySales` y `lots` en `null` se toman como
vacíos o valores por defecto; `maxDiscountPct` admite 0..100, `targetCoverageDays` 0 se toma como 1 día y los lead
times admiten hasta 365 días. Las cantidades de la respuesta se acotan a enteros de 32 bits y los importes a
NUMERIC(14,2): un dato absurdo (una venta de 2.000 millones de unidades) nunca rompe la deserialización en Java ni
el guardado en la base.

Los logs son **JSON de una línea** (`ts`, `level`, `logger`, `message` + campos): cada request con `requestId`
(respeta `X-Request-Id`), método, ruta, status y duración; cada análisis con tenant, sucursal, rotación, productos,
recomendaciones y duración. No se registran datos de ventas ni imágenes. `/health` se loguea en nivel DEBUG para no
llenar el log con los healthchecks. El nivel se cambia con `LOG_LEVEL`.

## 6. Tests (`ai-service/tests`, 198 casos)

- `synthetic.py`: generadores de series para **cada patrón** (estable, finde fuerte, intermitente, crecimiento,
  declive, baja rotación, sin movimiento, poca historia) y armado de pedidos.
- `test_patterns.py`: el clasificador reconoce cada patrón (≥ 18/20 series), perfil semanal, tendencia significativa,
  KMeans reasigna casos dudosos, ABC/XYZ.
- `test_forecasting.py`: nivel correcto, forma semanal de Holt-Winters, Croston para intermitentes, intervalos, un
  pico extremo no infla el pronóstico y un cambio de nivel sostenido no se recorta.
- `test_anomalies.py`: tasa de falsos positivos en series limpias (estables, de bajo volumen, estacionales e
  intermitentes), detección de picos reales y ausencia de "caídas" falsas después de un pico.
- `test_inventory.py`: fórmulas de stock de seguridad, punto de pedido, cantidad sugerida, vida útil, cobertura.
- `test_lots.py`: orden FIFO/FEFO idéntico al backend con los lotes en liquidación primero, lotes vencidos nunca
  consumidos, lote nuevo bloqueado por FIFO, un lote ya en liquidación no queda en riesgo detrás de mercadería vieja,
  escalón mínimo según elasticidad, tope de descuento, simular el descuento pone el lote primero.
- `test_stockouts.py`: días sin stock completados (al final y en el medio de la serie, perfil semanal, tramos sin
  historia o demasiado largos); un producto agotado mantiene su demanda real en patrón, venta diaria, tendencia y
  reposición; un repuesto no queda "en declive"; la tendencia de un producto en alza es la del patrón.
- `test_discounts.py`: elasticidad por defecto, el feedback la sube o baja, transferencia entre categorías.
- `test_recommendations.py`: los 5 tipos, `dedupeKey`, orden por prioridad (vencido > reposición > anomalía),
  explicación FIFO, FEFO no genera el descuento, un lote ya en liquidación no recibe otro descuento, un lote detrás
  de una liquidación lo explica, el feedback cambia el descuento sugerido, liquidación sin ventas,
  fechas de otro año con año, formato es-AR.
- `test_parse.py`: más de 50 textos de etiquetas reales (`VTO: 12/10/26`, `VENCE 03-2027`, `L.2409A`,
  `LOTE 24091B F.ELAB 10/09/2026 VTO 10/03/2027`, `CONSUMIR ANTES DE 25 SEP 2026`…).
- `test_preprocess.py`: fotos de 50 MP se decodifican reducidas, orientación EXIF, archivos rotos o demasiado grandes.
- `test_api.py`: `/health`; `/v1/analyze` con **80 productos × 180 días en menos de 20 s** y claves exactas del contrato,
  sin `NaN`/`Infinity`; `null` en listas y configuración; cantidades absurdas acotadas a `int`; errores en español; `/v1/ocr` con etiquetas generadas con Pillow (limpia, girada y "foto" con
  ruido en JPEG); `/v1/barcode` con EAN-13 generados con zxing-cpp (limpio, girado, invertido).

## 7. Limitaciones (y cómo se mitigan)

- **Datos de demo sintéticos**: los algoritmos se validaron con series simuladas; con datos reales habría que
  recalibrar umbrales (por ejemplo la significancia de tendencia o la amplitud estacional).
- **Elasticidad lineal y por categoría**: no distingue productos dentro de una categoría ni efectos no lineales
  (un 40% no rinde el doble que un 20%), ni canibalización con otros productos. El aprendizaje con feedback compensa
  en parte; más datos permitirían elasticidad por producto.
- **Descuento y rotación**: el backend descuenta primero de los lotes en liquidación y la simulación hace lo mismo,
  pero supone que en la góndola también sale primero el lote en oferta (que está a la vista y señalizado).
- **Picos acotados**: un aumento real y brusco de la demanda en los últimos 2-3 días se trata como pico hasta que se
  sostiene (la mediana móvil de 15 días tarda unos días en "creerle"); la anomalía sí se informa enseguida.
- **Sin factores externos**: no conoce feriados, clima, inflación, cambios de precio ni promociones de la competencia.
  Los quiebres de stock sí: el backend informa los días sin stock y se completan con la demanda previa (§2.1). Un
  faltante de más de 8 semanas no se completa (la venta de hace dos meses ya no dice cuánto se vendería hoy).
- **Lead time fijo**: se usa el del proveedor sin variabilidad; el stock de seguridad solo cubre la incertidumbre de la demanda.
- **Productos nuevos**: con menos de 14 días no hay patrón y el pronóstico es una media ponderada con confianza baja.
- **KMeans** solo reasigna casos dudosos: con pocos productos (< 8) o categorías muy distintas aporta poco.
- **OCR**: Tesseract lee muy bien texto impreso nítido, pero le cuestan las fechas impresas con **matriz de puntos o
  chorro de tinta** (típicas de los vencimientos), superficies curvas o brillantes y fotos movidas. Por eso la
  respuesta trae **candidatos con confianza** y la pantalla de carga los muestra para que el empleado confirme o corrija.
- **Códigos de barras**: la lectura desde foto depende del foco; en el celular conviene el escáner en vivo del
  frontend (`@zxing/browser`) y dejar `/v1/barcode` como alternativa. El EAN del escenario de recall de la demo
  (`7791234500017`) tiene dígito verificador válido, así que se lee igual desde las barras, tipeado o como texto impreso.
- **Sin estado**: cada análisis parte de cero; el aprendizaje depende de que el backend envíe el feedback.

## 8. Preguntas frecuentes para la defensa

- **¿Por qué no redes neuronales?** Con 180 días de ventas por producto y comercios chicos, los modelos estadísticos
  clásicos son más precisos, rápidos (150 productos con 180 días de ventas en ~2 s) y, sobre todo, **explicables**: cada recomendación dice
  por qué. Las redes neuronales sí se usan donde brillan: el OCR (Tesseract usa una LSTM).
- **¿Por qué varios métodos de pronóstico?** No hay un método que sirva para todo: Holt-Winters captura tendencia y
  patrón semanal, Croston/SBA es el estándar para demanda intermitente y la media ponderada es robusta con poca
  historia. La elección es automática y queda informada en `forecastMethod`.
- **¿Cómo sabe que una anomalía es real?** Dos métodos independientes: un z-score robusto sobre los errores del
  pronóstico (qué tan lejos quedó de lo esperado) e Isolation Forest (qué tan raro es ese día comparado con todos los
  días de todos los productos), más reglas de negocio (una venta con descuento no es anomalía).
- **¿Qué aprende el sistema?** La respuesta real de cada categoría a los descuentos (elasticidad) y qué tipos de
  recomendaciones acepta el comercio (confianza). Ambos con promedios ponderados que evitan sobre-reaccionar a un caso.
- **¿Por qué FIFO puede generar merma?** Porque vende primero lo que entró antes aunque venza después; si entra un lote
  más nuevo con vencimiento más corto, queda atrás en la fila. La IA simula ambas rotaciones, lo detecta y lo explica.
