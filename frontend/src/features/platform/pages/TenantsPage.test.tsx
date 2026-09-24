import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeDto, PageResponse, Role } from '@/api/types';
import type { PlatformMetrics, TenantSummary } from '../types';
import TenantsPage from './TenantsPage';

const useAuth = vi.hoisted(() => vi.fn());
vi.mock('@/auth/AuthContext', () => ({ useAuth }));

const api = vi.hoisted(() => ({ list: vi.fn(), metrics: vi.fn() }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    platformApi: {
      ...actual.platformApi,
      metrics: api.metrics,
      tenants: { ...actual.platformApi.tenants, list: api.list },
    },
  };
});

function tenant(id: number, name: string, status: TenantSummary['status']): TenantSummary {
  return {
    id,
    name,
    businessType: 'KIOSCO',
    plan: 'FREEMIUM',
    status,
    city: 'Rosario',
    province: 'Santa Fe',
    contactName: null,
    contactEmail: null,
    contactPhone: null,
    userCount: 1,
    branchCount: 1,
    activeBranchCount: 1,
    modules: ['POS_GONDOLIA'],
    monthlyFee: 0,
    lastActivityAt: null,
    createdAt: '2026-01-10T12:00:00Z',
    statusChangedAt: null,
    statusReason: null,
  };
}

const PAGE: PageResponse<TenantSummary> = {
  content: [tenant(1, 'Almacén Don Pepe', 'ACTIVE'), tenant(2, 'Kiosco La Esquina', 'DISABLED')],
  page: 0,
  size: 20,
  totalElements: 2,
  totalPages: 1,
};

function renderAs(role: Role) {
  useAuth.mockReturnValue({ me: { id: 1, fullName: 'Usuario', role, tenant: null, branches: [] } as unknown as MeDto });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter
        initialEntries={['/owner/tenants']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/owner/tenants" element={<TenantsPage />} />
          <Route path="/owner/tenants/:id/edit" element={<p>Editando el cliente</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** Abre el menú de tres puntos de la fila (la tabla y la tarjeta de celular tienen uno cada una). */
async function openRowMenu(user: ReturnType<typeof userEvent.setup>, name: string) {
  const [trigger] = await screen.findAllByRole('button', { name: `Acciones de ${name}` });
  await user.click(trigger);
  return screen.getByRole('menu', { name: `Acciones de ${name}` });
}

describe('TenantsPage', () => {
  beforeEach(() => {
    api.list.mockResolvedValue(PAGE);
    api.metrics.mockResolvedValue({ tenants: { total: 2, active: 1, disabled: 1, cancelled: 0 } } as PlatformMetrics);
  });

  it('soporte ve los clientes, sin el alta ni las métricas del dueño', async () => {
    renderAs('SUPPORT_AGENT');

    expect(await screen.findAllByRole('link', { name: 'Kiosco La Esquina' })).not.toHaveLength(0);
    expect(screen.getByText('Consola de soporte')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Dar de alta un cliente' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Ver la matriz de módulos' })).toHaveAttribute('href', '/owner/modules');
    expect(api.metrics).not.toHaveBeenCalled();
  });

  it('el menú de soporte tiene ver y editar, sin acciones de estado, y lleva a la edición', async () => {
    const user = userEvent.setup();
    renderAs('SUPPORT_AGENT');

    const menu = await openRowMenu(user, 'Kiosco La Esquina');
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'Ver el detalle',
      'Editar los datos',
    ]);
    // Flota sobre la página: no estira la tabla (el caso de la última fila).
    expect(screen.getByRole('table', { name: 'Clientes de GondolIA' })).not.toContainElement(menu);
    expect(menu.parentElement).toBe(document.body);

    await user.click(screen.getByRole('menuitem', { name: 'Editar los datos' }));
    expect(await screen.findByText('Editando el cliente')).toBeInTheDocument();
  });

  it('el dueño ve el alta, los contadores y las acciones de estado según el cliente', async () => {
    const user = userEvent.setup();
    renderAs('PLATFORM_OWNER');

    expect(await screen.findAllByRole('link', { name: 'Dar de alta un cliente' })).not.toHaveLength(0);
    expect(screen.getByText('Consola de dueños')).toBeInTheDocument();
    expect(api.metrics).toHaveBeenCalled();

    await openRowMenu(user, 'Kiosco La Esquina');
    expect(screen.getAllByRole('menuitem').map((item) => item.textContent)).toEqual([
      'Ver el detalle',
      'Editar los datos',
      'Habilitar el acceso',
      'Dar de baja',
    ]);
  });
});
