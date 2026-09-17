import * as React from "react"
import { toast } from "sonner"
import {
  AlertTriangle,
  Ban,
  Banknote,
  CheckCircle2,
  CreditCard,
  DoorClosed,
  HandCoins,
  Landmark,
  Printer,
  QrCode,
  ScanBarcode,
  ShoppingBasket,
  Trash2,
  X,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { PriceTag } from "../components/PriceTag"
import { ExpiryChip } from "../components/ExpiryChip"
import { StatusPill } from "../components/StatusPill"
import { EmptyState, Field, Kbd, QtyStepper } from "../components/Controls"
import { Ticket, type TicketData } from "../components/Ticket"
import { POS_INITIAL_CART, POS_ITEMS, POS_SESSION, RECALL, TENANT, TODAY, BRANCHES, product, type PosItem } from "../data"
import { formatMoney, formatNumber, parseArs } from "../format"

type Method = "CASH" | "DEBIT" | "CREDIT" | "TRANSFER" | "QR"
const METHODS: { id: Method; label: string; icon: React.ReactNode }[] = [
  { id: "CASH", label: "Efectivo", icon: <Banknote /> },
  { id: "DEBIT", label: "Débito", icon: <CreditCard /> },
  { id: "CREDIT", label: "Crédito", icon: <CreditCard /> },
  { id: "TRANSFER", label: "Transferencia", icon: <Landmark /> },
  { id: "QR", label: "QR", icon: <QrCode /> },
]
const BILLS = [1000, 2000, 10000, 20000]
const CATEGORIES = ["Todos", "Almacén", "Lácteos", "Fiambrería", "Panadería", "Frescos", "Bebidas", "Limpieza"]

const norm = (s: string) =>
  s
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()

interface CartLine {
  productId: string
  qty: number
}
type Notice = { kind: "recall" | "out" | "limit" | "notfound"; productId?: string; query?: string; available?: number } | null

function priceOf(item: PosItem) {
  const p = product(item.productId)
  const pct = item.nextLot?.discountPct ?? 0
  return { list: p.price, unit: Math.round(p.price * (1 - pct / 100)), pct }
}

// ---------------------------------------------------------------------------
function ProductTile({ item, onAdd, inCart }: { item: PosItem; onAdd: () => void; inCart: number }) {
  const p = product(item.productId)
  const { list, unit, pct } = priceOf(item)
  const blocked = item.blocked === "RECALL"
  const out = item.stock <= 0
  return (
    <button
      type="button"
      onClick={onAdd}
      className={cn(
        "group relative flex min-h-[124px] min-w-0 flex-col rounded-panel border bg-card p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
        blocked ? "border-crit/50 bg-crit-soft/50 hover:bg-crit-soft" : out ? "bg-muted/40 hover:bg-muted" : "hover:border-primary/50 hover:bg-primary/[0.03]",
        inCart > 0 && !blocked && "border-primary/60"
      )}
      aria-label={`${p.name}, ${formatMoney(unit, 2)}${blocked ? ", bloqueado por recall" : out ? ", sin stock" : ""}`}
    >
      <div className="flex items-start justify-between gap-2">
        <span className={cn("line-clamp-2 text-base font-semibold leading-5 [text-wrap:balance]", blocked || out ? "text-muted-foreground" : "text-foreground")}>{p.name}</span>
        {inCart > 0 ? (
          <span className="grid h-6 min-w-6 place-items-center rounded-full bg-primary px-1.5 text-xs font-bold tabular-nums text-primary-foreground" aria-hidden="true">
            {inCart}
          </span>
        ) : null}
      </div>
      <span className="mt-0.5 truncate text-xs text-muted-foreground">{p.brand}</span>
      <div className="mt-auto flex flex-wrap items-end justify-between gap-x-2 gap-y-1 pt-2">
        {blocked ? (
          <span className="inline-flex items-center gap-1 text-sm font-semibold text-crit-ink">
            <Ban className="h-4 w-4" aria-hidden="true" />
            Recall · bloqueado
          </span>
        ) : out ? (
          <StatusPill tone="crit" solid>
            Sin stock
          </StatusPill>
        ) : (
          <span className="flex flex-col">
            {pct ? <span className="text-xs text-muted-foreground line-through tabular-nums">{formatMoney(list)}</span> : null}
            <span className="font-display text-lg font-semibold leading-6 tracking-[-0.01em] tabular-nums text-foreground">{formatMoney(unit)}</span>
          </span>
        )}
        {!blocked && !out ? (
          pct ? (
            <span className="rounded-tag bg-crit-soft px-1.5 py-0.5 font-mono text-[11px] font-semibold text-crit-ink">-{pct}% VTO</span>
          ) : (
            <span className="text-xs tabular-nums text-muted-foreground">{item.stock} u.</span>
          )
        ) : null}
      </div>
    </button>
  )
}

// ---------------------------------------------------------------------------
function PosNotice({ notice, onClose }: { notice: NonNullable<Notice>; onClose: () => void }) {
  const p = notice.productId ? product(notice.productId) : null
  const crit = notice.kind === "recall"
  return (
    <div
      role="alert"
      className={cn(
        "flex items-start gap-3 rounded-control border px-3 py-2.5",
        crit ? "border-crit/50 bg-crit-soft text-crit-ink" : "border-warn/40 bg-warn-soft text-warn-ink"
      )}
    >
      {crit ? <Ban className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" /> : <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />}
      <div className="min-w-0 flex-1 text-base">
        {notice.kind === "recall" && p ? (
          <>
            <strong className="font-semibold">{p.name} · En cuarentena por recall · no se puede vender.</strong>
            <span className="block text-sm">
              Alerta de seguridad alimentaria activa para el lote {RECALL.lot}. Sacalo de la bolsa y avisá al encargado.
            </span>
          </>
        ) : notice.kind === "out" && p ? (
          <>
            <strong className="font-semibold">{p.name} figura sin stock en Sucursal Centro.</strong>
            <span className="block text-sm">Si lo tenés en la mano, pedile al encargado que ajuste el stock antes de venderlo.</span>
          </>
        ) : notice.kind === "limit" && p ? (
          <>
            <strong className="font-semibold">Stock insuficiente de {p.name}.</strong>
            <span className="block text-sm">Quedan {notice.available} u. vendibles en esta sucursal.</span>
          </>
        ) : (
          <>
            <strong className="font-semibold">No encontramos «{notice.query}».</strong>
            <span className="block text-sm">Revisá el código o buscá por nombre o marca.</span>
          </>
        )}
      </div>
      <button type="button" onClick={onClose} className="grid h-7 w-7 shrink-0 place-items-center rounded-control hover:bg-card/60" aria-label="Cerrar aviso">
        <X className="h-4 w-4" />
      </button>
    </div>
  )
}

// ---------------------------------------------------------------------------
function CashDialog({ mode, onOpenChange }: { mode: "withdraw" | "close" | null; onOpenChange: (open: boolean) => void }) {
  const [amount, setAmount] = React.useState("")
  const [reason, setReason] = React.useState("Pago a proveedor")
  const expected = 184350
  const counted = parseArs(amount)
  const diff = counted === null ? null : counted - expected
  React.useEffect(() => setAmount(""), [mode])
  return (
    <Dialog open={mode !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <form
          className="grid gap-4"
          onSubmit={(e) => {
            e.preventDefault()
            if (counted === null || counted <= 0) return
            onOpenChange(false)
            if (mode === "withdraw") toast.success(`Retiro registrado: ${formatMoney(counted)}`, { description: `${reason} · Caja 1 · ${POS_SESSION.cashier}` })
            else toast.success("Caja cerrada", { description: `Contado ${formatMoney(counted)} · diferencia ${formatMoney(diff ?? 0)}. El reporte Z quedó en Mis turnos.` })
          }}
        >
          <DialogHeader>
            <DialogTitle>{mode === "withdraw" ? "Retiro de efectivo" : "Cerrar caja"}</DialogTitle>
            <DialogDescription>
              {mode === "withdraw"
                ? "Registrá el efectivo que sale del cajón. Queda en el arqueo del turno."
                : "Contá el efectivo del cajón. Comparamos con lo que debería haber según las ventas del turno."}
            </DialogDescription>
          </DialogHeader>
          {mode === "close" ? (
            <dl className="grid grid-cols-2 gap-y-1.5 rounded-control bg-muted/60 p-3 text-base">
              <dt className="text-muted-foreground">Apertura 08:02</dt>
              <dd className="text-right tabular-nums">{formatMoney(POS_SESSION.openingCash)}</dd>
              <dt className="text-muted-foreground">Ventas en efectivo</dt>
              <dd className="text-right tabular-nums">{formatMoney(179350)}</dd>
              <dt className="text-muted-foreground">Retiros</dt>
              <dd className="text-right tabular-nums">-{formatMoney(15000)}</dd>
              <dt className="font-semibold">Efectivo esperado</dt>
              <dd className="text-right font-semibold tabular-nums">{formatMoney(expected)}</dd>
            </dl>
          ) : null}
          <Field id="cash-amount" label={mode === "withdraw" ? "Monto a retirar" : "Efectivo contado"} help="Usá punto para los miles: 15.000">
            <Input id="cash-amount" inputMode="decimal" autoFocus value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="$ 0" className="h-11 text-md tabular-nums" />
          </Field>
          {mode === "withdraw" ? (
            <Field id="cash-reason" label="Motivo">
              <Select value={reason} onValueChange={setReason}>
                <SelectTrigger id="cash-reason">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {["Pago a proveedor", "Depósito en banco", "Cambio para otra caja", "Otro"].map((r) => (
                    <SelectItem key={r} value={r}>
                      {r}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          ) : diff !== null ? (
            <p className={cn("text-base font-semibold", diff === 0 ? "text-ok-ink" : "text-warn-ink")}>
              {diff === 0 ? "La caja cierra justa." : diff > 0 ? `Sobran ${formatMoney(diff)}.` : `Faltan ${formatMoney(-diff)}.`}
            </p>
          ) : null}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancelar
            </Button>
            <Button type="submit" variant={mode === "close" ? "destructive" : "default"} disabled={counted === null || counted <= 0}>
              {mode === "withdraw" ? "Registrar retiro" : "Cerrar caja"}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
interface PaymentLine {
  id: number
  method: Method
  amount: string
}

function PaymentSheet({
  open,
  onOpenChange,
  total,
  cart,
  ticketCode,
  onNewSale,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  total: number
  cart: CartLine[]
  ticketCode: string
  onNewSale: () => void
}) {
  const [lines, setLines] = React.useState<PaymentLine[]>([{ id: 1, method: "CASH", amount: "" }])
  const [active, setActive] = React.useState(1)
  const [ticket, setTicket] = React.useState<TicketData | null>(null)
  const nextId = React.useRef(2)

  React.useEffect(() => {
    if (open) {
      setLines([{ id: 1, method: "CASH", amount: "" }])
      setActive(1)
      setTicket(null)
      nextId.current = 2
    }
  }, [open])

  const amountOf = (l: PaymentLine) => parseArs(l.amount) ?? 0
  const paid = lines.reduce((a, l) => a + amountOf(l), 0)
  const cash = lines.filter((l) => l.method === "CASH").reduce((a, l) => a + amountOf(l), 0)
  const nonCash = paid - cash
  const remaining = Math.max(0, total - paid)
  const over = Math.max(0, paid - total)
  const nonCashOver = nonCash > total
  const change = nonCashOver ? 0 : Math.min(over, cash)
  const invalid = lines.some((l) => l.amount.trim() !== "" && parseArs(l.amount) === null)
  const canConfirm = paid >= total && !nonCashOver && !invalid

  const addMethod = (m: Method) => {
    const existing = lines.find((l) => l.method === m)
    if (existing) {
      setActive(existing.id)
      document.getElementById(`pay-${existing.id}`)?.focus()
      return
    }
    const id = nextId.current++
    const empty = lines.length === 1 && lines[0].amount.trim() === ""
    const line = { id, method: m, amount: m === "CASH" ? "" : remaining ? formatNumber(remaining) : "" }
    setLines(empty ? [line] : [...lines, line])
    setActive(id)
    requestAnimationFrame(() => document.getElementById(`pay-${id}`)?.focus())
  }

  /** El foco vuelve al monto en efectivo: así Enter cobra en vez de repetir el billete. */
  const focusCash = (id: number) => {
    setActive(id)
    requestAnimationFrame(() => {
      const el = document.getElementById(`pay-${id}`) as HTMLInputElement | null
      el?.focus()
      el?.select()
    })
  }

  const addBill = (bill: number) => {
    const cashLine = lines.find((l) => l.method === "CASH")
    if (cashLine) {
      setLines(lines.map((l) => (l.id === cashLine.id ? { ...l, amount: formatNumber(amountOf(l) + bill) } : l)))
      focusCash(cashLine.id)
    } else {
      const id = nextId.current++
      setLines([...lines, { id, method: "CASH", amount: formatNumber(bill) }])
      focusCash(id)
    }
  }

  const exact = () => {
    const cashLine = lines.find((l) => l.method === "CASH")
    const need = total - nonCash
    if (need <= 0) return
    if (cashLine) {
      setLines(lines.map((l) => (l.id === cashLine.id ? { ...l, amount: formatNumber(need) } : l)))
      focusCash(cashLine.id)
    } else {
      const id = nextId.current++
      setLines([...lines, { id, method: "CASH", amount: formatNumber(need) }])
      focusCash(id)
    }
  }

  const confirm = () => {
    if (!canConfirm) return
    const branch = BRANCHES.find((b) => b.id === POS_SESSION.branch)!
    setTicket({
      store: TENANT.name,
      branch: branch.full,
      address: `${branch.address} · Rosario`,
      cuit: TENANT.cuit,
      code: ticketCode,
      dateIso: TODAY,
      time: "10:42",
      register: POS_SESSION.register,
      cashier: POS_SESSION.cashier,
      lines: cart.map((c) => {
        const item = POS_ITEMS.find((i) => i.productId === c.productId)!
        const pr = priceOf(item)
        return {
          name: product(c.productId).name,
          qty: c.qty,
          unitPrice: pr.unit,
          listPrice: pr.list,
          lot: item.nextLot?.lot,
          expiry: item.nextLot?.expiry,
          discountPct: pr.pct || undefined,
        }
      }),
      payments: lines.filter((l) => amountOf(l) > 0).map((l) => ({ label: METHODS.find((m) => m.id === l.method)!.label, amount: amountOf(l) })),
      change,
    })
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className={cn(
          "max-w-[680px] gap-0 p-0 sm:p-0",
          "max-sm:bottom-0 max-sm:top-auto max-sm:w-full max-sm:max-w-none max-sm:translate-y-0 max-sm:rounded-b-none max-sm:shadow-sheet",
          "max-sm:data-[state=open]:slide-in-from-bottom max-sm:data-[state=closed]:slide-out-to-bottom"
        )}
        aria-describedby={undefined}
      >
        {ticket ? (
          <div className="flex flex-col">
            <div className="flex flex-wrap items-start justify-between gap-3 border-b py-4 pl-5 pr-14 sm:pl-6">
              <div className="flex items-start gap-3">
                <CheckCircle2 className="mt-0.5 h-6 w-6 shrink-0 text-ok" aria-hidden="true" />
                <div>
                  <DialogTitle>Venta registrada</DialogTitle>
                  <p className="font-mono text-sm text-muted-foreground">Ticket {ticket.code}</p>
                </div>
              </div>
              <div className="text-right">
                <div className="text-sm text-muted-foreground">Vuelto</div>
                <div className="font-display text-xl font-semibold leading-8 tabular-nums text-foreground">{formatMoney(ticket.change, 2)}</div>
              </div>
            </div>
            <div className="gd-scroll grid max-h-[min(58vh,560px)] place-items-start justify-center overflow-y-auto bg-muted px-4 py-6">
              <Ticket data={ticket} />
            </div>
            <div className="flex flex-col-reverse gap-2 border-t px-5 py-4 sm:flex-row sm:justify-end sm:px-6">
              <Button variant="outline" onClick={() => toast.success("Ticket enviado a la impresora", { description: "Impresora térmica 80 mm · Caja 1" })}>
                <Printer aria-hidden="true" />
                Imprimir
              </Button>
              <Button onClick={onNewSale} autoFocus>
                Nueva venta
                <Kbd className="border-primary-foreground/30 bg-transparent text-primary-foreground">Enter</Kbd>
              </Button>
            </div>
          </div>
        ) : (
          <form
            className="flex min-h-0 flex-col"
            onSubmit={(e) => {
              e.preventDefault()
              confirm()
            }}
          >
            <div className="flex flex-wrap items-center justify-between gap-4 border-b py-4 pl-5 pr-14 sm:pl-6">
              <div>
                <DialogTitle>Cobrar</DialogTitle>
                <p className="text-sm text-muted-foreground">
                  {cart.reduce((a, c) => a + c.qty, 0)} unidades · Ticket {ticketCode}
                </p>
              </div>
              <PriceTag size="md" price={total} label="Total" />
            </div>

            <div className="gd-scroll grid max-h-[min(62vh,600px)] gap-5 overflow-y-auto px-5 py-5 sm:px-6">
              <fieldset>
                <legend className="gd-eyebrow mb-2">Medio de pago · se pueden combinar</legend>
                <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
                  {METHODS.map((m) => {
                    const used = lines.some((l) => l.method === m.id && (l.amount.trim() !== "" || l.id === active))
                    return (
                      <button
                        key={m.id}
                        type="button"
                        onClick={() => addMethod(m.id)}
                        aria-pressed={used}
                        className={cn(
                          "flex h-16 flex-col items-center justify-center gap-1 rounded-control border text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring [&_svg]:size-5",
                          used ? "border-primary bg-primary/[0.08] text-foreground" : "border-input bg-card text-foreground hover:bg-muted"
                        )}
                      >
                        {m.icon}
                        {m.label}
                      </button>
                    )
                  })}
                </div>
              </fieldset>

              <div className="grid gap-2">
                {lines.map((l) => {
                  const m = METHODS.find((x) => x.id === l.method)!
                  const bad = l.amount.trim() !== "" && parseArs(l.amount) === null
                  return (
                    <div
                      key={l.id}
                      className={cn("flex items-center gap-3 rounded-control border px-3 py-2", l.id === active ? "border-primary/60 bg-primary/[0.04]" : "border-border")}
                    >
                      <span className="flex w-[118px] shrink-0 items-center gap-2 text-base font-semibold [&_svg]:size-4 [&_svg]:text-muted-foreground">
                        {m.icon}
                        {m.label}
                      </span>
                      <label htmlFor={`pay-${l.id}`} className="sr-only">
                        Monto en {m.label}
                      </label>
                      <div className="relative min-w-0 flex-1">
                        <span className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground">$</span>
                        <Input
                          id={`pay-${l.id}`}
                          inputMode="decimal"
                          value={l.amount}
                          aria-invalid={bad || undefined}
                          placeholder={l.method === "CASH" ? "Recibido" : "0"}
                          onFocus={() => setActive(l.id)}
                          onChange={(e) => setLines(lines.map((x) => (x.id === l.id ? { ...x, amount: e.target.value } : x)))}
                          className="h-10 pl-7 text-right font-semibold tabular-nums"
                        />
                      </div>
                      {lines.length > 1 ? (
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Quitar ${m.label}`}
                          onClick={() => setLines(lines.filter((x) => x.id !== l.id))}
                        >
                          <X aria-hidden="true" />
                        </Button>
                      ) : null}
                    </div>
                  )
                })}
              </div>

              <div>
                <div className="gd-eyebrow mb-2">Billetes rápidos (efectivo)</div>
                <div className="flex flex-wrap gap-2">
                  {BILLS.map((b) => (
                    <Button key={b} type="button" variant="outline" className="h-10 min-w-[92px] font-display text-md tabular-nums" onClick={() => addBill(b)}>
                      {formatMoney(b)}
                    </Button>
                  ))}
                  <Button type="button" variant="secondary" className="h-10" onClick={exact}>
                    Monto justo
                  </Button>
                </div>
              </div>

              <dl className="grid grid-cols-2 gap-y-1 border-t pt-4 text-base sm:grid-cols-4 sm:gap-x-6">
                <div>
                  <dt className="text-sm text-muted-foreground">Total</dt>
                  <dd className="font-semibold tabular-nums">{formatMoney(total, 2)}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Pagado</dt>
                  <dd className="font-semibold tabular-nums">{formatMoney(paid, 2)}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Falta</dt>
                  <dd className={cn("font-semibold tabular-nums", remaining > 0 ? "text-crit-ink" : "text-muted-foreground")}>{formatMoney(remaining, 2)}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Vuelto</dt>
                  <dd className="font-display text-xl font-semibold leading-8 tabular-nums text-ok-ink" aria-live="polite">
                    {formatMoney(change, 2)}
                  </dd>
                </div>
              </dl>
              {nonCashOver ? (
                <p role="alert" className="flex items-start gap-2 text-sm text-crit-ink">
                  <AlertTriangle className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
                  El vuelto solo se da en efectivo: bajá el monto con tarjeta, transferencia o QR.
                </p>
              ) : null}
            </div>

            <div className="flex flex-col-reverse gap-2 border-t bg-muted/40 px-5 py-4 sm:flex-row sm:items-center sm:justify-end sm:px-6">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
                Volver a la venta
                <Kbd>Esc</Kbd>
              </Button>
              <Button type="submit" size="lg" disabled={!canConfirm}>
                Confirmar cobro
                <Kbd className="border-primary-foreground/30 bg-transparent text-primary-foreground">Enter</Kbd>
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

// ---------------------------------------------------------------------------
export function PosScreen() {
  const [cart, setCart] = React.useState<CartLine[]>(POS_INITIAL_CART)
  const [query, setQuery] = React.useState("")
  const [category, setCategory] = React.useState("Todos")
  const [notice, setNotice] = React.useState<Notice>(null)
  const [selected, setSelected] = React.useState<string | null>(null)
  const [payOpen, setPayOpen] = React.useState(false)
  const [cashMode, setCashMode] = React.useState<"withdraw" | "close" | null>(null)
  const [ticketNo, setTicketNo] = React.useState(418)
  const searchRef = React.useRef<HTMLInputElement>(null)
  const ticketCode = `0001-${String(ticketNo).padStart(8, "0")}`

  const results = React.useMemo(() => {
    const q = norm(query.trim())
    return POS_ITEMS.filter((it) => {
      const p = product(it.productId)
      if (category !== "Todos" && p.category !== category) return false
      if (!q) return true
      return norm(`${p.name} ${p.brand}`).includes(q) || p.ean.includes(q)
    })
  }, [query, category])

  const lines = cart.map((c) => {
    const item = POS_ITEMS.find((i) => i.productId === c.productId)!
    return { ...c, item, p: product(c.productId), ...priceOf(item) }
  })
  const subtotal = lines.reduce((a, l) => a + l.list * l.qty, 0)
  const total = lines.reduce((a, l) => a + l.unit * l.qty, 0)
  const discounts = subtotal - total
  const units = lines.reduce((a, l) => a + l.qty, 0)

  const add = (item: PosItem) => {
    setQuery("")
    searchRef.current?.focus()
    if (item.blocked === "RECALL") {
      setNotice({ kind: "recall", productId: item.productId })
      return
    }
    if (item.stock <= 0) {
      setNotice({ kind: "out", productId: item.productId })
      return
    }
    const found = cart.find((c) => c.productId === item.productId)
    if (found && found.qty >= item.stock) {
      setNotice({ kind: "limit", productId: item.productId, available: item.stock })
      return
    }
    setNotice(null)
    setCart(found ? cart.map((c) => (c.productId === item.productId ? { ...c, qty: c.qty + 1 } : c)) : [...cart, { productId: item.productId, qty: 1 }])
    setSelected(item.productId)
  }

  const removeLine = React.useCallback((productId: string) => {
    setCart((prev) => prev.filter((c) => c.productId !== productId))
    setSelected(null)
  }, [])

  // Atajos de teclado: F2 buscar · F4 cobrar · F8 quitar ítem · Esc limpiar
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (cashMode) return
      if (e.key === "F2") {
        e.preventDefault()
        if (!payOpen) {
          searchRef.current?.focus()
          searchRef.current?.select()
        }
      } else if (e.key === "F4") {
        e.preventDefault()
        if (cart.length && !payOpen) setPayOpen(true)
      } else if (e.key === "F8") {
        e.preventDefault()
        if (payOpen || !cart.length) return
        const target = selected && cart.some((c) => c.productId === selected) ? selected : cart[cart.length - 1].productId
        removeLine(target)
        toast(`Quitaste ${product(target).name}`)
      } else if (e.key === "Escape" && !payOpen) {
        if (query) setQuery("")
        else setNotice(null)
      }
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [cart, payOpen, selected, query, cashMode, removeLine])

  React.useEffect(() => {
    searchRef.current?.focus({ preventScroll: true })
  }, [])

  const newSale = () => {
    setPayOpen(false)
    setCart([])
    setSelected(null)
    setNotice(null)
    setTicketNo((n) => n + 1)
    toast.success(`Venta ${ticketCode} registrada`, { description: `${formatMoney(total, 2)} · Caja 1` })
    requestAnimationFrame(() => searchRef.current?.focus())
  }

  return (
    <div className="flex min-h-full flex-col lg:h-full">
      {/* Franja de caja */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b bg-card px-4 py-2.5 sm:px-5">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
          <h1 className="font-display text-lg font-semibold leading-7 tracking-[-0.01em] text-foreground">Punto de venta</h1>
          <span className="text-sm text-muted-foreground">
            Sucursal Centro · {POS_SESSION.register} · {POS_SESSION.cashier}
          </span>
          <StatusPill tone="ok">Turno abierto {POS_SESSION.openedAt}</StatusPill>
        </div>
        <div className="flex-1" />
        <div className="hidden items-center gap-3 text-xs text-muted-foreground xl:flex" aria-label="Atajos de teclado">
          <span className="flex items-center gap-1">
            <Kbd>F2</Kbd> Buscar
          </span>
          <span className="flex items-center gap-1">
            <Kbd>F4</Kbd> Cobrar
          </span>
          <span className="flex items-center gap-1">
            <Kbd>F8</Kbd> Quitar ítem
          </span>
          <span className="flex items-center gap-1">
            <Kbd>Esc</Kbd> Limpiar
          </span>
        </div>
        <div className="flex items-center gap-2">
          <Button variant="outline" size="sm" onClick={() => setCashMode("withdraw")}>
            <HandCoins aria-hidden="true" />
            Retiro de efectivo
          </Button>
          <Button variant="outline" size="sm" onClick={() => setCashMode("close")}>
            <DoorClosed aria-hidden="true" />
            Cerrar caja
          </Button>
        </div>
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(0,1fr)_400px] xl:grid-cols-[minmax(0,1fr)_440px]">
        {/* Izquierda: escanear / buscar */}
        <section aria-label="Buscar productos" className="flex min-h-0 min-w-0 flex-col">
          <div className="flex flex-col gap-3 border-b bg-background px-4 pb-3 pt-4 sm:px-5">
            <form
              className="relative"
              onSubmit={(e) => {
                e.preventDefault()
                const q = query.trim()
                if (!q) return
                const exact = POS_ITEMS.find((i) => product(i.productId).ean === q)
                const target = exact ?? results[0]
                if (target) add(target)
                else setNotice({ kind: "notfound", query: q })
              }}
            >
              <ScanBarcode className="pointer-events-none absolute left-3.5 top-1/2 h-5 w-5 -translate-y-1/2 text-primary" aria-hidden="true" />
              <label htmlFor="pos-search" className="sr-only">
                Escaneá o buscá un producto
              </label>
              <input
                id="pos-search"
                ref={searchRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Escaneá o buscá (F2)"
                autoComplete="off"
                className="h-14 w-full rounded-control border-2 border-primary/50 bg-card pl-12 pr-16 text-md font-medium text-foreground placeholder:font-normal placeholder:text-muted-foreground focus-visible:border-primary focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-primary/15"
              />
              <Kbd className="absolute right-3 top-1/2 -translate-y-1/2">F2</Kbd>
            </form>
            <p className="text-sm text-muted-foreground">
              Probá escanear <span className="font-mono text-foreground">7790080000123</span> o buscar «sopa». Enter agrega el primer resultado.
            </p>
            <div className="gd-scroll -mx-4 overflow-x-auto px-4 sm:-mx-5 sm:px-5">
              <div className="flex w-max gap-1.5" role="group" aria-label="Filtrar por categoría">
                {CATEGORIES.map((c) => (
                  <button
                    key={c}
                    type="button"
                    aria-pressed={category === c}
                    onClick={() => setCategory(c)}
                    className={cn(
                      "h-8 whitespace-nowrap rounded-control border px-3 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                      category === c ? "border-foreground bg-foreground text-background" : "border-input bg-card text-foreground hover:bg-muted"
                    )}
                  >
                    {c}
                  </button>
                ))}
              </div>
            </div>
            {notice ? <PosNotice notice={notice} onClose={() => setNotice(null)} /> : null}
          </div>
          <div className="gd-scroll min-h-0 flex-1 px-4 py-4 sm:px-5 lg:overflow-y-auto">
            {results.length ? (
              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-[repeat(auto-fill,minmax(168px,1fr))]">
                {results.map((it) => (
                  <ProductTile key={it.productId} item={it} onAdd={() => add(it)} inCart={cart.find((c) => c.productId === it.productId)?.qty ?? 0} />
                ))}
              </div>
            ) : (
              <EmptyState icon={<ScanBarcode />} title="Sin resultados" description="Probá con otra palabra, la marca o el código de barras completo." />
            )}
          </div>
        </section>

        {/* Derecha: ticket en curso */}
        <aside aria-label="Venta en curso" className="flex min-h-0 flex-col border-t bg-card lg:border-l lg:border-t-0">
          <div className="flex items-center justify-between gap-3 border-b px-4 py-3 sm:px-5">
            <div>
              <h2 className="text-md font-semibold leading-6">Venta en curso</h2>
              <p className="font-mono text-xs text-muted-foreground">Ticket {ticketCode}</p>
            </div>
            {cart.length ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setCart([])
                  setNotice(null)
                  toast("Venta cancelada", { description: "Se vació el carrito." })
                }}
              >
                <Trash2 aria-hidden="true" />
                Cancelar venta
              </Button>
            ) : null}
          </div>

          <div className="gd-scroll min-h-0 flex-1 lg:overflow-y-auto">
            {lines.length ? (
              <ul className="divide-y">
                {lines.map((l) => (
                  <li
                    key={l.productId}
                    className={cn("px-4 py-3 sm:px-5", selected === l.productId ? "gd-stripe-info bg-info-soft/40" : "gd-stripe-none")}
                    onClick={() => setSelected(l.productId)}
                  >
                    <div className="flex items-start justify-between gap-2">
                      <div className="min-w-0">
                        <div className="text-base font-semibold leading-5 text-foreground">{l.p.name}</div>
                        <div className="text-xs text-muted-foreground">{l.p.brand}</div>
                      </div>
                      <Button variant="ghost" size="icon-sm" className="-mr-1.5 -mt-1 shrink-0 text-muted-foreground" aria-label={`Quitar ${l.p.name}`} onClick={() => removeLine(l.productId)}>
                        <X aria-hidden="true" />
                      </Button>
                    </div>
                    {l.item.nextLot || l.pct ? (
                      <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                        {l.item.nextLot ? <ExpiryChip expiry={l.item.nextLot.expiry} lot={l.item.nextLot.lot} /> : null}
                        {l.pct ? (
                          <span className="inline-flex h-[22px] items-center rounded-tag bg-crit px-1.5 font-mono text-[11px] font-semibold uppercase text-crit-foreground">
                            -{l.pct}% VTO CERCANO
                          </span>
                        ) : null}
                      </div>
                    ) : null}
                    <div className="mt-2 flex items-center justify-between gap-3">
                      <QtyStepper
                        id={`qty-${l.productId}`}
                        label={`Cantidad de ${l.p.name}`}
                        size="sm"
                        value={l.qty}
                        min={1}
                        max={l.item.stock}
                        onChange={(n) => setCart((prev) => prev.map((c) => (c.productId === l.productId ? { ...c, qty: n } : c)))}
                      />
                      <div className="text-right">
                        <div className="text-xs tabular-nums text-muted-foreground">
                          {l.pct ? <span className="mr-1 line-through">{formatMoney(l.list, 2)}</span> : null}
                          {formatMoney(l.unit, 2)} c/u
                        </div>
                        <div className="text-md font-semibold tabular-nums text-foreground">{formatMoney(l.unit * l.qty, 2)}</div>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState icon={<ShoppingBasket />} title="Escaneá el primer producto" description="El ticket se arma acá. También podés tocar un producto de la lista." />
            )}
          </div>

          <div id="pos-totales" className="border-t bg-card px-4 pb-4 pt-3 sm:px-5">
            <dl className="grid grid-cols-2 gap-y-0.5 text-base">
              <dt className="text-muted-foreground">Subtotal · {units} u.</dt>
              <dd className="text-right tabular-nums">{formatMoney(subtotal, 2)}</dd>
              <dt className="text-muted-foreground">Descuentos por vencimiento</dt>
              <dd className="text-right tabular-nums text-crit-ink">{discounts ? `-${formatMoney(discounts, 2)}` : formatMoney(0, 2)}</dd>
            </dl>
            <div className="mt-3 flex items-end justify-between gap-3">
              <PriceTag size="lg" price={total} label="Total" className="hidden sm:inline-flex" />
              <PriceTag size="md" price={total} label="Total" className="sm:hidden" />
              <span className="pb-1 text-right text-xs text-muted-foreground">
                IVA incluido
                <br />
                Ticket no fiscal
              </span>
            </div>
            <Button size="xl" className="mt-3 w-full justify-between" disabled={!cart.length} onClick={() => setPayOpen(true)}>
              <span>Cobrar</span>
              <Kbd className="h-6 border-primary-foreground/30 bg-transparent px-1.5 text-xs text-primary-foreground">F4</Kbd>
            </Button>
          </div>
        </aside>
      </div>

      {/* Móvil: barra fija con total y cobro al alcance del pulgar */}
      <div
        className="sticky bottom-0 z-10 flex items-center gap-3 border-t-2 border-foreground/10 bg-card px-4 pt-3 lg:hidden"
        style={{ paddingBottom: "calc(12px + env(safe-area-inset-bottom, 0px))" }}
      >
        <button
          type="button"
          onClick={() => document.getElementById("pos-totales")?.scrollIntoView({ block: "end" })}
          className="min-w-0 flex-1 rounded-control text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <span className="block text-xs text-muted-foreground">{units} u. · ver ticket</span>
          <span className="block font-display text-lg font-semibold leading-6 tabular-nums text-foreground">{formatMoney(total, 2)}</span>
        </button>
        <Button size="lg" disabled={!cart.length} onClick={() => setPayOpen(true)}>
          Cobrar
        </Button>
      </div>

      <PaymentSheet open={payOpen} onOpenChange={setPayOpen} total={total} cart={cart} ticketCode={ticketCode} onNewSale={newSale} />
      <CashDialog mode={cashMode} onOpenChange={(o) => !o && setCashMode(null)} />
    </div>
  )
}
