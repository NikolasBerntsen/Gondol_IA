import * as React from "react"
import { toast } from "sonner"
import { ArrowLeft, ArrowRight, ChevronDown, Download, EyeOff, FileSpreadsheet, RotateCcw, Search, ShieldAlert } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Checkbox } from "@/components/ui/checkbox"
import { Input } from "@/components/ui/input"
import { Progress } from "@/components/ui/progress"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Panel, PageHeader } from "../components/Panel"
import { SeverityRow } from "../components/SeverityRow"
import { StatusPill } from "../components/StatusPill"
import { Segmented, WizardSteps } from "../components/Controls"
import { BRANCHES, EXISTING_CATEGORIES, IMPORT_BASE, IMPORT_FILE, IMPORT_ROWS, KNOWN_CATEGORIES, RECALL, TODAY, product, type ImportRow } from "../data"
import { formatMoney, formatNumber, isValidEan13, parseArs, parseDmy } from "../format"

type Field = "code" | "name" | "category" | "price" | "stock" | "lot" | "expiry" | "branch"
type Level = "ERROR" | "WARNING"
interface Msg {
  field: Field
  level: Level
  message: string
}
type RowStatus = "VALID" | "WARNING" | "ERROR" | "SKIPPED"

const COLUMNS: { field: Field; label: string; mono?: boolean; align?: "right"; width: string }[] = [
  { field: "code", label: "Código", mono: true, width: "min-w-[150px]" },
  { field: "name", label: "Nombre", width: "min-w-[220px]" },
  { field: "category", label: "Categoría", width: "min-w-[120px]" },
  { field: "price", label: "Precio venta", align: "right", width: "min-w-[110px]" },
  { field: "stock", label: "Stock", align: "right", width: "min-w-[72px]" },
  { field: "lot", label: "Lote", mono: true, width: "min-w-[90px]" },
  { field: "expiry", label: "Vencimiento", mono: true, width: "min-w-[118px]" },
  { field: "branch", label: "Sucursal", width: "min-w-[120px]" },
]
const BRANCH_NAMES = BRANCHES.map((b) => b.name)

export function validateRow(r: ImportRow): Msg[] {
  const m: Msg[] = []
  if (!r.name.trim()) m.push({ field: "name", level: "ERROR", message: "Falta el nombre del producto" })
  if (/^\d{13}$/.test(r.code) && !isValidEan13(r.code)) m.push({ field: "code", level: "WARNING", message: "Dígito verificador del EAN inválido" })
  if (r.category && !EXISTING_CATEGORIES.includes(r.category))
    m.push({ field: "category", level: "WARNING", message: `Categoría «${r.category}» nueva: se va a crear` })
  const price = parseArs(r.price)
  if (r.price.trim() && price === null) m.push({ field: "price", level: "ERROR", message: `Precio inválido: «${r.price}»` })
  else if (price !== null && price < 0) m.push({ field: "price", level: "ERROR", message: `El precio no puede ser negativo: «${r.price}»` })
  else if (price !== null && price < r.cost) m.push({ field: "price", level: "WARNING", message: `Precio de venta menor al costo (${formatMoney(r.cost)})` })
  const s = r.stock.trim()
  if (s && !/^-?\d+$/.test(s)) m.push({ field: "stock", level: "ERROR", message: `La cantidad debe ser un número entero: «${s}»` })
  else if (/^-\d+$/.test(s)) m.push({ field: "stock", level: "ERROR", message: `La cantidad no puede ser negativa: «${s}»` })
  if (r.expiry.trim()) {
    const iso = parseDmy(r.expiry)
    if (!iso) m.push({ field: "expiry", level: "ERROR", message: `Fecha inválida: ${r.expiry}` })
    else if (iso < TODAY) m.push({ field: "expiry", level: "WARNING", message: "Vencimiento pasado: entra como vencido pendiente de descarte" })
  }
  if (!BRANCH_NAMES.includes(r.branch.trim())) m.push({ field: "branch", level: "ERROR", message: `Sucursal «${r.branch}» no existe` })
  if (r.lot.toUpperCase() === RECALL.lot && r.code === product(RECALL.productId).ean)
    m.push({ field: "lot", level: "WARNING", message: "Lote con alerta de recall: entra en cuarentena" })
  return m
}

