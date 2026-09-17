import * as React from "react"
import { toast } from "sonner"
import { Ban, Lock, Search } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Switch } from "@/components/ui/switch"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { Panel, PageHeader } from "../components/Panel"
import { StatusPill } from "../components/StatusPill"
import { SeverityRow } from "../components/SeverityRow"
import {
  MODULES,
  PLAN_LABEL,
  PLAN_PRICE,
  PLATFORM_ACTIVE_TENANTS,
  TENANTS,
  tenantMrr,
  type TenantModule,
  type TenantPlan,
  type TenantRow,
  type TenantStatus,
} from "../data"
import { formatMoney, formatNumber } from "../format"

const STATUS: Record<TenantStatus, { label: string; tone: "ok" | "crit" | "neutral" }> = {
  ACTIVE: { label: "Activo", tone: "ok" },
  DISABLED: { label: "Deshabilitado", tone: "crit" },
  CANCELLED: { label: "Baja", tone: "neutral" },
}

function PlanBadge({ plan }: { plan: TenantPlan }) {
  return (
    <span
      className={cn(
        "inline-flex h-[22px] items-center rounded-tag border px-1.5 font-mono text-[11px] font-semibold uppercase tracking-[0.04em]",
        plan === "PROFESIONAL" && "border-foreground bg-foreground text-background",
        plan === "BASICO" && "border-input bg-card text-foreground",
        plan === "FREEMIUM" && "border-dashed border-input bg-transparent text-muted-foreground"
      )}
    >
      {PLAN_LABEL[plan]}
    </span>
  )
}

type Pending = { tenant: TenantRow; module: TenantModule; blocked: boolean } | null

