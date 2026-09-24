# Góndola UI — sistema de diseño de GondolIA

> Referencia de diseño para el frontend real (SPEC §9 + Addendum 1b). Reemplaza lo visual de SPEC §9.5
> (Inter, `rounded-2xl` uniforme, `shadow-sm` en tarjetas).
>
> - Prototipo navegable: `design/gondola-ui/` (React 18 + Tailwind 3.4 + shadcn/ui). Los tokens viven en
>   `design/gondola-ui/src/index.css` y `tailwind.config.js`; los componentes firma en `src/gondola/components/`.
> - Pantallas de referencia: Góndola UI (catálogo del sistema), Inicio, Punto de venta, Carga de mercadería (móvil),
>   Importar Excel/CSV (revisión), Módulos por cliente (consola de dueños) y la alerta de recall.

GondolIA se usa **parado, con apuro y entre góndolas**: en el depósito con el celular, en la caja con escáner y
teclado, en la oficina mirando el tablero. El sistema prioriza lectura rápida del estado, números que se comparan
columna a columna y acciones grandes donde hay manos ocupadas. Su identidad sale del mundo físico del comercio de
barrio: la **etiqueta amarilla de góndola**, el **ticket térmico** y el **código de barras**.

---

## 1. Principios

1. **Resumen antes que detalle.** Cada pantalla abre con lo que necesita atención ("Para hoy"), después los números y
   al final las tablas.
2. **El estado se lee por forma y palabra, no solo por color.** Píldora + texto + franja; nunca un color suelto.
3. **Un amarillo por pantalla.** El amarillo de etiqueta se reserva para precios, el total del POS y ofertas.
4. **Bordes antes que sombras.** La sombra significa "esto flota por encima" (popover, diálogo, hoja de cobro).
5. **El radio dice qué es cada cosa.** Control 8 · panel 12 · diálogo 16 · etiqueta 4 · tabla 0 · píldora solo estado.
6. **Denso y alineado a la izquierda.** Nada de héroes gigantes ni contenido centrado.
7. **Voseo y verbos concretos.** "Cargá", "Elegí", "Registrar ingreso".

---

## 2. Tokens de color

Formato shadcn: las variables guardan **`h s% l%` sin `hsl()`** para poder usar `hsl(var(--x) / <alpha>)` y los
modificadores de opacidad de Tailwind (`bg-primary/10`). Todo color de la UI sale de un token: ningún literal.

### 2.1 Tres estados de tema

```css
:root { /* tema CLARO completo */ }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) { /* tokens oscuros */ }
}
:root[data-theme="dark"] { /* tokens oscuros otra vez: gana el selector explícito */ }
body { background: hsl(var(--background)); color: hsl(var(--foreground)); }
```

- Sin atributo = sigue al sistema. `data-theme="light"`/`"dark"` en `<html>` = elección explícita.
- **No** se usa la estrategia `.dark` de Tailwind (queda `darkMode: ["class"]` sin uso) ni `next-themes`.
- Cada token se declara primero en `:root`; los bloques oscuros solo lo redefinen.
- **Selector de tema en la app real:** "Sistema / Claro / Oscuro" (íconos Monitor / Sol / Luna) en la barra superior
  (todos los roles, también en el POS), en el menú de usuario, en "Apariencia" del perfil y en una esquina del login.
  La preferencia se guarda por navegador (`localStorage["gondolia.theme"]`) y se aplica antes del primer pintado;
  detalle en `docs/frontend-guide.md` §7.1.
- **Impresión siempre en claro:** `@media print` vuelve a declarar los tokens claros sobre los bloques oscuros, así el
  ticket sale en papel claro aunque la pantalla esté en oscuro.

### 2.2 Base (variables shadcn)

| Token | Rol | Claro (hex · HSL) | Oscuro (hex · HSL) |
|---|---|---|---|
| `--background` | Lienzo, sesgado a verde | `#F3F5F1` · `90 16.7% 95.3%` | `#0D1410` · `145.7 21.2% 6.5%` |
| `--card` / `--popover` | Paneles, tablas, barra superior | `#FFFFFF` · `0 0% 100%` | `#141E18` · `144 20% 9.8%` |
| `--foreground` | Tinta, texto principal | `#15231A` · `141.4 25% 11%` | `#E4EEE7` · `138 22.7% 91.4%` |
| `--muted` / `--secondary` | Hover, segmentados, zonas secundarias | `#E9EEE7` · `102.9 17.1% 92%` | `#1B2820` · `143.1 19.4% 13.1%` |
| `--muted-foreground` | Bajadas, rótulos, metadatos | `#5A6A5F` · `138.8 8.2% 38.4%` | `#93A69A` · `142.1 9.6% 61.4%` |
| `--border` | Divisores y bordes de panel | `#D9E1D6` · `103.6 15.5% 86.1%` | `#26352C` · `144 16.5% 17.8%` |
| `--input` | Borde de controles | `#B9C5B5` · `105 12.1% 74.1%` | `#34463A` · `140 14.8% 23.9%` |
| `--ring` | Foco | = `--primary` | = `--primary` |
| `--primary` | Verde góndola: acción principal | `#1E6A42` · `148.4 55.9% 26.7%` | `#52B883` · `148.8 41.8% 52.2%` |
| `--primary-foreground` | Texto sobre primario | `#F4FAF6` · `140 37.5% 96.9%` | `#06140C` · `145.7 53.8% 5.1%` |
| `--accent` | Amarillo etiqueta de precio | `#F4C542` · `44.2 89% 60.8%` | `#F2C542` · `44.7 87.1% 60.4%` |
| `--accent-foreground` | Texto sobre amarillo | `#231A02` · `43.6 89.2% 7.3%` | `#231A02` · `43.6 89.2% 7.3%` |
| `--destructive` | Alias de `--crit` | ver semánticos | ver semánticos |
| `--scrim` | Velo de diálogos (se usa al 60–75%) | `#0B1A11` · `144 40.5% 7.3%` | `#000000` · `0 0% 0%` |

