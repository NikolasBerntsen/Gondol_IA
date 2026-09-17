import * as React from "react"
import { toast } from "sonner"
import { AlertTriangle, Camera, CheckCircle2, Keyboard, RotateCcw, ScanLine } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { BarcodeDigits } from "../components/BarcodeDigits"
import { ExpiryChip } from "../components/ExpiryChip"
import { LotRankChip } from "../components/LotRankChip"
import { Field, QtyStepper, fieldDescribedBy } from "../components/Controls"
import { INTAKE, POS_ITEMS, product } from "../data"
import { formatDate, formatMoney, parseDmy } from "../format"

function confidenceTone(c: number) {
  return c >= 80 ? "text-ok-ink" : c >= 50 ? "text-warn-ink" : "text-crit-ink"
}

function OcrChoice({
  name,
  label,
  options,
  value,
  onChange,
}: {
  name: string
  label: string
  options: { value: string; confidence: number; display: string }[]
  value: string
  onChange: (v: string) => void
}) {
  return (
    <div role="radiogroup" aria-label={label} className="flex flex-col gap-1.5">
      <span className="gd-eyebrow">{label}</span>
      <div className="flex flex-wrap gap-2">
        {options.map((o) => {
          const active = o.value === value
          return (
            <button
              key={o.value}
              type="button"
              role="radio"
              aria-checked={active}
              name={name}
              onClick={() => onChange(o.value)}
              className={cn(
                "inline-flex h-10 items-center gap-2 rounded-control border px-3 font-mono text-sm font-medium tabular-nums transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                active ? "border-primary bg-primary/[0.08] text-foreground" : "border-input bg-card text-muted-foreground hover:bg-muted"
              )}
            >
              {active ? <CheckCircle2 className="h-4 w-4 text-primary" aria-hidden="true" /> : null}
              <span className={active ? "text-foreground" : ""}>{o.display}</span>
              <span className={cn("font-semibold", confidenceTone(o.confidence))}>· {o.confidence}%</span>
              <span className="sr-only">de confianza</span>
            </button>
          )
        })}
      </div>
    </div>
  )
}

function Viewfinder({ onDetect }: { onDetect: () => void }) {
  const p = product(INTAKE.productId)
  return (
    <div className="relative aspect-[4/3] w-full max-w-full overflow-hidden rounded-panel bg-camera text-camera-foreground">
      {/* escena de cámara simulada */}
      <div
        aria-hidden="true"
        className="absolute inset-0 opacity-90"
        style={{
          background:
            "radial-gradient(60% 50% at 30% 30%, hsl(var(--primary) / 0.35), transparent 70%), radial-gradient(50% 60% at 80% 80%, hsl(var(--info) / 0.25), transparent 70%)",
        }}
      />
      <div aria-hidden="true" className="absolute left-1/2 top-1/2 w-[62%] -translate-x-1/2 -translate-y-1/2 rotate-[-3deg] rounded-[6px] bg-paper px-3 pb-2 pt-3 text-paper-ink blur-[0.4px]">
        <BarcodeDigits code={p.ean} width={180} bare className="w-full [&_svg]:h-auto [&_svg]:w-full" />
      </div>
      {/* marco */}
      <div aria-hidden="true" className="absolute inset-x-[12%] inset-y-[18%]">
        <span className="gd-viewfinder-corner left-0 top-0 rounded-tl-[6px] border-l-[3px] border-t-[3px]" />
        <span className="gd-viewfinder-corner right-0 top-0 rounded-tr-[6px] border-r-[3px] border-t-[3px]" />
        <span className="gd-viewfinder-corner bottom-0 left-0 rounded-bl-[6px] border-b-[3px] border-l-[3px]" />
        <span className="gd-viewfinder-corner bottom-0 right-0 rounded-br-[6px] border-b-[3px] border-r-[3px]" />
        <div className="absolute inset-0 overflow-hidden">
          <span className="absolute inset-x-2 top-0 block h-0.5 animate-scanline bg-crit shadow-[0_0_12px_2px_hsl(var(--crit)/0.7)] motion-reduce:top-1/2 motion-reduce:animate-none" />
        </div>
      </div>
      <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 bg-gradient-to-t from-camera/90 to-transparent px-3 pb-3 pt-8">
        <span className="inline-flex items-center gap-2 rounded-full bg-camera/70 px-2.5 py-1 text-sm font-semibold" aria-live="polite">
          <ScanLine className="h-4 w-4" aria-hidden="true" />
          Escaneando código…
        </span>
        <button
          type="button"
          onClick={onDetect}
          className="rounded-control px-2 py-1 text-sm font-semibold underline underline-offset-2 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
        >
          Leer ahora
        </button>
      </div>
    </div>
  )
}

