import * as React from "react"
import { toast } from "sonner"
import { AlertTriangle, CalendarClock, Download, Info, Package, Plus, RefreshCw, Save, ShieldAlert, Trash2, WifiOff } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Textarea } from "@/components/ui/textarea"
import { Switch } from "@/components/ui/switch"
import { Checkbox } from "@/components/ui/checkbox"
import { Skeleton } from "@/components/ui/skeleton"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Panel, PageHeader } from "../components/Panel"
import { StatusPill } from "../components/StatusPill"
import { ExpiryChip } from "../components/ExpiryChip"
import { LotRankChip } from "../components/LotRankChip"
import { StockStatusPill } from "../components/StockStatusPill"
import { SeverityRow } from "../components/SeverityRow"
import { PriceTag } from "../components/PriceTag"
import { BarcodeDigits } from "../components/BarcodeDigits"
import { Ticket } from "../components/Ticket"
import { EmptyState, Field, QtyStepper, fieldDescribedBy } from "../components/Controls"
import { BASE_COLORS, SEMANTIC_COLORS, TYPE_SCALE } from "../tokens"
import { TENANT, product } from "../data"

const SECTIONS = [
  ["ds-color", "Color"],
  ["ds-tipografia", "Tipografía"],
  ["ds-forma", "Radios y elevación"],
  ["ds-botones", "Botones"],
  ["ds-formularios", "Formularios"],
  ["ds-estados", "Estados y chips"],
  ["ds-firma", "Componentes firma"],
  ["ds-tabla", "Tabla densa"],
  ["ds-feedback", "Feedback"],
  ["ds-vacio", "Vacío, carga y error"],
] as const

const SWATCH = {
  ok: ["bg-ok", "bg-ok-soft", "bg-ok-ink"],
  warn: ["bg-warn", "bg-warn-soft", "bg-warn-ink"],
  crit: ["bg-crit", "bg-crit-soft", "bg-crit-ink"],
  info: ["bg-info", "bg-info-soft", "bg-info-ink"],
} as const

