# Datos de demostración de GondolIA

Guía del mundo demo que carga el módulo G la primera vez que arranca el sistema (`APP_SEED_DEMO=true` y base sin
comercios). Todo es **ficticio**: comercios, personas, marcas y códigos de barras (EAN-13 argentinos válidos `779…`
de empresas inventadas). Para volver a este estado inicial: `./start.sh reset`.

> Los datos se generan **relativos al día de la siembra**: "hace 40 días", "vence en 6 días", "turno abierto hoy",
> "recall publicado ayer a la tarde". Si la demo se hace varios días después de sembrar, conviene correr
> `./start.sh reset` esa mañana (también deja otra vez pendiente el recall del dulce de leche si lo resolviste en un
> ensayo).

## 1. Credenciales

Contraseña de la plataforma: **`Gondolia2026!`** · contraseña de todos los comercios: **`Demo2026!`**

| Usuario | Rol | Comercio / sucursales |
|---|---|---|
| `dueno@gondolia.app` | Dueño GondolIA (Federico Almada) | Plataforma |
| `socia@gondolia.app` | Dueña GondolIA (Valeria Quiroga) | Plataforma |
| `soporte@gondolia.app` | Soporte (Sofía Martínez) | Todos los tickets |
| `soporte2@gondolia.app` | Soporte (Tomás Aguirre) | Todos los tickets |
| `jefe@donpepe.com` · `admin@donpepe.com` | Jefe · Administradora | Almacén Don Pepe |
| `empleado@donpepe.com` · `cajero@donpepe.com` | Empleado · Cajera | Don Pepe — Sucursal Principal |
| `jefe@vidasana.com` · `admin@vidasana.com` | Jefe · Administradora | Dietética Vida Sana (las 2 sucursales) |
| `empleado@vidasana.com` | Empleada | Vida Sana — solo Nueva Córdoba |
| `empleado.cerro@vidasana.com` | Empleado | Vida Sana — solo Cerro de las Rosas |
| `jefe@elsol.com` · `admin@elsol.com` | Jefe · Administradora (Laura Gómez) | Minimercado El Sol (las 3 sucursales) |
| `empleado@elsol.com` | Empleado | El Sol — Centro y Fisherton |
| `empleado.echesortu@elsol.com` | Empleada | El Sol — solo Echesortu |
| `cajero@elsol.com` | Cajera (Brenda Sosa) | El Sol — solo Centro (**tiene la caja abierta hoy**) |
| `cajero.fisherton@elsol.com` · `cajera.tarde@elsol.com` | Cajeros | El Sol — solo Fisherton |
| `admin@laesquina.com` (+ `jefe@`, `empleado@`) | Administradora | Kiosco La Esquina — **deshabilitado** |

Comercios livianos: `jefe@`, `admin@` y `empleado@` + dominio (por ejemplo `admin@farmaciasanmartin.com.ar`,
`admin@minimercadolosandes.com.ar`, `admin@puntofresco.com.ar`). Dados de baja o deshabilitados: Kiosco 24 Horas
Centro y Despensa El Trébol (baja), Dietética Grano de Oro (deshabilitada).

## 2. Quién es quién

### Almacén Don Pepe — almacén de barrio (CABA, plan Básico)
- 1 sucursal ("Sucursal Principal"), rotación **FIFO**, módulo **POS GondolIA** (sin multi-sucursal ni POS propio).
- 60 productos. Vende todo por el POS GondolIA: Caja 1 (turno mañana Rocío, turno tarde Lucas) y Caja 2 los sábados.
- Tiene en stock el **lote `L2409A` de la sopa de tomate** (recall en vivo) y el **lote `DV2603B` del dulce de leche
  en cuarentena** por el recall que salió ayer y todavía nadie atendió (ver §5.1).

### Dietética Vida Sana — dos locales en Córdoba (plan Profesional)
- "Sucursal Nueva Córdoba" y "Sucursal Cerro de las Rosas", rotación **FEFO** (sale primero lo que vence antes),
  módulos **Integración con POS propio** y **Multi-sucursal**. 60 productos de dietética.
- Las ventas llegan de su propio sistema de caja por API (`POS externo`, referencias `S-EXT-NC-…` / `S-EXT-CR-…`),
  con algún faltante cuando el POS vendió algo que no estaba cargado. El administrador además registra ventas
  mayoristas a mano cada quince días. Transferencias entre los dos locales.
