import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import type { PosSessionReport } from '../types';
import { CloseSessionDialog } from './CloseSessionDialog';

/** Turno abierto en $ 0 y sin ventas: el caso del cajero que abrió la caja por error. */
const session = (overrides: Partial<PosSessionReport> = {}): PosSessionReport => ({
  id: 7,
  branchId: 1,
  branchName: 'Sucursal Centro',
  registerId: 3,
  registerName: 'Caja 1',
  status: 'OPEN',
  openedById: 5,
  openedByName: 'Carla Gómez',
  closedByName: null,
  openedAt: '2026-09-24T12:00:00Z',
  closedAt: null,
  openingCash: 0,
  totalsByMethod: { CASH: 0, DEBIT: 0, CREDIT: 0, TRANSFER: 0, QR: 0 },
  cashIn: 0,
  cashOut: 0,
  changeGiven: 0,
  expectedCash: 0,
  countedCash: null,
  difference: null,
  salesCount: 0,
  salesTotal: 0,
  units: 0,
  voidedCount: 0,
  voidedTotal: 0,
  topProducts: [],
  cashMovements: [],
  mine: true,
  closingNote: null,
  closedWithoutSales: false,
  ...overrides,
});

function setup(overrides: Partial<PosSessionReport> = {}) {
  const onSubmit = vi.fn();
  const user = userEvent.setup();
  render(<CloseSessionDialog open session={session(overrides)} onClose={vi.fn()} onSubmit={onSubmit} pending={false} />);
  const counted = () => screen.getByRole('textbox', { name: /Efectivo contado/ });
  const closeButton = () => screen.getByRole('button', { name: 'Cerrar caja' });
  const warning = () => screen.queryByRole('dialog', { name: 'Estás a punto de cerrar la caja sin ventas' });
  return { onSubmit, user, counted, closeButton, warning };
}

describe('CloseSessionDialog', () => {
  it('con el efectivo contado vacío "Cerrar caja" no queda gris: explica qué escribir', async () => {
    // Antes el botón quedaba deshabilitado sin ningún mensaje y el campo mostraba "$ 0" de ejemplo: en un turno
    // sin ventas (esperado $ 0) el cajero no tenía nada que contar y la caja parecía imposible de cerrar.
    const { onSubmit, user, counted, closeButton, warning } = setup();
    expect(closeButton()).toBeEnabled();
    expect(counted()).not.toHaveAttribute('placeholder', '$ 0');

    await user.click(closeButton());
    expect(screen.getByText('Escribí cuánto efectivo contaste.')).toBeInTheDocument();
    // Lo del cajón vacío lo dice la ayuda del campo, una sola vez: el error no la repite.
    expect(screen.getAllByText(/Si el cajón quedó vacío, escribí 0\./)).toHaveLength(1);
    expect(counted()).toHaveAccessibleDescription(
      'Escribí cuánto efectivo contaste. Usá punto para los miles: 184.350. Si el cajón quedó vacío, escribí 0.',
    );
    expect(counted()).toHaveAttribute('aria-invalid', 'true');
    expect(warning()).not.toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();

    await user.type(counted(), 'mucho');
    expect(screen.getByText('No entendimos el importe: escribilo como 184.350 o 184350.')).toBeInTheDocument();
  });

  it('sin ventas avisa antes de cerrar y deja cerrar igual', async () => {
    const { onSubmit, user, counted, closeButton, warning } = setup();
    expect(screen.getByText(/En este turno no se cobró ninguna venta\. Igual podés cerrar la caja/)).toBeInTheDocument();

    await user.type(counted(), '0');
    expect(screen.getByText('La caja cierra justa.')).toBeInTheDocument();
    await user.click(closeButton());

    // El último paso es el aviso: todavía no se cerró nada.
    expect(warning()).toBeInTheDocument();
    expect(warning()).toHaveTextContent(
      'En este turno no se cobró ninguna venta. Podés cerrar la caja igual: el turno queda registrado en Mis turnos de ' +
        'caja como «Cerrado sin ventas», con quién lo cerró y a qué hora.',
    );
    expect(onSubmit).not.toHaveBeenCalled();
    // En una acción que no se deshace el foco arranca en la salida: Enter no cierra la caja sin querer.
    expect(screen.getByRole('button', { name: 'Volver' })).toHaveFocus();

    await user.click(screen.getByRole('button', { name: 'Cerrar sin ventas' }));
    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith({ countedCash: 0, note: null });
  });

  it('"Volver" cierra el aviso sin cerrar la caja y conserva lo escrito', async () => {
    const { onSubmit, user, counted, closeButton, warning } = setup({ openingCash: 1500, expectedCash: 1500 });
    await user.type(counted(), '1.500');
    await user.type(screen.getByRole('textbox', { name: /Nota del cierre/ }), '  La abrí por error  ');
    // Enter en el importe hace lo mismo que el botón.
    await user.type(counted(), '{Enter}');
    expect(warning()).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Volver' }));
    expect(warning()).not.toBeInTheDocument();
    expect(onSubmit).not.toHaveBeenCalled();
    expect(counted()).toHaveValue('1.500');

    await user.click(closeButton());
    await user.click(screen.getByRole('button', { name: 'Cerrar sin ventas' }));
    expect(onSubmit).toHaveBeenCalledWith({ countedCash: 1500, note: 'La abrí por error' });
  });

  it('si todas las ventas del turno están anuladas también es un cierre sin ventas', async () => {
    const { user, counted, closeButton, warning } = setup({ voidedCount: 2, voidedTotal: 5600 });
    expect(screen.getByText(/Las 2 ventas de este turno están anuladas\./)).toBeInTheDocument();

    await user.type(counted(), '100');
    await user.click(closeButton());
    expect(warning()).toHaveTextContent('Las 2 ventas de este turno están anuladas.');
    expect(warning()).toHaveTextContent('Sobran $ 100,00.');
  });

  it('con una sola venta anulada lo dice en singular', () => {
    setup({ voidedCount: 1, voidedTotal: 1400 });
    expect(screen.getByText(/La única venta de este turno está anulada\./)).toBeInTheDocument();
  });

  it('con ventas cierra sin el aviso', async () => {
    const { onSubmit, user, counted, closeButton, warning } = setup({
      salesCount: 3,
      salesTotal: 4200,
      totalsByMethod: { CASH: 4200 },
      expectedCash: 4200,
    });
    expect(screen.queryByText(/no se cobró ninguna venta/)).not.toBeInTheDocument();

    await user.type(counted(), '4.000');
    expect(screen.getByText('Faltan $ 200,00.')).toBeInTheDocument();
    await user.click(closeButton());
    expect(warning()).not.toBeInTheDocument();
    expect(onSubmit).toHaveBeenCalledWith({ countedCash: 4000, note: null });
  });
});