function IntakeApp() {
  const p = product(INTAKE.productId)
  const posItem = POS_ITEMS.find((i) => i.productId === p.id)!
  const [phase, setPhase] = React.useState<"scanning" | "detected">("scanning")
  const [ocr, setOcr] = React.useState<"idle" | "reading" | "done">("idle")
  const [expiry, setExpiry] = React.useState("")
  const [lot, setLot] = React.useState("")
  const [qty, setQty] = React.useState(24)
  const [cost, setCost] = React.useState("1.380")
  const [supplier, setSupplier] = React.useState(INTAKE.suppliers[0])
  const [saving, setSaving] = React.useState(false)
  const [detections, setDetections] = React.useState(0)

  React.useEffect(() => {
    if (phase !== "scanning") return
    const t = window.setTimeout(() => setPhase("detected"), detections === 0 ? 1800 : 2600)
    return () => window.clearTimeout(t)
  }, [phase, detections])

  const readLabel = () => {
    setOcr("reading")
    window.setTimeout(() => {
      setOcr("done")
      setExpiry(INTAKE.ocr.expiry[0].value)
      setLot(INTAKE.ocr.lot[0].value)
    }, 1300)
  }

  const expiryIso = parseDmy(expiry)
  const expiryError = expiry.trim() && !expiryIso ? `Fecha inválida: ${expiry}. Usá dd/mm/aaaa.` : undefined
  const olderLots = INTAKE.existingLots
  const rotationWarning = !!expiryIso && olderLots.some((l) => l.expiry > expiryIso)
  const canSave = phase === "detected" && !!expiryIso && qty > 0 && !saving

  const reset = () => {
    setPhase("scanning")
    setOcr("idle")
    setExpiry("")
    setLot("")
    setQty(24)
    setDetections((d) => d + 1)
  }

  const save = () => {
    if (!canSave) return
    setSaving(true)
    window.setTimeout(() => {
      setSaving(false)
      toast.success("Ingreso registrado", {
        description: `${qty} u. de ${p.name} · lote ${lot || "sin lote"} · VTO ${expiry} · Sucursal Centro`,
      })
      reset()
    }, 900)
  }

  return (
    <div className="flex min-h-full flex-col">
      <div className="flex flex-col gap-4 px-4 pb-6 pt-4">
        <div className="flex items-center justify-between gap-2">
          <div>
            <h1 className="font-display text-lg font-semibold leading-7 tracking-[-0.01em] text-foreground">Carga de mercadería</h1>
            <p className="text-sm text-muted-foreground">Sucursal Centro · Sofía A.</p>
          </div>
          {phase === "detected" ? (
            <Button variant="outline" size="sm" onClick={reset}>
              <RotateCcw aria-hidden="true" />
              Escanear otro
            </Button>
          ) : null}
        </div>

        {phase === "scanning" ? (
          <>
            <Viewfinder onDetect={() => setPhase("detected")} />
            <Button variant="ghost" className="self-start" onClick={() => toast("Ingresá el código a mano", { description: "En la app real se abre el teclado numérico." })}>
              <Keyboard aria-hidden="true" />
              Ingresar código a mano
            </Button>
          </>
        ) : (
          <>
            <div className="flex items-center gap-2 rounded-control border border-ok/30 bg-ok-soft px-3 py-2 text-sm font-semibold text-ok-ink" role="status">
              <CheckCircle2 className="h-4 w-4" aria-hidden="true" />
              Código leído · EAN-13
            </div>

            <section aria-label="Producto detectado" className="rounded-panel border bg-card p-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="gd-eyebrow">{p.category}</div>
                  <h2 className="mt-0.5 text-md font-semibold leading-6 text-foreground">{p.name}</h2>
                  <p className="text-sm text-muted-foreground">{p.brand}</p>
                </div>
                <div className="text-right">
                  <div className="text-xs text-muted-foreground">Precio de venta</div>
                  <div className="font-display text-lg font-semibold leading-6 tabular-nums">{formatMoney(p.price)}</div>
                </div>
              </div>
              <div className="mt-3 flex items-end justify-between gap-3 border-t pt-3">
                <BarcodeDigits code={p.ean} width={150} />
                <div className="text-right text-sm">
                  <div className="text-muted-foreground">Stock vendible</div>
                  <div className="font-semibold tabular-nums">{posItem.stock} u.</div>
                </div>
              </div>
            </section>

            <section aria-label="Vencimiento y lote" className="flex flex-col gap-3">
              {ocr === "idle" ? (
                <Button variant="outline" size="lg" className="w-full" onClick={readLabel}>
                  <Camera aria-hidden="true" />
                  Leer vencimiento y lote con la cámara
                </Button>
              ) : ocr === "reading" ? (
                <div className="flex flex-col gap-2" aria-live="polite">
                  <Button variant="outline" size="lg" className="w-full" loading>
                    Leyendo etiqueta…
                  </Button>
                  <div className="flex gap-2">
                    <Skeleton className="h-10 w-40" />
                    <Skeleton className="h-10 w-28" />
                  </div>
                </div>
              ) : (
                <div className="flex flex-col gap-3 rounded-panel border border-dashed border-input p-3">
                  <p className="text-sm text-muted-foreground">La cámara sugiere, vos elegís. Tocá la lectura correcta o corregila abajo.</p>
                  <OcrChoice
                    name="ocr-vto"
                    label="Vencimiento"
                    value={expiry}
                    onChange={setExpiry}
                    options={INTAKE.ocr.expiry.map((o) => ({ value: o.value, confidence: o.confidence, display: `VTO ${o.value}` }))}
                  />
                  <OcrChoice
                    name="ocr-lote"
                    label="Lote"
                    value={lot}
                    onChange={setLot}
                    options={INTAKE.ocr.lot.map((o) => ({ value: o.value, confidence: o.confidence, display: `LOTE ${o.value}` }))}
                  />
                </div>
              )}

              <div className="grid grid-cols-2 gap-3">
                <Field id="intake-expiry" label="Vencimiento" error={expiryError}>
                  <Input
                    id="intake-expiry"
                    inputMode="numeric"
                    placeholder="dd/mm/aaaa"
                    value={expiry}
                    onChange={(e) => setExpiry(e.target.value)}
                    aria-invalid={!!expiryError || undefined}
                    aria-describedby={fieldDescribedBy("intake-expiry", { error: expiryError })}
                    className="h-11 font-mono tabular-nums"
                  />
                </Field>
                <Field id="intake-lot" label="Lote" optional>
                  <Input id="intake-lot" value={lot} onChange={(e) => setLot(e.target.value.toUpperCase())} placeholder="L0000" className="h-11 font-mono uppercase" />
                </Field>
              </div>
            </section>

            <section aria-label="Cantidad y costo" className="grid grid-cols-1 gap-3">
              <div className="flex items-end justify-between gap-3">
                <div className="flex flex-col gap-1.5">
                  <label htmlFor="intake-qty" className="text-sm font-semibold text-foreground">
                    Cantidad
                  </label>
                  <QtyStepper id="intake-qty" label="Cantidad" size="lg" value={qty} onChange={setQty} min={0} max={999} />
                </div>
                <Field id="intake-cost" label="Costo unitario" className="flex-1">
                  <div className="relative">
                    <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">$</span>
                    <Input
                      id="intake-cost"
                      inputMode="decimal"
                      value={cost}
                      onChange={(e) => setCost(e.target.value)}
                      className="h-11 pl-7 text-right tabular-nums"
                      aria-describedby="intake-cost-help"
                    />
                  </div>
                </Field>
              </div>
              <p id="intake-cost-help" className="-mt-1 text-right text-sm text-muted-foreground">
                Último costo: $ 1.350 (12/09/2026)
              </p>
              <Field id="intake-supplier" label="Proveedor">
                <Select value={supplier} onValueChange={setSupplier}>
                  <SelectTrigger id="intake-supplier" className="h-11">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {INTAKE.suppliers.map((s) => (
                      <SelectItem key={s} value={s}>
                        {s}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </Field>
            </section>

            <section aria-labelledby="lotes-existentes" className="rounded-panel border bg-card">
              <div className="flex items-baseline justify-between gap-2 px-4 pb-2 pt-3">
                <h2 id="lotes-existentes" className="text-base font-semibold">
                  Ya tenés en Sucursal Centro
                </h2>
                <span className="text-xs text-muted-foreground">Orden de salida FIFO</span>
              </div>
              <ul className="divide-y border-t">
                {olderLots.map((l, i) => (
                  <li key={l.lot} className="flex items-center gap-2.5 px-4 py-2.5">
                    <LotRankChip rank={i + 1} />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="font-mono text-sm font-medium">{l.lot}</span>
                        <ExpiryChip expiry={l.expiry} />
                      </div>
                      <div className="text-xs text-muted-foreground">Ingresó el {l.receivedAt}</div>
                    </div>
                    <span className="font-semibold tabular-nums">{l.qty} u.</span>
                  </li>
                ))}
                {expiryIso ? (
                  <li className={cn("flex items-center gap-2.5 bg-primary/[0.04] px-4 py-2.5", rotationWarning ? "gd-stripe-warn" : "gd-stripe-ok")}>
                    <LotRankChip rank={olderLots.length + 1} />
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-1.5">
                        <span className="font-mono text-sm font-medium">{lot || "Sin lote"}</span>
                        <ExpiryChip expiry={expiryIso} />
                      </div>
                      <div className="text-xs font-semibold text-primary">Nuevo · ingresa hoy</div>
                    </div>
                    <span className="font-semibold tabular-nums">{qty} u.</span>
                  </li>
                ) : null}
              </ul>
            </section>

            {rotationWarning ? (
              <div role="status" className="flex gap-3 rounded-control border border-warn/40 bg-warn-soft px-3 py-3 text-warn-ink">
                <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                <div className="min-w-0">
                  <p className="text-base font-semibold">Revisá el orden de salida</p>
                  <p className="mt-0.5 text-base">Este lote vence antes que mercadería que ingresó antes: con FIFO se venderá después.</p>
                  <Button
                    variant="link"
                    className="mt-1 h-auto px-0 text-warn-ink"
                    onClick={() => toast("Descuento programado", { description: `-15% al lote ${lot} desde el ${formatDate("2026-10-20")}` })}
                  >
                    Programar descuento para este lote
                  </Button>
                </div>
              </div>
            ) : null}
          </>
        )}
      </div>

      <div
        className="sticky bottom-0 mt-auto border-t bg-card px-4 pt-3"
        style={{ paddingBottom: "calc(16px + env(safe-area-inset-bottom, 0px))" }}
      >
        <Button size="xl" className="w-full" disabled={!canSave} loading={saving} onClick={save}>
          {saving ? "Registrando…" : phase === "detected" ? `Registrar ingreso · ${qty} u.` : "Registrar ingreso"}
        </Button>
        {phase === "detected" && !expiryIso ? (
          <p className="mt-2 text-center text-sm text-muted-foreground">Cargá el vencimiento para registrar el ingreso.</p>
        ) : null}
      </div>
    </div>
  )
}

export function IntakeScreen() {
  return (
    <div className="lg:flex lg:items-start lg:gap-12 lg:px-8 lg:py-8 xl:pl-[7%]">
      <div className="lg:w-[390px] lg:shrink-0 lg:rounded-[48px] lg:bg-device lg:p-[11px]">
        <div className="relative bg-background lg:overflow-hidden lg:rounded-[38px]">
          <div className="hidden h-9 items-center justify-between px-7 pt-1 font-mono text-xs font-semibold text-foreground lg:flex" aria-hidden="true">
            <span>10:42</span>
            <span className="h-5 w-24 rounded-full bg-device" />
            <span className="flex items-center gap-1">
              4G
              <span className="relative inline-block h-2.5 w-5 rounded-[3px] border border-foreground/70">
                <span className="absolute inset-y-[1px] left-[1px] w-3 rounded-[1px] bg-foreground/80" />
              </span>
            </span>
          </div>
          <div className="gd-scroll lg:h-[760px] lg:overflow-y-auto">
            <IntakeApp />
          </div>
        </div>
      </div>

      <aside className="hidden max-w-[46ch] pt-10 lg:block">
        <div className="gd-eyebrow">Vista móvil · rol Empleado</div>
        <h2 className="mt-1 font-display text-2xl font-semibold leading-10 tracking-[-0.015em] text-foreground">Carga con una mano, en el depósito</h2>
        <p className="mt-2 text-read text-muted-foreground">
          Sofía recibe la mercadería con el celular. Una columna, controles de 44 px y el botón principal fijo abajo, al alcance del pulgar.
        </p>
        <ol className="mt-5 space-y-3">
          {[
            ["Escaneá el código", "El visor detecta EAN-13 sola; si la etiqueta está dañada, se carga a mano."],
            ["Leé vencimiento y lote", "El OCR propone lecturas con su confianza: la persona elige, la cámara sugiere."],
            ["Revisá la rotación", "Si el lote nuevo vence antes que uno más viejo, avisamos sin bloquear (FIFO)."],
            ["Registrá el ingreso", "Cada ingreso crea un lote propio, aunque el producto ya tenga stock."],
          ].map(([t, d], i) => (
            <li key={t} className="flex gap-3">
              <span className="grid h-6 w-6 shrink-0 place-items-center rounded-full border border-primary text-xs font-bold tabular-nums text-primary">{i + 1}</span>
              <span>
                <span className="block text-base font-semibold text-foreground">{t}</span>
                <span className="block text-base text-muted-foreground">{d}</span>
              </span>
            </li>
          ))}
        </ol>
      </aside>
    </div>
  )
}
