/** Adaptadores al componente firma `Ticket80mm` (docs/design-system.md §5). */
import type { Ticket80mmData } from '@/components/gondola';
import type { PosSale, PosTicket } from './types';

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
    items: sale.items.map((item) => {
      const discounted = item.discountAmount > 0;
      const lot = item.lots.find((entry) => (entry.discountPct ?? 0) > 0) ?? item.lots[0];
      return {
        name: item.productName,
        quantity: item.quantity,
        unitPrice: item.unitPrice,
        listPrice: discounted ? item.listUnitPrice : null,
        lotNumber: lot?.lotNumber ?? null,
        expiryDate: lot?.expiryDate ?? null,
        discountPct: lot?.discountPct ?? null,
      };
    }),
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
