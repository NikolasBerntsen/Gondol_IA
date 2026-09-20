import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { keepPreviousData } from '@tanstack/react-query';
import { CheckCircle2, Eraser, Receipt, ShoppingCart, TriangleAlert } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { useAccess } from '@/auth/useAccess';
import { BranchPicker } from '@/branches/BranchPicker';
import { useBranch, useBranchQueryKey, useWriteBranch } from '@/branches/BranchContext';
import { useBranchColumn } from '@/branches/branchColumn';
import { ExpiryChip, PriceTag, StatusPill } from '@/components/gondola';
import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  Table,
  Tabs,
  Truncate,
  pageInfo,
  type TableColumn,
} from '@/components/ui';
import { formatDateTime, formatMoney, formatNumber } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { movementKeys, salesApi, type ProductPick } from '../api';
import { DateRangeFilter, EMPTY_RANGE, type DateRange } from '../components/DateRangeFilter';
import { ProductPicker, stockInBranch } from '../components/ProductPicker';
import { SaleCart, lineTotal, linePrice, type CartLine } from '../components/SaleCart';
import { SaleDetailSheet } from '../components/SaleDetailSheet';
import { MOVEMENT_SOURCE_LABELS_EXT, SALE_SOURCE_FILTERS, type Sale, type SaleSummary } from '../types';

type TabValue = 'new' | 'history';

// ---------------------------------------------------------------------------
// Registrar una venta
// ---------------------------------------------------------------------------

function SaleResultPanel({ sale, onNew }: { sale: Sale; onNew: () => void }) {
  return (
    <Card padding="none" className="overflow-hidden">
      <CardHeader
        icon={CheckCircle2}
        title={`Venta ${sale.batchRef} registrada`}
        description={`${sale.branchName} · ${formatDateTime(sale.occurredAt)}`}
        actions={
          <Button onClick={onNew} leftIcon={<ShoppingCart className="h-4 w-4" />}>
            Nueva venta
          </Button>
        }
      />
      <ul className="divide-y divide-border">
        {sale.lines.map((line) => (
          <li key={line.productId} className="px-4 py-3">
            <div className="flex items-start justify-between gap-3">
              <Truncate as="p" className="min-w-0 text-base font-semibold text-foreground">
                {line.productName}
              </Truncate>
              <span className="shrink-0 text-base font-semibold tabular-nums text-foreground">
                {formatMoney(line.total)}
              </span>
            </div>
            <ul className="mt-1.5 flex flex-col gap-1">
              {line.lots.map((lot) => (
                <li key={lot.lotId} className="flex flex-wrap items-center justify-between gap-2">
                  <span className="flex flex-wrap items-center gap-1.5">
                    {lot.expiryDate ? (
                      <ExpiryChip expiry={lot.expiryDate} lot={lot.lotNumber} />
                    ) : (
                      <Badge tone="neutral" className="font-mono">
                        {lot.lotNumber ?? `Lote #${lot.lotId}`}
                      </Badge>
                    )}
                    {lot.discountPct ? <Badge tone="warn">−{formatNumber(lot.discountPct)}% liquidación</Badge> : null}
                  </span>
                  <span className="text-sm tabular-nums text-muted-foreground">
                    {formatNumber(lot.quantity)} u. × {formatMoney(lot.unitPrice)}
                  </span>
                </li>
              ))}
              {line.shortage > 0 ? (
                <li className="flex items-center gap-1.5 text-sm text-crit-ink">
                  <TriangleAlert className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                  {formatNumber(line.shortage)} u. sin stock disponible
                </li>
              ) : null}
            </ul>
          </li>
        ))}
      </ul>
      <div className="flex items-center justify-between gap-3 border-t border-border px-4 py-3">
        <span className="text-sm text-muted-foreground tabular-nums">
          {formatNumber(sale.units)} u. en {sale.lines.length}{' '}
          {sale.lines.length === 1 ? 'producto' : 'productos'}
        </span>
        <PriceTag price={sale.total} size="md" label="Total" />
      </div>
    </Card>
  );
}