function Section({ id, title, lead, children }: { id: string; title: string; lead: React.ReactNode; children: React.ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-t`} className="scroll-mt-4 border-t pt-7">
      <div className="mb-4 max-w-[68ch]">
        <h2 id={`${id}-t`} className="font-display text-xl font-semibold leading-8 tracking-[-0.01em]">
          {title}
        </h2>
        <p className="mt-1 text-read text-muted-foreground">{lead}</p>
      </div>
      {children}
    </section>
  )
}

function Usage({ use, avoid }: { use: React.ReactNode; avoid: React.ReactNode }) {
  return (
    <dl className="mt-3 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
      <div>
        <dt className="font-semibold text-ok-ink">Usalo para</dt>
        <dd className="text-muted-foreground">{use}</dd>
      </div>
      <div>
        <dt className="font-semibold text-crit-ink">Evitalo para</dt>
        <dd className="text-muted-foreground">{avoid}</dd>
      </div>
    </dl>
  )
}

function Specimen({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <Panel as="div" className={cn("flex flex-col gap-3 p-4", className)}>
      <div className="gd-eyebrow">{label}</div>
      {children}
    </Panel>
  )
}

export function DesignSystemScreen({ resolvedTheme }: { resolvedTheme: "light" | "dark" }) {
  const [loading, setLoading] = React.useState(false)
  const [sw, setSw] = React.useState(true)
  const [chk, setChk] = React.useState(true)
  const [qty, setQty] = React.useState(12)
  const [confirmOpen, setConfirmOpen] = React.useState(false)
  const [price, setPrice] = React.useState("2.45O")
  const priceErr = /^[\d.]+(,\d+)?$/.test(price.trim()) ? undefined : `Precio inválido: «${price}». Usá números, punto para miles y coma para centavos.`
  const hex = (t: { light: string; dark: string }) => (resolvedTheme === "dark" ? t.dark : t.light)

  return (
    <div className="grid max-w-[1560px] grid-cols-1 gap-8 px-4 py-5 sm:px-6 lg:px-8 lg:py-7 xl:grid-cols-[minmax(0,1fr)_200px]">
      <div className="flex min-w-0 flex-col gap-8">
        <PageHeader
          eyebrow="Sistema de diseño de GondolIA"
          title="Góndola UI"
          description={
            <>
              Tokens, tipografías y componentes para una app de inventario que se usa parada, con apuro y entre góndolas. Todo sale de tokens: cambiá el tema
              arriba y mirá cómo responde. Estás viendo el tema <strong className="font-semibold text-foreground">{resolvedTheme === "dark" ? "oscuro" : "claro"}</strong>.
            </>
          }
        />

        {/* ------------------------------------------------------------ */}
        <Section id="ds-color" title="Color" lead="Neutros con sesgo verde, un verde de góndola para actuar y un amarillo de etiqueta de precio que se usa con cuentagotas. Lo semántico no es acento.">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-5">
            {BASE_COLORS.map((c) => (
              <div key={c.token} className="min-w-0 overflow-hidden rounded-panel border bg-card">
                <div className={cn("flex h-16 items-end border-b p-2", c.swatch, c.on)}>
                  <span className="font-display text-lg font-semibold leading-none">Aa</span>
                </div>
                <div className="p-2.5">
                  <div className="text-base font-semibold">{c.name}</div>
                  <div className="flex flex-wrap justify-between gap-x-2 font-mono text-xs text-muted-foreground">
                    <span>{c.token}</span>
                    <span className="text-foreground">{hex(c)}</span>
                  </div>
                  <p className="mt-1 text-xs text-muted-foreground">{c.usage}</p>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 grid grid-cols-1 gap-3 md:grid-cols-2">
            {SEMANTIC_COLORS.map((s) => {
              const [solid, soft, ink] = resolvedTheme === "dark" ? s.dark : s.light
              return (
                <div key={s.key} className="min-w-0 rounded-panel border bg-card p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-base font-semibold">{s.name}</span>
                    <StatusPill tone={s.key}>{s.sample}</StatusPill>
                  </div>
                  <div className="mt-3 grid grid-cols-3 gap-2 font-mono text-[11px]">
                    {[
                      ["sólido", solid, SWATCH[s.key][0]],
                      ["suave", soft, SWATCH[s.key][1]],
                      ["tinta", ink, SWATCH[s.key][2]],
                    ].map(([label, h, cls]) => (
                      <div key={label}>
                        <div className={cn("h-8 rounded-[4px] border", cls)} />
                        <div className="mt-1 text-muted-foreground">--{s.key}{label === "sólido" ? "" : label === "suave" ? "-soft" : "-ink"}</div>
                        <div className="text-foreground">{h}</div>
                      </div>
                    ))}
                  </div>
                  <p className="mt-2 text-xs text-muted-foreground">{s.usage}</p>
                </div>
              )
            })}
          </div>
          <Usage
            use="Amarillo solo en precios, el total del POS y ofertas por vencimiento: una aparición por pantalla. Texto sobre fondos suaves siempre con el tono «tinta»."
            avoid="Degradados violeta/azul, amarillo en botones o bordes decorativos, rojo para cosas que no bloquean, colores literales fuera de los tokens."
          />
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-tipografia" title="Tipografía" lead="Tres voces con roles fijos. Bricolage Grotesque aporta el carácter con moderación; Figtree hace el trabajo; JetBrains Mono muestra datos que se comparan dígito a dígito.">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
            <Specimen label="Display · Bricolage Grotesque 600–700">
              <div className="font-display text-3xl font-bold leading-none tracking-[-0.03em]">Aa $ 16.270</div>
              <p className="text-sm text-muted-foreground">Títulos de página, números de KPI, total del POS y etiquetas de precio. Nunca en párrafos ni en tablas.</p>
            </Specimen>
            <Specimen label="UI y lectura · Figtree 400/500/600">
              <div className="text-xl font-semibold leading-8">Cargá la mercadería</div>
              <p className="text-read text-foreground">Base 14 px para la interfaz y 15 px para leer explicaciones. Semibold para jerarquía, nunca bold en bloque.</p>
            </Specimen>
            <Specimen label="Datos · JetBrains Mono 400–600">
              <div className="font-mono text-lg font-medium leading-7">7790080000123</div>
              <p className="font-mono text-sm text-foreground">L2410C · VTO 25/10/26 · 0001-00000418</p>
              <p className="text-sm text-muted-foreground">Códigos, lotes, fechas en chips, tickets y claves de API.</p>
            </Specimen>
          </div>
          <Panel as="div" className="mt-3 overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead className="w-[80px] text-right">Tamaño</TableHead>
                  <TableHead className="w-[100px]">Clase</TableHead>
                  <TableHead>Rol</TableHead>
                  <TableHead>Muestra</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {TYPE_SCALE.map((t) => (
                  <TableRow key={t.px}>
                    <TableCell className="text-right font-mono text-sm tabular-nums">{t.px}px</TableCell>
                    <TableCell className="font-mono text-sm text-muted-foreground">{t.cls}</TableCell>
                    <TableCell className="min-w-[180px] text-sm text-muted-foreground">{t.role}</TableCell>
                    <TableCell className="min-w-[240px]">
                      <span className={cn(t.face === "display" ? "font-display tracking-[-0.015em]" : t.face === "mono" ? "font-mono" : "font-sans", t.weight, "whitespace-nowrap tabular-nums")} style={{ fontSize: t.px, lineHeight: 1.15 }}>
                        {t.sample}
                      </span>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Panel>
          <Usage
            use="tabular-nums en toda columna numérica; text-wrap: balance en títulos; mayúsculas con +0,08em de tracking solo en rótulos de 12 px."
            avoid="Inter o Space Grotesk; más de dos tamaños display en una pantalla; mono para texto corrido."
          />
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-forma" title="Radios y elevación" lead="El radio dice qué es cada cosa. Los bordes separan; la sombra queda para lo que flota por encima (popovers, diálogos, hoja de cobro).">
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
            {[
              ["Control", "8 px", "rounded-control", "Botones, inputs, selects"],
              ["Panel", "12 px", "rounded-panel", "Superficies y mosaicos"],
              ["Diálogo", "16 px", "rounded-dialog", "Diálogos y hojas"],
              ["Etiqueta", "4 px", "rounded-tag", "Precio, chips de datos"],
              ["Píldora", "total", "rounded-full", "Solo estados"],
              ["Tabla y barras", "0", "rounded-none", "Tablas, riel, barra superior"],
            ].map(([n, v, cls, u]) => (
              <div key={n} className="flex flex-col gap-2">
                <div className={cn("h-16 border-2 border-primary/60 bg-primary/[0.07]", cls)} />
                <div>
                  <div className="text-base font-semibold">
                    {n} <span className="font-mono text-xs font-normal text-muted-foreground">{v}</span>
                  </div>
                  <div className="text-xs text-muted-foreground">{u}</div>
                </div>
              </div>
            ))}
          </div>
          <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Panel as="div" className="p-4">
              <div className="gd-eyebrow">Nivel 0 · borde</div>
              <p className="mt-1 text-base text-muted-foreground">Paneles, tablas, mosaicos del POS. Separan con 1 px de --border.</p>
            </Panel>
            <div className="rounded-panel border bg-card p-4 shadow-pop">
              <div className="gd-eyebrow">Nivel 1 · sombra pop</div>
              <p className="mt-1 text-base text-muted-foreground">Solo popovers, menús, diálogos y la hoja de cobro del POS.</p>
            </div>
          </div>
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-botones" title="Botones" lead="Un solo botón primario por zona de decisión. Los verbos dicen exactamente qué pasa: «Registrar ingreso», no «Aceptar».">
          <Panel as="div" className="flex flex-col gap-5 p-4 sm:p-5">
            <div className="flex flex-wrap items-center gap-2">
              <Button>Registrar ingreso</Button>
              <Button variant="secondary">Guardar borrador</Button>
              <Button variant="outline">Ver vencimientos</Button>
              <Button variant="ghost">Descartar</Button>
              <Button variant="destructive">Retirar del stock</Button>
              <Button variant="link">Ver detalle</Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button size="sm">Chico · 32</Button>
              <Button>Base · 36</Button>
              <Button size="lg">Grande · 44</Button>
              <Button size="xl">Táctil · 56</Button>
              <Button size="icon" variant="outline" aria-label="Agregar producto">
                <Plus />
              </Button>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline">
                <Download aria-hidden="true" />
                Descargar errores (CSV)
              </Button>
              <Button
                loading={loading}
                onClick={() => {
                  setLoading(true)
                  window.setTimeout(() => setLoading(false), 1600)
                }}
              >
                {loading ? "Guardando…" : (
                  <>
                    <Save aria-hidden="true" />
                    Guardar (probá)
                  </>
                )}
              </Button>
              <Button disabled>Continuar a confirmar</Button>
            </div>
          </Panel>
          <Usage use="Primario para la acción que avanza el trabajo; destructivo solo si borra, retira o bloquea; tamaño táctil (56 px) en POS y móvil." avoid="Dos primarios juntos; amarillo en botones; textos genéricos como «OK» o «Enviar»." />
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-formularios" title="Formularios" lead="Etiqueta arriba, ayuda debajo, error que explica qué pasó y cómo arreglarlo. Cada control tiene id estable y aria-describedby.">
          <Panel as="div" className="grid grid-cols-1 gap-5 p-4 sm:p-5 md:grid-cols-2">
            <Field id="ds-name" label="Nombre del producto" help="Como figura en el envase, con presentación: «Yogur bebible frutilla 1 L».">
              <Input id="ds-name" defaultValue="Yogur bebible frutilla 1 L" aria-describedby={fieldDescribedBy("ds-name", { help: true })} />
            </Field>
            <Field id="ds-price" label="Precio de venta" error={priceErr} help={priceErr ? undefined : "Precio final con IVA."}>
              <Input
                id="ds-price"
                value={price}
                onChange={(e) => setPrice(e.target.value)}
                aria-invalid={!!priceErr || undefined}
                aria-describedby={fieldDescribedBy("ds-price", { error: priceErr, help: !priceErr })}
                className="tabular-nums"
              />
            </Field>
            <Field id="ds-branch" label="Sucursal" help="Obligatoria para cargar stock cuando ves todas las sucursales.">
              <Select defaultValue="centro">
                <SelectTrigger id="ds-branch" aria-describedby="ds-branch-help">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="centro">Sucursal Centro</SelectItem>
                  <SelectItem value="fisherton">Sucursal Fisherton</SelectItem>
                  <SelectItem value="echesortu">Sucursal Echesortu</SelectItem>
                </SelectContent>
              </Select>
            </Field>
            <Field id="ds-note" label="Nota para el ajuste" optional>
              <Textarea id="ds-note" placeholder="Ej.: 3 unidades con la tapa abollada" />
            </Field>
            <div className="flex flex-col gap-3">
              <div className="flex items-center justify-between gap-4 rounded-control border px-3 py-2.5">
                <label htmlFor="ds-switch" className="text-base">
                  <span className="block font-semibold">Descuento automático por vencimiento</span>
                  <span className="block text-sm text-muted-foreground">Aplica -20% a lotes que vencen en 3 días o menos.</span>
                </label>
                <Switch id="ds-switch" checked={sw} onCheckedChange={setSw} />
              </div>
              <label htmlFor="ds-check" className="flex items-start gap-2.5 text-base">
                <Checkbox id="ds-check" checked={chk} onCheckedChange={(c) => setChk(c === true)} className="mt-0.5" />
                <span>
                  <span className="block font-semibold">Producto perecedero</span>
                  <span className="block text-sm text-muted-foreground">Pide vencimiento en cada ingreso.</span>
                </span>
              </label>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="ds-qty" className="text-sm font-semibold">
                Cantidad
              </label>
              <QtyStepper id="ds-qty" label="Cantidad" size="lg" value={qty} onChange={setQty} />
              <p className="text-sm text-muted-foreground">Stepper de 44 px: se usa con el pulgar en el depósito.</p>
            </div>
          </Panel>
          <Usage use="Validar al salir del campo y al enviar; inputs de 16 px en móvil; ejemplos reales en los placeholders." avoid="Placeholder como etiqueta; errores genéricos («Dato inválido»); deshabilitar sin decir por qué." />
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-estados" title="Estados y chips" lead="El estado se lee por forma y por palabra, no solo por color. Píldora redonda = estado; chip rectangular = dato rotulado.">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <Specimen label="StatusPill · suave y sólida">
              <div className="flex flex-wrap gap-2">
                <StatusPill tone="ok">Activo</StatusPill>
                <StatusPill tone="warn">Por vencer</StatusPill>
                <StatusPill tone="crit">Crítico</StatusPill>
                <StatusPill tone="info">Próximo</StatusPill>
                <StatusPill tone="neutral">Descartada</StatusPill>
                <StatusPill tone="crit" solid>
                  Vencido
                </StatusPill>
              </div>
              <p className="text-sm text-muted-foreground">Sólida solo para estados terminales o que bloquean (Vencido, Sin stock, Deshabilitado).</p>
            </Specimen>
            <Specimen label="StockStatusPill · por sucursal">
              <div className="flex flex-wrap gap-2">
                <StockStatusPill status="OUT" />
                <StockStatusPill status="CRITICAL" />
                <StockStatusPill status="LOW" />
                <StockStatusPill status="OK" />
              </div>
              <p className="text-sm text-muted-foreground">Sin stock = 0 · Crítico ≤ 50% del mínimo · Bajo ≤ mínimo. En vista consolidada muestra el peor.</p>
            </Specimen>
            <Specimen label="ExpiryChip · buckets de vencimiento (hoy 17/09/2026)">
              <div className="flex flex-wrap gap-2">
                <ExpiryChip expiry="2026-09-15" />
                <ExpiryChip expiry="2026-09-18" />
                <ExpiryChip expiry="2026-09-22" />
                <ExpiryChip expiry="2026-10-09" />
                <ExpiryChip expiry="2027-03-12" />
              </div>
              <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                <span>Vencido &lt; 0 d</span>
                <span>Crítico 0–2 d</span>
                <span>Por vencer 3–7 d</span>
                <span>Próximo 8–30 d</span>
                <span>OK &gt; 30 d</span>
              </div>
              <div className="flex flex-wrap gap-2">
                <ExpiryChip expiry="2026-10-25" lot="L2410C" />
                <ExpiryChip expiry="2026-09-18" lot="L2409B" showDays />
              </div>
            </Specimen>
            <Specimen label="LotRankChip · orden de salida FIFO">
              <div className="flex flex-wrap items-center gap-2">
                <LotRankChip rank={1} />
                <LotRankChip rank={2} />
                <LotRankChip rank={3} />
              </div>
              <p className="text-sm text-muted-foreground">
                «1º sale» marca el lote que se descuenta en la próxima venta. Con FEFO el orden sigue el vencimiento; el chip no cambia de forma.
              </p>
            </Specimen>
          </div>
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-firma" title="Componentes firma" lead="Piezas que solo tiene GondolIA: salen del mundo físico del comercio de barrio (la etiqueta de góndola, el ticket térmico, el código de barras).">
          <div className="grid grid-cols-1 gap-3 xl:grid-cols-[minmax(0,1fr)_360px]">
            <div className="flex min-w-0 flex-col gap-3">
              <Specimen label="PriceTag · etiqueta de góndola">
                <div className="flex flex-wrap items-end gap-4">
                  <PriceTag size="sm" price={1340} unit="c/u" />
                  <PriceTag size="md" price={2150} unit="c/u" perUnit="$ 2.150,00 x litro" />
                  <PriceTag size="md" price={1720} listPrice={2150} offer="-20% VTO CERCANO" perUnit="L2409B · VTO 18/09/26" />
                  <PriceTag size="lg" price={16270} label="Total" />
                </div>
                <Usage
                  use="Precio de un producto, total del POS y ofertas por vencimiento. Incluí precio por unidad de medida cuando corresponda, como pide la normativa de exhibición de precios."
                  avoid="Montos contables (valor de inventario, MRR): esos van en Bricolage sin etiqueta. Más de una etiqueta grande por pantalla."
                />
              </Specimen>
              <Specimen label="BarcodeDigits · EAN-13 real">
                <div className="flex flex-wrap items-end gap-6">
                  <BarcodeDigits code="7790080000123" width={170} />
                  <BarcodeDigits code={product("sopa").ean} width={130} />
                  <BarcodeDigits code="7790080000123" digitsOnly />
                </div>
                <Usage use="Confirmar lo que leyó el escáner y en alertas de recall, donde el código es la prueba." avoid="Listas largas: ahí alcanza con los dígitos en mono." />
              </Specimen>
              <Specimen label="SeverityRow · franja de severidad">
                <ul className="divide-y overflow-hidden border-y">
                  <li className="gd-stripe-crit py-2 pl-4 text-base">
                    <strong className="font-semibold">Crítico</strong> · Recall en Fisherton, 24 u. en cuarentena
                  </li>
                  <li className="gd-stripe-warn py-2 pl-4 text-base">
                    <strong className="font-semibold">Atención</strong> · 6 lotes vencen en 5 días o menos
                  </li>
                  <li className="gd-stripe-info py-2 pl-4 text-base">
                    <strong className="font-semibold">Info</strong> · 3 recomendaciones de la IA
                  </li>
                </ul>
                <Usage use="Filas de tabla e ítems de lista con esquinas rectas, siempre acompañada de una palabra de estado." avoid="Barras de acento en tarjetas redondeadas o como decoración." />
              </Specimen>
            </div>
            <Specimen label="Ticket 80 mm · no fiscal" className="items-center bg-muted/60">
              <Ticket
                data={{
                  store: TENANT.name,
                  branch: "Sucursal Centro",
                  address: "Córdoba 1450 · Rosario",
                  cuit: TENANT.cuit,
                  code: "0001-00000417",
                  dateIso: "2026-09-17",
                  time: "10:31",
                  register: "Caja 1",
                  cashier: "Martín R.",
                  lines: [
                    { name: "Yogur bebible frutilla 1 L", qty: 2, unitPrice: 1720, listPrice: 2150, lot: "L2409B", expiry: "2026-09-18", discountPct: 20 },
                    { name: "Yerba mate suave 1 kg", qty: 1, unitPrice: 4890, listPrice: 4890 },
                  ],
                  payments: [
                    { label: "Débito", amount: 5000 },
                    { label: "Efectivo", amount: 5000 },
                  ],
                  change: 670,
                }}
              />
              <p className="text-center text-sm text-muted-foreground">Leyenda obligatoria: «Comprobante no válido como factura».</p>
            </Specimen>
          </div>
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-tabla" title="Tabla densa" lead="Tablas cuadradas con divisores de fila, encabezados en mayúscula chica, números alineados a la derecha y franja de severidad en la primera celda.">
          <Panel as="div" className="overflow-hidden">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Producto</TableHead>
                  <TableHead>Lote</TableHead>
                  <TableHead>Vence</TableHead>
                  <TableHead className="text-right">Stock</TableHead>
                  <TableHead>Salida</TableHead>
                  <TableHead>Estado</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {[
                  { p: "Jamón cocido feteado 200 g", b: "Campo Serrano", lot: "JC2201", exp: "2026-09-15", qty: 3, rank: 1, sev: "crit" as const, st: <StatusPill tone="crit" solid>Vencido</StatusPill> },
                  { p: "Yogur bebible frutilla 1 L", b: "Lácteos del Valle", lot: "L2409B", exp: "2026-09-18", qty: 26, rank: 1, sev: "crit" as const, st: <StatusPill tone="crit">Crítico</StatusPill> },
                  { p: "Pan lactal blanco 550 g", b: "Panificados San Roque", lot: "PL1709", exp: "2026-09-22", qty: 14, rank: 1, sev: "warn" as const, st: <StatusPill tone="warn">Por vencer</StatusPill> },
                  { p: "Yogur bebible frutilla 1 L", b: "Lácteos del Valle", lot: "L2409F", exp: "2026-11-02", qty: 36, rank: 2, sev: "none" as const, st: <StatusPill tone="ok">OK</StatusPill> },
                  { p: "Crema de leche 200 ml", b: "Lácteos del Valle", lot: "L2410A", exp: "2026-10-09", qty: 24, rank: 1, sev: "info" as const, st: <StatusPill tone="info">Próximo</StatusPill> },
                ].map((r) => (
                  <SeverityRow key={r.lot} severity={r.sev}>
                    <TableCell className="min-w-[200px]">
                      <div className="font-semibold">{r.p}</div>
                      <div className="text-xs text-muted-foreground">{r.b}</div>
                    </TableCell>
                    <TableCell className="font-mono text-sm">{r.lot}</TableCell>
                    <TableCell>
                      <ExpiryChip expiry={r.exp} />
                    </TableCell>
                    <TableCell className="text-right font-semibold tabular-nums">{r.qty}</TableCell>
                    <TableCell>
                      <LotRankChip rank={r.rank} />
                    </TableCell>
                    <TableCell>{r.st}</TableCell>
                  </SeverityRow>
                ))}
              </TableBody>
            </Table>
          </Panel>
          <Usage use="Listados operativos: vencimientos, reposición, importaciones, clientes. En pantallas chicas scrollean dentro de su contenedor." avoid="Cebras de colores, bordes verticales, tablas con radio o sombra." />
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-feedback" title="Feedback" lead="Toast para confirmar lo que ya pasó; aviso en línea para lo que hay que resolver acá; diálogo solo para decisiones que no se pueden deshacer.">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-2">
            <Specimen label="Toasts (sonner)">
              <div className="flex flex-wrap gap-2">
                <Button variant="outline" size="sm" onClick={() => toast.success("Ingreso registrado", { description: "24 u. de Yogur bebible frutilla 1 L · lote L2410C" })}>
                  Éxito
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => toast.error("No se pudo cobrar", { description: "El posnet no respondió. Probá de nuevo o elegí otro medio." })}
                >
                  Error
                </Button>
                <Button variant="outline" size="sm" onClick={() => toast.warning("Stock insuficiente", { description: "Quedan 6 u. de Aceite de girasol 1,5 L." })}>
                  Advertencia
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => toast("Producto descartado", { description: "Salchichas x 6 · 3 u.", action: { label: "Deshacer", onClick: () => toast.success("Descarte deshecho") } })}
                >
                  Con acción
                </Button>
              </div>
            </Specimen>
            <Specimen label="Diálogo de confirmación">
              <Dialog open={confirmOpen} onOpenChange={setConfirmOpen}>
                <DialogTrigger asChild>
                  <Button variant="outline" size="sm" className="self-start">
                    <Trash2 aria-hidden="true" />
                    Descartar vencidos
                  </Button>
                </DialogTrigger>
                <DialogContent className="max-w-md">
                  <DialogHeader>
                    <DialogTitle>¿Descartar 3 u. vencidas de Jamón cocido feteado?</DialogTitle>
                    <DialogDescription>
                      Lote JC2201 · venció el 15/09/2026. Se registra como merma por vencimiento y no se puede vender más.
                    </DialogDescription>
                  </DialogHeader>
                  <DialogFooter>
                    <Button variant="outline" onClick={() => setConfirmOpen(false)}>
                      Cancelar
                    </Button>
                    <Button
                      variant="destructive"
                      onClick={() => {
                        toast.success("Descarte registrado", { description: "3 u. · Jamón cocido feteado 200 g" })
                        setConfirmOpen(false)
                      }}
                    >
                      Descartar 3 u.
                    </Button>
                  </DialogFooter>
                </DialogContent>
              </Dialog>
            </Specimen>
            <div className="flex flex-col gap-2 lg:col-span-2">
              <div className="flex items-start gap-3 rounded-control border border-crit/40 bg-crit-soft px-3 py-2.5 text-crit-ink">
                <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                <p className="text-base">
                  <strong className="font-semibold">Crítico:</strong> En cuarentena por recall · no se puede vender.
                </p>
              </div>
              <div className="flex items-start gap-3 rounded-control border border-warn/40 bg-warn-soft px-3 py-2.5 text-warn-ink">
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                <p className="text-base">
                  <strong className="font-semibold">Atención:</strong> Este lote vence antes que mercadería que ingresó antes: con FIFO se venderá después.
                </p>
              </div>
              <div className="flex items-start gap-3 rounded-control border border-info/30 bg-info-soft px-3 py-2.5 text-info-ink">
                <Info className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                <p className="text-base">
                  <strong className="font-semibold">Info:</strong> Solo ves datos administrativos: nunca el stock, las ventas ni los chats de tus clientes.
                </p>
              </div>
            </div>
          </div>
        </Section>

        {/* ------------------------------------------------------------ */}
        <Section id="ds-vacio" title="Vacío, carga y error" lead="Cada estado dice qué pasa y qué hacer. El vacío invita a la primera acción; la carga respeta la forma final; el error ofrece salida.">
          <div className="grid grid-cols-1 gap-3 lg:grid-cols-3">
            <Panel as="div">
              <EmptyState
                icon={<Package />}
                title="Todavía no cargaste productos"
                description="Hacé tu primera carga importando tu planilla de Excel o CSV. Tarda unos minutos."
                action={
                  <Button size="sm">
                    <Plus aria-hidden="true" />
                    Importar Excel/CSV
                  </Button>
                }
              />
            </Panel>
            <Panel as="div" className="p-4" aria-busy="true" aria-label="Cargando vencimientos">
              <div className="gd-eyebrow mb-3">Cargando vencimientos…</div>
              <div className="flex flex-col gap-3">
                {[0, 1, 2, 3].map((i) => (
                  <div key={i} className="flex items-center gap-3">
                    <Skeleton className="h-9 w-1 rounded-none" />
                    <div className="flex-1 space-y-1.5">
                      <Skeleton className="h-3.5 w-3/4" />
                      <Skeleton className="h-3 w-1/3" />
                    </div>
                    <Skeleton className="h-[22px] w-24 rounded-tag" />
                  </div>
                ))}
              </div>
            </Panel>
            <Panel as="div">
              <EmptyState
                icon={<WifiOff />}
                title="No pudimos cargar los vencimientos"
                description="Se cortó la conexión con el servidor. Tus datos están a salvo: probá de nuevo en unos segundos."
                action={
                  <Button size="sm" variant="outline" onClick={() => toast.success("Vencimientos actualizados")}>
                    <RefreshCw aria-hidden="true" />
                    Reintentar
                  </Button>
                }
              />
            </Panel>
          </div>
          <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
            <CalendarClock className="h-4 w-4" aria-hidden="true" />
            Los skeletons y animaciones se apagan con «reducir movimiento» del sistema.
          </p>
        </Section>
      </div>

      <nav aria-label="Secciones de Góndola UI" className="hidden xl:block">
        <div className="sticky top-4">
          <div className="gd-eyebrow mb-2">En esta página</div>
          <ul className="space-y-0.5 border-l">
            {SECTIONS.map(([id, label]) => (
              <li key={id}>
                <button
                  type="button"
                  onClick={() => document.getElementById(id)?.scrollIntoView({ block: "start" })}
                  className="-ml-px block w-full border-l-2 border-transparent py-1 pl-3 text-left text-sm text-muted-foreground hover:border-primary hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  {label}
                </button>
              </li>
            ))}
          </ul>
        </div>
      </nav>
    </div>
  )
}
