# Datos de demostración de GondolIA

Guía del mundo demo que carga el módulo G la primera vez que arranca el sistema (`APP_SEED_DEMO=true` y base sin
comercios). Todo es **ficticio**: comercios, personas, marcas y códigos de barras (EAN-13 argentinos válidos `779…`
de empresas inventadas). Para volver a este estado inicial: `./start.sh reset`.

> Los datos se generan **relativos al día de la siembra**: "hace 52 días", "vence en 6 días", "turno abierto hoy".
> Si la demo se hace varios días después de sembrar, conviene correr `./start.sh reset` esa mañana.

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
- Tiene en stock el **lote `L2409A` de la sopa de tomate** (recall en vivo) y resolvió el recall histórico del dulce
  de leche devolviéndolo al proveedor.

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
- Arrancó con una **importación masiva APLICADA** ("stock-el-sol-marzo-2026.xlsx": catálogo + stock inicial de las 3
  sucursales, con 3 filas omitidas en la revisión).
- El lote `L2409A` de la sopa está **solo en Fisherton**; Centro y Echesortu tienen la sopa con otros lotes.

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
- **Sin stock / bajo mínimo** (el proveedor dejó de entregar): Yerba y Aceite en Don Pepe, Leche descremada y
  Detergente en Centro, Huevos y Arroz en Fisherton, Agua y Gaseosa cola en Echesortu, Chía y Granola en Nueva Córdoba,
  Avena en Cerro de las Rosas.
- **Vencidos pendientes de descarte**: Manteca (Don Pepe), Leche chocolatada (Centro), Salame (Fisherton), Pan integral
  (Echesortu), Tofu (Nueva Córdoba), Hummus (Cerro), además de los que el personal todavía no descartó.
- **Mermas** por vencimiento y roturas, **ajustes** de recuento y **transferencias** entre sucursales.
- **POS GondolIA**: ~46.000 tickets con pagos en efectivo, débito, crédito, transferencia y QR, pagos combinados,
  vuelto, anulaciones, retiros de efectivo, turnos cerrados con arqueo (algunos con faltante o sobrante y su nota) y
  turnos abiertos hoy.
- **Recomendaciones ya decididas** (aceptadas y descartadas con su nota) y las que genera la IA al arrancar.
- **Avisos**: 4 generales publicados (uno solo para almacenes, minimercados, dietéticas y kioscos), uno archivado, un
  borrador y el **recall histórico** "Dulce de leche Dulce Valle 400 g, lote DV2603B" (hace 52 días), resuelto por Don
  Pepe (devolución al proveedor) y por El Sol Centro (retiro del stock).
- **Soporte**: 10 tickets con conversación — uno de Don Pepe con **foto de la etiqueta** que la cámara no leía, uno de
  El Sol **abierto y sin asignar** hace 25 minutos ("La impresora de tickets de Caja 1 imprime cortado", urgente), uno
  **esperando respuesta** del jefe de El Sol, calificaciones y chats cerrados.

Las **alertas** y las **recomendaciones pendientes** no se siembran: las generan el motor de alertas y la IA en los
primeros segundos después de arrancar.

## 4. Guion sugerido (10–15 minutos)

1. **Dueño** (`dueno@gondolia.app`): Métricas (crecimiento de 12 meses con altas y bajas, MRR, adopción de módulos,
   soporte), Clientes (Kiosco La Esquina deshabilitado con su motivo), Módulos por cliente.
2. **Bloqueo**: login con `admin@laesquina.com` → mensaje de comercio deshabilitado. Rehabilitarlo desde la consola
   y volver a entrar.
3. **Jefe de El Sol** (`jefe@elsol.com`): Inicio consolidado de las 3 sucursales (productos, por vencer, stock bajo,
   valor de inventario, ventas de hoy), cambiar a una sucursal, Estadísticas, Inteligencia IA (patrones: finde fuerte,
   creciente, intermitente, sin movimiento; recomendaciones con explicación) y Alertas.
4. **Administradora de El Sol** (`admin@elsol.com`): aceptar un descuento sugerido; ver en Inventario el Queso untable
   de Centro con dos lotes y la etiqueta "Se vende primero"; Vencimientos (vencidos pendientes → descartar);
   Transferencias; Importar Excel/CSV (la importación de marzo aplicada, con sus filas); Cajas y turnos (arqueos con
   diferencia); Ventas (fuentes POS GondolIA, POS externo, CSV y manual).
