import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeDto, PageResponse, Role } from '@/api/types';
import type { TenantModulesRow } from '../types';
import ModulesMatrixPage from './ModulesMatrixPage';

const useAuth = vi.hoisted(() => vi.fn());
vi.mock('@/auth/AuthContext', () => ({ useAuth }));

const api = vi.hoisted(() => ({ matrix: vi.fn(), catalog: vi.fn() }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    platformApi: {
      ...actual.platformApi,
      modules: { ...actual.platformApi.modules, matrix: api.matrix, catalog: api.catalog },
    },
  };
});

const ROW: TenantModulesRow = {
  tenantId: 1,
  tenantName: 'Almacén Don Pepe',
  businessType: 'ALMACEN',
  city: 'CABA',
  plan: 'BASICO',
  status: 'ACTIVE',
  activeBranchCount: 1,
  modules: { POS_GONDOLIA: true, POS_INTEGRATION: false, MULTI_BRANCH: false },
  estimatedMonthlyFee: 37000,
  lastActivityAt: null,
};

const page = (content: TenantModulesRow[]): PageResponse<TenantModulesRow> => ({
  content,
  page: 0,
  size: 20,
  totalElements: content.length,
  totalPages: 1,
});

function renderAs(role: Role) {
  useAuth.mockReturnValue({ me: { id: 1, fullName: 'Usuario', role, tenant: null, branches: [] } as unknown as MeDto });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/owner/modules']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <ModulesMatrixPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ModulesMatrixPage', () => {
  beforeEach(() => {
    api.matrix.mockResolvedValue(page([ROW]));
    api.catalog.mockResolvedValue([]);
  });

  it('soporte ve la cuota de cada cliente con ese nombre, sin la adopción ni el MRR de la lista', async () => {
    renderAs('SUPPORT_AGENT');

    expect(await screen.findAllByRole('link', { name: 'Almacén Don Pepe' })).not.toHaveLength(0);
    expect(screen.getByRole('columnheader', { name: 'Cuota' })).toBeInTheDocument();
    expect(screen.queryByText(/MRR/)).not.toBeInTheDocument();
    expect(screen.getByText(/^Cuota = sucursales activas/)).toBeInTheDocument();
    expect(api.catalog).not.toHaveBeenCalled();
  });

  it('el dueño ve el MRR estimado por cliente y el de la lista', async () => {
    renderAs('PLATFORM_OWNER');

    expect(await screen.findAllByRole('link', { name: 'Almacén Don Pepe' })).not.toHaveLength(0);
    expect(screen.getByRole('columnheader', { name: 'MRR estimado' })).toBeInTheDocument();
    expect(screen.getByText('MRR estimado de esta lista')).toBeInTheDocument();
    expect(screen.getByText(/^MRR = sucursales activas/)).toBeInTheDocument();
  });

  it('sin clientes, a soporte no le pide que dé de alta uno', async () => {
    api.matrix.mockResolvedValue(page([]));
    renderAs('SUPPORT_AGENT');

    expect(
      await screen.findByText('Cuando un dueño dé de alta un comercio, vas a poder manejar sus módulos desde acá.'),
    ).toBeInTheDocument();
  });
});