- API keys de demo (solo para mostrar el webhook; regeneralas si la instancia queda expuesta):
  - Nueva Córdoba: `gk_DemovidasanaNCB0123456789ABCDEFGHJKLMNPQ`
  - Cerro de las Rosas: `gk_DemovidasanaCDR0123456789ABCDEFGHJKLMNPQ`

### Minimercado El Sol — tres sucursales en Rosario (plan Profesional)
- "Sucursal Centro", "Sucursal Fisherton" y "Sucursal Echesortu", rotación **FIFO**, **los 3 módulos**. 66 productos.
- Centro y Fisherton venden con el **POS GondolIA** (Caja 1 y Caja 2); Echesortu importó un CSV de ventas por día los
  primeros 60 días y después conectó su POS por API (key de demo `gk_DemoelsolECH0123456789ABCDEFGHJKLMNPQRST`).
  Además, un viernes por medio la administradora carga a mano (Ventas → Registrar venta) el pedido de bebidas y snacks
  que el club del barrio retira de Centro para el fin de semana, así El Sol tiene ventas de las cuatro fuentes.
- Arrancó con una **importación masiva APLICADA** ("stock-el-sol-marzo-2026.xlsx": catálogo + stock inicial de las 3
  sucursales, con 3 filas omitidas en la revisión).
- El lote `L2409A` de la sopa está **solo en Fisherton**; Centro y Echesortu tienen la sopa con otros lotes.
- El lote `DV2603B` del dulce de leche (recall pendiente, §5.1) está **solo en Centro**, en cuarentena.

### Kiosco La Esquina (La Plata, plan Gratis, POS GondolIA) — **deshabilitado**
Deshabilitado hace 18 días "por uso indebido" (compartían el usuario entre locales; hay un ticket de soporte que lo
anticipa). Sirve para mostrar el bloqueo: login → "El acceso de tu comercio está deshabilitado…". Desde la consola
de dueños se puede rehabilitar en vivo.

### 13 comercios livianos
Farmacias, almacenes, kioscos, dietéticas y minimercados de todo el país dados de alta a lo largo de los últimos 12
meses, con planes y módulos variados, un cambio de plan (Doña Rosa pasó de Gratis a Básico), una deshabilitación
por falta de pago ya regularizada (Maxikiosco Estación), una deshabilitación vigente (Grano de Oro) y dos bajas.
Tienen catálogos chicos y algunas semanas de ventas manuales. Alimentan las métricas de crecimiento, churn, MRR,
adopción de módulos y actividad de la consola de dueños.

### Módulos por comercio

Con qué módulos arranca cada comercio (SPEC §11 y §14). Es la matriz que muestra **Módulos por cliente**
(`/owner/modules`) y la que alimenta la adopción por módulo y el MRR estimado de las métricas del dueño: ningún módulo
llega al 100 % ni se queda sin clientes. El ID es el del comercio en una base recién sembrada.

| ID | Comercio | Estado | POS GondolIA | Integración POS | Multi-sucursal |
|---|---|---|---|---|---|
| 1 | Almacén Don Pepe | Activo | sí | — | — |
| 2 | Dietética Vida Sana | Activo | — | sí | sí |
| 3 | Minimercado El Sol | Activo | sí | sí | sí |
| 4 | Kiosco La Esquina | Deshabilitado | sí | — | — |
| 5 | Farmacia San Martín | Activo | sí | sí | sí |
| 6 | Almacén La Abuela | Activo | sí | sí | sí |
| 7 | Kiosco 24 Horas Centro | Dado de baja | sí | — | — |
| 8 | Dietética Natural Sur | Activo | — | sí | — |
| 9 | Minimercado Los Andes | Activo | sí | sí | sí |
| 10 | Almacén Doña Rosa | Activo | sí | sí | — |
| 11 | Farmacia del Pueblo | Activo | sí | sí | sí |
| 12 | Despensa El Trébol | Dado de baja | sí | — | — |
| 13 | Maxikiosco Estación | Activo | sí | sí | — |
| 14 | Dietética Grano de Oro | Deshabilitado | sí | sí | sí |
| 15 | Almacén Los Hermanos | Activo | sí | — | — |
| 16 | Kiosco El Paso | Activo | sí | — | — |
| 17 | Minimercado Punto Fresco | Activo | sí | sí | sí |

> Si durante una demo (o una prueba automatizada que hace clic en todo) quedaron switches tocados, **no hace falta
> resembrar**: volvé a esta tabla desde `/owner/modules` —habilitar es directo, deshabilitar pide confirmación— o con
> `PUT /api/platform/tenants/{id}/modules/{MODULO}` `{"enabled": false}` como dueño. `./start.sh reset` también deja
> esta matriz, pero borra todo el resto del mundo demo.

