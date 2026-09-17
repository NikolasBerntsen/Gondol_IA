import * as React from "react"
import { toast } from "sonner"
import {
  ArrowRight,
  CalendarClock,
  Check,
  Lightbulb,
  Package,
  PackageX,
  ShieldAlert,
  Sparkles,
  TrendingDown,
  TrendingUp,
  Undo2,
  Wallet,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Panel, PanelHeader, PageHeader } from "../components/Panel"
import { StatusPill, type Tone } from "../components/StatusPill"
import { ExpiryChip, EXPIRY_LABEL } from "../components/ExpiryChip"
import { StockStatusPill, stockSeverity } from "../components/StockStatusPill"
import { SeverityRow, SeverityItem } from "../components/SeverityRow"
import { Sparkline } from "../components/Sparkline"
import { PriceTag } from "../components/PriceTag"
import { EmptyState } from "../components/Controls"
import { SalesStockChart } from "./dashboard/SalesStockChart"
import {
  BRANCHES,
  BRANCH_SUMMARY,
  EXPIRING,
  KPI_SPARKS,
  RECALL,
  RECOMMENDATIONS,
  RESTOCK,
  TODAY,
  TREND,
  USERS,
  branchName,
  expiryBucket,
  product,
  stockStatus,
  type ExpiryBucket,
  type Recommendation,
  type Role,
} from "../data"
import { daysBetween, formatLongDate, formatMoney, formatNumber } from "../format"
import type { Scope } from "../shell/AppShell"

const BUCKET_TONE: Record<ExpiryBucket, Tone> = { EXPIRED: "crit", CRITICAL: "crit", WARNING: "warn", UPCOMING: "info", OK: "neutral" }
const BUCKET_SEV = { EXPIRED: "crit", CRITICAL: "crit", WARNING: "warn", UPCOMING: "info", OK: "none" } as const

function scrollToId(id: string) {
  const el = document.getElementById(id)
  if (!el) return
  const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
  el.scrollIntoView({ behavior: reduce ? "auto" : "smooth", block: "start" })
  el.focus({ preventScroll: true })
}

// ---------------------------------------------------------------------------
function KpiTile({
  label,
  value,
  icon,
  tone,
  delta,
  deltaTone,
  spark,
}: {
  label: string
  value: string
  icon: React.ReactNode
  tone: Tone
  delta: React.ReactNode
  deltaTone?: "up-good" | "up-bad" | "neutral"
  spark: number[]
}) {
  const iconBg: Record<Tone, string> = {
    ok: "bg-ok-soft text-ok-ink",
    warn: "bg-warn-soft text-warn-ink",
    crit: "bg-crit-soft text-crit-ink",
    info: "bg-info-soft text-info-ink",
    neutral: "bg-primary/10 text-primary",
  }
  return (
    <Panel as="div" className="flex flex-col gap-2 p-4">
      <div className="flex items-start justify-between gap-2">
        <div className="flex min-w-0 items-center gap-2">
          <span className={cn("grid h-7 w-7 shrink-0 place-items-center rounded-control [&_svg]:size-4", iconBg[tone])} aria-hidden="true">
            {icon}
          </span>
          <span className="min-w-0 text-sm font-semibold leading-[18px] text-muted-foreground">{label}</span>
        </div>
        <span className="shrink-0">
          <Sparkline data={spark} tone={tone === "neutral" ? "neutral" : tone} width={72} height={26} />
        </span>
      </div>
      <div className="whitespace-nowrap font-display text-xl font-semibold leading-none tracking-[-0.02em] tabular-nums text-foreground sm:text-2xl">
        {value}
      </div>
      <div
        className={cn(
          "flex items-center gap-1 text-sm",
          deltaTone === "up-bad" ? "text-warn-ink" : deltaTone === "up-good" ? "text-ok-ink" : "text-muted-foreground"
        )}
      >
        {delta}
      </div>
    </Panel>
  )
}

