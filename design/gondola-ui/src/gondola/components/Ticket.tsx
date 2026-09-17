import { cn } from "@/lib/utils"
import { formatDate, formatMoney } from "../format"

export interface TicketLine {
  name: string
  qty: number
  unitPrice: number
  listPrice: number
  lot?: string
  expiry?: string
  discountPct?: number
}

export interface TicketData {
  store: string
  branch: string
  address: string
  cuit: string
  code: string
  dateIso: string
  time: string
  register: string
  cashier: string
  lines: TicketLine[]
  payments: { label: string; amount: number }[]
  change: number
}

function Row({ left, right, strong, className }: { left: React.ReactNode; right: React.ReactNode; strong?: boolean; className?: string }) {
  return (
    <div className={cn("flex items-baseline justify-between gap-3", strong && "font-bold", className)}>
      <span className="min-w-0">{left}</span>
      <span className="shrink-0 tabular-nums">{right}</span>
    </div>
  )
}

/**
 * Ticket térmico 80 mm (≈ 302 px): papel, bordes dentados, texto mono.
 * Siempre lleva la leyenda "Comprobante no válido como factura".
 */
export function Ticket({ data, className }: { data: TicketData; className?: string }) {
  const subtotal = data.lines.reduce((a, l) => a + l.listPrice * l.qty, 0)
  const total = data.lines.reduce((a, l) => a + l.unitPrice * l.qty, 0)
  const discounts = subtotal - total
  const units = data.lines.reduce((a, l) => a + l.qty, 0)
  return (
    <article
      className={cn(
        "gd-ticket w-[302px] max-w-full bg-paper px-4 py-6 font-mono text-[12px] leading-[17px] text-paper-ink",
        className
      )}
      aria-label={`Ticket ${data.code}`}
    >
      <header className="text-center">
        <div className="text-[14px] font-bold uppercase tracking-[0.06em]">{data.store}</div>
        <div>{data.branch}</div>
        <div>{data.address}</div>
        <div>CUIT {data.cuit}</div>
      </header>
      <div className="gd-ticket-rule my-2" />
      <Row left={`Ticket ${data.code}`} right="" />
      <Row left={`${formatDate(data.dateIso)} ${data.time}`} right={data.register} />
      <Row left={`Cajero: ${data.cashier}`} right="" />
      <div className="gd-ticket-rule my-2" />
      <div className="space-y-1.5">
        {data.lines.map((l, i) => (
          <div key={i}>
            <div className="uppercase">{l.name}</div>
            <Row
              left={
                <span>
                  {l.qty} x {formatMoney(l.unitPrice, 2)}
                </span>
              }
              right={formatMoney(l.unitPrice * l.qty, 2)}
            />
            {l.discountPct ? (
              <div className="text-[11px]">
                -{l.discountPct}% VTO {l.expiry ? formatDate(l.expiry, true) : ""} · lote {l.lot} (antes {formatMoney(l.listPrice, 2)})
              </div>
            ) : null}
          </div>
        ))}
      </div>
      <div className="gd-ticket-rule my-2" />
      <Row left={`Subtotal (${units} u.)`} right={formatMoney(subtotal, 2)} />
      {discounts > 0 ? <Row left="Desc. por vencimiento" right={`-${formatMoney(discounts, 2)}`} /> : null}
      <Row left="TOTAL" right={formatMoney(total, 2)} strong className="mt-1 text-[15px] leading-5" />
      <div className="gd-ticket-rule my-2" />
      {data.payments.map((p, i) => (
        <Row key={i} left={p.label} right={formatMoney(p.amount, 2)} />
      ))}
      <Row left="Vuelto" right={formatMoney(data.change, 2)} />
      <div className="gd-ticket-rule my-2" />
      <footer className="text-center">
        <div className="font-bold uppercase">Comprobante no válido como factura</div>
        <div className="mt-1">¡Gracias por tu compra!</div>
        <div className="mt-1 text-[11px] opacity-80">Cambios con este ticket dentro de los 30 días</div>
      </footer>
    </article>
  )
}