> **Importante al portar shadcn:** en shadcn `accent` es el gris de hover. En Góndola UI `accent` es el amarillo.
> Todas las clases `bg-accent`/`text-accent-foreground` de hover/foco de los componentes se reemplazaron por
> `bg-muted`/`text-foreground` (ver `components/ui/*` del prototipo).

### 2.3 Riel (navegación)

| Token | Claro | Oscuro |
|---|---|---|
| `--rail` | `#0F3A25` · `150.7 58.9% 14.3%` | `#09100C` · `145.7 28% 4.9%` |
| `--rail-foreground` | `#D9E9DF` · `142.5 26.7% 88.2%` | `#C9DDD0` · `141 22.7% 82.7%` |
| `--rail-active` | `#1E6A42` · `148.4 55.9% 26.7%` | `#1E6A42` · `148.4 55.9% 26.7%` |
| `--rail-hover` | `#164B31` · `150.6 54.6% 19%` | `#132019` · `147.7 25.5% 10%` |
| `--rail-muted` | `#8FB39E` · `145 19.1% 63.1%` | `#6F8A7A` · `144.4 10.8% 48.8%` |
| `--rail-strong` | `#FFFFFF` · `0 0% 100%` | `#FFFFFF` · `0 0% 100%` |

`--rail-strong` es el blanco del ítem activo, del logotipo y de los separadores del riel (`bg-rail-strong/10`,
`border-rail-strong/[0.07]`): el riel es verde profundo en los dos temas, pero igual sale de un token — en la UI no
hay literales de color.

### 2.4 Semánticos

Cada semántico tiene tres tonos: **sólido** (franjas, íconos, rellenos que bloquean), **suave** (fondo de píldoras
y avisos) y **tinta** (texto sobre el suave; garantiza contraste AA). Texto sobre sólido: `--on-solid`.

| Semántico | Uso | Claro sólido · suave · tinta | Oscuro sólido · suave · tinta |
|---|---|---|---|
| `ok` | Stock suficiente, operación completada | `#2F8A55` `145.1 49.2% 36.3%` · `#E2F1E7` `140 34.9% 91.6%` · `#23693F` `144 50% 27.5%` | `#5BC98C` `146.7 50.5% 57.3%` · `#16301F` `140.8 37.1% 13.7%` · `#7AD7A3` `146.5 53.8% 66.1%` |
| `warn` | Por vencer, stock bajo, advertencias que no bloquean | `#C96A12` `28.9 83.6% 42.9%` · `#FBEBDB` `30 80% 92.2%` · `#8F4A0B` `28.6 85.7% 30.2%` | `#F0A24C` `31.5 84.5% 62%` · `#35240F` `33.2 55.9% 13.3%` · `#F5B872` `32.1 86.8% 70.4%` |
| `crit` | Vencido, crítico, sin stock, recall, errores que bloquean | `#BF3A2B` `6.1 63.2% 45.9%` · `#F9E3E0` `7.2 67.6% 92.7%` · `#9C2E22` `5.9 64.2% 37.3%` | `#F0705F` `7 82.9% 65.7%` · `#3A1A16` `6.7 45% 15.7%` · `#F58E80` `7.2 85.4% 73.1%` |
| `info` | Próximo, IA, avisos neutrales | `#2C6E9B` `204.3 55.8% 39%` · `#E1EDF5` `204 50% 92.2%` · `#225A80` `204.3 58% 31.8%` | `#6AAED9` `203.2 59.4% 63.3%` · `#132838` `205.9 49.3% 14.7%` · `#8EC3E6` `203.9 63.8% 72.9%` |
| `--on-solid` | Texto sobre relleno sólido | `#FFFFFF` · `0 0% 100%` | `#0D1410` · `145.7 21.2% 6.5%` |

Variables: `--ok`, `--ok-soft`, `--ok-ink` (ídem `warn`, `crit`, `info`). Tailwind: `bg-crit`, `bg-crit-soft`,
`text-crit-ink`, `text-crit-foreground`.

### 2.5 Superficies físicas

| Token | Uso | Claro | Oscuro |
|---|---|---|---|
| `--paper` / `--paper-ink` | Ticket térmico (sigue siendo papel en tema oscuro) | `#FFFEFA` `48 100% 99%` / `#1C1F1C` `120 5.1% 11.6%` | `#ECEFE8` `85.7 17.9% 92.4%` / `#1C1F1C` |
| `--device` | Bisel del mockup de teléfono | `#101412` `150 12% 7%` | igual |
| `--camera` / `--camera-foreground` | Visor de cámara | `#0A0F0D` `150 20% 5%` / `#FFFFFF` | igual |

### 2.6 Contraste medido (WCAG)

