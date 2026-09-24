import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { PosSale } from '../types';
import { PaymentSheet, type PaymentSheetProps } from './PaymentSheet';

const METHODS = ['Efectivo', 'Débito', 'Crédito', 'Transferencia', 'QR'];

/** Hoja de cobro abierta por $ 3.450 (2 unidades), con los callbacks espiados. */
function renderSheet(props: Partial<PaymentSheetProps> = {}) {
  const handlers = {
    onOpenChange: vi.fn(),
    onConfirm: vi.fn(),
    onNewSale: vi.fn(),
    onPrint: vi.fn(),
  };
  const element = (extra: Partial<PaymentSheetProps> = {}) => (
    <PaymentSheet
      open
      total={3450}
      units={2}
      pending={false}
      sale={null}
      storeName="Almacén Serrano"
      taxId={null}
      {...handlers}
      {...props}
      {...extra}
    />
  );
  const view = render(element());
  return {
    ...handlers,
    user: userEvent.setup(),
    rerender: (extra: Partial<PaymentSheetProps>) => view.rerender(element(extra)),
  };
}

const methodButton = (name: string) => screen.getByRole('button', { name });
const summary = () => screen.getByRole('region', { name: 'Resumen del cobro' });
const summaryValue = (term: string) => within(summary()).getByText(term).nextElementSibling?.textContent;
const confirmButton = () => screen.getByRole('button', { name: /Confirmar cobro/ });
const amountInput = (method: string) => screen.getByLabelText(`Monto en ${method}`) as HTMLInputElement;