// ---------------------------------------------------------------------------
function RecommendationItem({
  rec,
  readOnly,
  state,
  onState,
}: {
  rec: Recommendation
  readOnly: boolean
  state: "PENDING" | "ACCEPTED" | "DISCARDED"
  onState: (s: "PENDING" | "ACCEPTED" | "DISCARDED") => void
}) {
  const typeLabel = { REORDER: "Reposición", DISCOUNT: "Descuento por vencimiento", REVIEW_ANOMALY: "Anomalía de stock" }[rec.type]
  const typeIcon = { REORDER: <Package />, DISCOUNT: <CalendarClock />, REVIEW_ANOMALY: <Lightbulb /> }[rec.type]
  return (
    <li className={cn("flex min-w-0 flex-col gap-3 p-4 sm:p-5", state === "DISCARDED" && "opacity-60")}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-[0.08em] text-muted-foreground [&_svg]:size-3.5">
          {typeIcon}
          {typeLabel}
        </span>
        <span className="text-xs text-muted-foreground">Sucursal {branchName(rec.branch)}</span>
      </div>
      <h3 className="text-md font-semibold leading-6 text-foreground">{rec.title}</h3>
      <p className="text-base text-muted-foreground">{rec.explanation}</p>
      <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 text-sm">
        <dt className="text-muted-foreground">Confianza</dt>
        <dd className="flex items-center gap-2">
          <span className="h-1.5 w-full max-w-[120px] overflow-hidden rounded-full bg-muted" aria-hidden="true">
            <span className="block h-full rounded-full bg-primary" style={{ width: `${rec.confidence}%` }} />
          </span>
          <span className="font-semibold tabular-nums">{rec.confidence}%</span>
        </dd>
        <dt className="text-muted-foreground">Impacto</dt>
        <dd className="font-medium text-foreground">{rec.impact}</dd>
        <dt className="text-muted-foreground">Acción</dt>
        <dd className="font-medium text-foreground">{rec.action}</dd>
      </dl>
      <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
        {readOnly ? (
          <span className="text-sm text-muted-foreground">Solo el administrador puede aceptarla o descartarla.</span>
        ) : state === "PENDING" ? (
          <>
            <Button size="sm" onClick={() => onState("ACCEPTED")}>
              <Check aria-hidden="true" />
              Aceptar
            </Button>
            <Button size="sm" variant="ghost" onClick={() => onState("DISCARDED")}>
              Descartar
            </Button>
          </>
        ) : (
          <>
            <StatusPill tone={state === "ACCEPTED" ? "ok" : "neutral"}>{state === "ACCEPTED" ? "Aceptada" : "Descartada"}</StatusPill>
            <Button size="sm" variant="ghost" onClick={() => onState("PENDING")}>
              <Undo2 aria-hidden="true" />
              Deshacer
            </Button>
          </>
        )}
      </div>
    </li>
  )
}