- Texto secundario sobre fondo: 5,2:1 (claro) · 7,3:1 (oscuro).
- Texto sobre primario: 6,2:1 · 7,7:1. Riel: texto 10,1:1; rótulos de grupo 5,5:1 / 5,1:1.
- Tinta sobre suave (claro): ok 5,7 · warn 5,7 · crit 6,0 · info 6,2. Oscuro: 8,2 · 8,5 · 6,8 · 8,0.
- Blanco sobre `crit` claro: 5,5:1 e `info` 5,5:1. Sobre `warn` (3,8:1) y `ok` (4,3:1) el blanco **no** llega a AA:
  las píldoras sólidas de esos dos tonos usan la tinta como fondo (`bg-warn-ink` / `bg-ok-ink` con texto `card`:
  6,7:1 y 6,6:1). El `warn` sólido puro queda para franjas, íconos y barras.

---

## 3. Tipografía

| Cara | Rol | Pesos | Nunca |
|---|---|---|---|
| **Bricolage Grotesque** (display) | Títulos de página, números de KPI, total del POS, etiquetas de precio, precios en mosaicos | 600–700 | Párrafos, tablas, botones con verbo (sí en billetes rápidos del POS: son montos) |
| **Figtree** (UI y lectura) | Toda la interfaz | 400 / 500 / 600 | Bold en bloques largos |
| **JetBrains Mono** (datos) | Códigos de barras, lotes, fechas en chips, tickets, claves de API, atajos | 400–600 | Texto corrido |

**Inter y Space Grotesk están prohibidas.** En el frontend real se instalan con `@fontsource-variable/*`
(sin CDN, Addendum 1b); el prototipo usa Google Fonts. Pilas de respaldo:

```js
display: ['"Bricolage Grotesque"', '"Segoe UI"', "system-ui", "-apple-system", "Roboto", "sans-serif"]
sans:    ["Figtree", '"Segoe UI"', "system-ui", "-apple-system", "Roboto", '"Helvetica Neue"', "Arial", "sans-serif"]
mono:    ['"JetBrains Mono"', "ui-monospace", '"Cascadia Mono"', "Consolas", '"SFMono-Regular"', "Menlo", "monospace"]
```

### Escala cerrada (reemplaza `theme.fontSize` de Tailwind)

| Clase | px / interlínea | Rol |
|---|---|---|
| `text-xs` | 12 / 16 | Rótulos en mayúscula (+0,08em), chips mono, metadatos |
| `text-sm` | 13 / 18 | Ayudas, bajadas, botones chicos |
| `text-base` | 14 / 20 | **Base de UI**, celdas de tabla |
| `text-read` | 15 / 24 | Lectura corrida (explicaciones de la IA, motivos de recall) |
| `text-md` | 16 / 24 | Títulos de panel; inputs en móvil (evita el zoom de iOS) |
| `text-lg` | 20 / 28 | Títulos de diálogo, precios en mosaicos |
| `text-xl` | 26 / 32 | Título de página en móvil, montos destacados |
| `text-2xl` | 34 / 40 | Título de página, número de KPI |
| `text-3xl` | 48 / 52 | Total del POS (etiqueta grande) |

Reglas: `tabular-nums` en **toda** columna o cifra que se compara; `text-wrap: balance` en títulos; ancho de lectura
≤ 68ch; mayúsculas solo en rótulos de 12 px con tracking; como máximo dos tamaños display por pantalla.

### Números grandes que no entran

Un monto largo (`$ 11.974.748,39`) **nunca** se sale de su tarjeta ni queda cortado. El orden es siempre el mismo:

1. **Formato compacto** con el valor exacto al lado: `formatMoneyCompact` de `lib/format.ts`
   (`$ 11,97 M`, `$ 850 mil`, `$ 8.450`) y el importe completo en el `title` y para lectores de pantalla.
   `StatCard` lo hace **solo** cuando recibe el monto en `money` (en vez de `value={formatMoney(x)}`);
   en los demás lugares apretados (mosaicos, ejes de gráficos, celdas angostas) llamalo a mano.
2. Si no hay compacto posible, se achica la letra lo justo para que entre (`fittedFontSize`, mínimo 16 px).
3. Nunca dos líneas: el número de KPI va en una sola (`whitespace-nowrap` + `tabular-nums`).

**Dónde no usar el compacto:** lo que se cobra o se firma va con el importe completo y los centavos —
total del POS, `PriceTag`, líneas del carrito, hoja de cobro, ticket y cualquier monto de una operación.

El resto del encuadre sale de las mismas reglas de siempre: `min-w-0` en los hijos de un flex o grid que
pueden encogerse, `truncate` con `title` para nombres largos, `break-words` en textos libres, contenedor
con `overflow-x` para las tablas anchas y grillas que **pasan a menos columnas** en vez de apretar
(`sm:grid-cols-2 xl:grid-cols-4`).

**El `title` no se escribe a mano:** el componente `Truncate` (`components/ui/Truncate.tsx`, o el hook
`useTruncationTitle` si el elemento lo arma otro componente) mide el nodo y pone el texto completo en el
`title` **solo cuando de verdad quedó cortado** — así no aparecen globos en los textos que entran enteros, y
ningún nombre, marca, correo, asunto, nota o archivo se queda sin manera de leerse completo. Vale igual para
`line-clamp` (`lines={2}`): el rótulo de un KPI, el nombre en un mosaico del POS y el asunto de un ticket no
tienen pantalla de detalle que los rescate.

---

## 4. Forma, espacio y elevación

### Radios por rol (no uniformes)

