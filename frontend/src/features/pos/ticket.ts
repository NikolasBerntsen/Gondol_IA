/** Adaptadores al componente firma `Ticket80mm` (docs/design-system.md §5). */
import type { Ticket80mmData, Ticket80mmItem } from '@/components/gondola';
import type { PosSale, PosSaleItem, PosTicket } from './types';

const toCents = (value: number) => Math.round(value * 100);

/**
 * Renglones del ticket de una línea de la venta, igual que `GET /sales/{id}/ticket`: si todas las unidades salieron
 * al mismo precio es un renglón; si la cantidad cruzó de un lote en liquidación a otro sin descuento (o hubo faltante
 * a precio de lista), un renglón por precio en el orden en el que se consumieron (`15 x $ 2.850` y `1 x $ 3.800`).
 * Así cada renglón muestra lo que se cobró de verdad y `cantidad x precio` coincide con el importe.
 */
export function saleItemTicketLines(item: PosSaleItem): Ticket80mmItem[] {
  interface Group {
    quantity: number;
    unitPrice: number;
    discountPct: number | null;
    lotNumber: string | null;
    expiryDate: string | null;
  }
  const groups: Group[] = [];
  const add = (next: Group) => {
    const last = groups[groups.length - 1];
    if (last && toCents(last.unitPrice) === toCents(next.unitPrice) && (last.discountPct ?? 0) === (next.discountPct ?? 0)) {
      last.quantity += next.quantity;
      last.lotNumber ??= next.lotNumber;
      last.expiryDate ??= next.expiryDate;
    } else {
      groups.push({ ...next });
    }
  };
  let fromLots = 0;
  for (const lot of item.lots) {
    fromLots += lot.quantity;
    const pct = lot.discountPct && lot.discountPct > 0 ? lot.discountPct : null;
    add({ quantity: lot.quantity, unitPrice: lot.unitPrice, discountPct: pct, lotNumber: lot.lotNumber, expiryDate: lot.expiryDate });
  }
  const shortage = item.quantity - fromLots;
  if (shortage > 0) {
    add({ quantity: shortage, unitPrice: item.listUnitPrice, discountPct: null, lotNumber: null, expiryDate: null });
  }

  if (groups.length <= 1) {
    const discounted = item.discountAmount > 0;
    const lot = item.lots.find((entry) => (entry.discountPct ?? 0) > 0) ?? item.lots[0];
    return [
      {
        name: item.productName,
        quantity: item.quantity,
        unitPrice: groups[0]?.unitPrice ?? item.unitPrice,
        listPrice: discounted ? item.listUnitPrice : null,
        lotNumber: lot?.lotNumber ?? null,
        expiryDate: lot?.expiryDate ?? null,
        discountPct: lot?.discountPct ?? null,
      },
    ];
  }
  return groups.map((group) => ({
    name: item.productName,
    quantity: group.quantity,
    unitPrice: group.unitPrice,
    listPrice: group.discountPct ? item.listUnitPrice : null,
    lotNumber: group.discountPct ? group.lotNumber : null,
    expiryDate: group.discountPct ? group.expiryDate : null,
    discountPct: group.discountPct,
  }));
}

/** Datos del endpoint `/sales/{id}/ticket`: el backend ya trae comercio, sucursal y leyenda. */
export function ticketToTicketData(ticket: PosTicket): Ticket80mmData {
  return {
    store: ticket.store,
    branch: ticket.branch ?? '',
    address: ticket.address,
    cuit: ticket.taxId,
    ticketCode: ticket.ticketCode,
    dateTime: ticket.dateTime,
    registerName: ticket.registerName ?? '',
    cashierName: ticket.cashierName ?? '',
    customerName: ticket.customerName,
    customerDoc: ticket.customerDoc,
    items: ticket.items.map((item) => ({
      name: item.name,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
      listPrice: item.listPrice,
      lotNumber: item.lotNumber,
      expiryDate: item.expiryDate,
      discountPct: item.discountPct,
    })),
    payments: ticket.payments.map((payment) => ({
      label: payment.label,
      amount: payment.amount,
      reference: payment.reference,
    })),
    change: ticket.change,
    subtotal: ticket.subtotal,
    discountTotal: ticket.discountTotal,
    total: ticket.total,
  };
}

/**
 * Vista previa inmediata después de cobrar, con la venta que devolvió `POST /sales`
 * (evita un segundo request mientras el cajero todavía tiene al cliente enfrente).
 */
export function saleToTicketData(sale: PosSale, storeName: string, taxId: string | null): Ticket80mmData {
  return {
    store: storeName,
    branch: sale.branchName ?? '',
    cuit: taxId,
    ticketCode: sale.ticketCode,
    dateTime: sale.createdAt,
    registerName: sale.registerName ?? '',
    cashierName: sale.cashierName ?? '',
    customerName: sale.customerName,
    customerDoc: sale.customerDoc,
    items: sale.items.flatMap(saleItemTicketLines),
    payments: sale.payments.map((payment) => ({
      label: payment.label,
      amount: payment.amount,
      reference: payment.reference,
    })),
    change: sale.changeAmount,
    subtotal: sale.subtotal,
    discountTotal: sale.discountTotal,
    total: sale.total,
  };
}