## 3. Qué hay en los datos (180 días por sucursal)

- **Patrones de venta**: finde fuerte (gaseosas, cerveza, snacks, fiambres), estable (leche, pan, fideos),
  intermitente (especias, harina de almendras, tahini), creciente (pan integral, yerba compuesta, bebidas vegetales,
  kombucha, helado), decreciente (café, soda, jabón en polvo, quinoa), **sin movimiento** (Aceite de oliva Olivar del
  Sur en Don Pepe, Salsa barbacoa en El Sol, Colágeno en Vida Sana) y **picos anómalos** (papas fritas en Don Pepe y El
  Sol Centro en los últimos días, mix de frutos secos en Nueva Córdoba, alfajores en Echesortu).
- **Varios lotes por producto** con distintas fechas de ingreso y vencimiento (compras de oportunidad, compras
  grandes de fideos, arroz, atún, lentejas, almendras…).
- **Lote nuevo que vence antes que uno viejo** (aviso FIFO): Crema de leche en Don Pepe y en El Sol Fisherton, Queso
  untable en El Sol Centro y Echesortu. En Vida Sana (FEFO) el mismo caso con la Kombucha muestra que el lote que
  vence antes sale primero.
- **Liquidación en curso**: Jamón cocido en El Sol Centro con 25 % aceptado hace 2 días ("En liquidación · sale
  primero"). **Descuentos históricos aceptados con resultado medido** (yogures, jamón, crema, salchichas, hummus): la
  IA los recibe como feedback para ajustar la elasticidad.
- **Sin stock / bajo mínimo** (el proveedor dejó de entregar entre 11 y 24 días antes de la siembra; los pedidos
  pendientes ya no llegan y la historia no los cubre con transferencias, así se puede mostrar una en vivo): Yerba y
  Aceite en Don Pepe, Leche descremada y Detergente en Centro, Huevos y Arroz en Fisherton, Agua y Gaseosa cola en
  Echesortu, Chía y Granola en Nueva Córdoba, Avena en Cerro de las Rosas. Pasa lo mismo, sea cual sea el día en que
  se siembre, con Galletitas de agua y Lavandina en Don Pepe, Galletitas dulces en Centro y Bebida de almendras en
  Cerro de las Rosas.
- **Vencidos pendientes de descarte**: Manteca (Don Pepe), Leche chocolatada (Centro), Salame (Fisherton), Pan integral
  (Echesortu), Tofu (Nueva Córdoba), Hummus (Cerro), además de los que el personal todavía no descartó.
- **Mermas** por vencimiento y roturas, **ajustes** de recuento y **transferencias** entre sucursales.
- **POS GondolIA**: ~46.000 tickets con pagos en efectivo, débito, crédito, transferencia y QR, pagos combinados,
  vuelto, anulaciones, retiros de efectivo, turnos cerrados con arqueo (algunos con faltante o sobrante y su nota) y
  turnos abiertos hoy.
- **Recomendaciones ya decididas** (aceptadas y descartadas con su nota) y las que genera la IA al arrancar.
- **Avisos**: 4 generales publicados (uno solo para almacenes, minimercados, dietéticas y kioscos), uno archivado, un
  borrador y el **recall pendiente** "Dulce de leche Dulce Valle 400 g, lote DV2603B", publicado **ayer a las 18:40**:
  alcanzó a Don Pepe y a El Sol Centro y ninguno de los dos lo resolvió todavía (lotes en cuarentena, alerta de recall
  abierta, notificaciones sin leer). Está listo para mostrarlo y resolverlo en la demo (§5.1).
- **Soporte**: 10 tickets con conversación — uno de Don Pepe con **foto de la etiqueta** que la cámara no leía, uno de
  El Sol **abierto y sin asignar** hace 25 minutos ("La impresora de tickets de Caja 1 imprime cortado", urgente), uno
  **esperando respuesta** del jefe de El Sol, calificaciones y chats cerrados.

Las **alertas** y las **recomendaciones pendientes** no se siembran: las generan el motor de alertas y la IA en los
primeros segundos después de arrancar. La excepción son las dos alertas críticas del recall del dulce de leche, que
el motor no maneja: quedan abiertas desde que se detectó el recall, igual que en vivo.

## 4. Guion sugerido (10–15 minutos)

1. **Dueño** (`dueno@gondolia.app`): Métricas (crecimiento de 12 meses con altas y bajas, MRR, adopción de módulos,
   soporte, recalls en curso), Clientes (Kiosco La Esquina deshabilitado con su motivo), Módulos por cliente, Avisos y
   recalls (el del dulce de leche con 2 clientes alcanzados: el dueño ve cuántos, nunca cuáles).
2. **Bloqueo**: login con `admin@laesquina.com` → mensaje de comercio deshabilitado. Rehabilitarlo desde la consola
   y volver a entrar.
3. **Recall pendiente en Don Pepe** (`admin@donpepe.com`): al entrar salta el diálogo rojo de Seguridad alimentaria
   por el dulce de leche; mostrar el Inicio, la campana, el POS que no deja venderlo (`cajero@donpepe.com`) y
   resolverlo en vivo (ver §5.1). Así Don Pepe queda limpio para el recall en vivo del final.
4. **Jefe de El Sol** (`jefe@elsol.com`): al entrar le salta el diálogo del dulce de leche de Centro; como jefe puede
   confirmarlo ("Entendido") o ver el detalle, pero no retirarlo del stock (lo hace la administradora en el paso
   siguiente). Después: Inicio consolidado de las 3 sucursales (productos, por vencer, stock bajo, valor de
   inventario, ventas de hoy), cambiar a una sucursal, Estadísticas, Inteligencia IA (patrones: finde fuerte,
   creciente, intermitente, sin movimiento; recomendaciones con explicación, que el jefe acepta o descarta) y
   Alertas. Desde el Inicio, "Ver todos" abre Vencimientos e Inventario (stock bajo) en solo lectura, y también ve
   el historial de Ventas.
5. **Administradora de El Sol** (`admin@elsol.com`): resolver el dulce de leche de Centro desde Seguridad alimentaria
   (§5.1); aceptar un descuento sugerido; ver en Inventario el Queso untable de Centro con dos lotes y la etiqueta "Se
   vende primero"; Vencimientos (vencidos pendientes → descartar); Transferencias; Importar Excel/CSV (la importación
   de marzo aplicada, con sus filas); Cajas y turnos (arqueos con diferencia); Ventas (fuentes POS GondolIA, POS
   externo, CSV y manual).
