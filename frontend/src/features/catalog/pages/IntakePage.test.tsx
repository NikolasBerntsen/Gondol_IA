import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/api/client';
import type { MeDto } from '@/api/types';
import type { BarcodeLookupResponse, CategoryDto } from '../types';
import IntakePage from './IntakePage';
import ProductFormPage from './ProductFormPage';

const useCurrentUser = vi.hoisted(() => vi.fn());
vi.mock('@/auth/AuthContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/auth/AuthContext')>()),
  useCurrentUser,
}));

const branch = vi.hoisted(() => ({ useBranch: vi.fn(), useWriteBranch: vi.fn() }));
vi.mock('@/branches/BranchContext', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/branches/BranchContext')>()),
  useBranch: branch.useBranch,
  useWriteBranch: branch.useWriteBranch,
}));

const api = vi.hoisted(() => ({ byBarcode: vi.fn(), lookup: vi.fn(), suppliers: vi.fn(), categories: vi.fn() }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    productsApi: { ...actual.productsApi, getByBarcode: api.byBarcode },
    catalogLookupApi: { ...actual.catalogLookupApi, byBarcode: api.lookup },
    suppliersApi: { ...actual.suppliersApi, list: api.suppliers },
    categoriesApi: { ...actual.categoriesApi, list: api.categories },
  };
});

const CATEGORIES = [{ id: 1, name: 'Almacén', productCount: 12 }] as CategoryDto[];

const NOT_IN_TENANT_CATALOG = new ApiError({ status: 404, code: 'NOT_FOUND', message: 'No existe el producto.' });

function NewProductRoute() {
  const { search } = useLocation();
  return (
    <>
      <output data-testid="search">{search}</output>
      <ProductFormPage />
    </>
  );
}

function renderIntake() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/app/intake']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/app/intake" element={<IntakePage />} />
          <Route path="/app/products/new" element={<NewProductRoute />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

async function searchCode(user: ReturnType<typeof userEvent.setup>, code: string) {
  await user.type(screen.getByRole('textbox', { name: 'Código de barras' }), code);
  await user.click(screen.getByRole('button', { name: 'Buscar' }));
}

describe('IntakePage → alta de un código que el comercio no tiene', () => {
  beforeEach(() => {
    // jsdom no trae ResizeObserver y el interruptor de Radix del alta ("Tiene vencimiento") lo usa para medirse.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    useCurrentUser.mockReturnValue({ fullName: 'Empleada', tenant: { stockRotation: 'FIFO' } } as unknown as MeDto);
    const centro = { id: 1, name: 'Centro' };
    branch.useBranch.mockReturnValue({ isAll: false, branches: [centro], currentBranch: centro, selectedBranchId: 1 });
    branch.useWriteBranch.mockReturnValue({ needsPicker: false, branchId: 1, setBranchId: vi.fn(), isReady: true });
    api.byBarcode.mockRejectedValue(NOT_IN_TENANT_CATALOG);
    api.suppliers.mockResolvedValue([]);
    api.categories.mockResolvedValue(CATEGORIES);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('pasa los datos del catálogo de referencia al alta: contenido, categoría y fuente', async () => {
    api.lookup.mockResolvedValue({
      found: true,
      source: 'REFERENCE_CATALOG',
      barcode: '7793704000911',
      name: 'Yerba mate Playadito 500 g',
      brand: 'Playadito',
      quantity: '500 g',
      categoryHint: 'Almacén',
      imageUrl: null,
    } satisfies BarcodeLookupResponse);
    const user = userEvent.setup();
    renderIntake();

    await searchCode(user, '7793704000911');

    expect(await screen.findByText('Este código no está en tu catálogo')).toBeInTheDocument();
    expect(screen.getByText('Yerba mate Playadito 500 g')).toBeInTheDocument();
    expect(
      screen.getByText('Fuente: catálogo de productos argentinos (datos de Open Food Facts, licencia ODbL).'),
    ).toBeInTheDocument();
    expect(api.lookup).toHaveBeenCalledWith('7793704000911');

    await user.click(screen.getByRole('button', { name: 'Crear el producto' }));

    const params = new URLSearchParams(screen.getByTestId('search').textContent ?? '');
    expect(Object.fromEntries(params)).toEqual({
      barcode: '7793704000911',
      name: 'Yerba mate Playadito 500 g',
      brand: 'Playadito',
      quantity: '500 g',
      category: 'Almacén',
      source: 'REFERENCE_CATALOG',
    });
    expect(screen.getByRole('textbox', { name: /^Descripción/ })).toHaveValue('Contenido: 500 g');
    await waitFor(() => expect(screen.getByRole('combobox', { name: /^Categoría/ })).toHaveDisplayValue('Almacén'));
  });

  it('sin datos públicos pide crearlo a mano y el alta llega solo con el código', async () => {
    api.lookup.mockResolvedValue({
      found: false,
      source: null,
      barcode: '7791234500017',
      name: null,
      brand: null,
      quantity: null,
      categoryHint: null,
      imageUrl: null,
    } satisfies BarcodeLookupResponse);
    const user = userEvent.setup();
    renderIntake();

    await searchCode(user, '7791234500017');

    expect(
      await screen.findByText(
        'No encontramos el producto en el catálogo ni en la base pública. Crealo a mano y después cargá su mercadería.',
      ),
    ).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Crear el producto' }));

    expect(screen.getByTestId('search')).toHaveTextContent('?barcode=7791234500017');
    expect(screen.getByRole('textbox', { name: /^Nombre/ })).toHaveValue('');
  });
});