function NewSaleTab() {
  const queryClient = useQueryClient();
  const writeBranch = useWriteBranch();
  const [lines, setLines] = useState<CartLine[]>([]);
  const [branchError, setBranchError] = useState<string>();
  const [result, setResult] = useState<Sale | null>(null);

  const branchId = writeBranch.branchId;

  const addProduct = (product: ProductPick) => {
    setResult(null);
    setLines((current) => {
      const existing = current.find((line) => line.product.id === product.id);
      if (existing) {
        return current.map((line) =>
          line.product.id === product.id ? { ...line, quantity: line.quantity + 1 } : line,
        );
      }
      return [
        ...current,
        { product, quantity: 1, unitPrice: null, stock: stockInBranch(product, branchId) },
      ];
    });
  };

  const register = useMutation({
    mutationFn: () =>
      salesApi.register({
        branchId,
        items: lines.map((line) => ({
          productId: line.product.id,
          quantity: line.quantity,
          unitPrice: line.unitPrice,
        })),
      }),
    onSuccess: (sale) => {
      toast.success(`Registraste la venta ${sale.batchRef}.`);
      setResult(sale);
      setLines([]);
      queryClient.invalidateQueries({ queryKey: movementKeys.sales });
      queryClient.invalidateQueries({ queryKey: movementKeys.movements });
      queryClient.invalidateQueries({ queryKey: movementKeys.expirations });
      queryClient.invalidateQueries({ queryKey: ['products'] });
    },
    onError: (error) => {
      setBranchError(undefined);
      toast.error(getErrorMessage(error, 'No pudimos registrar la venta.'));
    },
  });

  const total = lines.reduce((sum, line) => sum + lineTotal(line), 0);
  const units = lines.reduce((sum, line) => sum + line.quantity, 0);

  const submit = () => {
    if (!writeBranch.isReady) {
      setBranchError('Elegí una sucursal para registrar la venta.');
      return;
    }
    if (lines.length === 0) return;
    setBranchError(undefined);
    register.mutate();
  };

  if (result) {
    return <SaleResultPanel sale={result} onNew={() => setResult(null)} />;
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_22rem]">
      <div className="flex flex-col gap-4">
        <BranchPicker
          value={writeBranch.branchId}
          onChange={(value) => {
            writeBranch.setBranchId(value);
            setBranchError(undefined);
            setLines((current) =>
              current.map((line) => ({ ...line, stock: stockInBranch(line.product, value) })),
            );
          }}
          label="Sucursal de la venta"
          error={branchError}
        />

        <ProductPicker onPick={addProduct} branchId={branchId} disabled={register.isPending} />

        <Card padding="none" className="overflow-hidden">
          <CardHeader
            title="Carrito"
            description={lines.length === 0 ? 'Todavía no cargaste productos.' : undefined}
            actions={
              lines.length > 0 ? (
                <Button
                  variant="ghost"
                  size="sm"
                  leftIcon={<Eraser className="h-4 w-4" />}
                  disabled={register.isPending}
                  onClick={() => setLines([])}
                >
                  Vaciar
                </Button>
              ) : undefined
            }
          />
          {lines.length === 0 ? (
            <EmptyState
              icon={ShoppingCart}
              title="El carrito está vacío"
              description="Buscá o escaneá un producto para empezar la venta."
              size="sm"
            />
          ) : (
            <SaleCart
              lines={lines}
              disabled={register.isPending}
              onQuantityChange={(productId, quantity) =>
                setLines((current) =>
                  current.map((line) => (line.product.id === productId ? { ...line, quantity } : line)),
                )
              }
              onPriceChange={(productId, unitPrice) =>
                setLines((current) =>
                  current.map((line) => (line.product.id === productId ? { ...line, unitPrice } : line)),
                )
              }
              onRemove={(productId) =>
                setLines((current) => current.filter((line) => line.product.id !== productId))
              }
            />
          )}
        </Card>
      </div>

      <aside className="flex flex-col gap-3 lg:sticky lg:top-4 lg:self-start">
        <Card>
          <div className="flex flex-col items-center gap-3">
            <PriceTag price={total} size="lg" label="Total" />
            <p className="text-sm tabular-nums text-muted-foreground">
              {formatNumber(units)} u. en {lines.length} {lines.length === 1 ? 'producto' : 'productos'}
            </p>
          </div>
          <Button
            size="xl"
            fullWidth
            className="mt-4"
            loading={register.isPending}
            disabled={lines.length === 0}
            onClick={submit}
          >
            Registrar la venta
          </Button>
          <p className="mt-2 text-center text-xs text-muted-foreground">
            El stock sale de los lotes según la rotación del comercio: liquidaciones primero y después
            FIFO o FEFO.
          </p>
        </Card>

        {lines.some((line) => linePrice(line) !== line.product.salePrice) ? (
          <p className="text-sm text-muted-foreground">
            Cambiaste el precio de alguna línea: se va a cobrar ese precio, con el descuento del lote si
            lo tiene.
          </p>
        ) : null}
      </aside>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Historial
// ---------------------------------------------------------------------------

function HistoryTab() {
  const { isAll } = useBranch();
  const { canOpen } = useAccess();
  const [range, setRange] = useState<DateRange>(EMPTY_RANGE);
  const [source, setSource] = useState('');
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [openBatchRef, setOpenBatchRef] = useState<string | null>(null);
  const q = useDebounce(search.trim(), 300);

  const params = {
    from: range.from || undefined,
    to: range.to || undefined,
    source: (source || undefined) as SaleSummary['source'] | undefined,
    q: q || undefined,
    page,
    size: 20,
  };
  const queryKey = useBranchQueryKey('sales', 'list', params);
  const sales = useQuery({
    queryKey,
    queryFn: () => salesApi.list(params),
    placeholderData: keepPreviousData,
  });

  const branchCol = useBranchColumn<SaleSummary>({ mobile: 'subtitle' });

  const columns: Array<TableColumn<SaleSummary> | null> = [
    {
      id: 'batchRef',
      header: 'Venta',
      mobile: 'title',
      cell: (row) => (
        <span className="flex flex-col gap-0.5">
          <span className="font-mono text-sm font-semibold text-foreground">{row.batchRef}</span>
          <span className="text-xs tabular-nums text-muted-foreground">{formatDateTime(row.occurredAt)}</span>
        </span>
      ),
    },
    branchCol,
    {
      id: 'source',
      header: 'Origen',
      mobile: 'field',
      cell: (row) =>
        // El ticket del POS se abre solo si el rol usa el POS (el jefe ve el código sin link).
        row.ticketCode && row.posSaleId && canOpen(`/app/pos/sales/${row.posSaleId}/ticket`) ? (
          <Link
            to={`/app/pos/sales/${row.posSaleId}/ticket`}
            onClick={(event) => event.stopPropagation()}
            className="inline-flex items-center gap-1.5 font-mono text-sm text-primary underline underline-offset-2"
          >
            <Receipt className="h-3.5 w-3.5" aria-hidden="true" />
            {row.ticketCode}
          </Link>
        ) : row.ticketCode ? (
          <Badge tone="neutral" className="font-mono">
            {row.ticketCode}
          </Badge>
        ) : (
          <Badge tone="neutral">{row.sourceLabel}</Badge>
        ),
    },
    {
      id: 'items',
      header: 'Productos',
      align: 'right',
      hideBelow: 'lg',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.itemsCount)}</span>,
    },
    {
      id: 'units',
      header: 'Unidades',
      align: 'right',
      mobile: 'field',
      cell: (row) => <span className="tabular-nums">{formatNumber(row.units)}</span>,
    },
    {
      id: 'user',
      header: 'Registró',
      hideBelow: 'xl',
      mobile: 'field',
      cell: (row) => <span className="text-muted-foreground">{row.userName ?? '—'}</span>,
    },
    {
      id: 'status',
      header: 'Estado',
      mobile: 'aside',
      cell: (row) =>
        row.voided ? (
          <StatusPill tone="crit" solid>
            Anulada
          </StatusPill>
        ) : (
          <StatusPill tone="ok">Vigente</StatusPill>
        ),
    },
    {
      id: 'total',
      header: 'Total',
      align: 'right',
      mobile: 'aside',
      cell: (row) => (
        <span className="font-semibold tabular-nums text-foreground">{formatMoney(row.total)}</span>
      ),
    },
  ];

  const resetPage = <T,>(setter: (value: T) => void) => (value: T) => {
    setter(value);
    setPage(0);
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <SearchInput
          value={search}
          onValueChange={resetPage(setSearch)}
          label="Buscar por venta, producto o código"
          placeholder="Buscá por venta, producto o código…"
          inputSize="sm"
          containerClassName="md:max-w-xs"
        />
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <Select
            selectSize="sm"
            aria-label="Filtrar por origen"
            value={source}
            placeholder="Todos los orígenes"
            options={SALE_SOURCE_FILTERS.map((value) => ({
              value,
              label: MOVEMENT_SOURCE_LABELS_EXT[value],
            }))}
            onChange={(event) => resetPage(setSource)(event.target.value)}
          />
          <DateRangeFilter value={range} onChange={resetPage(setRange)} label="Rango de fechas" />
        </div>
      </div>

      <Table
        columns={columns}
        data={sales.data?.content}
        rowKey={(row) => row.batchRef}
        loading={sales.isPending}
        error={sales.isError ? sales.error : undefined}
        onRetry={() => void sales.refetch()}
        onRowClick={(row) => setOpenBatchRef(row.batchRef)}
        rowSeverity={(row) => (row.voided ? 'crit' : undefined)}
        caption="Historial de ventas de todas las fuentes, con las anulaciones ya descontadas"
        empty={{
          icon: Receipt,
          title: 'Todavía no hay ventas',
          description: isAll
            ? 'Cuando se registren ventas (manuales, del POS o importadas) las vas a ver acá.'
            : 'No hay ventas en esta sucursal con los filtros elegidos.',
        }}
        footer={
          (sales.data?.totalPages ?? 0) > 1 ? (
            <Pagination {...pageInfo(sales.data)} onPageChange={setPage} disabled={sales.isFetching} />
          ) : undefined
        }
      />

      <SaleDetailSheet batchRef={openBatchRef} onClose={() => setOpenBatchRef(null)} />
    </div>
  );
}

// ---------------------------------------------------------------------------

export default function SalesPage() {
  const { can } = useAccess();
  // El jefe ve el historial pero no registra ventas (SPEC §3.3): sin la pestaña "Registrar venta".
  const canRegister = can('sales.write');
  const [tab, setTab] = useState<TabValue>(canRegister ? 'new' : 'history');

  return (
    <>
      <PageHeader
        title="Ventas"
        icon={ShoppingCart}
        description={
          canRegister
            ? 'Registrá ventas manuales y revisá el historial de todas las fuentes.'
            : 'Historial de ventas de todas las fuentes, con el detalle de cada una.'
        }
      >
        {canRegister ? (
          <Tabs<TabValue>
            value={tab}
            onChange={setTab}
            ariaLabel="Secciones de ventas"
            tabs={[
              { value: 'new', label: 'Registrar venta', icon: ShoppingCart },
              { value: 'history', label: 'Historial', icon: Receipt },
            ]}
          />
        ) : null}
      </PageHeader>

      {canRegister && tab === 'new' ? <NewSaleTab /> : <HistoryTab />}
    </>
  );
}