6. **Cajera** (`cajero@elsol.com`): el turno de Caja 1 ya está abierto; cobrar con escáner o búsqueda, pago combinado,
   ticket, y cerrar la caja con arqueo. (Si el dulce de leche de Centro sigue pendiente, al entrar le salta el diálogo
   y el POS no deja venderlo.)
7. **Vida Sana** (`admin@vidasana.com`): rotación FEFO (Kombucha), Integración POS y el webhook en vivo:
   ```bash
   curl -X POST http://localhost:8080/api/integrations/pos/sales \
     -H "X-API-Key: gk_DemovidasanaNCB0123456789ABCDEFGHJKLMNPQ" -H "Content-Type: application/json" \
     -d '{"externalId":"NC-DEMO-1","items":[{"barcode":"7799101100018","quantity":2}]}'
   ```
   (Almendras peladas Cosecha Natural 250 g; la venta aparece en Ventas como "POS externo").
8. **Soporte** (`soporte@gondolia.app`): tomar el ticket urgente de El Sol y responder en vivo mientras
   `cajero@elsol.com` mira su chat; mostrar la foto adjunta del ticket de Don Pepe.
9. **Recall en vivo** (ver §5.2).

## 5. Seguridad alimentaria: recall pendiente y recall en vivo

### 5.1 Recall pendiente del dulce de leche (ya está cargado)

Producto: **Dulce de leche Dulce Valle 400 g**, EAN **`7798101100011`**, lote **`DV2603B`** (vence unos dos meses
después de la siembra). Motivo: falla en el sellado de los potes detectada por el fabricante.

**Qué pasó.** La socia de GondolIA (`socia@gondolia.app`) publicó el recall **ayer a las 18:40** (siempre relativo
al día de la siembra). GondolIA lo detectó en el acto en dos comercios: **Almacén Don Pepe — Sucursal Principal**
(24 u.) y **Minimercado El Sol — Sucursal Centro** (30 u.). El lote había entrado esa misma mañana, así que quedó
entero en cuarentena. **Nadie lo atendió todavía**: la coincidencia está sin confirmar, la alerta de recall abierta
y las notificaciones sin leer. Es exactamente lo que deja el recall en vivo de §5.2 un rato después de publicarlo.