| Rol | Radio | Token / clase | Dónde |
|---|---|---|---|
| Control | 8 px | `--r-control` · `rounded-control` | Botones, inputs, selects, segmentados, avisos en línea |
| Panel | 12 px | `--r-panel` · `rounded-panel` | Paneles, mosaicos del POS, popovers, menús |
| Diálogo | 16 px | `--r-dialog` · `rounded-dialog` | Diálogos, hojas (la de cobro pierde el radio inferior en móvil) |
| Etiqueta | 4 px | `--r-tag` · `rounded-tag` | PriceTag, ExpiryChip, LotRankChip, badges de plan |
| Píldora | total | `rounded-full` | **Solo estados** (StatusPill, StockStatusPill) |
| Tabla / riel / barra superior | 0 | — | Tablas con divisores de fila; riel y barra superior al ras |

### Elevación

| Nivel | Cómo | Dónde |
|---|---|---|
| 0 | Borde 1 px `--border` | Paneles, tablas, mosaicos, KPI |
| 1 | `shadow-pop` | Popovers, menús, tooltips, diálogos |
| 1 (hoja) | `shadow-sheet` | Hoja de cobro del POS en móvil |

### Espaciado

Base de 4 px. Gutter de página 16 (móvil) / 24 (sm) / 32 (lg). Separación entre bloques 20–24; dentro de panel
16–20 de padding; filas de tabla 8 px vertical (densas). Alturas: control 36 (base), 32 (chico), 44 (grande,
táctil mínimo), 56 (acción principal del POS y la carga móvil).

### Aire de la burbuja de soporte

El botón flotante de soporte no tiene que tapar nada. Mientras está montado publica
`--support-bubble-space` en `<html>` (88 px = botón 56 + margen 16 + aire 16) y el contenedor de scroll del
shell lo suma como `padding-bottom`: **el final de cualquier pantalla se puede desplazar por encima de la
burbuja**, en escritorio y en móvil.

- Si la pantalla tiene una **barra de acción pegada abajo** (carga de mercadería, asistente de importación,
  cobro del POS), marcala con `data-bottom-action-bar`: la burbuja se corre arriba de la barra y el aire va
  en el contenedor de la barra (`pb-[var(--support-bubble-space,0px)]`), no en el scroll de la página, para
  que la barra siga pegada al borde inferior.
- Con un **diálogo o una hoja abierta** la burbuja se esconde: no se ve a través del velo ni tapa el pie.

---

## 5. Componentes firma

Todos en `design/gondola-ui/src/gondola/components/` → en el frontend real, `components/gondola/`.

### 5.1 PriceTag — etiqueta de góndola

- **Propósito:** mostrar un precio como en la góndola; es la única superficie amarilla grande.
- **Anatomía:** fondo `accent`, radio 4 px, **agujero perforado** a la izquierda (máscara radial), rótulo opcional
  ("TOTAL"), `$` chico + entero en Bricolage + **centavos en superíndice subrayados**, unidad ("c/u"), precio de lista
  tachado ("Antes $ 2.150,00") y precio por unidad de medida en mono ("$ 2.150,00 x litro", como pide la normativa de exhibición de precios).
- **Variantes:** `size` sm (mosaico) · md (cobro) · lg (total del POS) · xl; **oferta** con franja `crit` superior
  "-20% VTO CERCANO" + precio de lista tachado.
- **API:** `<PriceTag price={16270} size="lg" label="Total" unit="c/u" perUnit="…" listPrice={2150} offer="-20% VTO CERCANO" />`
  (`aria-label` completo; el dibujo es `aria-hidden`).
- **Usar:** total del POS, precio del producto en la ficha, ofertas por vencimiento.
- **No usar:** montos contables (valor de inventario, MRR, ventas del día → Bricolage sin etiqueta); más de una
  etiqueta grande por pantalla; botones o badges amarillos.

### 5.2 ExpiryChip — chip de vencimiento

- **Propósito:** leer de un vistazo cuándo vence un lote.
- **Anatomía:** chip de 22 px, radio 4, mono tabular: `[LOTE ·] VTO dd/mm/aa [(en N d)]`; texto oculto con el bucket.
- **Buckets** (con `tenant_settings`; demo: crítico 2, por vencer 7, próximo 30):

  | Bucket | Regla | Estilo |
  |---|---|---|
  | Vencido | < 0 días | `crit` sólido |
  | Crítico | 0..critical_days | `crit-soft` + tinta |
  | Por vencer | ..warning_days | `warn-soft` + tinta |
  | Próximo | ..30 días | `info-soft` + tinta |
  | OK | resto | superficie + texto secundario |

- **Usar:** tablas de vencimientos, líneas del carrito ("L2410C · VTO 25/10/26"), lotes existentes en la carga.
- **No usar:** como único indicador de estado de una fila (acompañar con StatusPill o franja).

### 5.3 LotRankChip — orden de salida

- **Propósito:** mostrar qué lote sale primero con la rotación del comercio (FIFO por defecto, FEFO opcional).
- **Anatomía:** chip 22 px, radio 4; `1º sale` en `primary` sólido; `2º`, `3º`… en contorno.
- **Usar:** listas de lotes de un producto (carga, ficha de producto, vencimientos agrupados).
- **No usar:** listas de productos distintos (el orden solo tiene sentido dentro de un producto y sucursal).

### 5.4 StatusPill y StockStatusPill

- **StatusPill** (`tone` ok/warn/crit/info/neutral, `solid`, `dot`): único elemento totalmente redondeado. Sólida
  solo para estados terminales o que bloquean (Vencido, Sin stock, Deshabilitado, "Sin ver" en recall). En `crit` e
  `info` el relleno sólido es el color pleno con `--on-solid`; en `ok` y `warn` es la **tinta** (§2.6).
