import type { ReactNode } from 'react';
import { cn } from '@/lib/cn';
import { formatDateCompact, formatDateTime, formatMoney } from '@/lib/format';

/** Leyenda obligatoria del comprobante (SPEC §15: ticket **no fiscal**). */
export const TICKET_LEGEND = 'Comprobante no válido como factura';

export interface Ticket80mmItem {
  name: string;
  quantity: number;
  /** Precio efectivamente cobrado por unidad (con el descuento del lote aplicado). */
  unitPrice: number;
  /** Precio de lista, si hubo descuento. */
  listPrice?: number | null;
  lotNumber?: string | null;
  expiryDate?: string | null;
  discountPct?: number | null;
}

export interface Ticket80mmPayment {
  /** Etiqueta en español: "Efectivo", "Débito", "Transferencia"… */
  label: string;
  amount: number;
  reference?: string | null;
}

export interface Ticket80mmData {
  store: string;
  branch: string;
  address?: string | null;
  cuit?: string | null;
  /** Código del ticket: `0001-00000418`. */
  ticketCode: string;
  /** Instante de la venta (ISO). */
  dateTime: string;
  registerName: string;
  cashierName: string;
  customerName?: string | null;
  customerDoc?: string | null;
  items: Ticket80mmItem[];
  payments: Ticket80mmPayment[];
  change?: number | null;
  /** Se calculan a partir de los ítems si no vienen del backend. */
  subtotal?: number | null;
  discountTotal?: number | null;
  total?: number | null;
  /** Texto extra al pie (por defecto, la leyenda de cambios). */
  footerNote?: ReactNode;
}

function Row({
  left,
  right,
  strong,
  className,
}: {
  left: ReactNode;
  right?: ReactNode;
  strong?: boolean;
  className?: string;
}) {
  return (
    <div className={cn('flex items-baseline justify-between gap-3', strong && 'font-bold', className)}>
      <span className="min-w-0">{left}</span>
      <span className="shrink-0 tabular-nums">{right}</span>
    </div>
  );
}

export interface Ticket80mmProps {
  data: Ticket80mmData;
  className?: string;
}

/**
 * Ticket térmico de 80 mm (302 px): papel, bordes dentados y texto en JetBrains Mono.
 * Se usa después de confirmar el cobro, en el historial de ventas y en la página de impresión
 * `/app/pos/sales/:id/ticket` (que va **fuera** del AppShell y llama a `window.print()`).
 */
export function Ticket80mm({ data, className }: Ticket80mmProps) {
  const money = (value: number) => formatMoney(value, { decimals: 2 });
  const subtotal = data.subtotal ?? data.items.reduce((acc, i) => acc + (i.listPrice ?? i.unitPrice) * i.quantity, 0);
  const total = data.total ?? data.items.reduce((acc, i) => acc + i.unitPrice * i.quantity, 0);
  const discounts = data.discountTotal ?? subtotal - total;
  const units = data.items.reduce((acc, i) => acc + i.quantity, 0);

  return (
    <article
      className={cn(
        'gd-ticket w-[302px] max-w-full bg-paper px-4 py-6 font-mono text-[12px] leading-[17px] text-paper-ink',
        className,
      )}
      aria-label={`Ticket ${data.ticketCode}`}
    >
      <header className="text-center">
        <div className="text-[14px] font-bold uppercase tracking-[0.06em]">{data.store}</div>
        <div>{data.branch}</div>
        {data.address && <div>{data.address}</div>}
        {data.cuit && <div>CUIT {data.cuit}</div>}
      </header>

      <div className="gd-ticket-rule my-2" />
      <Row left={`Ticket ${data.ticketCode}`} />
      <Row left={formatDateTime(data.dateTime)} right={data.registerName} />
      <Row left={`Cajero: ${data.cashierName}`} />
      {data.customerName && <Row left={`Cliente: ${data.customerName}`} right={data.customerDoc ?? undefined} />}

      <div className="gd-ticket-rule my-2" />
      <div className="space-y-1.5">
        {data.items.map((item, index) => (
          <div key={`${item.name}-${index}`}>
            <div className="uppercase">{item.name}</div>
            <Row
              left={
                <span>
                  {item.quantity} x {money(item.unitPrice)}
                </span>
              }
              right={money(item.unitPrice * item.quantity)}
            />
            {item.discountPct ? (
              <div className="text-[11px]">
                -{item.discountPct}% VTO {item.expiryDate ? formatDateCompact(item.expiryDate) : ''}
                {item.lotNumber ? ` · lote ${item.lotNumber}` : ''}
                {item.listPrice ? ` (antes ${money(item.listPrice)})` : ''}
              </div>
            ) : null}
          </div>
        ))}
      </div>

      <div className="gd-ticket-rule my-2" />
      <Row left={`Subtotal (${units} u.)`} right={money(subtotal)} />
      {discounts > 0 && <Row left="Desc. por vencimiento" right={`-${money(discounts)}`} />}
      <Row left="TOTAL" right={money(total)} strong className="mt-1 text-[15px] leading-5" />

      <div className="gd-ticket-rule my-2" />
      {data.payments.map((payment, index) => (
        <Row key={`${payment.label}-${index}`} left={payment.label} right={money(payment.amount)} />
      ))}
      {data.change ? <Row left="Vuelto" right={money(data.change)} /> : null}

      <div className="gd-ticket-rule my-2" />
      <footer className="text-center">
        <div className="font-bold uppercase">{TICKET_LEGEND}</div>
        <div className="mt-1">¡Gracias por tu compra!</div>
        {data.footerNote !== undefined ? (
          <div className="mt-1 text-[11px] opacity-80">{data.footerNote}</div>
        ) : (
          <div className="mt-1 text-[11px] opacity-80">Cambios con este ticket dentro de los 30 días</div>
        )}
      </footer>
    </article>
  );
}
