import * as React from "react"
import {
  Bell,
  Check,
  ChevronDown,
  Menu,
  Monitor,
  Moon,
  PanelLeftClose,
  PanelLeftOpen,
  Search,
  ShieldAlert,
  Store,
  Sun,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Logo, LogoMark } from "../components/Logo"
import { Segmented } from "../components/Controls"
import { stripeClass } from "../components/SeverityRow"
import { BRANCHES, NOTIFICATIONS, TENANT, USERS, type BranchId, type Role } from "../data"
import { NAV, ROLE_OPTIONS, type NavItem } from "./nav"

export type ThemeChoice = "system" | "light" | "dark"
export type Scope = BranchId | "all"

// ---------------------------------------------------------------------------
// Riel
// ---------------------------------------------------------------------------
function RailItem({
  item,
  active,
  collapsed,
  onNavigate,
}: {
  item: NavItem
  active: boolean
  collapsed: boolean
  onNavigate: (key: string) => void
}) {
  const Icon = item.icon
  const btn = (
    <button
      type="button"
      onClick={() => onNavigate(item.key)}
      aria-current={active ? "page" : undefined}
      className={cn(
        "group relative flex h-9 w-full items-center gap-3 rounded-control text-left text-base font-medium transition-colors",
        collapsed ? "justify-center px-0" : "px-3",
        active ? "bg-rail-active text-rail-strong" : "text-rail-foreground hover:bg-rail-hover hover:text-rail-strong"
      )}
    >
      <Icon className={cn("h-[18px] w-[18px] shrink-0", active ? "text-rail-strong" : "text-rail-muted group-hover:text-rail-foreground")} aria-hidden="true" />
      {collapsed ? (
        <span className="sr-only">{item.label}</span>
      ) : (
        <span className="min-w-0 flex-1 truncate">{item.label}</span>
      )}
      {item.badge ? (
        collapsed ? (
          <span
            aria-hidden="true"
            className={cn("absolute right-2.5 top-2 h-2 w-2 rounded-full ring-2 ring-rail", item.badge.tone === "crit" ? "bg-crit" : "bg-rail-muted")}
          />
        ) : (
          <span
            className={cn(
              "rounded-[4px] px-1.5 font-mono text-[11px] font-semibold leading-[18px] tabular-nums",
              item.badge.tone === "crit" ? "bg-crit text-crit-foreground" : "bg-rail-strong/10 text-rail-foreground"
            )}
          >
            {item.badge.text}
            <span className="sr-only"> pendientes</span>
          </span>
        )
      ) : null}
    </button>
  )
  if (!collapsed) return btn
  return (
    <Tooltip delayDuration={200}>
      <TooltipTrigger asChild>{btn}</TooltipTrigger>
      <TooltipContent side="right">{item.label}</TooltipContent>
    </Tooltip>
  )
}