**Quién lo ve al entrar** (el diálogo rojo bloqueante, con sonido, y la alerta en la campana):

| Comercio | Le salta el diálogo | No le salta (solo le llega el aviso en Avisos, sin "Te afecta") |
|---|---|---|
| Almacén Don Pepe | los 4 usuarios (`jefe@`, `admin@`, `empleado@`, `cajero@donpepe.com`) | — |
| Minimercado El Sol | `jefe@`, `admin@`, `empleado@` (Centro y Fisherton) y `cajero@elsol.com` (Centro) | `empleado.echesortu@`, `cajero.fisherton@` y `cajera.tarde@elsol.com` (no tienen Centro) |
| El resto | — | todos los usuarios de los comercios habilitados |

**Qué mostrar antes de resolverlo** (por ejemplo en Don Pepe):
- El **diálogo** "Alerta de seguridad alimentaria": "lote detectado el …18:40", "Sucursal Principal · 24 u. en
  cuarentena", el motivo y los pasos. "Entendido" confirma que lo viste; "Ver detalle y retirar del stock" lleva a
  Seguridad alimentaria (el jefe y la cajera ven "Ver detalle": ellos no pueden retirarlo).
- **Inicio**: "1 lote alcanzado por un recall" con el botón "Ver recalls". **Alertas** (jefe y administradora): la
  alerta crítica de recall abierta. **Campana**: "Alerta de recall: Dulce de leche…" sin leer (lleva directo a la
  coincidencia) y el aviso del recall. **Avisos**: la tarjeta del recall con la píldora "Te afecta".
