import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { FileSpreadsheet, Package, PackagePlus, PackageSearch, Pencil, ScanBarcode } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAccess } from '@/auth/useAccess';
import { useBranch } from '@/branches/BranchContext';
import { useBranchQueryKey } from '@/branches/BranchContext';
import { ExpiryChip, StockStatusPill } from '@/components/gondola';
import {
  Button,
  ButtonLink,
  Card,
  EmptyState,
  Pagination,
  PageHeader,
  SearchInput,
  Segmented,
  Select,
  Table,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { useDebounce } from '@/lib/useDebounce';
import { formatMoney, formatNumber } from '@/lib/format';
import { productsApi, categoriesApi } from '../api';
import { STOCK_FILTERS, notHandledInScope, unitShort } from '../lib';
import type { ProductListItem, ProductStockFilter } from '../types';

const PAGE_SIZE = 20;

/** Filtro de stock que llega por URL (`?stockStatus=LOW`); cualquier otro valor es "Todos". */
function parseStockFilter(value: string | null): ProductStockFilter {
  return STOCK_FILTERS.find((option) => option.value === value)?.value ?? 'ALL';
}

export default function InventoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { can } = useAccess();
  const { branches, isAll } = useBranch();
  // El jefe ve el inventario completo pero no carga ni edita (SPEC §3.3): sin esos botones.
  const canImport = can('imports.use');
  const canWrite = can('products.write');
  const canIntake = can('intake.use');

  // El buscador de la barra superior navega a /app/inventory?q=<texto> (docs/frontend-guide.md §9).
  const urlQuery = searchParams.get('q') ?? '';
  const [search, setSearch] = useState(urlQuery);
  // Categorías abre /app/inventory?categoryId=<id>.
  const urlCategoryId = searchParams.get('categoryId') ?? '';
  const [categoryId, setCategoryId] = useState<string>(urlCategoryId);
  // El Inicio abre /app/inventory?stockStatus=LOW desde "Artículos a reponer".
  const urlStockStatus = searchParams.get('stockStatus');
  const [stockStatus, setStockStatus] = useState<ProductStockFilter>(() => parseStockFilter(urlStockStatus));
  const [page, setPage] = useState(0);

  useEffect(() => {
    setSearch(urlQuery);
  }, [urlQuery]);

  useEffect(() => {
    setStockStatus(parseStockFilter(urlStockStatus));
  }, [urlStockStatus]);

  useEffect(() => {
    setCategoryId(urlCategoryId);
  }, [urlCategoryId]);

  const debouncedSearch = useDebounce(search, 300);

  useEffect(() => {
    setPage(0);
  }, [debouncedSearch, categoryId, stockStatus]);

  const params = useMemo(
    () => ({
      q: debouncedSearch || undefined,
      categoryId: categoryId ? Number(categoryId) : undefined,
      stockStatus: stockStatus === 'ALL' ? undefined : stockStatus,
      page,
      size: PAGE_SIZE,
      sort: 'name,asc',
    }),
    [debouncedSearch, categoryId, stockStatus, page],
  );

  const productsQuery = useQuery({
    queryKey: useBranchQueryKey('products', 'list', params),
    queryFn: () => productsApi.list(params),
    placeholderData: keepPreviousData,
  });

  const categoriesQuery = useQuery({ queryKey: ['categories'], queryFn: () => categoriesApi.list() });

  const hasFilters = !!debouncedSearch || !!categoryId || stockStatus !== 'ALL';
  const isEmptyCatalog = !hasFilters && productsQuery.data?.totalElements === 0;

  const branchColumns: Array<TableColumn<ProductListItem>> =
    isAll && branches.length > 1
      ? branches.map((branch) => ({
          id: `branch-${branch.id}`,
          header: branch.name,
          // El nombre de la sucursal puede partirse en dos renglones: los valores son números cortos y así la tabla
          // de "Todas las sucursales" entra (o casi) sin scroll en una pantalla de 1360 px.
          headerClassName: 'whitespace-normal leading-4',
          align: 'right' as const,
          hideBelow: 'xl' as const,
          mobile: 'field' as const,
          mobileLabel: branch.name,
          cell: (row: ProductListItem) => {
            const stock = row.stockByBranch.find((item) => item.branchId === branch.id);
            if (!stock) return <span className="text-muted-foreground">—</span>;
            return (
              <span className="tabular-nums" title={`${stock.branchName}: ${stock.sellableStock} vendibles`}>
                {formatNumber(stock.sellableStock)}
              </span>
            );
          },
        }))
      : [];

  const columns: Array<TableColumn<ProductListItem> | null> = [
    {
      id: 'name',
      header: 'Producto',
      mobile: 'title',
      cell: (row) => (
        <div className="min-w-0">
          <Link to={`/app/products/${row.id}`} className="font-semibold text-foreground hover:underline">
            {row.name}
          </Link>
          <div className="truncate text-sm text-muted-foreground">
            {[row.brand, row.categoryName].filter(Boolean).join(' · ') || 'Sin marca'}
          </div>
          {row.barcode && <div className="font-mono text-xs tabular-nums text-muted-foreground">{row.barcode}</div>}
          {!row.active && <div className="text-xs font-semibold text-muted-foreground">Dado de baja</div>}
        </div>
      ),
    },
    {
      id: 'stock',
      header: isAll ? 'Stock total' : 'Stock',
      align: 'right',
      mobile: 'field',
      mobileLabel: 'Stock vendible',
      cell: (row) =>
        // Producto que el alcance no trabaja (nunca tuvo lotes acá): no es un faltante, no hay número que mostrar.
        notHandledInScope(row) ? (
          <span
            className="text-muted-foreground"
            title={isAll ? 'Ninguna de tus sucursales lo trabaja' : 'Esta sucursal no lo trabaja'}
          >
            —
          </span>
        ) : (
          <div className="whitespace-nowrap">
            <span className="font-semibold tabular-nums">{formatNumber(row.sellableStock)}</span>{' '}
            <span className="text-sm text-muted-foreground">{unitShort(row.unit)}</span>
            {row.minStock > 0 && (
              <div className="text-xs text-muted-foreground">mín. {formatNumber(row.minStock)}</div>
            )}
          </div>
        ),
    },
    ...branchColumns,
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) => <StockStatusPill status={row.stockStatus} />,
    },
    {
      id: 'expiry',
      header: 'Próximo vencimiento',
      hideBelow: 'lg',
      mobile: 'aside',
      cell: (row) =>
        row.nextExpiryDate ? (
          <ExpiryChip expiry={row.nextExpiryDate} showDays />
        ) : (
          <span className="text-sm text-muted-foreground">{row.perishable ? 'Sin lotes' : 'No perece'}</span>
        ),
    },
    {
      id: 'price',
      header: 'Precio',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      mobileLabel: 'Precio',
      cell: (row) => <span className="whitespace-nowrap tabular-nums">{formatMoney(row.salePrice)}</span>,
    },
    canIntake || canWrite
      ? {
          id: 'actions',
          header: <span className="sr-only">Acciones</span>,
          align: 'right',
          mobile: 'actions',
          cell: (row) => (
            <div className="flex items-center justify-end gap-1">
              {canIntake ? (
                <ButtonLink
                  to={`/app/intake?productId=${row.id}${row.barcode ? `&barcode=${encodeURIComponent(row.barcode)}` : ''}`}
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Cargar mercadería de ${row.name}`}
                  title="Cargar mercadería"
                >
                  <ScanBarcode className="h-4 w-4" aria-hidden="true" />
                </ButtonLink>
              ) : null}
              {canWrite ? (
                <ButtonLink
                  to={`/app/products/${row.id}/edit`}
                  variant="ghost"
                  size="icon-sm"
                  aria-label={`Editar ${row.name}`}
                  title="Editar producto"
                >
                  <Pencil className="h-4 w-4" aria-hidden="true" />
                </ButtonLink>
              ) : null}
            </div>
          ),
        }
      : null,
  ];

  return (
    <>
      <PageHeader
        title="Inventario"
        icon={Package}
        description={
          productsQuery.data
            ? `${formatNumber(productsQuery.data.totalElements)} ${
                productsQuery.data.totalElements === 1 ? 'producto' : 'productos'
              } en el catálogo de tu comercio.`
            : 'Productos, stock y vencimientos de tu comercio.'
        }
        actions={
          canImport || canWrite ? (
            <div className="flex flex-wrap items-center gap-2">
              {canImport && (
                <ButtonLink to="/app/imports" variant="outline" leftIcon={<FileSpreadsheet className="h-4 w-4" />}>
                  Importar Excel/CSV
                </ButtonLink>
              )}
              {canWrite && (
                <ButtonLink to="/app/products/new" leftIcon={<PackagePlus className="h-4 w-4" />}>
                  Nuevo producto
                </ButtonLink>
              )}
            </div>
          ) : undefined
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-col gap-3 md:flex-row md:items-center">
            <SearchInput
              value={search}
              onValueChange={(value) => {
                setSearch(value);
                const next = new URLSearchParams(searchParams);
                if (value) next.set('q', value);
                else next.delete('q');
                setSearchParams(next, { replace: true });
              }}
              label="Buscar productos"
              placeholder="Buscar por nombre, marca o código…"
              containerClassName="md:max-w-sm"
            />
            <Select
              aria-label="Filtrar por categoría"
              value={categoryId}
              onChange={(event) => {
                setCategoryId(event.target.value);
                const next = new URLSearchParams(searchParams);
                if (event.target.value) next.set('categoryId', event.target.value);
                else next.delete('categoryId');
                setSearchParams(next, { replace: true });
              }}
              placeholder="Todas las categorías"
              containerClassName="md:max-w-[220px]"
              options={(categoriesQuery.data ?? []).map((category) => ({
                value: String(category.id),
                label: `${category.name} (${category.productCount})`,
              }))}
            />
          </div>
          <Segmented
            label="Filtrar por estado de stock"
            value={stockStatus}
            onChange={(value) => {
              setStockStatus(value);
              const next = new URLSearchParams(searchParams);
              if (value === 'ALL') next.delete('stockStatus');
              else next.set('stockStatus', value);
              setSearchParams(next, { replace: true });
            }}
            options={STOCK_FILTERS.map((option) => ({ value: option.value, label: option.label }))}
          />
        </div>
      </PageHeader>

      {isEmptyCatalog ? (
        <Card padding="lg">
          <EmptyState
            icon={PackageSearch}
            title={canWrite ? 'Todavía no cargaste productos' : 'Todavía no hay productos cargados'}
            description={
              canWrite
                ? 'Empezá por tu planilla de Excel o CSV, o cargá el primer producto a mano y después su mercadería.'
                : 'Cuando el administrador cargue el catálogo, lo vas a ver acá con su stock y sus vencimientos.'
            }
            action={
              canImport || canWrite ? (
                <div className="flex flex-col gap-2 sm:flex-row">
                  {canImport && (
                    <ButtonLink to="/app/imports" leftIcon={<FileSpreadsheet className="h-4 w-4" />}>
                      Importar Excel/CSV
                    </ButtonLink>
                  )}
                  {canWrite && (
                    <ButtonLink
                      to="/app/products/new"
                      variant={canImport ? 'outline' : 'default'}
                      leftIcon={<PackagePlus className="h-4 w-4" />}
                    >
                      Cargar el primer producto
                    </ButtonLink>
                  )}
                </div>
              ) : undefined
            }
          />
        </Card>
      ) : (
        <div className="flex flex-col gap-4">
          <Table
            columns={columns}
            data={productsQuery.data?.content}
            rowKey={(row) => row.id}
            loading={productsQuery.isPending}
            error={productsQuery.isError ? productsQuery.error : undefined}
            onRetry={() => void productsQuery.refetch()}
            rowSeverity={(row) => (row.stockStatus === 'OUT' ? 'crit' : row.stockStatus === 'LOW' ? 'warn' : 'none')}
            caption="Productos del catálogo con su stock en el alcance de sucursales elegido"
            empty={{
              icon: PackageSearch,
              title: 'No encontramos productos con esos filtros',
              description: 'Probá con otro texto, otra categoría u otro estado de stock.',
              action: (
                <Button
                  variant="outline"
                  onClick={() => {
                    setSearch('');
                    setCategoryId('');
                    setStockStatus('ALL');
                    const next = new URLSearchParams(searchParams);
                    next.delete('q');
                    next.delete('stockStatus');
                    next.delete('categoryId');
                    setSearchParams(next, { replace: true });
                  }}
                >
                  Limpiar los filtros
                </Button>
              ),
            }}
          />
          <Pagination {...pageInfo(productsQuery.data)} onPageChange={setPage} disabled={productsQuery.isFetching} />
        </div>
      )}
    </>
  );
}
