import { describe, expect, it } from 'vitest';
import type { PosSale, PosSaleItem, PosTicket } from './types';
import { saleItemTicketLines, saleToTicketData, ticketToTicketData } from './ticket';

type Lot = PosSaleItem['lots'][number];

const lot = (quantity: number, unitPrice: number, extra: Partial<Lot> = {}): Lot =>
  ({
    lotId: 1,
    lotNumber: 'L1',
    expiryDate: '2026-12-31',
    quantity,
    unitPrice,
    discountPct: null,
    ...extra,
  }) as Lot;

const item = (overrides: Partial<PosSaleItem> = {}): PosSaleItem =>
  ({
    productId: 1,
    productName: 'Yerba Serrana 1 kg',
    quantity: 2,
    unitPrice: 2850,
    listUnitPrice: 2850,
    discountAmount: 0,
    lineTotal: 5700,
    lots: [lot(2, 2850)],
    ...overrides,
  }) as PosSaleItem;

describe('saleItemTicketLines', () => {
  it('todas las unidades al mismo precio son un solo renglón', () => {
    const lines = saleItemTicketLines(item());
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ name: 'Yerba Serrana 1 kg', quantity: 2, unitPrice: 2850, listPrice: null });
  });

  it('cuando la cantidad cruza de un lote en liquidación a otro hay un renglón por precio', () => {
    const lines = saleItemTicketLines(
      item({
        quantity: 16,
        listUnitPrice: 3800,
        discountAmount: 14_250,
        lots: [
          lot(15, 2850, { lotNumber: 'L-LIQ', discountPct: 25 }),
          lot(1, 3800, { lotNumber: 'L-NORMAL', discountPct: null }),
        ],
      }),
    );
    expect(lines).toHaveLength(2);
    expect(lines[0]).toMatchObject({ quantity: 15, unitPrice: 2850, listPrice: 3800, discountPct: 25, lotNumber: 'L-LIQ' });
    // El renglón a precio de lista no repite el lote ni el vencimiento: no hubo descuento que justificar.
    expect(lines[1]).toMatchObject({ quantity: 1, unitPrice: 3800, listPrice: null, discountPct: null, lotNumber: null });
  });

  it('agrupa lotes consecutivos con el mismo precio', () => {
    const lines = saleItemTicketLines(
      item({ quantity: 5, lots: [lot(2, 2850, { lotNumber: 'A' }), lot(3, 2850, { lotNumber: 'B' })] }),
    );
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(5);
  });

  it('el faltante sale a precio de lista', () => {
    const lines = saleItemTicketLines(
      item({ quantity: 5, listUnitPrice: 3800, lots: [lot(2, 2850, { discountPct: 25 })], discountAmount: 1900 }),
    );
    expect(lines).toHaveLength(2);
    expect(lines[1]).toMatchObject({ quantity: 3, unitPrice: 3800, listPrice: null });
  });

  it('una venta sin lotes cargados usa el precio de la línea', () => {
    const lines = saleItemTicketLines(item({ quantity: 3, unitPrice: 1000, listUnitPrice: 1000, lots: [] }));
    expect(lines).toHaveLength(1);
    expect(lines[0]).toMatchObject({ quantity: 3, unitPrice: 1000 });
  });

  it('un renglón único con descuento muestra el precio de lista tachado', () => {
    const lines = saleItemTicketLines(
      item({ quantity: 2, unitPrice: 2850, listUnitPrice: 3800, discountAmount: 1900, lots: [lot(2, 2850, { discountPct: 25 })] }),
    );
    expect(lines[0]).toMatchObject({ listPrice: 3800, discountPct: 25, lotNumber: 'L1' });
  });

  it('la suma de los renglones es la cantidad vendida', () => {
    const sold = item({
      quantity: 16,
      listUnitPrice: 3800,
      lots: [lot(10, 2850, { discountPct: 25 }), lot(4, 3800), lot(1, 3000)],
    });
    const total = saleItemTicketLines(sold).reduce((sum, line) => sum + line.quantity, 0);
    expect(total).toBe(16);
  });
});

describe('ticketToTicketData', () => {
  it('copia lo que manda el backend y completa los opcionales', () => {
    const ticket = {
      store: 'Almacén Serrano',
      branch: null,
      address: 'San Martín 100',
      taxId: '30-12345678-9',
      ticketCode: 'T-0001',
      dateTime: '2026-09-25T17:30:00Z',
      registerName: null,
      cashierName: null,
      customerName: null,
      customerDoc: null,
      items: [
        {
          name: 'Yerba',
          quantity: 1,
          unitPrice: 2850,
          listPrice: null,
          lotNumber: 'L1',
          expiryDate: '2026-12-31',
          discountPct: null,
        },
      ],
      payments: [{ label: 'Efectivo', amount: 2850, reference: null }],
      change: 150,
      subtotal: 2850,
      discountTotal: 0,
      total: 2850,
    } as unknown as PosTicket;

    const data = ticketToTicketData(ticket);
    expect(data.branch).toBe('');
    expect(data.registerName).toBe('');
    expect(data.cashierName).toBe('');
    expect(data.cuit).toBe('30-12345678-9');
    expect(data.items).toHaveLength(1);
    expect(data.payments[0]).toEqual({ label: 'Efectivo', amount: 2850, reference: null });
    expect(data.total).toBe(2850);
  });
});

describe('saleToTicketData', () => {
  it('arma la vista previa con la venta recién cobrada', () => {
    const sale = {
      id: 10,
      ticketCode: 'T-0010',
      createdAt: '2026-09-25T17:30:00Z',
      branchName: 'Centro',
      registerName: 'Caja 1',
      cashierName: 'Laura',
      customerName: null,
      customerDoc: null,
      items: [item({ quantity: 2 })],
      payments: [{ label: 'Efectivo', amount: 5700, reference: null }],
      changeAmount: 300,
      subtotal: 5700,
      discountTotal: 0,
      total: 5700,
    } as unknown as PosSale;

    const data = saleToTicketData(sale, 'Almacén Serrano', '30-12345678-9');
    expect(data.store).toBe('Almacén Serrano');
    expect(data.branch).toBe('Centro');
    expect(data.items).toHaveLength(1);
    expect(data.items[0].quantity).toBe(2);
    expect(data.change).toBe(300);
  });
});