// ---------------------------------------------------------------------------
export function DashboardScreen({
  role,
  scope,
  onScope,
  onOpenRecall,
  recallAcknowledged,
}: {
  role: Role
  scope: Scope
  onScope: (s: Scope) => void
  onOpenRecall: () => void
  recallAcknowledged: boolean
}) {
  const readOnly = role === "TENANT_BOSS"
  const user = USERS[role]
  const inScope = <T extends { branch: string }>(rows: T[]) => (scope === "all" ? rows : rows.filter((r) => r.branch === scope))
  const branch = scope === "all" ? null : BRANCH_SUMMARY.find((b) => b.id === scope)!
  const [recState, setRecState] = React.useState<Record<string, "PENDING" | "ACCEPTED" | "DISCARDED">>({})
  const [ordered, setOrdered] = React.useState<Record<string, boolean>>({})

  const expiring = inScope(EXPIRING)
  const restock = inScope(RESTOCK)
  const recs = inScope(RECOMMENDATIONS)
  const soon = expiring.filter((e) => daysBetween(TODAY, e.expiry) <= 5)
  const out = restock.filter((r) => r.stock === 0)
  const showRecall = scope === "all" || scope === RECALL.branch

  const kpi = {
    expiring: branch ? branch.expiring : BRANCH_SUMMARY.reduce((a, b) => a + b.expiring, 0),
    low: branch ? branch.lowStock : BRANCH_SUMMARY.reduce((a, b) => a + b.lowStock, 0),
    value: branch ? branch.inventoryValue : BRANCH_SUMMARY.reduce((a, b) => a + b.inventoryValue, 0),
  }
  const share = branch ? branch.inventoryValue / kpi.value : 1
  const totalValue = BRANCH_SUMMARY.reduce((a, b) => a + b.inventoryValue, 0)
  const trend = React.useMemo(
    () =>
      TREND.map((p) => ({
        ...p,
        ventas: Math.round(p.ventas * (branch ? branch.inventoryValue / totalValue : share)),
        stock: Math.round(p.stock * (branch ? branch.inventoryValue / totalValue : share)),
      })),
    [branch, share, totalValue]
  )
  const salesTotal = BRANCH_SUMMARY.reduce((a, b) => a + b.salesToday, 0)
  const pending = recs.filter((r) => (recState[r.id] ?? "PENDING") === "PENDING").length

  const setRec = (r: Recommendation, s: "PENDING" | "ACCEPTED" | "DISCARDED") => {
    setRecState((prev) => ({ ...prev, [r.id]: s }))
    if (s === "ACCEPTED") toast.success("Recomendación aceptada", { description: `${r.title} · ${r.action}` })
    if (s === "DISCARDED") toast("Recomendación descartada", { description: "No la vas a volver a ver para este lote." })
  }

  const attention: {
    id: string
    sev: "crit" | "warn" | "info"
    icon: React.ReactNode
    title: string
    detail: string
    meta?: React.ReactNode
    action: React.ReactNode
  }[] = []
  if (showRecall) {
    attention.push({
      id: "recall",
      sev: "crit",
      icon: <ShieldAlert />,
      title: "Alerta de recall en Fisherton",
      detail: `${RECALL.productName} · lote ${RECALL.lot} · ${RECALL.units} u. en cuarentena`,
      meta: recallAcknowledged ? (
        <StatusPill tone="neutral">Vista por {user.short} · 10:43</StatusPill>
      ) : (
        <StatusPill tone="crit" solid>
          Sin ver
        </StatusPill>
      ),
      action: (
        <Button size="sm" variant={recallAcknowledged ? "outline" : "destructive"} onClick={onOpenRecall}>
          Ver alerta
        </Button>
      ),
    })
  }
  if (soon.length) {
    const [a, b] = soon
    attention.push({
      id: "soon",
      sev: "warn",
      icon: <CalendarClock />,
      title: `${soon.length} ${soon.length === 1 ? "lote vence" : "lotes vencen"} en 5 días o menos`,
      detail: [a && `${product(a.productId).name} vence mañana (${branchName(a.branch)})`, b && `${product(b.productId).name} en ${daysBetween(TODAY, b.expiry)} días (${branchName(b.branch)})`]
        .filter(Boolean)
        .join(" · "),
      action: (
        <Button size="sm" variant="outline" onClick={() => scrollToId("vencimientos")}>
          Ver vencimientos
        </Button>
      ),
    })
  }
  if (out.length) {
    attention.push({
      id: "out",
      sev: "crit",
      icon: <PackageX />,
      title: `${out.length} ${out.length === 1 ? "producto sin stock" : "productos sin stock"}`,
      detail: out.map((r) => `${product(r.productId).name.split(" ").slice(0, 2).join(" ")} (${branchName(r.branch)})`).join(" · "),
      action: (
        <Button size="sm" variant="outline" onClick={() => scrollToId("reponer")}>
          Ver reposición
        </Button>
      ),
    })
  }
  if (recs.length) {
    attention.push({
      id: "ia",
      sev: "info",
      icon: <Sparkles />,
      title: `${pending} ${pending === 1 ? "recomendación" : "recomendaciones"} de la IA ${readOnly ? "para revisar" : "sin responder"}`,
      detail: recs.map((r) => ({ REORDER: "reposición", DISCOUNT: "descuento por vencimiento", REVIEW_ANOMALY: "anomalía de stock" })[r.type]).join(", ").replace(/^./, (c) => c.toUpperCase()),
      action: (
        <Button size="sm" variant="outline" onClick={() => scrollToId("ia")}>
          Revisar
        </Button>
      ),
    })
  }

  return (
    <div className="flex max-w-[1560px] flex-col gap-5 px-4 py-5 sm:px-6 lg:gap-6 lg:px-8 lg:py-7">
      <PageHeader
        title="Resumen del negocio"
        description={
          <>
            ¡Hola, {user.short}! Esto es lo que pasa hoy {branch ? `en la Sucursal ${branchName(branch.id)}` : `en tus ${BRANCHES.length} sucursales`}.
          </>
        }
        actions={
          <div className="flex flex-col items-start gap-1 sm:items-end">
            <span className="text-base font-semibold text-foreground">{formatLongDate(TODAY).replace(/^./, (c) => c.toUpperCase())}</span>
            <span className="flex items-center gap-2 text-sm text-muted-foreground">
              {readOnly ? <StatusPill tone="info">Modo lectura</StatusPill> : null}
              Actualizado a las 10:42
            </span>
          </div>
        }
      />

      {/* Resumen antes que detalle */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-12">
        <Panel className="xl:col-span-8" aria-labelledby="para-hoy">
          <PanelHeader
            id="para-hoy"
            title="Para hoy"
            description={attention.length ? `${attention.length} temas necesitan tu atención, ordenados por urgencia` : "No hay nada urgente"}
          />
          <ul className="divide-y border-t">
            {attention.map((a) => (
              <SeverityItem key={a.id} severity={a.sev} className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3 pr-4 sm:pr-5">
                <span
                  className={cn(
                    "hidden h-8 w-8 shrink-0 place-items-center rounded-control sm:grid [&_svg]:size-4",
                    a.sev === "crit" ? "bg-crit-soft text-crit-ink" : a.sev === "warn" ? "bg-warn-soft text-warn-ink" : "bg-info-soft text-info-ink"
                  )}
                  aria-hidden="true"
                >
                  {a.icon}
                </span>
                <div className="min-w-0 flex-1 basis-[240px]">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-base font-semibold text-foreground">{a.title}</span>
                    {a.meta}
                  </div>
                  <p className="mt-0.5 text-sm text-muted-foreground">{a.detail}</p>
                </div>
                <div className="shrink-0">{a.action}</div>
              </SeverityItem>
            ))}
          </ul>
        </Panel>

        <Panel className="flex flex-col justify-between gap-4 border-primary/25 bg-primary/[0.05] p-4 sm:p-5 xl:col-span-4">
          <div>
            <div className="gd-eyebrow text-primary">Sugerencia GondolIA</div>
            <h2 className="mt-1 font-display text-lg font-semibold leading-7 tracking-[-0.01em] text-foreground">Mantené tu negocio siempre fresco</h2>
            <p className="mt-1.5 text-base text-muted-foreground">
              Activá descuentos automáticos para lotes que vencen en 3 días o menos. La etiqueta se actualiza en la góndola y el precio en la caja.
            </p>
          </div>
          <div className="flex flex-wrap items-end justify-between gap-4">
            <PriceTag size="sm" price={1720} listPrice={2150} offer="-20% VTO CERCANO" unit="c/u" />
            <Button
              variant="outline"
              size="sm"
              onClick={() => toast.info("Descuentos automáticos", { description: "Se configuran desde Configuración → Vencimientos." })}
            >
              Configurar descuentos
            </Button>
          </div>
        </Panel>
      </div>

      {/* KPIs */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiTile
          label="Productos"
          value={formatNumber(1248)}
          icon={<Package />}
          tone="neutral"
          spark={KPI_SPARKS.products}
          delta={
            <>
              <TrendingUp className="h-3.5 w-3.5" aria-hidden="true" />
              +36 este mes · catálogo
            </>
          }
          deltaTone="up-good"
        />
        <KpiTile
          label="Por vencer"
          value={formatNumber(kpi.expiring)}
          icon={<CalendarClock />}
          tone="warn"
          spark={KPI_SPARKS.expiring}
          delta={<>{soon.length} lotes en 5 días o menos</>}
          deltaTone="up-bad"
        />
        <KpiTile
          label="Stock bajo"
          value={formatNumber(kpi.low)}
          icon={<PackageX />}
          tone="crit"
          spark={KPI_SPARKS.lowStock}
          delta={<>{out.length} sin stock · mínimo por sucursal</>}
        />
        <KpiTile
          label="Valor inventario"
          value={formatMoney(kpi.value)}
          icon={<Wallet />}
          tone="ok"
          spark={KPI_SPARKS.value}
          delta={
            <>
              {branch ? <TrendingDown className="h-3.5 w-3.5" aria-hidden="true" /> : <TrendingUp className="h-3.5 w-3.5" aria-hidden="true" />}
              {branch ? "-1,1% vs. mes anterior" : "+3,2% vs. mes anterior"} · a costo
            </>
          }
          deltaTone={branch ? "neutral" : "up-good"}
        />
      </div>

      {/* Tendencia + sucursales */}
      <div className="grid grid-cols-1 gap-5 xl:grid-cols-12">
        <Panel className="xl:col-span-7">
          <PanelHeader
            title="Ventas y stock"
            description="Tendencia de ventas e inventario · últimos 30 días"
            actions={
              <div className="flex items-center gap-4 text-sm text-muted-foreground" aria-hidden="true">
                <span className="flex items-center gap-1.5">
                  <span className="h-0.5 w-4 rounded bg-foreground" />
                  Ventas (u.)
                </span>
                <span className="flex items-center gap-1.5">
                  <span className="h-3 w-4 rounded-[2px] border-t-2 border-primary bg-primary/20" />
                  Stock total (u.)
                </span>
              </div>
            }
          />
          <div className="px-3 pb-4 sm:px-4">
            <SalesStockChart data={trend} />
          </div>
        </Panel>

        <Panel className="xl:col-span-5" aria-labelledby="sucursales">
          <PanelHeader id="sucursales" title="Sucursales" description="Hoy hasta las 10:42 · tocá una para filtrar" />
          <Table containerClassName="border-t" className="[&_td]:px-2 [&_th]:px-2">
            <TableHeader>
              <TableRow>
                <TableHead className="pl-4">Sucursal</TableHead>
                <TableHead className="text-right">Ventas hoy</TableHead>
                <TableHead className="text-right">Vence</TableHead>
                <TableHead className="pr-4 text-right">Bajo</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {BRANCH_SUMMARY.map((b) => (
                <SeverityRow
                  key={b.id}
                  severity={b.recall ? "crit" : "none"}
                  data-state={scope === b.id ? "selected" : undefined}
                  className="cursor-pointer"
                  onClick={() => onScope(scope === b.id ? "all" : b.id)}
                >
                  <TableCell>
                    <button
                      type="button"
                      className="flex flex-col items-start text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                      aria-pressed={scope === b.id}
                      onClick={(e) => {
                        e.stopPropagation()
                        onScope(scope === b.id ? "all" : b.id)
                      }}
                    >
                      <span className="flex items-center gap-1.5 font-semibold text-foreground">
                        {branchName(b.id)}
                        {b.recall ? (
                          <StatusPill tone="crit" dot={false} className="h-[18px] px-1.5 text-[11px]">
                            Recall
                          </StatusPill>
                        ) : null}
                      </span>
                      <span className="mt-1 block h-1 w-24 overflow-hidden rounded-full bg-muted" aria-hidden="true">
                        <span className="block h-full bg-primary/70" style={{ width: `${(b.salesToday / salesTotal) * 100}%` }} />
                      </span>
                      <span className="mt-0.5 text-xs text-muted-foreground">{b.tickets} tickets</span>
                    </button>
                  </TableCell>
                  <TableCell className="whitespace-nowrap text-right font-medium tabular-nums">{formatMoney(b.salesToday)}</TableCell>
                  <TableCell className="text-right tabular-nums text-warn-ink">{b.expiring}</TableCell>
                  <TableCell className="pr-4 text-right tabular-nums text-crit-ink">{b.lowStock}</TableCell>
                </SeverityRow>
              ))}
              <TableRow className="hover:bg-transparent">
                <TableCell className="pl-4 text-sm font-semibold text-muted-foreground">Total</TableCell>
                <TableCell className="whitespace-nowrap text-right font-semibold tabular-nums">{formatMoney(salesTotal)}</TableCell>
                <TableCell className="text-right font-semibold tabular-nums">{BRANCH_SUMMARY.reduce((a, b) => a + b.expiring, 0)}</TableCell>
                <TableCell className="pr-4 text-right font-semibold tabular-nums">{BRANCH_SUMMARY.reduce((a, b) => a + b.lowStock, 0)}</TableCell>
              </TableRow>
            </TableBody>
          </Table>
        </Panel>
      </div>

      {/* Detalle: tablas */}
      <div className="grid grid-cols-1 gap-5 2xl:grid-cols-2">
        <Panel id="vencimientos" tabIndex={-1} className="scroll-mt-4 focus:outline-none">
          <PanelHeader
            title="Próximos vencimientos"
            description={`${expiring.length} lotes en los próximos 30 días · se venden primero (FIFO)`}
            actions={
              <Button variant="ghost" size="sm">
                Ver todos
                <ArrowRight aria-hidden="true" />
              </Button>
            }
          />
          <Table containerClassName="border-t">
            <TableHeader>
              <TableRow>
                <TableHead>Producto</TableHead>
                {scope === "all" ? <TableHead>Sucursal</TableHead> : null}
                <TableHead>Lote</TableHead>
                <TableHead>Vence</TableHead>
                <TableHead className="text-right">Días</TableHead>
                <TableHead>Estado</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {expiring.map((e) => {
                const p = product(e.productId)
                const b = expiryBucket(e.expiry)
                const d = daysBetween(TODAY, e.expiry)
                return (
                  <SeverityRow key={e.id} severity={BUCKET_SEV[b]}>
                    <TableCell className="min-w-[200px]">
                      <div className="font-semibold text-foreground">{p.name}</div>
                      <div className="text-xs text-muted-foreground">
                        {p.brand} · {e.qty} u.
                      </div>
                    </TableCell>
                    {scope === "all" ? <TableCell className="whitespace-nowrap">{branchName(e.branch)}</TableCell> : null}
                    <TableCell className="font-mono text-sm">{e.lot}</TableCell>
                    <TableCell>
                      <ExpiryChip expiry={e.expiry} bucket={b} />
                    </TableCell>
                    <TableCell className="whitespace-nowrap text-right tabular-nums">{d === 1 ? "1 día" : `${d} días`}</TableCell>
                    <TableCell>
                      <StatusPill tone={BUCKET_TONE[b]}>{EXPIRY_LABEL[b]}</StatusPill>
                    </TableCell>
                  </SeverityRow>
                )
              })}
            </TableBody>
          </Table>
        </Panel>

        <Panel id="reponer" tabIndex={-1} className="scroll-mt-4 focus:outline-none">
          <PanelHeader
            title="Artículos a reponer"
            description="Una fila por producto y sucursal · sugerencia según ventas de 14 días"
            actions={
              <Button variant="ghost" size="sm">
                Ver todos
                <ArrowRight aria-hidden="true" />
              </Button>
            }
          />
          <Table containerClassName="border-t">
            <TableHeader>
              <TableRow>
                <TableHead>Producto</TableHead>
                {scope === "all" ? <TableHead>Sucursal</TableHead> : null}
                <TableHead className="text-right">Stock</TableHead>
                <TableHead className="text-right">Mínimo</TableHead>
                <TableHead>Sugerencia</TableHead>
                <TableHead>Estado</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {restock.map((r) => {
                const p = product(r.productId)
                const st = stockStatus(r.stock, r.min)
                return (
                  <SeverityRow key={r.id} severity={stockSeverity(st)}>
                    <TableCell className="min-w-[190px]">
                      <div className="font-semibold text-foreground">{p.name}</div>
                      <div className="text-xs text-muted-foreground">{p.brand}</div>
                    </TableCell>
                    {scope === "all" ? <TableCell className="whitespace-nowrap">{branchName(r.branch)}</TableCell> : null}
                    <TableCell className={cn("text-right font-semibold tabular-nums", r.stock === 0 && "text-crit-ink")}>{r.stock}</TableCell>
                    <TableCell className="text-right tabular-nums text-muted-foreground">{r.min}</TableCell>
                    <TableCell className="whitespace-nowrap">
                      {readOnly ? (
                        <span className="font-medium">Comprar {r.suggest}</span>
                      ) : ordered[r.id] ? (
                        <span className="inline-flex h-8 items-center gap-1.5 text-sm font-semibold text-ok-ink">
                          <Check className="h-4 w-4" aria-hidden="true" />
                          En el pedido
                        </span>
                      ) : (
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setOrdered((o) => ({ ...o, [r.id]: true }))
                            toast.success(`Agregado al pedido: ${r.suggest} u.`, { description: `${p.name} · Sucursal ${branchName(r.branch)}` })
                          }}
                        >
                          Comprar {r.suggest}
                        </Button>
                      )}
                    </TableCell>
                    <TableCell>
                      <StockStatusPill status={st} />
                    </TableCell>
                  </SeverityRow>
                )
              })}
            </TableBody>
          </Table>
        </Panel>
      </div>

      {/* IA */}
      <Panel id="ia" tabIndex={-1} className="scroll-mt-4 focus:outline-none">
        <PanelHeader
          icon={<Sparkles />}
          title="Recomendaciones de la IA"
          description="Generadas hoy a las 07:30 con las ventas de los últimos 90 días"
          actions={
            <span className="text-sm text-muted-foreground">
              <span className="font-semibold tabular-nums text-foreground">{pending}</span> sin responder
            </span>
          }
        />
        {recs.length ? (
          <ul className="grid grid-cols-1 divide-y border-t lg:grid-cols-3 lg:divide-x lg:divide-y-0">
            {recs.map((r) => (
              <RecommendationItem key={r.id} rec={r} readOnly={readOnly} state={recState[r.id] ?? "PENDING"} onState={(s) => setRec(r, s)} />
            ))}
          </ul>
        ) : (
          <EmptyState
            className="border-t"
            icon={<Sparkles />}
            title="Sin recomendaciones para esta sucursal"
            description="La IA vuelve a analizar las ventas y el stock esta noche. Si algo cambia, te avisamos."
          />
        )}
      </Panel>
    </div>
  )
}