5. **Cajera** (`cajero@elsol.com`): el turno de Caja 1 ya está abierto; cobrar con escáner o búsqueda, pago combinado,
   ticket, y cerrar la caja con arqueo.
6. **Vida Sana** (`admin@vidasana.com`): rotación FEFO (Kombucha), Integración POS y el webhook en vivo:
   ```bash
   curl -X POST http://localhost:8080/api/integrations/pos/sales \
     -H "X-API-Key: gk_DemovidasanaNCB0123456789ABCDEFGHJKLMNPQ" -H "Content-Type: application/json" \
     -d '{"externalId":"NC-DEMO-1","items":[{"barcode":"7799101100018","quantity":2}]}'
   ```
   (Almendras peladas Cosecha Natural 250 g; la venta aparece en Ventas como "POS externo").
7. **Soporte** (`soporte@gondolia.app`): tomar el ticket urgente de El Sol y responder en vivo mientras
   `cajero@elsol.com` mira su chat; mostrar la foto adjunta del ticket de Don Pepe.
8. **Recall en vivo** (ver §5).

## 5. Recall en vivo, paso a paso

Producto: **Sopa de tomate en lata La Huerta 340 g**, EAN **`7791234500017`**, lote **`L2409A`**
(vence 30/09/2027). Está en stock en **Almacén Don Pepe** (Sucursal Principal) y en **Minimercado El Sol, solo en
Fisherton**. Vida Sana no vende el producto; El Sol Centro y Echesortu tienen la sopa con otros lotes.

1. Abrí tres ventanas (o perfiles del navegador) y entrá con:
   - `admin@donpepe.com` (o `cajero@donpepe.com`) → va a recibir la alerta;
   - `empleado@elsol.com` (Centro y Fisherton) → va a recibir la alerta de **Fisherton**;
   - `empleado.echesortu@elsol.com` o `cajero@elsol.com` (control: **no** recibe nada, no tiene acceso a Fisherton).
2. En otra ventana, `dueno@gondolia.app` → **Avisos y recalls** → **Nuevo aviso** → tipo **Recall**:
   - Producto: `Sopa de tomate en lata La Huerta 340 g` · Marca: `La Huerta` · Código de barras: `7791234500017`
   - Lotes: `L2409A` (sin marcar "todos los lotes", sin rango de vencimiento)
   - Motivo: "Posible falla en el cierre de las latas detectada por el fabricante"
   - Instrucciones: "Retirá las latas del lote L2409A de la góndola y del depósito y separalas para su devolución."
   - **Vista previa**: 2 comercios afectados y 2 lotes (unas 50 unidades). El dueño solo ve cantidades, nunca cuáles.
3. **Publicar**. En el acto:
   - Don Pepe y los usuarios de El Sol con acceso a Fisherton ven el aviso de **Seguridad alimentaria** en pantalla
     (y la notificación crítica en la campana). El usuario de control no recibe nada.
   - Los dos lotes quedan **en cuarentena** (`RECALLED`): dejan de ser vendibles, el POS los bloquea y el Inicio muestra
     la coincidencia abierta.
   - La consola de dueños muestra "2 comercios afectados".
4. En Don Pepe: **Seguridad alimentaria** → "Entendido" → **Retirar del stock** (o "Devolver al proveedor") con una nota.
   El lote queda en 0 con su movimiento `Retiro por recall`. Probá vender la sopa desde el POS con `cajero@donpepe.com`:
   el producto aparece bloqueado por cuarentena.
5. En El Sol, `empleado@elsol.com` resuelve la de Fisherton. La sopa de Centro (otro lote) se sigue vendiendo.
6. Para comparar: en **Seguridad alimentaria** de Don Pepe o de El Sol queda también el **recall histórico resuelto**
   del Dulce de leche (lote `DV2603B`), con quién lo reconoció, quién lo resolvió y la nota.

## 6. Notas técnicas

- Detalles de implementación, tiempos y decisiones: [`api-g.md`](api-g.md).
- La siembra tarda ≈ 30 s y corre una sola vez (si la base ya tiene comercios no hace nada). Con `APP_DEV_FIXTURE=true`
  se carga el fixture de desarrollo en su lugar.
- Los datos son determinísticos por día: sembrar dos veces el mismo día produce el mismo mundo.
