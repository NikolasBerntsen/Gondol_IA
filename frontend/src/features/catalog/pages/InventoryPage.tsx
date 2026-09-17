import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { FileSpreadsheet, Package, PackagePlus, PackageSearch, Pencil, ScanBarcode } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useAuth } from '@/auth/AuthContext';
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
import { STOCK_FILTERS, unitShort } from '../lib';
import type { ProductListItem, ProductStockFilter } from '../types';

const PAGE_SIZE = 20;

export default function InventoryPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const { hasRole } = useAuth();
  const { branches, isAll } = useBranch();
  const isAdmin = hasRole('TENANT_ADMIN');

  // El buscador de la barra superior navega a /app/inventory?q=<texto> (docs/frontend-guide.md §9).
  const urlQuery = searchParams.get('q') ?? '';
  const [search, setSearch] = useState(urlQuery);
  const [categoryId, setCategoryId] = useState<string>('');
  const [stockStatus, setStockStatus] = useState<ProductStockFilter>('ALL');
  const [page, setPage] = useState(0);

  useEffect(() => {
    setSearch(urlQuery);
  }, [urlQuery]);

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
      cell: (row) => (
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
      cell: (row) => <span className="tabular-nums">{formatMoney(row.salePrice)}</span>,
    },
    {
      id: 'actions',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      mobile: 'actions',
      cell: (row) => (
        <div className="flex items-center justify-end gap-1">
          <ButtonLink
            to={`/app/intake?productId=${row.id}${row.barcode ? `&barcode=${encodeURIComponent(row.barcode)}` : ''}`}
            variant="ghost"
            size="icon-sm"
            aria-label={`Cargar mercadería de ${row.name}`}
            title="Cargar mercadería"
          >
            <ScanBarcode className="h-4 w-4" aria-hidden="true" />
          </ButtonLink>
          <ButtonLink
            to={`/app/products/${row.id}/edit`}
            variant="ghost"
            size="icon-sm"
            aria-label={`Editar ${row.name}`}
            title="Editar producto"
          >
            <Pencil className="h-4 w-4" aria-hidden="true" />
          </ButtonLink>
        </div>
      ),
    },
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
          <div className="flex flex-wrap items-center gap-2">
            {isAdmin && (
              <ButtonLink to="/app/imports" variant="outline" leftIcon={<FileSpreadsheet className="h-4 w-4" />}>
                Importar Excel/CSV
              </ButtonLink>
            )}
            <ButtonLink to="/app/products/new" leftIcon={<PackagePlus className="h-4 w-4" />}>
              Nuevo producto
            </ButtonLink>
          </div>
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
              onChange={(event) => setCategoryId(event.target.value)}
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
            onChange={setStockStatus}
            options={STOCK_FILTERS.map((option) => ({ value: option.value, label: option.label }))}
          />
        </div>
      </PageHeader>

      {isEmptyCatalog ? (
        <Card padding="lg">
          <EmptyState
            icon={PackageSearch}
            title="Todavía no cargaste productos"
            description="Empezá por tu planilla de Excel o CSV, o cargá el primer producto a mano y después su mercadería."
            action={
              <div className="flex flex-col gap-2 sm:flex-row">
                {isAdmin && (
                  <ButtonLink to="/app/imports" leftIcon={<FileSpreadsheet className="h-4 w-4" />}>
                    Importar Excel/CSV
                  </ButtonLink>
                )}
                <ButtonLink
                  to="/app/products/new"
                  variant={isAdmin ? 'outline' : 'default'}
                  leftIcon={<PackagePlus className="h-4 w-4" />}
                >
                  Cargar el primer producto
                </ButtonLink>
              </div>
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