export function OwnerModulesScreen() {
  const [tenants, setTenants] = React.useState<TenantRow[]>(TENANTS)
  const [q, setQ] = React.useState("")
  const [plan, setPlan] = React.useState<"ALL" | TenantPlan>("ALL")
  const [status, setStatus] = React.useState<"ALL" | TenantStatus>("ALL")
  const [pending, setPending] = React.useState<Pending>(null)

  const adoption = MODULES.map((m) => {
    const delta = tenants.reduce((acc, t) => {
      const base = TENANTS.find((x) => x.id === t.id)!
      return acc + (t.modules[m.id] === base.modules[m.id] ? 0 : t.modules[m.id] ? 1 : -1)
    }, 0)
    const n = m.enabledTenants + delta
    return { ...m, n, pct: Math.round((n / PLATFORM_ACTIVE_TENANTS) * 100) }
  })

  const visible = tenants.filter((t) => {
    if (plan !== "ALL" && t.plan !== plan) return false
    if (status !== "ALL" && t.status !== status) return false
    if (q.trim() && !`${t.name} ${t.city} ${t.type}`.toLowerCase().includes(q.trim().toLowerCase())) return false
    return true
  })
  const mrrTotal = tenants.reduce((a, t) => a + tenantMrr(t), 0)

  const apply = (tenant: TenantRow, module: TenantModule, enabled: boolean) => {
    setTenants((prev) => prev.map((t) => (t.id === tenant.id ? { ...t, modules: { ...t.modules, [module]: enabled } } : t)))
    const m = MODULES.find((x) => x.id === module)!
    toast.success(`${m.short} ${enabled ? "habilitado" : "deshabilitado"} para ${tenant.name}`, {
      description: "Sus usuarios ven el cambio al instante: el menú se actualiza solo.",
    })
  }

  const onToggle = (tenant: TenantRow, module: TenantModule, next: boolean) => {
    if (next) return apply(tenant, module, true)
    setPending({ tenant, module, blocked: module === "MULTI_BRANCH" && tenant.branches > 1 })
  }

  const pm = pending ? MODULES.find((m) => m.id === pending.module)! : null
  const mrrAfter = pending ? tenantMrr({ ...pending.tenant, modules: { ...pending.tenant.modules, [pending.module]: false } }) : 0

  return (
    <div className="flex max-w-[1560px] flex-col gap-5 px-4 py-5 sm:px-6 lg:gap-6 lg:px-8 lg:py-7">
      <PageHeader
        eyebrow="Consola de dueños"
        title="Módulos por cliente"
        description="Activá o desactivá funciones por comercio. Los cambios aplican al instante para todos sus usuarios."
        actions={
          <div className="text-left sm:text-right">
            <div className="text-sm text-muted-foreground">MRR estimado de esta lista</div>
            <div className="font-display text-xl font-semibold leading-8 tabular-nums">{formatMoney(mrrTotal)}</div>
          </div>
        }
      />

      <div className="flex items-start gap-3 rounded-control border border-info/30 bg-info-soft px-3.5 py-2.5 text-info-ink">
        <Lock className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
        <p className="text-base">
          <strong className="font-semibold">Solo ves datos administrativos:</strong> nunca el stock, las ventas ni los chats de tus clientes.
        </p>
      </div>

      <section aria-label="Adopción de módulos" className="grid grid-cols-1 gap-4 md:grid-cols-3">
        {adoption.map((m) => (
          <Panel as="div" key={m.id} className="flex flex-col gap-3 p-4">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <h2 className="text-base font-semibold text-foreground">{m.name}</h2>
                <p className="mt-0.5 text-sm text-muted-foreground">{m.description}</p>
              </div>
              <span className="shrink-0 whitespace-nowrap rounded-tag bg-muted px-1.5 py-0.5 font-mono text-[11px] font-semibold text-muted-foreground">
                {m.price ? `+${formatMoney(m.price)}/suc.` : "Sin adicional"}
              </span>
            </div>
            <div className="flex items-end justify-between gap-3">
              <div className="font-display text-2xl font-semibold leading-none tabular-nums">{m.pct}%</div>
              <div className="text-sm text-muted-foreground">
                <span className="font-semibold tabular-nums text-foreground">{m.n}</span> de {PLATFORM_ACTIVE_TENANTS} clientes activos
              </div>
            </div>
            <div className="h-2 overflow-hidden rounded-full bg-muted" role="img" aria-label={`Adopción ${m.pct}%`}>
              <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${m.pct}%` }} />
            </div>
          </Panel>
        ))}
      </section>

      <Panel className="flex min-w-0 flex-col">
        <div className="flex flex-wrap items-end gap-3 px-4 py-3 sm:px-5">
          <div className="relative w-full sm:w-[280px]">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
            <label htmlFor="owner-search" className="sr-only">
              Buscar cliente
            </label>
            <Input id="owner-search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Buscar cliente o ciudad" className="pl-9" />
          </div>
          <div className="flex flex-wrap gap-3">
            <div className="flex items-center gap-2">
              <label htmlFor="owner-plan" className="text-sm font-medium text-muted-foreground">
                Plan
              </label>
              <Select value={plan} onValueChange={(v) => setPlan(v as typeof plan)}>
                <SelectTrigger id="owner-plan" className="w-[140px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">Todos</SelectItem>
                  {(Object.keys(PLAN_LABEL) as TenantPlan[]).map((p) => (
                    <SelectItem key={p} value={p}>
                      {PLAN_LABEL[p]} · {formatMoney(PLAN_PRICE[p])}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
            <div className="flex items-center gap-2">
              <label htmlFor="owner-status" className="text-sm font-medium text-muted-foreground">
                Estado
              </label>
              <Select value={status} onValueChange={(v) => setStatus(v as typeof status)}>
                <SelectTrigger id="owner-status" className="w-[150px]">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="ALL">Todos</SelectItem>
                  {(Object.keys(STATUS) as TenantStatus[]).map((s) => (
                    <SelectItem key={s} value={s}>
                      {STATUS[s].label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          </div>
          <div className="flex-1" />
          <span className="text-sm text-muted-foreground">
            {visible.length} de {tenants.length} clientes
          </span>
        </div>

        <Table containerClassName="border-t gd-scroll">
          <TableHeader>
            <TableRow>
              <TableHead className="pl-4">Cliente</TableHead>
              <TableHead>Plan</TableHead>
              <TableHead>Estado</TableHead>
              <TableHead className="w-[64px] whitespace-normal px-2 text-right leading-4">Suc.</TableHead>
              {MODULES.map((m) => (
                <TableHead key={m.id} className="w-[104px] whitespace-normal px-2 text-center leading-4">
                  {m.short}
                </TableHead>
              ))}
              <TableHead className="pr-4 text-right">MRR estimado</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {visible.map((t) => {
              const inactive = t.status !== "ACTIVE"
              return (
                <SeverityRow key={t.id} severity={t.status === "DISABLED" ? "crit" : "none"} className={cn(t.status === "CANCELLED" && "text-muted-foreground")}>
                  <TableCell className="min-w-[190px]">
                    <div className={cn("font-semibold", inactive ? "text-muted-foreground" : "text-foreground")}>{t.name}</div>
                    <div className="text-xs text-muted-foreground">
                      {t.type} · {t.city}
                      <span className="hidden 2xl:inline"> · actividad {t.lastActivity}</span>
                    </div>
                  </TableCell>
                  <TableCell>
                    <PlanBadge plan={t.plan} />
                  </TableCell>
                  <TableCell>
                    <StatusPill tone={STATUS[t.status].tone} solid={t.status === "DISABLED"}>
                      {STATUS[t.status].label}
                    </StatusPill>
                  </TableCell>
                  <TableCell className="px-2 text-right font-medium tabular-nums">{t.branches}</TableCell>
                  {MODULES.map((m) => {
                    const id = `mod-${t.id}-${m.id}`
                    return (
                      <TableCell key={m.id} className="px-2 text-center">
                        <Switch
                          id={id}
                          checked={t.modules[m.id]}
                          onCheckedChange={(c) => onToggle(t, m.id, c)}
                          aria-label={`${m.name} para ${t.name}`}
                          disabled={t.status === "CANCELLED"}
                        />
                      </TableCell>
                    )
                  })}
                  <TableCell className="whitespace-nowrap pr-4 text-right tabular-nums">
                    {inactive ? <span className="text-muted-foreground">{formatMoney(0)} · no factura</span> : <span className="font-semibold">{formatMoney(tenantMrr(t))}</span>}
                  </TableCell>
                </SeverityRow>
              )
            })}
          </TableBody>
        </Table>
        {visible.length === 0 ? <p className="border-t px-5 py-8 text-base text-muted-foreground">Ningún cliente coincide con los filtros.</p> : null}
        <p className="border-t px-4 py-3 text-sm text-muted-foreground sm:px-5">
          MRR = sucursales activas × (precio del plan + adicionales de módulos). Los clientes deshabilitados o dados de baja no facturan.
        </p>
      </Panel>

      <Dialog open={!!pending} onOpenChange={(o) => !o && setPending(null)}>
        <DialogContent className="max-w-md">
          {pending && pm ? (
            pending.blocked ? (
              <>
                <DialogHeader>
                  <DialogTitle>No se puede deshabilitar Multi-sucursal</DialogTitle>
                  <DialogDescription>
                    {pending.tenant.name} tiene {pending.tenant.branches} sucursales activas.
                  </DialogDescription>
                </DialogHeader>
                <div className="flex items-start gap-3 rounded-control border border-crit/40 bg-crit-soft px-3 py-2.5 text-crit-ink">
                  <Ban className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                  <p className="text-base">
                    Sin este módulo el cliente puede tener una sola sucursal. Pedile que desactive {pending.tenant.branches - 1}{" "}
                    {pending.tenant.branches - 1 === 1 ? "sucursal" : "sucursales"} antes, o dejá el módulo habilitado.
                  </p>
                </div>
                <DialogFooter>
                  <Button onClick={() => setPending(null)}>Entendido</Button>
                </DialogFooter>
              </>
            ) : (
              <>
                <DialogHeader>
                  <DialogTitle>
                    ¿Deshabilitar {pm.short} para {pending.tenant.name}?
                  </DialogTitle>
                  <DialogDescription>Esto es lo que cambia para el cliente:</DialogDescription>
                </DialogHeader>
                <ul className="space-y-2 text-base text-foreground">
                  {(pending.module === "POS_GONDOLIA"
                    ? ["Sus cajeros dejan de ver el Punto de venta y no pueden abrir turnos.", "Los tickets y cierres de caja anteriores siguen disponibles en el historial."]
                    : pending.module === "POS_INTEGRATION"
                      ? ["Las ventas que envíe su sistema de caja se rechazan hasta que lo vuelvas a habilitar.", "Deja de ver el menú Integración POS y la importación de ventas por CSV."]
                      : ["Pierde las transferencias y la vista consolidada de sucursales."]
                  ).map((s) => (
                    <li key={s} className="flex gap-2">
                      <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-muted-foreground" />
                      {s}
                    </li>
                  ))}
                  <li className="flex gap-2">
                    <span aria-hidden="true" className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-muted-foreground" />
                    No se borra ningún dato: podés volver a habilitarlo cuando quieras.
                  </li>
                </ul>
                {pending.tenant.status === "ACTIVE" ? (
                  <div className="flex items-center justify-between rounded-control bg-muted px-3 py-2 text-base">
                    <span className="text-muted-foreground">MRR estimado</span>
                    <span className="tabular-nums">
                      {formatMoney(tenantMrr(pending.tenant))} → <strong className="font-semibold">{formatMoney(mrrAfter)}</strong>
                    </span>
                  </div>
                ) : null}
                <DialogFooter>
                  <Button variant="outline" onClick={() => setPending(null)}>
                    Cancelar
                  </Button>
                  <Button
                    variant="destructive"
                    onClick={() => {
                      apply(pending.tenant, pending.module, false)
                      setPending(null)
                    }}
                  >
                    Deshabilitar módulo
                  </Button>
                </DialogFooter>
              </>
            )
          ) : null}
        </DialogContent>
      </Dialog>
      <p className="sr-only" aria-live="polite">
        {formatNumber(visible.length)} clientes visibles
      </p>
    </div>
  )
}
