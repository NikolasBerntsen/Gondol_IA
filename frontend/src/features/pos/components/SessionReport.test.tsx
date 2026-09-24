import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { PosSessionReport } from '../types';
import { SessionReport } from './SessionReport';

/** Turno cerrado sin ventas: se abrió en $ 0 y se cerró contando $ 0. */
const closedWithoutSales = (overrides: Partial<PosSessionReport> = {}): PosSessionReport => ({
  id: 7,
  branchId: 1,
  branchName: 'Sucursal Centro',
  registerId: 3,
  registerName: 'Caja 1',
  status: 'CLOSED',
  openedById: 5,
  openedByName: 'Carla Gómez',
  closedByName: 'Carla Gómez',
  openedAt: '2026-09-24T12:00:00Z',
  closedAt: '2026-09-24T12:20:00Z',
  openingCash: 0,
  totalsByMethod: { CASH: 0, DEBIT: 0, CREDIT: 0, TRANSFER: 0, QR: 0 },
  cashIn: 0,
  cashOut: 0,
  changeGiven: 0,
  expectedCash: 0,
  countedCash: 0,
  difference: 0,
  salesCount: 0,
  salesTotal: 0,
  units: 0,
  voidedCount: 0,
  voidedTotal: 0,
  topProducts: [],
  cashMovements: [],
  mine: true,
  closingNote: null,
  closedWithoutSales: true,
  ...overrides,
});

describe('SessionReport', () => {
  it('un turno cerrado sin ventas lo dice en el estado y en la línea del cierre', () => {
    render(<SessionReport session={closedWithoutSales({ closingNote: 'La abrí por error' })} />);
    expect(screen.getByText('Cerrado sin ventas')).toBeInTheDocument();
    expect(screen.getByText(/^Cerrado sin ventas el .+ por Carla Gómez\. «La abrí por error»$/)).toBeInTheDocument();
    expect(screen.getByText('Cierra justa')).toBeInTheDocument();
    // Sin ventas no hay "Lo que más salió" (y nada se rompe con los totales en cero).
    expect(screen.queryByText('Lo que más salió')).not.toBeInTheDocument();
    expect(screen.getByText('Tickets cobrados').nextElementSibling).toHaveTextContent('0');
  });

  it('un turno cerrado con ventas sigue diciendo "Cerrado"', () => {
    render(
      <SessionReport
        session={closedWithoutSales({
          closedWithoutSales: false,
          salesCount: 2,
          salesTotal: 2800,
          units: 2,
          totalsByMethod: { CASH: 2800 },
          expectedCash: 2800,
          countedCash: 2800,
          topProducts: [{ productId: 11, productName: 'Leche entera La Pradera 1 L', units: 2, total: 2800 }],
        })}
      />,
    );
    expect(screen.getByText('Cerrado')).toBeInTheDocument();
    expect(screen.queryByText(/sin ventas/)).not.toBeInTheDocument();
    expect(screen.getByText('Lo que más salió')).toBeInTheDocument();
  });

  it('un turno abierto sin ventas todavía no es un cierre sin ventas', () => {
    render(
      <SessionReport
        session={closedWithoutSales({
          status: 'OPEN',
          closedWithoutSales: false,
          closedAt: null,
          closedByName: null,
          countedCash: null,
          difference: null,
        })}
      />,
    );
    expect(screen.getByText('Abierto')).toBeInTheDocument();
    expect(screen.queryByText(/sin ventas/)).not.toBeInTheDocument();
  });
});