- **POS** (`cajero@donpepe.com`): el dulce de leche aparece bloqueado ("En cuarentena por recall · no se puede
  vender") aunque en la góndola haya otros lotes: mientras haya unidades en cuarentena, el mostrador no vende el
  producto. Desde que salió el recall no se vendió ni un dulce de leche en esas dos sucursales.
- **Consola de dueños** → Avisos y recalls: "1 recall en curso" y "2 clientes alcanzados" (solo cantidades).

**Cómo resolverlo en vivo, paso a paso:**
1. Entrá con `admin@donpepe.com` (o `empleado@donpepe.com`). Salta el diálogo: tocá **Entendido** (queda
   "Confirmado por Marta Fernández") o **Ver detalle y retirar del stock**.
2. En **Seguridad alimentaria** (filtro "Para resolver") está la tarjeta del dulce de leche con el lote, el
   vencimiento, las unidades en cuarentena, el motivo y las instrucciones.
3. **Retirar del stock** → en "¿Qué hiciste con la mercadería?" elegí **Devuelto al proveedor** (o "Retirado del
   stock"), escribí una nota (por ejemplo "Lo retira el distribuidor el jueves") y confirmá **Retirar 24 u.**
4. Resultado: el lote queda en 0 con su movimiento (`Devolución al proveedor por recall #…` o `Retiro por recall #…`),
   la alerta se cierra, el Inicio deja de pedirlo y la tarjeta pasa a "Resueltos" con quién lo confirmó, quién lo
   resolvió y la nota. El POS vuelve a vender los otros lotes del dulce de leche; mientras el recall siga publicado
   no ofrece "Vender igual" por encima del stock cargado (una unidad sin lote registrado podría ser del lote retirado).
5. En El Sol, lo mismo en Centro con `admin@elsol.com` o `empleado@elsol.com` (si tienen elegida otra sucursal en el
   topbar, la alerta los lleva igual a Centro).

> **Ojo con "Entendido"**: la confirmación es de la coincidencia, no de cada usuario. Cuando alguien toca "Entendido",
> el diálogo deja de saltarle al resto (la tarjeta sigue en Seguridad alimentaria como "Confirmados, sin retirar"
> hasta que alguien la resuelva). "Ver detalle" no confirma nada: el diálogo vuelve a salir la próxima vez que se
> entra o se recarga la página. Si lo resolviste en un ensayo, `./start.sh reset` lo deja otra vez pendiente (y
> vuelve a sembrar todo lo demás).

### 5.2 Recall en vivo, paso a paso

Producto: **Sopa de tomate en lata La Huerta 340 g**, EAN **`7791234500017`**, lote **`L2409A`**
(vence 30/09/2027). Está en stock en **Almacén Don Pepe** (Sucursal Principal) y en **Minimercado El Sol, solo en
Fisherton**. Vida Sana no vende el producto; El Sol Centro y Echesortu tienen la sopa con otros lotes.

Conviene hacerlo con el dulce de leche ya resuelto (§5.1): si sigue pendiente, a los usuarios de Don Pepe y de El Sol
Centro les salta primero ese diálogo al entrar y el Inicio cuenta los lotes de los dos recalls.

1. Abrí tres ventanas (o perfiles del navegador) y entrá con:
   - `admin@donpepe.com` (o `cajero@donpepe.com`) → va a recibir la alerta;
   - `empleado@elsol.com` (Centro y Fisherton) → va a recibir la alerta de **Fisherton**;
   - `empleado.echesortu@elsol.com` (control: solo tiene Echesortu, que no tiene ni el lote de la sopa ni el del
     dulce de leche, así que no le salta ningún diálogo; el aviso general del recall sí le llega, ver el paso 3).
     No uses `cajero@elsol.com` como control mientras el dulce de leche de Centro siga pendiente: tiene Centro y al
     entrar le salta ese diálogo.
2. En otra ventana, `dueno@gondolia.app` → **Avisos y recalls** → **Nuevo aviso** → tipo **Recall**:
   - Producto: `Sopa de tomate en lata La Huerta 340 g` · Marca: `La Huerta` · Código de barras: `7791234500017`
   - Lotes: `L2409A` (sin marcar "todos los lotes", sin rango de vencimiento)
   - Motivo: "Posible falla en el cierre de las latas detectada por el fabricante"
   - Instrucciones: "Retirá las latas del lote L2409A de la góndola y del depósito y separalas para su devolución."
   - **Vista previa**: 2 comercios afectados y 2 lotes (unas 50 unidades). El dueño solo ve cantidades, nunca cuáles.
3. **Publicar**. En el acto:
   - Don Pepe y los usuarios de El Sol con acceso a Fisherton ven el aviso de **Seguridad alimentaria** en pantalla
     (el diálogo rojo bloqueante) y la alerta de recall en la campana.
   - El usuario de control **no** ve el diálogo ni la alerta de recall: los recalls llegan como aviso a todos los
     comercios, así que en su campana sí aparece la notificación del aviso y en **Avisos** la tarjeta del recall,
     pero **sin** la píldora "Te afecta" y sin lotes para retirar. Ese es el contraste que se muestra: el que
     tiene el lote queda bloqueado, el que no, solo se entera.
   - Los dos lotes quedan **en cuarentena** (`RECALLED`): dejan de ser vendibles, el POS los bloquea y el Inicio muestra
     la coincidencia abierta.
   - La consola de dueños muestra "2 clientes alcanzados" en la tarjeta del recall de la sopa, y el contador de
     recalls en curso suma uno.
4. En Don Pepe, **antes de resolver**, probá vender la sopa desde el POS con `cajero@donpepe.com`: el producto aparece
   bloqueado por cuarentena ("En cuarentena por recall · no se puede vender").
   Después, **Seguridad alimentaria** → "Entendido" → **Retirar del stock** (o "Devolver al proveedor") con una nota.
   El lote queda en 0 con su movimiento `Retiro por recall`. Si volvés a escanear la sopa en el POS, sigue sin poder
   venderse: mientras el recall esté publicado el mostrador avisa "Recall vigente (lote L2409A)" y **no** ofrece
   "Vender igual", porque una lata sin lote registrado puede ser del lote retirado.
5. En El Sol, `empleado@elsol.com` resuelve la de Fisherton. Aunque tenga elegida Centro en el topbar, la alerta le
   aparece igual (se piden las de todas sus sucursales) y "Ver detalle y retirar del stock" lo lleva a Fisherton;
   la alerta de recall de la campana lleva a la misma coincidencia, también cambiando de sucursal sola. La
   sopa de Centro (otro lote, ya chequeado al cargarlo) se sigue vendiendo hasta agotar lo cargado; por encima de eso
   tampoco hay "Vender igual" mientras dure el recall.
6. Para comparar: en **Seguridad alimentaria** de Don Pepe o de El Sol, en "Resueltos", queda el dulce de leche que
   resolviste en §5.1, con quién lo confirmó, quién lo resolvió y la nota.

## 6. Notas técnicas

- Detalles de implementación, tiempos y decisiones: [`api-g.md`](api-g.md).
- La siembra tarda ≈ 30 s y corre una sola vez (si la base ya tiene comercios no hace nada). Con `APP_DEV_FIXTURE=true`
  se carga el fixture de desarrollo en su lugar.
- Los datos son determinísticos por día: sembrar dos veces el mismo día produce el mismo mundo.