const statusOf = (r: ImportRow, msgs: Msg[]): RowStatus =>
  r.skipped ? "SKIPPED" : msgs.some((x) => x.level === "ERROR") ? "ERROR" : msgs.length ? "WARNING" : "VALID"

// ---------------------------------------------------------------------------
function EditableCell({
  row,
  col,
  msgs,
  onCommit,
  disabled,
}: {
  row: ImportRow
  col: (typeof COLUMNS)[number]
  msgs: Msg[]
  onCommit: (value: string) => void
  disabled?: boolean
}) {
  const [editing, setEditing] = React.useState(false)
  const [draft, setDraft] = React.useState("")
  const value = row[col.field] as string
  const level: Level | null = msgs.some((m) => m.level === "ERROR") ? "ERROR" : msgs.length ? "WARNING" : null
  const id = `cell-${row.id}-${col.field}`

  if (editing) {
    return (
      <input
        id={id}
        autoFocus
        value={draft}
        list={col.field === "branch" ? "dl-branches" : col.field === "category" ? "dl-categories" : undefined}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={() => {
          setEditing(false)
          if (draft !== value) onCommit(draft)
        }}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault()
            ;(e.target as HTMLInputElement).blur()
          } else if (e.key === "Escape") {
            e.preventDefault()
            e.stopPropagation()
            setDraft(value)
            setEditing(false)
          }
        }}
        aria-label={`${col.label}, fila ${row.row}`}
        className={cn(
          "h-8 w-full rounded-[6px] border border-ring bg-card px-2 text-base text-foreground outline-none ring-2 ring-ring/25",
          col.mono && "font-mono text-sm",
          col.align === "right" && "text-right tabular-nums"
        )}
      />
    )
  }

  const button = (
    <button
      type="button"
      id={id}
      disabled={disabled}
      onClick={() => {
        setDraft(value)
        setEditing(true)
      }}
      aria-label={`${col.label}, fila ${row.row}: ${value || "vacío"}${msgs.length ? `. ${msgs.map((m) => m.message).join(". ")}` : ""}. Editar`}
      className={cn(
        "flex h-8 w-full min-w-0 items-center rounded-[6px] px-2 text-left text-base text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:hover:bg-transparent",
        col.mono && "font-mono text-sm",
        col.align === "right" && "justify-end tabular-nums",
        level === "ERROR" && "bg-crit-soft/70 text-crit-ink shadow-[inset_0_0_0_1.5px_hsl(var(--crit))] hover:bg-crit-soft",
        level === "WARNING" && "bg-warn-soft/60 shadow-[inset_0_0_0_1px_hsl(var(--warn))] hover:bg-warn-soft"
      )}
    >
      <span className="truncate">{value || <span className="text-muted-foreground">—</span>}</span>
    </button>
  )
  if (!msgs.length) return button
  return (
    <Tooltip delayDuration={80}>
      <TooltipTrigger asChild>{button}</TooltipTrigger>
      <TooltipContent side="bottom" align="start" className={cn(level === "ERROR" ? "bg-crit text-crit-foreground" : "")}>
        {msgs.map((m, i) => (
          <div key={i} className="font-medium">
            {m.level === "ERROR" ? "Error: " : "Advertencia: "}
            {m.message}
          </div>
        ))}
      </TooltipContent>
    </Tooltip>
  )
}