function Rail({
  role,
  activeKey,
  onNavigate,
  collapsed,
  onToggleCollapse,
  inDrawer,
}: {
  role: Role
  activeKey: string
  onNavigate: (key: string) => void
  collapsed: boolean
  onToggleCollapse?: () => void
  inDrawer?: boolean
}) {
  return (
    <nav aria-label="Menú principal" className="gd-rail flex h-full min-h-0 flex-col bg-rail text-rail-foreground">
      <div className={cn("flex h-14 shrink-0 items-center border-b border-rail-strong/[0.07]", collapsed ? "justify-center px-2" : "px-4")}>
        {collapsed ? <LogoMark /> : <Logo />}
      </div>
      <div className="gd-scroll min-h-0 flex-1 overflow-y-auto px-2.5 pb-3 pt-2" style={{ scrollbarColor: "hsl(var(--rail-hover)) transparent" }}>
        {NAV[role].map((group, gi) => (
          <div key={gi} className={cn(gi > 0 && "mt-3")}>
            {group.label ? (
              collapsed ? (
                <div aria-hidden="true" className="mx-3 mb-2 mt-1 h-px bg-rail-strong/10" />
              ) : (
                <div className="mb-1 px-3 pt-1 text-[11px] font-semibold uppercase tracking-[0.1em] text-rail-muted">{group.label}</div>
              )
            ) : null}
            <ul className="space-y-0.5">
              {group.items.map((it) => (
                <li key={it.key}>
                  <RailItem item={it} active={activeKey === it.key} collapsed={collapsed} onNavigate={onNavigate} />
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
      <div className={cn("shrink-0 border-t border-rail-strong/[0.07]", collapsed ? "px-2 py-2" : "px-4 py-3")}>
        {collapsed ? null : <p className="mb-2 text-xs text-rail-muted">Productos de hoy, clientes de siempre.</p>}
        {inDrawer || !onToggleCollapse ? null : (
          <button
            type="button"
            onClick={onToggleCollapse}
            className={cn(
              "flex h-8 items-center gap-2 rounded-control text-sm font-medium text-rail-muted hover:bg-rail-hover hover:text-rail-foreground",
              collapsed ? "w-full justify-center" : "-mx-2 px-2"
            )}
            aria-label={collapsed ? "Expandir menú" : "Colapsar menú"}
          >
            {collapsed ? <PanelLeftOpen className="h-4 w-4" /> : <PanelLeftClose className="h-4 w-4" />}
            {collapsed ? null : "Colapsar menú"}
          </button>
        )}
      </div>
    </nav>
  )
}

// ---------------------------------------------------------------------------
// Barra de demo (fuera del producto): rol y tema
// ---------------------------------------------------------------------------
function DemoStrip({
  role,
  onRole,
  theme,
  onTheme,
  onSimulateRecall,
}: {
  role: Role
  onRole: (r: Role) => void
  theme: ThemeChoice
  onTheme: (t: ThemeChoice) => void
  onSimulateRecall: () => void
}) {
  return (
    <div className="flex h-11 shrink-0 items-center gap-2 border-b border-dashed border-input bg-muted/70 px-3 sm:gap-3 sm:px-4">
      <span className="hidden font-mono text-[11px] font-semibold uppercase tracking-[0.08em] text-muted-foreground md:inline">
        Prototipo · datos ficticios
      </span>
      <span aria-hidden="true" className="hidden h-4 w-px bg-input md:inline" />
      <label htmlFor="demo-role" className="hidden whitespace-nowrap text-sm font-medium text-muted-foreground sm:inline">
        Ver como:
      </label>
      <Select value={role} onValueChange={(v) => onRole(v as Role)}>
        <SelectTrigger id="demo-role" aria-label="Ver la app como" className="h-8 w-[150px] bg-card text-sm sm:w-[170px]">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {ROLE_OPTIONS.map((o) => (
            <SelectItem key={o.value} value={o.value}>
              {o.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
      {role === "PLATFORM_OWNER" ? null : (
        <Tooltip delayDuration={300}>
          <TooltipTrigger asChild>
            <Button variant="outline" size="sm" onClick={onSimulateRecall} className="h-8 shrink-0 border-dashed bg-card px-2 lg:px-2.5">
              <ShieldAlert className="text-crit" aria-hidden="true" />
              <span className="sr-only lg:not-sr-only">Simular recall</span>
            </Button>
          </TooltipTrigger>
          <TooltipContent>Abrir la alerta de seguridad alimentaria (demo)</TooltipContent>
        </Tooltip>
      )}
      <div className="flex-1" />
      <Segmented
        id="demo-theme"
        label="Tema"
        size="sm"
        value={theme}
        onChange={onTheme}
        options={[
          { value: "system", label: <span className="hidden sm:inline">Sistema</span>, icon: <Monitor aria-hidden="true" />, title: "Seguir el tema del sistema" },
          { value: "light", label: <span className="hidden sm:inline">Claro</span>, icon: <Sun aria-hidden="true" />, title: "Tema claro" },
          { value: "dark", label: <span className="hidden sm:inline">Oscuro</span>, icon: <Moon aria-hidden="true" />, title: "Tema oscuro" },
        ]}
      />
    </div>
  )
}

// ---------------------------------------------------------------------------
// Barra superior
// ---------------------------------------------------------------------------
function ScopeControl({ role, scope, onScope }: { role: Role; scope: Scope; onScope: (s: Scope) => void }) {
  const base =
    // shrink-0: el alcance es dato operativo, se encoge el buscador antes que el nombre de la sucursal
    "inline-flex h-9 min-w-0 max-w-[58vw] shrink-0 items-center gap-2 rounded-control border border-input bg-card px-2.5 text-sm font-semibold text-foreground sm:max-w-[min(42vw,320px)] xl:max-w-none"
  if (role === "PLATFORM_OWNER") {
    return (
      <span className={cn(base, "border-dashed")}>
        <LogoMark size={18} />
        <span className="truncate">Consola GondolIA</span>
      </span>
    )
  }
  if (role === "TENANT_EMPLOYEE" || role === "TENANT_CASHIER") {
    return (
      <span className={base} title="Sucursal asignada">
        <Store className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        <span className="truncate">
          <span className="hidden xl:inline">{TENANT.name} · </span>
          Sucursal Centro{role === "TENANT_CASHIER" ? " · Caja 1" : ""}
        </span>
      </span>
    )
  }
  const label = scope === "all" ? "Todas las sucursales" : `Sucursal ${BRANCHES.find((b) => b.id === scope)!.name}`
  return (
    <DropdownMenu>
      <DropdownMenuTrigger className={cn(base, "hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")}>
        <Store className="h-4 w-4 shrink-0 text-primary" aria-hidden="true" />
        <span className="truncate">
          <span className="hidden xl:inline">{TENANT.name} · </span>
          {label}
        </span>
        <ChevronDown className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-64">
        <DropdownMenuLabel className="text-xs font-semibold uppercase tracking-[0.08em] text-muted-foreground">{TENANT.name}</DropdownMenuLabel>
        {[{ id: "all" as Scope, name: "Todas las sucursales", sub: "Vista consolidada" }, ...BRANCHES.map((b) => ({ id: b.id as Scope, name: `Sucursal ${b.name}`, sub: b.address }))].map((o, i) => (
          <React.Fragment key={o.id}>
            {i === 1 ? <DropdownMenuSeparator /> : null}
            <DropdownMenuItem onSelect={() => onScope(o.id)} className="items-start gap-2">
              <Check className={cn("mt-0.5 h-4 w-4", scope === o.id ? "opacity-100 text-primary" : "opacity-0")} aria-hidden="true" />
              <span className="flex flex-col">
                <span className="font-medium">{o.name}</span>
                <span className="text-xs text-muted-foreground">{o.sub}</span>
              </span>
            </DropdownMenuItem>
          </React.Fragment>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

function NotificationBell({ role, onOpenRecall }: { role: Role; onOpenRecall: () => void }) {
  const items = role === "PLATFORM_OWNER" ? [] : NOTIFICATIONS
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="relative" aria-label={`Notificaciones: ${items.length} sin leer`}>
          <Bell className="h-[18px] w-[18px]" />
          {items.length ? (
            <span className="absolute right-1 top-1 grid h-4 min-w-4 place-items-center rounded-full bg-crit px-1 font-mono text-[10px] font-bold leading-none text-crit-foreground ring-2 ring-card">
              {items.length}
            </span>
          ) : null}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[340px] max-w-[calc(100vw-24px)] p-0">
        <div className="flex items-center justify-between border-b px-4 py-3">
          <span className="text-base font-semibold">Notificaciones</span>
          <span className="text-xs text-muted-foreground">Hoy</span>
        </div>
        {items.length === 0 ? (
          <p className="px-4 py-6 text-base text-muted-foreground">No tenés notificaciones nuevas.</p>
        ) : (
          <ul className="divide-y">
            {items.map((n) => (
              <li key={n.id} className={cn(stripeClass(n.sev), "pl-4")}>
                <button
                  type="button"
                  className="flex w-full flex-col items-start gap-0.5 py-2.5 pr-4 text-left hover:bg-muted/50"
                  onClick={n.sev === "crit" ? onOpenRecall : undefined}
                >
                  <span className="flex w-full items-baseline justify-between gap-2">
                    <span className="text-base font-semibold text-foreground">{n.title}</span>
                    <span className="shrink-0 font-mono text-[11px] text-muted-foreground">{n.time}</span>
                  </span>
                  <span className="text-sm text-muted-foreground">{n.body}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  )
}

function TopBar({
  role,
  scope,
  onScope,
  onMenu,
  onOpenRecall,
}: {
  role: Role
  scope: Scope
  onScope: (s: Scope) => void
  onMenu: () => void
  onOpenRecall: () => void
}) {
  const user = USERS[role]
  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b bg-card px-3 sm:gap-3 sm:px-4">
      <Button variant="ghost" size="icon" className="lg:hidden" onClick={onMenu} aria-label="Abrir menú">
        <Menu className="h-5 w-5" />
      </Button>
      <div className="relative hidden min-w-0 shrink basis-[420px] md:block">
        <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" aria-hidden="true" />
        <label htmlFor="global-search" className="sr-only">
          Buscar
        </label>
        <input
          id="global-search"
          type="search"
          placeholder={role === "PLATFORM_OWNER" ? "Buscar clientes o avisos…" : "Buscar productos o códigos…"}
          className="h-9 w-full rounded-control border border-transparent bg-muted pl-9 pr-3 text-base text-foreground placeholder:text-muted-foreground hover:border-input focus-visible:border-ring focus-visible:bg-card focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/25"
        />
      </div>
      <div className="flex-1" />
      <ScopeControl role={role} scope={scope} onScope={onScope} />
      <NotificationBell role={role} onOpenRecall={onOpenRecall} />
      <div className="flex items-center gap-2.5 pl-1">
        <span
          className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-primary/[0.12] text-xs font-bold text-primary"
          aria-hidden="true"
        >
          {user.initials}
        </span>
        <span className="hidden flex-col leading-tight lg:flex">
          <span className="text-sm font-semibold text-foreground">{user.name}</span>
          <span className="text-xs text-muted-foreground">{user.roleLabel}</span>
        </span>
        <span className="sr-only lg:hidden">
          {user.name}, {user.roleLabel}
        </span>
      </div>
    </header>
  )
}

// ---------------------------------------------------------------------------
// Shell
// ---------------------------------------------------------------------------
export function AppShell({
  role,
  onRole,
  navKey,
  onNavigate,
  theme,
  onTheme,
  scope,
  onScope,
  compact,
  onSimulateRecall,
  children,
}: {
  role: Role
  onRole: (r: Role) => void
  navKey: string
  onNavigate: (key: string) => void
  theme: ThemeChoice
  onTheme: (t: ThemeChoice) => void
  scope: Scope
  onScope: (s: Scope) => void
  /** Variante compacta (POS): riel colapsado por defecto */
  compact?: boolean
  onSimulateRecall: () => void
  children: React.ReactNode
}) {
  const [collapsedPref, setCollapsedPref] = React.useState<boolean | null>(null)
  const [drawer, setDrawer] = React.useState(false)
  const collapsed = collapsedPref ?? !!compact

  React.useEffect(() => {
    setCollapsedPref(null)
  }, [compact])

  const go = (key: string) => {
    setDrawer(false)
    onNavigate(key)
  }

  return (
    <div className="flex h-full min-h-0 bg-background">
      <a
        href="#contenido"
        className="sr-only z-[60] rounded-control bg-primary px-3 py-2 font-semibold text-primary-foreground focus:not-sr-only focus:fixed focus:left-3 focus:top-3"
      >
        Saltar al contenido
      </a>
      <aside className={cn("hidden shrink-0 transition-[width] duration-200 lg:block", collapsed ? "w-[68px]" : "w-[256px]")}>
        <Rail role={role} activeKey={navKey} onNavigate={go} collapsed={collapsed} onToggleCollapse={() => setCollapsedPref(!collapsed)} />
      </aside>
      <Sheet open={drawer} onOpenChange={setDrawer}>
        <SheetContent side="left" className="w-[284px] max-w-[86%] border-r-0 bg-rail p-0 text-rail-foreground shadow-pop sm:max-w-[284px] [&>button]:text-rail-foreground" aria-describedby={undefined}>
          <SheetTitle className="sr-only">Menú principal</SheetTitle>
          <Rail role={role} activeKey={navKey} onNavigate={go} collapsed={false} inDrawer />
        </SheetContent>
      </Sheet>
      <div className="flex min-w-0 flex-1 flex-col">
        <DemoStrip role={role} onRole={onRole} theme={theme} onTheme={onTheme} onSimulateRecall={onSimulateRecall} />
        <TopBar role={role} scope={scope} onScope={onScope} onMenu={() => setDrawer(true)} onOpenRecall={onSimulateRecall} />
        <main id="contenido" tabIndex={-1} className="gd-scroll relative min-h-0 flex-1 overflow-y-auto focus:outline-none">
          {children}
        </main>
      </div>
    </div>
  )
}