- **StockStatusPill** (`status` OUT/CRITICAL/LOW/OK → Sin stock sólido · Crítico · Bajo · OK). Reglas SPEC §4.2:
  Sin stock = 0; Crítico ≤ 50% del mínimo; Bajo ≤ mínimo. En vista consolidada, el peor de las sucursales.
- **No usar** píldoras para datos (planes, categorías, lotes): eso es un tag de 4 px.

### 5.5 SeverityRow / SeverityItem — franja de severidad

- **Propósito:** ordenar la vista por urgencia sin leer.
- **Anatomía:** franja de 4 px a la izquierda (`box-shadow: inset 4px 0 0`) en la primera celda o en el ítem.
  Clases `gd-stripe-crit|warn|info|ok|none`.
- **Usar:** filas de tabla e ítems de lista **con esquinas rectas** ("Para hoy", notificaciones, tablas), siempre
  junto a una palabra de estado.
- **No usar:** nunca como barra de acento sobre una tarjeta redondeada ni como decoración.

### 5.6 Ticket 80 mm

- **Propósito:** vista previa fiel del comprobante impreso del POS.
- **Anatomía:** 302 px (80 mm) de ancho, `--paper` con `--paper-ink`, **bordes dentados** arriba y abajo (máscara
  `conic-gradient`), JetBrains Mono 12/17, reglas punteadas. Encabezado (comercio, sucursal, dirección, CUIT), ticket
  `0001-00000418`, fecha y caja, cajero, ítems (cantidad × precio, descuento por lote con lote y vencimiento),
  subtotal, descuentos por vencimiento, TOTAL, pagos, vuelto y la leyenda **"Comprobante no válido como factura"**.
- **Usar:** después de confirmar el cobro, en el historial de ventas y en la página de impresión (con CSS `@media print`).
- **No usar:** para comprobantes fiscales (ARCA es etapa futura).

### 5.7 BarcodeDigits

- **Propósito:** confirmar lo que leyó el escáner y mostrar la prueba en un recall.
- **Anatomía:** SVG con **codificación EAN-13 real** (paridad L/G/R y barras de guarda más largas) + dígitos en mono
  agrupados 1-6-6, siempre sobre una **etiqueta de papel** (`bg-paper` + `text-paper-ink` + borde, radio de tag): tinta
  oscura sobre claro en los dos temas, como el envase. `role="img"` con el código completo. Otros formatos (Code128,
  alfanumérico) → solo dígitos. Prop `bare` cuando ya está sobre papel (el visor de la cámara).
- **Usar:** tarjeta de producto detectado, alerta de recall, ficha de producto.
- **No usar:** en listados largos (alcanza con los dígitos en mono: `digitsOnly`).

---

## 6. Componentes base (shadcn adaptados)

| Componente | Decisiones |
|---|---|
| **Button** | Variantes `default` (primario), `secondary`, `outline`, `ghost`, `destructive`, `link`. Tamaños `sm` 32, `default` 36, `lg` 44, `xl` 56, `icon`. Prop `loading` (spinner + `aria-busy`, deshabilita). Sin sombras. Un primario por zona de decisión. |
| **Input / Textarea / Select** | Radio 8, borde `--input`, foco borde `ring` + halo 25%; `aria-invalid` pinta `crit`. 16 px en móvil, 14 px desde `sm`. |
| **Field** | Etiqueta arriba (13 px semibold), control, error con ícono (`id-error`), ayuda (`id-help`); `fieldDescribedBy()` arma `aria-describedby`. Marca "(opcional)" en vez de asteriscos. |
| **Switch / Checkbox** | Encendido: riel `primary` con pulgar `primary-foreground`. Apagado: riel `muted` con **borde y pulgar `muted-foreground`** (visible en oscuro, ≥3:1 contra la superficie). Checkbox radio 4 con estado indeterminado. Siempre con `<label>`. |
| **Segmented** | `radiogroup` con flechas; opción con contador tonal (filtros de importación, tema). |
| **QtyStepper** | − [n] +, tamaños 32/36/44; input numérico editable. |
| **WizardSteps** | Solo para procesos secuenciales reales (importación). Paso hecho = check en primario; actual = anillo. |
| **Dialog** | Radio 16, `shadow-pop`, velo `scrim/60`. `hideClose` para decisiones obligatorias (recall). En móvil la hoja de cobro se ancla abajo. |
| **Table** | Cuadrada, divisores de fila, encabezado 12 px mayúscula, números a la derecha, contenedor con `overflow-x: auto`. |
| **Tooltip** | Fondo tinta (invierte en oscuro), 13 px; en errores de celda usa `crit`. |
| **Toaster (sonner)** | Superficie `card`, radio 12, `shadow-pop`, íconos semánticos. Para confirmar lo que ya pasó. |
| **EmptyState / Skeleton / Kbd** | Ver §8. `Kbd` en mono para atajos (F2, F4, F8, Esc, Enter). |

---

## 7. Layout por tipo de pantalla

### 7.1 App shell

- **Riel** verde profundo al ras (256 px; colapsado 68 px con tooltips; **drawer** debajo de `lg`). Logo (etiqueta
  amarilla + "Gondol**IA**"), grupos con rótulo (Operación, Análisis, Administración, Comunicación), ítem activo con
  `rail-active`, contadores en mono (rojo sólido solo para Seguridad alimentaria). Pie: "Productos de hoy, clientes de
  siempre" + colapsar. El foco dentro del riel usa el amarillo.
