import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BarcodeLookupResponse, CategoryDto, ProductDetail } from '../types';
import ProductFormPage from './ProductFormPage';

const api = vi.hoisted(() => ({ categories: vi.fn(), suppliers: vi.fn(), create: vi.fn(), lookup: vi.fn() }));
vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    categoriesApi: { ...actual.categoriesApi, list: api.categories },
    suppliersApi: { ...actual.suppliersApi, list: api.suppliers },
    productsApi: { ...actual.productsApi, create: api.create },
    catalogLookupApi: { ...actual.catalogLookupApi, byBarcode: api.lookup },
  };
});

const CATEGORIES = [
  { id: 1, name: 'Almacén', productCount: 12 },
  { id: 2, name: 'Lácteos', productCount: 5 },
] as CategoryDto[];

/** La URL que arma IntakePage al tocar "Crear el producto" con un código del catálogo de referencia. */
function fromIntake(data: Record<string, string>): string {
  return `/app/products/new?${new URLSearchParams({ ...data, source: 'REFERENCE_CATALOG' }).toString()}`;
}

/** El campo del código se busca por su ejemplo: la etiqueta de BarcodeField apunta a su contenedor, no al input. */
function barcodeInput(): HTMLElement {
  return screen.getByPlaceholderText('7791234500017');
}

function renderAt(url: string) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          <Route path="/app/products/new" element={<ProductFormPage />} />
          <Route path="/app/products/:id" element={<p>Ficha del producto</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ProductFormPage (alta con autocompletado)', () => {
  beforeEach(() => {
    // jsdom no trae ResizeObserver y el interruptor de Radix ("Tiene vencimiento") lo usa para medirse.
    vi.stubGlobal(
      'ResizeObserver',
      class {
        observe() {}
        unobserve() {}
        disconnect() {}
      },
    );
    api.categories.mockResolvedValue(CATEGORIES);
    api.suppliers.mockResolvedValue([]);
    api.create.mockResolvedValue({ id: 99 } as ProductDetail);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('llega desde la carga con los datos del catálogo y elige la categoría del comercio', async () => {
    const user = userEvent.setup();
    renderAt(
      fromIntake({
        barcode: '7793704000911',
        name: 'Yerba mate Playadito 500 g',
        brand: 'Playadito',
        quantity: '500 g',
        category: 'Almacén',
      }),
    );

    expect(barcodeInput()).toHaveValue('7793704000911');
    expect(screen.getByRole('textbox', { name: /^Nombre/ })).toHaveValue('Yerba mate Playadito 500 g');
    expect(screen.getByRole('textbox', { name: /^Marca/ })).toHaveValue('Playadito');
    expect(screen.getByRole('textbox', { name: /^Descripción/ })).toHaveValue('Contenido: 500 g');
    const category = screen.getByRole('combobox', { name: /^Categoría/ });
    await waitFor(() => expect(category).toHaveDisplayValue('Almacén'));
    expect(screen.queryByRole('textbox', { name: /^Categoría nueva/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Crear producto' }));

    expect(await screen.findByText('Ficha del producto')).toBeInTheDocument();
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({
        barcode: '7793704000911',
        name: 'Yerba mate Playadito 500 g',
        brand: 'Playadito',
        description: 'Contenido: 500 g',
        categoryId: 1,
        categoryName: null,
      }),
    );
  });

  it('con una categoría que el comercio no tiene propone crearla con ese nombre', async () => {
    const user = userEvent.setup();
    renderAt(
      fromIntake({
        barcode: '77939234',
        name: 'Alfajor Terrabusi clásico 50 g',
        brand: 'Terrabusi',
        quantity: '50 g',
        category: 'Golosinas',
      }),
    );

    expect(await screen.findByRole('textbox', { name: /^Categoría nueva/ })).toHaveValue('Golosinas');

    await user.click(screen.getByRole('button', { name: 'Crear producto' }));

    expect(await screen.findByText('Ficha del producto')).toBeInTheDocument();
    expect(api.create).toHaveBeenCalledWith(
      expect.objectContaining({ barcode: '77939234', categoryId: null, categoryName: 'Golosinas' }),
    );
  });

  it('"Completar con la base pública" no pisa la categoría que ya se eligió', async () => {
    api.lookup.mockResolvedValue({
      found: true,
      source: 'REFERENCE_CATALOG',
      barcode: '7790742625304',
      name: 'Dulce de leche La Serenísima clásico 400 g',
      brand: 'La Serenísima',
      quantity: '400 g',
      categoryHint: 'Almacén',
      imageUrl: null,
    } satisfies BarcodeLookupResponse);
    const user = userEvent.setup();
    renderAt('/app/products/new');

    await user.type(barcodeInput(), '7790742625304');
    const category = screen.getByRole('combobox', { name: /^Categoría/ });
    await screen.findByRole('option', { name: 'Lácteos' });
    await user.selectOptions(category, 'Lácteos');
    await user.click(screen.getByRole('button', { name: 'Completar con la base pública' }));

    await waitFor(() =>
      expect(screen.getByRole('textbox', { name: /^Nombre/ })).toHaveValue('Dulce de leche La Serenísima clásico 400 g'),
    );
    expect(api.lookup).toHaveBeenCalledWith('7790742625304');
    expect(screen.getByRole('textbox', { name: /^Descripción/ })).toHaveValue('Contenido: 400 g');
    expect(category).toHaveDisplayValue('Lácteos');
    expect(screen.queryByRole('textbox', { name: /^Categoría nueva/ })).not.toBeInTheDocument();
  });
});
