import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { PosRegister, PosSessionReport } from '../types';
import PosTerminalPage from './PosTerminalPage';

vi.mock('@/auth/AuthContext', () => ({
  useAuth: () => ({ me: { fullName: 'Carla Gómez' }, hasRole: () => false }),
}));

vi.mock('@/branches/BranchContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/branches/BranchContext')>()),
  useBranch: () => ({ isAll: false, selectedBranchId: 1, branchName: () => 'Sucursal Centro' }),
  useBranchQueryKey: (...parts: unknown[]) => [...parts, { branch: 1 }],
}));

const toast = vi.hoisted(() => Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn(), info: vi.fn() }));
vi.mock('sonner', async (importOriginal) => ({ ...(await importOriginal<typeof import('sonner')>()), toast }));

const api = vi.hoisted(() => ({
  currentSession: vi.fn(),
  closeSession: vi.fn(),
  openSession: vi.fn(),
  registers: vi.fn(),
  search: vi.fn(),
  categories: vi.fn(),
}));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, posApi: { ...actual.posApi, ...api } };
});

/** Turno abierto con $ 1.500 de apertura y una venta con tarjeta. */
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
  openingCash: 1500,
  totalsByMethod: { CASH: 0, DEBIT: 1400, CREDIT: 0, TRANSFER: 0, QR: 0 },
  cashIn: 0,
  cashOut: 0,
  changeGiven: 0,
  expectedCash: 1500,
  countedCash: null,
  difference: null,
  salesCount: 1,
  salesTotal: 1400,
  units: 1,
  voidedCount: 0,
  voidedTotal: 0,
  topProducts: [],
  cashMovements: [],
  mine: true,
  closingNote: null,
  closedWithoutSales: false,
  ...overrides,
});

/** La misma venta, anulada por un administrador desde otro equipo: el turno quedó sin ventas vigentes. */
const voided = (overrides: Partial<PosSessionReport> = {}) =>
  session({
    totalsByMethod: { CASH: 0, DEBIT: 0, CREDIT: 0, TRANSFER: 0, QR: 0 },
    salesCount: 0,
    salesTotal: 0,
    units: 0,
    voidedCount: 1,
    voidedTotal: 1400,
    ...overrides,
  });

const REGISTER: PosRegister = {
  id: 3,
  branchId: 1,
  branchName: 'Sucursal Centro',
  name: 'Caja 1',
  active: true,
  createdAt: '2026-01-10T12:00:00Z',
  openSession: null,
};

function renderTerminal() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/app/pos']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/app/pos" element={<PosTerminalPage />} />
          <Route path="/app/pos/sessions" element={<p>Historial de turnos</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('PosTerminalPage · cierre de caja', () => {
  beforeEach(() => {
    api.search.mockResolvedValue([]);
    api.categories.mockResolvedValue([]);
    api.registers.mockResolvedValue([REGISTER]);
  });

  it('al abrir el cierre vuelve a traer el turno: si quedó sin ventas avisa y el toast lo dice', async () => {
    // El mostrador tiene en caché el turno con su venta; mientras tanto la anularon desde otro equipo.
    api.currentSession.mockResolvedValueOnce(session()).mockResolvedValue(voided());
    api.closeSession.mockResolvedValue(
      voided({ status: 'CLOSED', closedAt: '2026-09-24T20:00:00Z', countedCash: 1500, difference: 0, closedWithoutSales: true }),
    );
    const user = userEvent.setup();
    renderTerminal();

    await user.click(await screen.findByRole('button', { name: 'Cerrar caja' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cerrar caja' });
    // Con el turno recién traído, el diálogo ya sabe que no quedan ventas vigentes.
    expect(await within(dialog).findByText(/La única venta de este turno está anulada\./)).toBeInTheDocument();
    expect(api.currentSession).toHaveBeenCalledTimes(2);

    await user.type(within(dialog).getByRole('textbox', { name: /Efectivo contado/ }), '1.500');
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar caja' }));
    const warning = await screen.findByRole('dialog', { name: 'Estás a punto de cerrar la caja sin ventas' });
    expect(warning).toHaveTextContent('en Mis turnos de caja como «Cerrado sin ventas»');
    await user.click(within(warning).getByRole('button', { name: 'Cerrar sin ventas' }));

    expect(api.closeSession).toHaveBeenCalledWith(7, { countedCash: 1500, note: null });
    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith('Cerraste la caja sin ventas.', {
        description: 'La caja cerró justa. Quedó registrado en Mis turnos de caja como «Cerrado sin ventas».',
      }),
    );
    expect(await screen.findByText('Historial de turnos')).toBeInTheDocument();
  });

  it('un cierre con ventas avisa en el toast dónde quedó el reporte Z', async () => {
    api.currentSession.mockResolvedValue(session({ totalsByMethod: { CASH: 1400 }, expectedCash: 2900 }));
    api.closeSession.mockResolvedValue(
      session({ status: 'CLOSED', closedAt: '2026-09-24T20:00:00Z', expectedCash: 2900, countedCash: 2800, difference: -100 }),
    );
    const user = userEvent.setup();
    renderTerminal();

    await user.click(await screen.findByRole('button', { name: 'Cerrar caja' }));
    const dialog = await screen.findByRole('dialog', { name: 'Cerrar caja' });
    await user.type(within(dialog).getByRole('textbox', { name: /Efectivo contado/ }), '2.800');
    await user.click(within(dialog).getByRole('button', { name: 'Cerrar caja' }));

    expect(screen.queryByRole('dialog', { name: 'Estás a punto de cerrar la caja sin ventas' })).not.toBeInTheDocument();
    await waitFor(() =>
      expect(toast.success).toHaveBeenCalledWith('Cerraste la caja.', {
        description: 'Diferencia de -$ 100,00. El reporte Z quedó en Mis turnos de caja.',
      }),
    );
  });

  it('si el turno ya se cerró en otro equipo, vuelve a la apertura sin dejar el cierre pendiente', async () => {
    api.currentSession
      .mockResolvedValueOnce(session())
      .mockResolvedValueOnce(null)
      .mockResolvedValue(session({ id: 8, salesCount: 0, salesTotal: 0 }));
    api.openSession.mockResolvedValue(session({ id: 8, salesCount: 0, salesTotal: 0 }));
    const user = userEvent.setup();
    renderTerminal();

    await user.click(await screen.findByRole('button', { name: 'Cerrar caja' }));
    expect(await screen.findByText('Abrí tu caja')).toBeInTheDocument();

    // El turno nuevo arranca en el mostrador, no con el diálogo de cierre del anterior.
    await user.click(screen.getByRole('button', { name: 'Abrir caja y empezar a vender' }));
    expect(await screen.findByRole('button', { name: 'Cerrar caja' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Cerrar caja' })).not.toBeInTheDocument();
  });
});