- **Barra superior** al ras, 56 px, `card` + borde inferior: menú (móvil), buscador "Buscar productos o códigos…"
  (hasta 420 px, se encoge antes que el resto), selector de alcance **"Minimercado El Sol · Todas las sucursales ▾"**
  (control de 8 px, no píldora), botón de tema (ícono de la preferencia, menú Sistema / Claro / Oscuro), campana con
  contador y lista con franjas, avatar con nombre y rol. Solo producto: los controles del prototipo viven en la franja
  punteada de arriba. En celulares los botones de ícono van más juntos y el alcance se recorta antes que ellos.
- **Franja de demo** (punteada, fuera del producto): "Prototipo · datos ficticios", "Ver como" (5 roles), "Simular
  recall" y el selector de tema. La franja no existe en la app real, pero el **selector de tema sí**: pasó a la barra
  superior (botón "Cambiar tema"), al menú de usuario, al perfil ("Apariencia") y al login (§2.1).
- Menú por rol según Addendum (Admin, Jefe, Empleado, Cajero, Dueño). Empleado y Cajero ven su sucursal fija.
- POS usa la variante **compacta** (riel colapsado).

### 7.2 Tablero (Inicio)

1. Encabezado: "Resumen del negocio" + saludo con alcance ("¡Hola, Laura! Esto es lo que pasa hoy en tus 3
   sucursales.") + fecha larga a la derecha.
2. **Para hoy** (8/12) — lista con franjas ordenada por urgencia, cada ítem con una acción — + panel sutil
   "Mantené tu negocio siempre fresco" (4/12) con la única etiqueta amarilla.
3. Cuatro KPI (Productos, Por vencer, Stock bajo, Valor inventario): fila de ícono tonal + rótulo con el **sparkline
   a la derecha**, abajo el número en Bricolage 34 en una sola línea (`whitespace-nowrap`; 26 px en móvil) y el delta
   a todo el ancho. Sin barras de acento.
4. "Ventas y stock" 30 días (7/12): área verde para stock (eje izq.), línea tinta para ventas (eje der.), grilla
   horizontal tenue, punto final destacado, tooltip con tokens — + "Sucursales" (5/12) comparativa clickeable: la
   tabla entra completa a 1360 px, sin scroll horizontal.
5. "Próximos vencimientos" y "Artículos a reponer" (2 columnas desde 1536 px).
6. "Recomendaciones de la IA": explicación, confianza, impacto, acción, Aceptar/Descartar con Deshacer.

Con una sucursal elegida se oculta la columna Sucursal y los números se filtran. Jefe: decide sobre las
recomendaciones igual que el administrador; los "Ver todos" le abren Vencimientos e Inventario en solo lectura.

### 7.3 Tabla / listado

Encabezado de página → barra de filtros (segmentado de estado con contadores, búsqueda) → acciones masivas que
reemplazan la línea informativa cuando hay selección → tabla con franjas → barra de acciones fija abajo si hay
un paso siguiente.

### 7.4 Formulario

Una columna (dos en `md+` solo para pares cortos: vencimiento/lote, cantidad/costo). Etiquetas arriba, ayudas con
ejemplos reales, validación al salir del campo y al enviar, botón principal con el verbo exacto.

### 7.5 Punto de venta

- Pantalla completa en dos paneles: **izquierda** búsqueda/escaneo (input de 56 px con borde primario, autofocus,
  "Escaneá o buscá (F2)"), filtros de categoría, avisos en línea (recall en `crit`: "En cuarentena por recall · no se
  puede vender") y mosaicos de productos (precio Bricolage, "-20% VTO", "Sin stock", "Recall · bloqueado").
- **Derecha** (400–440 px): venta en curso con líneas (ExpiryChip del lote, "-20% VTO CERCANO", stepper, total de
  línea), subtotal, descuentos, **PriceTag TOTAL** y "Cobrar F4" de 56 px.
- Franja superior: sucursal, caja, cajero, "Turno abierto 08:02", "Retiro de efectivo", "Cerrar caja", atajos.
- **Hoja de cobro:** medios combinables (Efectivo, Débito, Crédito, Transferencia, QR), billetes rápidos
  ($ 1.000 / $ 2.000 / $ 10.000 / $ 20.000), "Monto justo", Total/Pagado/Falta/**Vuelto** (el vuelto solo sale del
  efectivo), Confirmar con Enter → ticket 80 mm con "Imprimir" y "Nueva venta". Arranca sin medios: cada medio que se
  toca queda iluminado (`aria-pressed`) y suma una línea con lo que falta; tocarlo de nuevo (o la X de la línea) la
  quita. Los montos que el cajero no escribió se acomodan solos: lo que falta después de los que sí escribió lo toma
  la primera línea sugerida (Débito + Crédito, $ 1.000 en Crédito → Débito pasa a $ 2.450), y siguen al total si el
  mostrador lo actualiza. Los billetes rápidos van dentro de la línea de efectivo y solo aparecen con ella (el primero
  reemplaza el monto sugerido, los siguientes suman). El resumen y los botones quedan fijos abajo; lo que se desplaza
  es la lista. Los montos del resumen no se parten en dos renglones (el vuelto va más chico en el celular).
- Atajos: **F2** buscar · **F4** cobrar · **F8** quitar ítem · **Esc** limpiar/cerrar · **Enter** agrega el primer
  resultado / confirma.
- Móvil: paneles apilados + barra fija inferior con total y "Cobrar"; la hoja de cobro se ancla abajo.

### 7.6 Carga móvil (Empleado)

Una columna, controles de 44 px, **botón principal de 56 px fijo abajo** (con safe-area). Visor de cámara con
esquinas amarillas y línea de escaneo (estática con movimiento reducido) → tarjeta de producto con BarcodeDigits →
"Leer vencimiento y lote con la cámara" → chips OCR seleccionables con confianza ("VTO 25/10/2026 · 92%",
"LOTE L2410C · 85%") → campos editables → "Ya tenés en Sucursal Centro" con LotRankChip + ExpiryChip → aviso FIFO
(no bloquea) → "Registrar ingreso · 24 u." + toast. En escritorio se muestra dentro de un marco de teléfono de 390 px
junto a una columna explicativa.

### 7.7 Asistente (importación)

Pasos reales numerados: Archivo ✓ · Columnas ✓ · **Revisión** · Confirmar · Resultado. Chip del archivo
("planilla_stock_elsol.xlsx · hoja Stock · 1.248 filas"). Segmentado Válidas / Advertencias / Errores con contadores
vivos. Grilla editable: clic o Enter en la celda → input; Enter/blur guarda, Esc cancela. Celdas con error: fondo
`crit-soft` + contorno `crit`; advertencia: `warn`. El motivo aparece en tooltip al pasar o enfocar
("Fecha inválida: 31/02/2026", "Sucursal «Rosario Norte» no existe", "Dígito verificador del EAN inválido",
"Precio de venta menor al costo"). Selección + acciones masivas. Barra fija: "Omitir filas con error (n)",
"Descargar errores (CSV)", y "Continuar a confirmar" deshabilitado con el motivo visible mientras haya errores.

### 7.8 Consola de dueños

Aviso `info` permanente: "Solo ves datos administrativos: nunca el stock, las ventas ni los chats de tus clientes."
Adopción por módulo (porcentaje en Bricolage, "n de 36 clientes activos", barra). Filtros (búsqueda, plan, estado).
Matriz: Cliente · Plan (tag mono de 4 px, no píldora) · Estado (píldora) · Sucursales · un **Switch** por módulo ·
MRR estimado. Activar = inmediato + toast. **Desactivar = diálogo** que explica el efecto y el cambio de MRR.
Multi-sucursal con más de una sucursal activa → diálogo bloqueante ("No se puede deshabilitar Multi-sucursal…").

### 7.9 Alerta de recall

Diálogo de atención total (`alertdialog`, velo 75%, no se cierra tocando afuera): cabecera `crit` "Alerta de seguridad
alimentaria", producto en Bricolage, LOTE + ExpiryChip, BarcodeDigits, bloque "Sucursal Fisherton · 24 unidades en
cuarentena", motivo, pasos numerados, "Entendido" (contorno) y "Ver detalle y retirar del stock" (destructivo).

---

## 8. Estados

| Estado | Patrón | Ejemplo de copy |
|---|---|---|
| **Vacío** | `EmptyState`: ícono en cuadro punteado, título que dice qué falta, texto con la próxima acción, botón | "Todavía no cargaste productos" · "Hacé tu primera carga importando tu planilla de Excel o CSV." |
| **Carga** | `Skeleton` con la forma final (filas con franja y chip); `aria-busy` en el contenedor; botones con `loading` | "Cargando vencimientos…" |
| **Error de red** | `EmptyState` con ícono de conexión y "Reintentar"; nunca culpar al usuario | "No pudimos cargar los vencimientos" · "Se cortó la conexión… probá de nuevo en unos segundos." |
| **Error de campo** | Borde `crit`, mensaje con ícono debajo, qué pasó + cómo arreglarlo | "Precio inválido: «2.45O». Usá números, punto para miles y coma para centavos." |
| **Bloqueado por regla** | Aviso en línea `crit` o diálogo bloqueante con salida | "En cuarentena por recall · no se puede vender." |
| **Deshabilitado** | Botón deshabilitado **con motivo visible** al lado | "Corregí los 9 errores u omitilos para continuar." |
| **Solo lectura** | Las acciones que el rol no puede usar **no se muestran** (regla de SPEC §3.3: todo botón visible funciona); donde hace falta, una frase explica quién lo hace | "Solo el administrador o un empleado pueden retirarlo." |
| **Módulo deshabilitado** | Página con EmptyState: qué es la función y a quién pedirla | "Esta función no está habilitada para tu comercio." |

---

## 9. Copy

- **Voseo rioplatense:** "Cargá", "Elegí", "Revisá", "Probá", "Tenés". Nada de "Usted" ni "tú".
- **Botones = verbo + objeto exacto:** "Registrar ingreso", "Confirmar cobro", "Deshabilitar módulo",
  "Descargar errores (CSV)". Evitar "Aceptar", "OK", "Enviar". El toast confirma con el participio:
  "Ingreso registrado".
- **Nombrar desde el comercio:** "Punto de venta", "Carga de mercadería", "Seguridad alimentaria"; no "POS module",
  "webhook", "tenant". En la consola de dueños se dice "cliente", no "tenant".
- **Formatos:** moneda `$ 8.450.000` (espacio después de `$`, punto de miles, coma decimal, centavos en POS y tickets);
  fechas `dd/mm/aaaa` (en chips `dd/mm/aa`); fecha larga "jueves, 17 de septiembre de 2026"; unidades "24 u.";
  porcentajes "92%"; horas "10:42".
- **Glosario fijo:** Vencido / Crítico / Por vencer / Próximo · Sin stock / Crítico / Bajo / OK · lote ·
  "1º sale" · cuarentena · recall · sucursal · caja · turno · ticket no fiscal.
- **Errores:** qué pasó + cómo seguir, sin disculpas ni signos de exclamación. Saludos cálidos solo en el tablero.
- **Datos de ejemplo:** marcas ficticias (La Huerta, Lácteos del Valle, Molinos Paraná, Don Julián, Campo Serrano),
  EAN `779…`, direcciones de Rosario. Nunca lorem ipsum.

---

## 10. Accesibilidad

- **Contraste AA** en todos los pares de texto (§2.6). Texto sobre suaves con la variante tinta.
- **Foco visible** siempre: `outline`/`ring` de 2 px con `ring` (amarillo dentro del riel). Nunca `outline: none`
  sin reemplazo.
- **Teclado:** skip link "Saltar al contenido"; segmentados con flechas; celdas editables con Enter/Esc; POS completo
  con F2/F4/F8/Esc/Enter; diálogos atrapan el foco y devuelven el foco al cerrar.
- **Semántica:** `nav` con `aria-label`, `aria-current="page"` en el riel, `role="alert"` en avisos que bloquean,
  `alertdialog` para recall, `aria-live` en vuelto y lecturas de OCR, `aria-invalid` + `aria-describedby` en campos.
- **No depender del color:** toda franja o color va con palabra (píldora, sr-only en chips).
- **Movimiento:** `prefers-reduced-motion` apaga shimmer, línea de escaneo, barra de progreso animada y scroll suave.
- **Táctil:** mínimo 44 px en móvil y POS; acción principal 56 px al alcance del pulgar; `env(safe-area-inset-bottom)`
  en barras fijas inferiores.
- **Ids estables** en todos los controles de formulario.
- **Responsive:** funciona a 375–400 px sin scroll horizontal de página; tablas con scroll propio; riel → drawer;
  POS apilado con barra de cobro fija.

---

## 11. Hacé / No hagas

| Hacé | No hagas |
|---|---|
| Abrí cada pantalla con lo que necesita atención ("Para hoy") | Héroes gigantes o banners que empujen el contenido |
| Alineá el contenido a la izquierda, denso | Centrar todo |
| Usá el radio según el rol (8/12/16/4/0/píldora) | `rounded-lg` (o `rounded-2xl`) uniforme en todo |
| Separá con bordes; sombra solo para lo que flota | `shadow-sm` en cada tarjeta, glassmorphism falso |
| Un amarillo por pantalla, para precios y totales | Amarillo en botones, bordes o fondos decorativos |
| Franja de severidad en filas y listas rectas | Barra de acento sobre tarjetas redondeadas |
| Íconos lucide con significado junto al texto | Emoji como marcadores de sección |
| Numerá solo procesos reales (importación, pasos de recall) | "01 / 02 / 03" decorativos |
| Bricolage + Figtree + JetBrains Mono | Inter, Space Grotesk, serif + crema + terracota |
| Verdes de góndola y neutros con sesgo verde | Degradados violeta/azul, grises puros sin intención |
| `tabular-nums` en cifras comparables | Números proporcionales en columnas |
| Estados con palabra + forma + color | Color como única señal |
| Copy en voseo con verbos exactos | "Aceptar", "Enviar", "Error desconocido" |
| Tokens para todo color, en ambos temas | Colores literales o definidos solo dentro de un bloque de tema |

---

## 12. Cómo portarlo al frontend real

1. Copiá los tokens de `design/gondola-ui/src/index.css` (bloques `:root`, media query y `[data-theme="dark"]`,
   más las utilidades `gd-*`) al `src/index.css` del frontend.
2. Reemplazá `tailwind.config.js` por el del prototipo (escala tipográfica, familias, colores por token, radios por
   rol, sombras, animación `scanline`). Quitá el `brand` de SPEC §9.5.
3. Instalá `@fontsource-variable/bricolage-grotesque`, `@fontsource-variable/figtree` y
   `@fontsource-variable/jetbrains-mono`; importalos en `main.tsx` y quitá `@fontsource-variable/inter`.
4. Copiá `src/components/ui/*` adaptados (button, input, textarea, select, switch, checkbox, dialog, sheet, tooltip,
   table, skeleton, dropdown-menu, popover) y mantené el barrel `components/ui/index.ts` con la API de los módulos.
5. Copiá `src/gondola/components/*` a `components/gondola/` (PriceTag, ExpiryChip, LotRankChip, StatusPill,
   StockStatusPill, SeverityRow, Ticket, BarcodeDigits, Sparkline, Panel, Controls, Toaster, Logo) y
   `src/gondola/format.ts` a `lib/format.ts` (o fusionalo con el existente: mismos formatos es-AR).
6. El tema lo resuelve un atributo `data-theme` en `<html>` (sin `next-themes`); "Sistema" quita el atributo. En el
   frontend real ya está hecho: `ThemeProvider` / `useTheme()` en `src/theme/`, `public/theme-init.js` en el `<head>`
   de `index.html` (sin parpadeo) y `ThemeToggle` en la barra superior (`docs/frontend-guide.md` §7.1).
7. Recharts: colores con `hsl(var(--token))`, grilla solo horizontal, ejes en `muted-foreground` 12 px, tooltip propio.
8. Si se vuelve a empaquetar con Parcel (skill web-artifacts-builder), el `package.json` necesita
   `"@parcel/resolver-default": { "packageExports": true }` para resolver los subpaths de Radix.