describe('PaymentSheet', () => {
  it('arranca sin medios de pago: falta todo el total y no se puede confirmar', () => {
    renderSheet();

    for (const method of METHODS) {
      expect(methodButton(method)).toHaveAttribute('aria-pressed', 'false');
    }
    expect(screen.queryByRole('list', { name: 'Pagos cargados' })).not.toBeInTheDocument();
    expect(screen.queryAllByRole('textbox')).toHaveLength(0);
    expect(screen.getByText(/Tocá cómo paga el cliente/)).toBeInTheDocument();
    expect(summaryValue('Total')).toBe('$ 3.450,00');
    expect(summaryValue('Pagado')).toBe('$ 0,00');
    expect(summaryValue('Falta')).toBe('$ 3.450,00');
    expect(screen.queryByText('Billetes rápidos')).not.toBeInTheDocument();
    expect(confirmButton()).toBeDisabled();
  });

  it('tocar Débito y Crédito agrega las dos líneas en ese orden y los ilumina', async () => {
    const { user } = renderSheet();

    await user.click(methodButton('Débito'));
    await user.click(methodButton('Crédito'));

    expect(methodButton('Débito')).toHaveAttribute('aria-pressed', 'true');
    expect(methodButton('Crédito')).toHaveAttribute('aria-pressed', 'true');
    expect(methodButton('Efectivo')).toHaveAttribute('aria-pressed', 'false');
    const items = within(screen.getByRole('list', { name: 'Pagos cargados' })).getAllByRole('listitem');
    expect(items).toHaveLength(2);
    expect(items[0]).toHaveTextContent('Débito');
    expect(items[1]).toHaveTextContent('Crédito');
    // El primero se lleva lo que faltaba (todo); el segundo queda vacío para repartir.
    expect(amountInput('Débito').value).toBe('3.450');
    expect(amountInput('Crédito').value).toBe('');
    expect(summaryValue('Falta')).toBe('$ 0,00');
    expect(confirmButton()).toBeEnabled();
    await waitFor(() => expect(amountInput('Crédito')).toHaveFocus());
  });

  it('volver a tocar un medio iluminado quita su línea, y la X de la línea también', async () => {
    const { user } = renderSheet();
    await user.click(methodButton('Débito'));
    await user.click(methodButton('Crédito'));

    await user.click(methodButton('Débito'));
    expect(methodButton('Débito')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByLabelText('Monto en Débito')).not.toBeInTheDocument();
    expect(methodButton('Débito')).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Quitar Crédito' }));
    expect(methodButton('Crédito')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.queryByRole('list', { name: 'Pagos cargados' })).not.toBeInTheDocument();
    expect(summaryValue('Falta')).toBe('$ 3.450,00');
    await waitFor(() => expect(methodButton('Crédito')).toHaveFocus());
  });

  it('los billetes rápidos aparecen solo con efectivo y cargan lo recibido en esa línea', async () => {
    const { user } = renderSheet();

    await user.click(methodButton('Débito'));
    expect(screen.queryByText('Billetes rápidos')).not.toBeInTheDocument();
    await user.clear(amountInput('Débito'));
    await user.type(amountInput('Débito'), '2000');

    await user.click(methodButton('Efectivo'));
    expect(screen.getByText('Billetes rápidos')).toBeInTheDocument();
    expect(amountInput('Efectivo').value).toBe('1.450');

    // El primer billete reemplaza lo sugerido; los siguientes suman.
    await user.click(screen.getByRole('button', { name: '$ 10.000' }));
    expect(amountInput('Efectivo').value).toBe('10.000');
    expect(amountInput('Débito').value).toBe('2000');
    expect(summaryValue('Vuelto')).toBe('$ 8.550,00');
    await user.click(screen.getByRole('button', { name: '$ 2.000' }));
    expect(amountInput('Efectivo').value).toBe('12.000');
    expect(summaryValue('Vuelto')).toBe('$ 10.550,00');

    await user.click(screen.getByRole('button', { name: 'Monto justo' }));
    expect(amountInput('Efectivo').value).toBe('1.450');
    expect(summaryValue('Vuelto')).toBe('$ 0,00');

    await user.click(methodButton('Efectivo'));
    expect(screen.queryByText('Billetes rápidos')).not.toBeInTheDocument();
  });

  it('el resumen y el botón de cobrar quedan fuera de la lista que se desplaza', async () => {
    const { user } = renderSheet();
    for (const method of METHODS) await user.click(methodButton(method));

    const list = screen.getByRole('list', { name: 'Pagos cargados' });
    expect(within(list).getAllByRole('listitem')).toHaveLength(METHODS.length);
    const scroller = list.parentElement as HTMLElement;
    expect(scroller).toHaveClass('overflow-y-auto');
    expect(scroller).not.toContainElement(summary());
    expect(scroller).not.toContainElement(confirmButton());
    for (const term of ['Total', 'Pagado', 'Falta', 'Vuelto']) {
      expect(within(summary()).getByText(term)).toBeInTheDocument();
    }
  });

  it('con Enter en un monto confirma y manda los pagos en el orden de la hoja', async () => {
    const { user, onConfirm } = renderSheet();
    await user.click(methodButton('Débito'));
    await user.clear(amountInput('Débito'));
    await user.type(amountInput('Débito'), '1.000');
    await user.click(methodButton('Efectivo'));

    await user.type(amountInput('Efectivo'), '{Enter}');

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(onConfirm).toHaveBeenCalledWith([
      { method: 'DEBIT', amount: 1000 },
      { method: 'CASH', amount: 2450 },
    ]);
  });

  it('no deja cobrar si falta, si la tarjeta pasa el total o si un monto no se entiende', async () => {
    const { user, onConfirm } = renderSheet();
    await user.click(methodButton('Débito'));
    const debit = amountInput('Débito');

    await user.clear(debit);
    await user.type(debit, '3000');
    expect(summaryValue('Falta')).toBe('$ 450,00');
    expect(confirmButton()).toBeDisabled();

    await user.clear(debit);
    await user.type(debit, '5000');
    expect(screen.getByRole('alert')).toHaveTextContent('El vuelto solo se da en efectivo');
    expect(summaryValue('Vuelto')).toBe('$ 0,00');
    expect(confirmButton()).toBeDisabled();

    await user.clear(debit);
    await user.type(debit, 'mil{Enter}');
    expect(debit).toHaveAttribute('aria-invalid', 'true');
    expect(confirmButton()).toBeDisabled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('mientras actualiza precios no confirma', async () => {
    const { user } = renderSheet({ refreshing: true });
    expect(screen.getByText(/actualizando precios/)).toBeInTheDocument();
    await user.click(methodButton('QR'));
    expect(confirmButton()).toBeDisabled();
  });

  it('Esc y "Volver a la venta" cierran la hoja, salvo mientras se está cobrando', async () => {
    const { user, onOpenChange, rerender } = renderSheet();
    await user.keyboard('{Escape}');
    expect(onOpenChange).toHaveBeenLastCalledWith(false);
    await user.click(screen.getByRole('button', { name: /Volver a la venta/ }));
    expect(onOpenChange).toHaveBeenCalledTimes(2);

    rerender({ pending: true });
    await user.keyboard('{Escape}');
    expect(onOpenChange).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('button', { name: /Volver a la venta/ })).toBeDisabled();
  });

  it('al volver a abrirla arranca de cero', async () => {
    const { user, rerender } = renderSheet();
    await user.click(methodButton('Débito'));
    expect(amountInput('Débito')).toBeInTheDocument();

    rerender({ open: false });
    rerender({ open: true });

    expect(screen.queryByRole('list', { name: 'Pagos cargados' })).not.toBeInTheDocument();
    expect(methodButton('Débito')).toHaveAttribute('aria-pressed', 'false');
  });

  it('con la venta cobrada muestra el ticket con Imprimir y Nueva venta', async () => {
    const sale = {
      id: 10,
      ticketCode: 'T-0010',
      createdAt: '2026-09-25T17:30:00Z',
      branchName: 'Centro',
      registerName: 'Caja 1',
      cashierName: 'Laura',
      customerName: null,
      customerDoc: null,
      items: [
        {
          productId: 1,
          productName: 'Yerba Serrana 1 kg',
          quantity: 1,
          unitPrice: 3450,
          listUnitPrice: 3450,
          discountAmount: 0,
          lineTotal: 3450,
          lots: [],
        },
      ],
      payments: [{ method: 'CASH', label: 'Efectivo', amount: 5000, reference: null }],
      changeAmount: 1550,
      subtotal: 3450,
      discountTotal: 0,
      total: 3450,
      units: 1,
      status: 'COMPLETED',
    } as unknown as PosSale;
    const { user, onPrint, onNewSale } = renderSheet({ sale });

    const dialog = screen.getByRole('dialog', { name: 'Venta registrada' });
    expect(within(dialog).getAllByText('Ticket T-0010').length).toBeGreaterThan(0);
    expect(within(dialog).getAllByText('$ 1.550,00').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: 'Efectivo' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Imprimir' }));
    expect(onPrint).toHaveBeenCalledWith(sale);
    await user.click(screen.getByRole('button', { name: /Nueva venta/ }));
    expect(onNewSale).toHaveBeenCalledTimes(1);
  });
});