// ---------------------------------------------------------------------------
export function ImportReviewScreen() {
  const [rows, setRows] = React.useState<ImportRow[]>(IMPORT_ROWS)
  const [filter, setFilter] = React.useState<"ALL" | "VALID" | "WARNING" | "ERROR">("ALL")
  const [q, setQ] = React.useState("")
  const [selected, setSelected] = React.useState<Set<string>>(new Set())
  const [skipErrors, setSkipErrors] = React.useState(false)
  const [step, setStep] = React.useState(2)
  const [progress, setProgress] = React.useState(0)

  const evaluated = React.useMemo(
    () =>
      rows.map((r) => {
        const msgs = validateRow(r)
        return { r, msgs, status: statusOf(r, msgs) }
      }),
    [rows]
  )
  const count = (s: RowStatus) => evaluated.filter((e) => e.status === s).length
  const counters = {
    valid: IMPORT_BASE.valid + count("VALID"),
    warning: IMPORT_BASE.warning + count("WARNING"),
    error: count("ERROR"),
    skipped: count("SKIPPED"),
  }
  const blocked = counters.error > 0 && !skipErrors
  const importable = IMPORT_FILE.rows - counters.skipped - (skipErrors ? counters.error : 0)

  const visible = evaluated.filter((e) => {
    if (filter === "VALID" && e.status !== "VALID") return false
    if (filter === "WARNING" && e.status !== "WARNING") return false
    if (filter === "ERROR" && e.status !== "ERROR") return false
    if (q.trim()) {
      const t = q.trim().toLowerCase()
      return `${e.r.row} ${e.r.code} ${e.r.name} ${e.r.category} ${e.r.branch}`.toLowerCase().includes(t)
    }
    return true
  })
  const allVisibleSelected = visible.length > 0 && visible.every((e) => selected.has(e.r.id))
  const someVisibleSelected = visible.some((e) => selected.has(e.r.id))

  const update = (id: string, patch: Partial<ImportRow>) => setRows((prev) => prev.map((r) => (r.id === id ? { ...r, ...patch } : r)))

  const commitCell = (row: ImportRow, field: Field, value: string) => {
    const before = statusOf(row, validateRow(row))
    const next = { ...row, [field]: value }
    const after = statusOf(next, validateRow(next))
    update(row.id, { [field]: value })
    if (before === "ERROR" && after !== "ERROR") toast.success(`Fila ${row.row} corregida`, { description: after === "WARNING" ? "Quedó con una advertencia: se puede importar." : "La fila ya es válida." })
  }

  const bulk = (patch: Partial<ImportRow>, label: string) => {
    const ids = Array.from(selected)
    setRows((prev) => prev.map((r) => (selected.has(r.id) ? { ...r, ...patch } : r)))
    toast.success(label, { description: `${ids.length} ${ids.length === 1 ? "fila" : "filas"}` })
    setSelected(new Set())
  }

  const runImport = () => {
    setStep(3)
    const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
    if (reduce) {
      setProgress(100)
      setStep(4)
      return
    }
    setProgress(0)
    let p = 0
    const t = window.setInterval(() => {
      p = Math.min(100, p + 7 + Math.random() * 9)
      setProgress(p)
      if (p >= 100) {
        window.clearInterval(t)
        window.setTimeout(() => setStep(4), 350)
      }
    }, 140)
  }

  const statusPill = (s: RowStatus) =>
    s === "ERROR" ? (
      <StatusPill tone="crit">Error</StatusPill>
    ) : s === "WARNING" ? (
      <StatusPill tone="warn">Advertencia</StatusPill>
    ) : s === "SKIPPED" ? (
      <StatusPill tone="neutral">Omitida</StatusPill>
    ) : (
      <StatusPill tone="ok">Válida</StatusPill>
    )

  return (
    <div className="flex min-h-full max-w-[1560px] flex-col gap-5 px-4 pt-5 sm:px-6 lg:px-8 lg:pt-7">
      <datalist id="dl-branches">
        {BRANCH_NAMES.map((b) => (
          <option key={b} value={b} />
        ))}
      </datalist>
      <datalist id="dl-categories">
        {KNOWN_CATEGORIES.map((c) => (
          <option key={c} value={c} />
        ))}
      </datalist>

      <PageHeader
        eyebrow="Importaciones · Nueva importación"
        title="Importar Excel/CSV"
        description={
          step === 2
            ? "Revisá las filas antes de importar. Hacé clic en una celda para corregirla: los contadores se actualizan al instante."
            : step === 3
              ? "Revisá qué va a pasar y confirmá la importación."
              : "La importación terminó."
        }
        actions={
          <div className="flex h-10 min-w-0 max-w-full items-center gap-2.5 rounded-control border bg-card pl-3 pr-1.5">
            <FileSpreadsheet className="h-4 w-4 shrink-0 text-ok" aria-hidden="true" />
            <span className="min-w-0 truncate text-sm">
              <span className="font-semibold text-foreground">{IMPORT_FILE.name}</span>
              <span className="text-muted-foreground">
                {" "}
                · hoja {IMPORT_FILE.sheet} · {formatNumber(IMPORT_FILE.rows)} filas
              </span>
            </span>
            <Button variant="ghost" size="sm" className="h-7 shrink-0" onClick={() => toast("Cambiar archivo", { description: "Volvés al paso Archivo sin perder el mapeo de columnas." })}>
              Cambiar
            </Button>
          </div>
        }
      />

      <Panel as="div" className="px-4 py-3 sm:px-5">
        <WizardSteps steps={["Archivo", "Columnas", "Revisión", "Confirmar", "Resultado"]} current={step} />
      </Panel>

      {step === 2 ? (
        <Panel className="flex min-w-0 flex-col" aria-label="Revisión de filas">
          <div className="flex flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-5">
            <div className="gd-scroll -mx-1 w-full max-w-full overflow-x-auto px-1 py-1 sm:w-auto">
              <Segmented
                id="import-filter"
                className="grid w-full grid-cols-2 sm:inline-flex sm:w-auto"
                label="Filtrar filas por estado"
                value={filter}
                onChange={setFilter}
                options={[
                  { value: "ALL", label: "Todas", count: formatNumber(IMPORT_FILE.rows) },
                  { value: "VALID", label: "Válidas", count: formatNumber(counters.valid), tone: "ok" },
                  { value: "WARNING", label: "Advertencias", count: formatNumber(counters.warning), tone: "warn" },
                  { value: "ERROR", label: "Errores", count: formatNumber(counters.error), tone: counters.error ? "crit" : "ok" },
                ]}
              />
            </div>
            <div className="relative w-full sm:w-[260px]">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
              <label htmlFor="import-search" className="sr-only">
                Buscar en las filas
              </label>
              <Input id="import-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar fila, código o nombre" className="pl-9" />
            </div>
          </div>

          {selected.size ? (
            <div className="flex flex-wrap items-center gap-2 border-y border-primary/25 bg-primary/[0.06] px-4 py-2 sm:px-5" role="region" aria-label="Acciones masivas">
              <span className="mr-2 text-sm font-semibold text-foreground">
                {selected.size} {selected.size === 1 ? "fila seleccionada" : "filas seleccionadas"}
              </span>
              <Button size="sm" variant="outline" onClick={() => bulk({ skipped: true }, "Filas omitidas")}>
                <EyeOff aria-hidden="true" />
                Omitir filas
              </Button>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="sm" variant="outline">
                    Asignar categoría
                    <ChevronDown aria-hidden="true" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {KNOWN_CATEGORIES.map((c) => (
                    <DropdownMenuItem key={c} onSelect={() => bulk({ category: c }, `Categoría «${c}» asignada`)}>
                      {c}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <Button size="sm" variant="outline">
                    Asignar sucursal
                    <ChevronDown aria-hidden="true" />
                  </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  {BRANCH_NAMES.map((b) => (
                    <DropdownMenuItem key={b} onSelect={() => bulk({ branch: b }, `Sucursal ${b} asignada`)}>
                      Sucursal {b}
                    </DropdownMenuItem>
                  ))}
                </DropdownMenuContent>
              </DropdownMenu>
              <Button size="sm" variant="ghost" onClick={() => setSelected(new Set())}>
                Quitar selección
              </Button>
            </div>
          ) : (
            <div className="flex flex-wrap items-center justify-between gap-2 border-t px-4 py-2 text-sm text-muted-foreground sm:px-5">
              <span>
                Mostrando {visible.length} filas con observaciones y de muestra · {formatNumber(IMPORT_FILE.rows)} en total
              </span>
              <span className="hidden sm:inline">Pasá el mouse o enfocá una celda marcada para ver el motivo</span>
            </div>
          )}

          <Table containerClassName="border-t gd-scroll">
            <TableHeader>
              <TableRow>
                <TableHead className="w-10 pl-4">
                  <Checkbox
                    id="import-select-all"
                    aria-label="Seleccionar filas visibles"
                    checked={allVisibleSelected ? true : someVisibleSelected ? "indeterminate" : false}
                    onCheckedChange={(c) => {
                      const next = new Set(selected)
                      visible.forEach((e) => (c === true ? next.add(e.r.id) : next.delete(e.r.id)))
                      setSelected(next)
                    }}
                  />
                </TableHead>
                <TableHead className="text-right">Fila</TableHead>
                {COLUMNS.map((c) => (
                  <TableHead key={c.field} className={cn(c.align === "right" && "text-right", "px-2")}>
                    {c.label}
                  </TableHead>
                ))}
                <TableHead>Estado</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {visible.map(({ r, msgs, status }) => (
                <SeverityRow
                  key={r.id}
                  severity={status === "ERROR" ? "crit" : status === "WARNING" ? "warn" : "none"}
                  data-state={selected.has(r.id) ? "selected" : undefined}
                  className={cn(status === "SKIPPED" && "opacity-55")}
                >
                  <TableCell className="w-10">
                    <Checkbox
                      id={`import-row-${r.id}`}
                      aria-label={`Seleccionar fila ${r.row}`}
                      checked={selected.has(r.id)}
                      onCheckedChange={(c) => {
                        const next = new Set(selected)
                        if (c === true) next.add(r.id)
                        else next.delete(r.id)
                        setSelected(next)
                      }}
                    />
                  </TableCell>
                  <TableCell className="text-right font-mono text-sm tabular-nums text-muted-foreground">{r.row}</TableCell>
                  {COLUMNS.map((c) => (
                    <TableCell key={c.field} className={cn("px-1.5 py-1", c.width)}>
                      <EditableCell
                        row={r}
                        col={c}
                        msgs={msgs.filter((m) => m.field === c.field)}
                        disabled={r.skipped}
                        onCommit={(v) => commitCell(r, c.field, v)}
                      />
                    </TableCell>
                  ))}
                  <TableCell className="whitespace-nowrap">
                    <div className="flex items-center gap-1">
                      {statusPill(status)}
                      {r.skipped ? (
                        <Button variant="ghost" size="icon-sm" aria-label={`Restaurar fila ${r.row}`} onClick={() => update(r.id, { skipped: false })}>
                          <RotateCcw aria-hidden="true" />
                        </Button>
                      ) : null}
                    </div>
                  </TableCell>
                </SeverityRow>
              ))}
            </TableBody>
          </Table>
          {visible.length === 0 ? (
            <p className="border-t px-5 py-8 text-base text-muted-foreground">
              {filter === "ERROR" ? "No quedan errores. Podés continuar a confirmar." : "No hay filas que coincidan con la búsqueda."}
            </p>
          ) : null}
        </Panel>
      ) : step === 3 ? (
        <Panel className="max-w-[760px] p-5 sm:p-6">
          <h2 className="text-md font-semibold">Qué va a pasar</h2>
          <dl className="mt-3 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
            {[
              ["Productos nuevos", "1.031"],
              ["Productos a actualizar", "208"],
              ["Lotes a crear", "1.412"],
              ["Unidades a cargar", "38.906"],
              ["Categorías nuevas", "1 · Congelados"],
              ["Filas omitidas", formatNumber(IMPORT_FILE.rows - importable)],
            ].map(([k, v]) => (
              <div key={k}>
                <dt className="text-sm text-muted-foreground">{k}</dt>
                <dd className="font-display text-lg font-semibold tabular-nums">{v}</dd>
              </div>
            ))}
          </dl>
          <div className="mt-4 flex items-start gap-2 rounded-control border border-crit/35 bg-crit-soft px-3 py-2 text-sm text-crit-ink">
            <ShieldAlert className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />1 lote coincide con una alerta de recall (Sopa de tomate La Huerta, lote {RECALL.lot}): entra en cuarentena.
          </div>
          {progress > 0 ? (
            <div className="mt-5" aria-live="polite">
              <div className="mb-1.5 flex justify-between text-sm">
                <span>Importando filas…</span>
                <span className="tabular-nums">{Math.round(progress)}%</span>
              </div>
              <Progress value={progress} className="h-2 bg-muted [&>div]:bg-primary" aria-label="Progreso de la importación" />
            </div>
          ) : null}
        </Panel>
      ) : (
        <Panel className="max-w-[760px] p-5 sm:p-6">
          <StatusPill tone="ok">Importación aplicada</StatusPill>
          <h2 className="mt-2 font-display text-xl font-semibold">{formatNumber(importable)} filas importadas</h2>
          <p className="mt-1 text-read text-muted-foreground">
            Se crearon 1.031 productos y 1.412 lotes. Las fechas de ingreso respetan el orden del archivo para que FIFO funcione desde el primer día.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <Button onClick={() => toast("Inventario", { description: "En la app real abre /app/inventory" })}>Ir a Inventario</Button>
            <Button
              variant="outline"
              onClick={() => {
                setStep(2)
                setProgress(0)
              }}
            >
              Ver la revisión
            </Button>
          </div>
        </Panel>
      )}

      {/* Barra de acciones fija */}
      <div
        className="sticky bottom-0 z-10 -mx-4 mt-auto flex flex-wrap items-center gap-x-4 gap-y-3 border-t bg-card px-4 py-3 sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8"
        style={{ paddingBottom: "calc(12px + env(safe-area-inset-bottom, 0px))" }}
      >
        {step === 2 ? (
          <>
            <label htmlFor="skip-errors" className={cn("flex items-center gap-2 text-base", counters.error === 0 && "opacity-50")}>
              <Checkbox id="skip-errors" checked={skipErrors} disabled={counters.error === 0} onCheckedChange={(c) => setSkipErrors(c === true)} />
              Omitir filas con error ({counters.error})
            </label>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => toast("errores.csv", { description: `${counters.error + counters.warning} filas con error o advertencia y su motivo. En el prototipo no se descargan archivos.` })}
            >
              <Download aria-hidden="true" />
              <span className="hidden sm:inline">Descargar errores (CSV)</span>
              <span className="sm:hidden">Errores (CSV)</span>
            </Button>
            <div className="flex-1" />
            {blocked ? (
              <span className="text-sm text-crit-ink" id="continue-hint">
                Corregí {counters.error === 1 ? "el error" : `los ${counters.error} errores`} u omitilos para continuar.
              </span>
            ) : null}
            <Button variant="outline" onClick={() => toast("Columnas", { description: "Volvés al mapeo de columnas." })}>
              <ArrowLeft aria-hidden="true" />
              <span className="hidden sm:inline">Volver a columnas</span>
              <span className="sm:hidden">Volver</span>
            </Button>
            <Button disabled={blocked} aria-describedby={blocked ? "continue-hint" : undefined} onClick={() => setStep(3)}>
              Continuar a confirmar
              <ArrowRight aria-hidden="true" />
            </Button>
          </>
        ) : step === 3 ? (
          <>
            <span className="text-base text-muted-foreground">
              Se van a importar <strong className="tabular-nums text-foreground">{formatNumber(importable)}</strong> filas.
            </span>
            <div className="flex-1" />
            <Button variant="outline" onClick={() => setStep(2)} disabled={progress > 0}>
              <ArrowLeft aria-hidden="true" />
              Volver a revisión
            </Button>
            <Button onClick={runImport} loading={progress > 0 && progress < 100}>
              Importar {formatNumber(importable)} filas
            </Button>
          </>
        ) : (
          <span className="text-base text-muted-foreground">Te avisamos por notificación cuando termine cada importación.</span>
        )}
      </div>
    </div>
  )
}
