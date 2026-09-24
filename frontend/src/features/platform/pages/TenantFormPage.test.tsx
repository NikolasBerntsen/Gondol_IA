import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MeDto, Role } from '@/api/types';
import type { TenantDetail } from '../types';
import TenantFormPage from './TenantFormPage';

const useAuth = vi.hoisted(() => vi.fn());
vi.mock('@/auth/AuthContext', () => ({ useAuth }));

const api = vi.hoisted(() => ({ get: vi.fn(), update: vi.fn() }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    platformApi: {
      ...actual.platformApi,
      tenants: { ...actual.platformApi.tenants, get: api.get, update: api.update },
    },
  };
});

const DETAIL = {
  id: 7,
  name: 'Kiosco La Esquina',
  businessType: 'KIOSCO',
  plan: 'BASICO',
  status: 'ACTIVE',
  city: 'Rosario',
  province: 'Santa Fe',
  contactName: 'Marta',
  contactEmail: null,
  contactPhone: '11-4000-0000',
  legalName: null,
  taxId: null,
  address: null,
  notes: null,
  stockRotation: 'FIFO',
  activeBranchCount: 1,
} as unknown as TenantDetail;

function renderAs(role: Role) {
  useAuth.mockReturnValue({ me: { id: 1, fullName: 'Usuario', role, tenant: null, branches: [] } as unknown as MeDto });
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter
        initialEntries={['/owner/tenants/7/edit']}
        future={{ v7_startTransition: true, v7_relativeSplatPath: true }}
      >
        <Routes>
          <Route path="/owner/tenants/:id/edit" element={<TenantFormPage />} />
          <Route path="/owner/tenants/:id" element={<p>Detalle del cliente</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function changePhone(user: ReturnType<typeof userEvent.setup>) {
  const phone = await screen.findByRole('textbox', { name: /Teléfono/ });
  await user.clear(phone);
  await user.type(phone, '11-4000-1111');
}

describe('TenantFormPage (edición)', () => {
  beforeEach(() => {
    api.get.mockResolvedValue(DETAIL);
    api.update.mockResolvedValue(DETAIL);
  });

  it('soporte ve el plan fijo, con el aviso, y guarda sin mandarlo', async () => {
    const user = userEvent.setup();
    renderAs('SUPPORT_AGENT');

    const plan = await screen.findByRole('combobox', { name: /^Plan/ });
    expect(plan).toBeDisabled();
    expect(plan).toHaveValue('BASICO');
    expect(screen.getByText('El plan lo cambia un dueño de GondolIA: pedíselo si hace falta.')).toBeInTheDocument();

    await changePhone(user);
    await user.click(screen.getByRole('button', { name: 'Guardar los cambios' }));

    expect(await screen.findByText('Detalle del cliente')).toBeInTheDocument();
    const [id, body] = api.update.mock.calls[0];
    expect(id).toBe(7);
    expect(body).toMatchObject({ name: 'Kiosco La Esquina', contactPhone: '11-4000-1111' });
    // Sin plan el backend deja el actual: no pisa un cambio del dueño ni responde 403.
    expect(body.plan).toBeUndefined();
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty('plan');
  });

  it('el dueño cambia el plan y lo manda con el motivo', async () => {
    const user = userEvent.setup();
    renderAs('PLATFORM_OWNER');

    const plan = await screen.findByRole('combobox', { name: /^Plan/ });
    expect(plan).toBeEnabled();
    expect(screen.queryByText('El plan lo cambia un dueño de GondolIA: pedíselo si hace falta.')).not.toBeInTheDocument();

    await user.selectOptions(plan, 'PROFESIONAL');
    await user.type(screen.getByRole('textbox', { name: /Motivo del cambio de plan/ }), 'Abre otra sucursal');
    await user.click(screen.getByRole('button', { name: 'Guardar los cambios' }));

    expect(await screen.findByText('Detalle del cliente')).toBeInTheDocument();
    expect(api.update.mock.calls[0][1]).toMatchObject({ plan: 'PROFESIONAL', planChangeReason: 'Abre otra sucursal' });
  });
});
