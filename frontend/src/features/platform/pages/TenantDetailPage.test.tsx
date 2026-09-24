import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeDto, Role, TenantModuleStatus } from '@/api/types';
import type { TenantDetail } from '../types';
import TenantDetailPage from './TenantDetailPage';

const useAuth = vi.hoisted(() => vi.fn());
vi.mock('@/auth/AuthContext', () => ({ useAuth }));

const api = vi.hoisted(() => ({ get: vi.fn(), ofTenant: vi.fn() }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    platformApi: {
      ...actual.platformApi,
      tenants: { ...actual.platformApi.tenants, get: api.get },
      modules: { ...actual.platformApi.modules, ofTenant: api.ofTenant },
    },
  };
});

function detail(status: TenantDetail['status']): TenantDetail {
  return {
    id: 7,
    name: 'Kiosco La Esquina',
    businessType: 'KIOSCO',
    plan: 'FREEMIUM',
    status,
    city: 'Rosario',
    province: 'Santa Fe',
    contactName: 'Marta',
    contactEmail: null,
    contactPhone: '11-4000-0000',
    userCount: 1,
    branchCount: 1,
    activeBranchCount: 1,
    modules: ['POS_GONDOLIA'],
    monthlyFee: 0,
    lastActivityAt: null,
    createdAt: '2026-01-10T12:00:00Z',
    statusChangedAt: null,
    statusReason: null,
    legalName: null,
    taxId: null,
    address: null,
    notes: null,
    maxBranches: 1,
    stockRotation: 'FIFO',
    usersByRole: { TENANT_ADMIN: 1 },
    branches: [],
    users: [],
    events: [
      {
        id: 3,
        type: 'ADMIN_PASSWORD_RESET',
        fromValue: null,
        toValue: null,
        reason: 'Contraseña temporal para admin@kiosco.com',
        actorName: 'Sofía Martínez',
        createdAt: '2026-09-20T15:00:00Z',
      },
      {
        id: 2,
        type: 'DATA_UPDATED',
        fromValue: null,
        toValue: null,
        reason: 'Cambios: teléfono',
        actorName: 'Sofía Martínez',
        createdAt: '2026-09-20T14:00:00Z',
      },
    ],
  };
}

const MODULES: TenantModuleStatus[] = [
  {
    module: 'POS_GONDOLIA',
    name: 'Punto de venta GondolIA',
    description: 'Cajas por sucursal',
    monthlyPricePerBranch: 12000,
    enabled: true,
    updatedAt: null,
    updatedByName: null,
  },
];

function renderAs(role: Role, status: TenantDetail['status']) {
  useAuth.mockReturnValue({ me: { id: 1, fullName: 'Usuario', role, tenant: null, branches: [] } as unknown as MeDto });
  api.get.mockResolvedValue(detail(status));
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/owner/tenants/7']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/owner/tenants/:id" element={<TenantDetailPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const STATUS_ACTIONS = ['Deshabilitar acceso', 'Habilitar acceso', 'Dar de baja', 'Reactivar', 'Eliminar'];

describe('TenantDetailPage', () => {
  beforeEach(() => {
    api.ofTenant.mockResolvedValue(MODULES);
  });

  it('soporte edita y restablece la contraseña del admin, sin acciones de estado', async () => {
    renderAs('SUPPORT_AGENT', 'ACTIVE');

    expect(await screen.findByRole('heading', { name: 'Kiosco La Esquina' })).toBeInTheDocument();
    expect(screen.getByText('Consola de soporte')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Editar' })).toHaveAttribute('href', '/owner/tenants/7/edit');
    expect(screen.getByRole('button', { name: 'Restablecer contraseña del admin' })).toBeInTheDocument();
    for (const action of STATUS_ACTIONS) {
      expect(screen.queryByRole('button', { name: action })).not.toBeInTheDocument();
    }
    // El historial muestra lo que hizo soporte, a su nombre.
    expect(screen.getByText('Contraseña del administrador restablecida')).toBeInTheDocument();
    expect(screen.getByText('Contraseña temporal para admin@kiosco.com')).toBeInTheDocument();
    expect(screen.getByText('Datos editados')).toBeInTheDocument();
    expect(screen.getByText('Cambios: teléfono')).toBeInTheDocument();
    expect(screen.getAllByText('Sofía Martínez')).toHaveLength(2);
  });

  it('con un cliente dado de baja, soporte no ve Reactivar ni un texto que se lo pida', async () => {
    renderAs('SUPPORT_AGENT', 'CANCELLED');

    expect(
      await screen.findByText(
        'El cliente está dado de baja: para cambiar sus módulos, un dueño de GondolIA tiene que reactivarlo.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reactivar' })).not.toBeInTheDocument();
    expect(screen.queryByText(/reactivalo/)).not.toBeInTheDocument();
  });

  it('el dueño ve las acciones de estado según el cliente', async () => {
    renderAs('PLATFORM_OWNER', 'ACTIVE');

    expect(await screen.findByRole('button', { name: 'Deshabilitar acceso' })).toBeInTheDocument();
    expect(screen.getByText('Consola de dueños')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Dar de baja' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Editar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Restablecer contraseña del admin' })).toBeInTheDocument();
  });

  it('con un cliente dado de baja, el dueño puede reactivarlo', async () => {
    renderAs('PLATFORM_OWNER', 'CANCELLED');

    expect(await screen.findByRole('button', { name: 'Reactivar' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Eliminar' })).toBeInTheDocument();
    expect(
      screen.getByText('El cliente está dado de baja: reactivalo para poder cambiar sus módulos.'),
    ).toBeInTheDocument();
  });
});
