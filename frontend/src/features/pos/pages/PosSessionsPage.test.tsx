import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PageResponse, Role } from '@/api/types';
import type { PosSessionSummary } from '../types';
import PosSessionsPage from './PosSessionsPage';

const useAuth = vi.hoisted(() => vi.fn());
vi.mock('@/auth/AuthContext', () => ({ useAuth }));

vi.mock('@/branches/BranchContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/branches/BranchContext')>()),
  useBranch: () => ({ isAll: false, selectedBranchId: 1 }),
  useBranchQueryKey: (...parts: unknown[]) => [...parts, { branch: 1 }],
}));

const sessions = vi.hoisted(() => vi.fn());
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, posApi: { ...actual.posApi, sessions } };
});

function row(id: number, registerName: string, overrides: Partial<PosSessionSummary>): PosSessionSummary {
  return {
    id,
    branchId: 1,
    branchName: 'Sucursal Centro',
    registerId: id,
    registerName,
    status: 'CLOSED',
    openedById: 5,
    openedByName: 'Carla Gómez',
    closedByName: 'Carla Gómez',
    openedAt: '2026-09-24T12:00:00Z',
    closedAt: '2026-09-24T20:00:00Z',
    openingCash: 0,
    expectedCash: 0,
    countedCash: 0,
    difference: 0,
    salesCount: 0,
    salesTotal: 0,
    voidedCount: 0,
    voidedTotal: 0,
    mine: true,
    closedWithoutSales: false,
    ...overrides,
  };
}

const PAGE: PageResponse<PosSessionSummary> = {
  content: [
    row(1, 'Caja 1', { closedWithoutSales: true }),
    // Cerró con ventas: aunque después se anularan todas, sigue siendo un cierre común.
    row(2, 'Caja 2', { salesCount: 3, salesTotal: 4200, voidedCount: 3, voidedTotal: 4200 }),
    row(3, 'Caja 3', { status: 'OPEN', closedAt: null, countedCash: null, difference: null }),
  ],
  page: 0,
  size: 20,
  totalElements: 3,
  totalPages: 1,
};

function renderAs(role: Role) {
  useAuth.mockReturnValue({ hasRole: (...roles: Role[]) => roles.includes(role) });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/app/pos/sessions']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <PosSessionsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Fila de la tabla de escritorio (la lista de tarjetas de celular repite los datos). */
async function tableRow(registerName: string) {
  const table = await screen.findByRole('table', { name: 'Turnos de caja del punto de venta' });
  const cell = await within(table).findByText(registerName);
  const found = cell.closest('tr');
  if (!found) throw new Error(`sin fila para ${registerName}`);
  return within(found);
}

describe('PosSessionsPage', () => {
  beforeEach(() => {
    sessions.mockResolvedValue(PAGE);
  });

  it('el historial marca «Cerrado sin ventas» solo en los turnos que cerraron sin ventas', async () => {
    renderAs('TENANT_CASHIER');

    const withoutSales = await tableRow('Caja 1');
    expect(withoutSales.getByText('Cerrado sin ventas')).toBeInTheDocument();

    const withSales = await tableRow('Caja 2');
    expect(withSales.getByText('Cerrado')).toBeInTheDocument();
    expect(withSales.queryByText('Cerrado sin ventas')).not.toBeInTheDocument();

    const open = await tableRow('Caja 3');
    expect(open.getByText('Abierto')).toBeInTheDocument();
    expect(open.queryByText(/Cerrado/)).not.toBeInTheDocument();

    // El cajero siempre ve sus propios turnos.
    expect(sessions).toHaveBeenCalledWith(expect.objectContaining({ mine: true }));
  });
});
