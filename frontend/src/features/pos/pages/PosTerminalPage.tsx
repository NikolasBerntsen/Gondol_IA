import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import {
  Camera,
  DoorClosed,
  HandCoins,
  PlusCircle,
  ScanBarcode,
  ShoppingBasket,
  Trash2,
  X,
} from 'lucide-react';
import { getErrorMessage, isApiError } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { useBranch, useBranchQueryKey } from '@/branches/BranchContext';
import { ExpiryChip, PriceTag, StatusPill } from '@/components/gondola';
import { BarcodeScanner } from '@/components/scanner';
import {
  Alert,
  Button,
  EmptyState,
  ErrorState,
  Kbd,
  Modal,
  QtyStepper,
  Skeleton,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatMoney, formatTime } from '@/lib/format';
import { useDebounce } from '@/lib/useDebounce';
import { posApi, posKeys } from '../api';
import { CashMovementDialog } from '../components/CashMovementDialog';
import { CloseSessionDialog } from '../components/CloseSessionDialog';
import { OpenSessionPanel } from '../components/OpenSessionPanel';
import { PaymentSheet } from '../components/PaymentSheet';
import { PosNotice, type PosNoticeData } from '../components/PosNotice';
import { PosProductTile } from '../components/PosProductTile';
import { subtractMoney, sumMoney } from '../money';
import type {
  CashMovementType,
  PaymentMethod,
  PosProduct,
  PosSale,
  PosSessionReport,
  PosShortageDetail,
} from '../types';

/** Línea del carrito: guarda una copia del producto para no depender de la búsqueda en curso. */
interface CartLine {
  product: PosProduct;
  quantity: number;
}

function shortageDetails(error: unknown): PosShortageDetail[] {
  if (!isApiError(error, 'INSUFFICIENT_STOCK')) return [];
  const body = error.response as unknown as { details?: PosShortageDetail[] } | undefined;
  return body?.details ?? [];
}

/**
 * Mostrador del POS GondolIA (SPEC §15.3). Se renderiza en la variante **compacta** del AppShell:
 * el shell no pone padding ni ancho máximo, así que el layout de dos paneles y el scroll son de esta página.
 */
export default function PosTerminalPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { me } = useAuth();
  const { branchName } = useBranch();

  const [cart, setCart] = useState<CartLine[]>([]);
  const [query, setQuery] = useState('');
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [notice, setNotice] = useState<PosNoticeData | null>(null);
  const [selected, setSelected] = useState<number | null>(null);
  const [payOpen, setPayOpen] = useState(false);
  const [scannerOpen, setScannerOpen] = useState(false);
  const [cashMode, setCashMode] = useState<CashMovementType | null>(null);
  const [closeOpen, setCloseOpen] = useState(false);
  const [lastSale, setLastSale] = useState<PosSale | null>(null);
  const [allowShortage, setAllowShortage] = useState(false);
  const [openError, setOpenError] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);

  const debouncedQuery = useDebounce(query, 250);

  // ----------------------------------------------------------------- turno
  const sessionQuery = useQuery({
    queryKey: posKeys.currentSession(),
    queryFn: posApi.currentSession,
  });
  const session: PosSessionReport | null = sessionQuery.data ?? null;
  const hasSession = !!session;

  const registersQuery = useQuery({
    queryKey: useBranchQueryKey(...posKeys.registers(false)),
    queryFn: () => posApi.registers(false),
    enabled: !sessionQuery.isPending && !hasSession,
  });

  const openSession = useMutation({
    mutationFn: posApi.openSession,
    meta: { errorToast: false },
    onSuccess: (report) => {
      setOpenError(null);
      queryClient.setQueryData(posKeys.currentSession(), report);
      queryClient.invalidateQueries({ queryKey: ['pos'] });
      toast.success('Abriste la caja.', { description: `${report.registerName} · ${report.branchName}` });
      requestAnimationFrame(() => searchRef.current?.focus());
    },
    onError: (error) => setOpenError(getErrorMessage(error)),
  });

  const cashMovement = useMutation({
    mutationFn: (values: { amount: number; reason: string }) => {
      if (!session) throw new Error('sin turno');
      return posApi.cashMovement(session.id, { type: cashMode ?? 'CASH_OUT', amount: values.amount, reason: values.reason });
    },
    onSuccess: (report) => {
      const wasOut = cashMode === 'CASH_OUT';
      setCashMode(null);
      queryClient.setQueryData(posKeys.currentSession(), report);
      queryClient.invalidateQueries({ queryKey: ['pos', 'sessions'] });
      toast.success(wasOut ? 'Registraste el retiro.' : 'Registraste el ingreso.');
    },
  });

  const closeSession = useMutation({
    mutationFn: (values: { countedCash: number; note: string | null }) => {
      if (!session) throw new Error('sin turno');
      return posApi.closeSession(session.id, values);
    },
    onSuccess: (report) => {
      setCloseOpen(false);
      setCart([]);
      setLastSale(null);
      queryClient.setQueryData(posKeys.currentSession(), null);
      queryClient.invalidateQueries({ queryKey: ['pos'] });
      const difference = report.difference ?? 0;
      toast.success('Cerraste la caja.', {
        description:
          difference === 0
            ? 'La caja cerró justa. El reporte Z quedó en Mis turnos.'
            : `Diferencia de ${formatMoney(difference, { decimals: 2 })}. El reporte Z quedó en Mis turnos.`,
      });
      navigate('/app/pos/sessions');
    },
  });

  // ----------------------------------------------------------------- catálogo
  const branchId = session?.branchId ?? null;

  const searchQuery = useQuery({
    queryKey: useBranchQueryKey(...posKeys.search({ q: debouncedQuery.trim(), categoryId })),
    queryFn: () => posApi.search({ q: debouncedQuery.trim() || undefined, categoryId, branchId, limit: 20 }),
    enabled: hasSession,
  });

  const categoriesQuery = useQuery({
    queryKey: useBranchQueryKey(...posKeys.categories()),
    queryFn: () => posApi.categories(branchId),
    enabled: hasSession,
  });

  // ----------------------------------------------------------------- carrito
  const lines = useMemo(
    () =>
      cart.map((line) => {
        const unitPrice = line.product.nextLot?.unitPrice ?? line.product.listPrice;
        const pct = line.product.nextLot?.discountPct ?? 0;
        return {
          ...line,
          unitPrice,
          pct,
          listTotal: Math.round(line.product.listPrice * line.quantity * 100) / 100,
          lineTotal: Math.round(unitPrice * line.quantity * 100) / 100,
        };
      }),
    [cart],
  );

  const subtotal = sumMoney(lines.map((line) => line.listTotal));
  const total = sumMoney(lines.map((line) => line.lineTotal));
  const discounts = subtractMoney(subtotal, total);
  const units = lines.reduce((acc, line) => acc + line.quantity, 0);

  const addProduct = useCallback(
    (product: PosProduct, options?: { force?: boolean }) => {
      setQuery('');
      if (product.hasRecalledStock) {
        setNotice({ kind: 'recall', productName: product.name });
        return;
      }
      const existing = cart.find((line) => line.product.productId === product.productId);
      const next = (existing?.quantity ?? 0) + 1;

      if (!options?.force) {
        if (product.outOfStock) {
          setNotice({ kind: 'out', productName: product.name, branchName: product.branchName });
          return;
        }
        if (next > product.sellableStock) {
          setNotice({ kind: 'limit', productName: product.name, available: product.sellableStock });
          return;
        }
      } else {
        setAllowShortage(true);
      }

      setNotice(null);
      setSelected(product.productId);
      setCart((prev) => {
        const found = prev.find((line) => line.product.productId === product.productId);
        return found
          ? prev.map((line) =>
              line.product.productId === product.productId
                ? { product, quantity: line.quantity + 1 }
                : line,
            )
          : [...prev, { product, quantity: 1 }];
      });
    },
    [cart],
  );

  const removeLine = useCallback((productId: number) => {
    setCart((prev) => prev.filter((line) => line.product.productId !== productId));
    setSelected(null);
  }, []);

  const resetSale = useCallback(() => {
    setCart([]);
    setSelected(null);
    setNotice(null);
    setAllowShortage(false);
    setQuery('');
  }, []);

  // Lectura por código exacto (lector USB con Enter, o cámara).
  const lookup = useMutation({
    mutationFn: (code: string) => posApi.lookup(code, branchId),
    meta: { errorToast: false },
    onSuccess: (product) => addProduct(product),
    onError: (error, code) => {
      if (isApiError(error, 'NOT_FOUND')) setNotice({ kind: 'notfound', query: code });
      else toast.error(getErrorMessage(error));
    },
  });

  // ----------------------------------------------------------------- cobro
  const createSale = useMutation({
    mutationFn: (payments: Array<{ method: PaymentMethod; amount: number }>) => {
      if (!session) throw new Error('sin turno');
      return posApi.createSale({
        sessionId: session.id,
        items: cart.map((line) => ({ productId: line.product.productId, quantity: line.quantity })),
        payments,
        allowShortage,
      });
    },
    meta: { errorToast: false },
    onSuccess: (sale) => {
      setLastSale(sale);
      queryClient.invalidateQueries({ queryKey: ['pos'] });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      queryClient.invalidateQueries({ queryKey: ['expirations'] });
      queryClient.invalidateQueries({ queryKey: ['movements'] });
      queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
    onError: (error) => {
      const details = shortageDetails(error);
      if (details.length) {
        const first = details[0];
        setPayOpen(false);
        setNotice({ kind: 'limit', productName: first.productName, available: first.available });
        return;
      }
      toast.error(getErrorMessage(error));
    },
  });

  const newSale = useCallback(() => {
    setPayOpen(false);
    setLastSale(null);
    resetSale();
    requestAnimationFrame(() => searchRef.current?.focus());
  }, [resetSale]);

  const printTicket = useCallback(
    (sale: PosSale) => {
      window.open(`/app/pos/sales/${sale.id}/ticket`, '_blank', 'noopener');
    },
    [],
  );

  // ----------------------------------------------------------------- atajos
  useEffect(() => {
    if (!hasSession) return undefined;
    const onKey = (event: KeyboardEvent) => {
      if (cashMode || closeOpen || scannerOpen) return;
      if (event.key === 'F2') {
        event.preventDefault();
        if (!payOpen) {
          searchRef.current?.focus();
          searchRef.current?.select();
        }
      } else if (event.key === 'F4') {
        event.preventDefault();
        if (cart.length && !payOpen) setPayOpen(true);
      } else if (event.key === 'F8') {
        event.preventDefault();
        if (payOpen || !cart.length) return;
        const target =
          selected !== null && cart.some((line) => line.product.productId === selected)
            ? selected
            : cart[cart.length - 1].product.productId;
        const name = cart.find((line) => line.product.productId === target)?.product.name ?? 'el ítem';
        removeLine(target);
        toast(`Quitaste ${name}.`);
      } else if (event.key === 'Escape' && !payOpen) {
        if (query) setQuery('');
        else setNotice(null);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [cart, payOpen, selected, query, cashMode, closeOpen, scannerOpen, hasSession, removeLine]);

  useEffect(() => {
    if (hasSession) searchRef.current?.focus({ preventScroll: true });
  }, [hasSession]);

  // ----------------------------------------------------------------- render
  if (sessionQuery.isPending) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <Skeleton className="mx-auto h-[420px] w-full max-w-xl rounded-panel" />
      </div>
    );
  }

  if (sessionQuery.isError) {
    return (
      <div className="px-4 py-6 sm:px-6">
        <ErrorState
          error={sessionQuery.error}
          onRetry={() => void sessionQuery.refetch()}
          title="No pudimos traer tu turno de caja"
        />
      </div>
    );
  }

  if (!session) {
    return (
      <div className="px-4 py-6 sm:px-6 lg:py-10">
        <OpenSessionPanel
          registers={registersQuery.data}
          loading={registersQuery.isPending}
          error={registersQuery.isError ? registersQuery.error : null}
          onRetry={() => void registersQuery.refetch()}
          onOpen={(values) => openSession.mutate(values)}
          pending={openSession.isPending}
          openError={openError}
        />
      </div>
    );
  }

  const results = searchQuery.data ?? [];
  const categories = categoriesQuery.data ?? [];

  return (
    <div className="flex min-h-full flex-col lg:h-full">
      {/* Franja de caja */}
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-border bg-card px-4 py-2.5 sm:px-5">
        <div className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1">
          <h1 className="font-display text-lg font-semibold leading-7 tracking-[-0.01em] text-foreground">
            Punto de venta
          </h1>
          <span className="text-sm text-muted-foreground">
            {session.branchName ?? branchName(session.branchId)} · {session.registerName} ·{' '}
            {session.openedByName ?? me?.fullName}
          </span>
          <StatusPill tone="ok">Turno abierto {formatTime(session.openedAt)}</StatusPill>
        </div>
        <div className="flex-1" />
        <div className="hidden items-center gap-3 text-xs text-muted-foreground xl:flex" aria-label="Atajos de teclado">
          <span className="flex items-center gap-1">
            <Kbd>F2</Kbd> Buscar
          </span>
          <span className="flex items-center gap-1">
            <Kbd>F4</Kbd> Cobrar
          </span>
          <span className="flex items-center gap-1">
            <Kbd>F8</Kbd> Quitar ítem
          </span>
          <span className="flex items-center gap-1">
            <Kbd>Esc</Kbd> Limpiar
          </span>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCashMode('CASH_IN')}
            leftIcon={<PlusCircle aria-hidden="true" />}
          >
            Ingreso
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCashMode('CASH_OUT')}
            leftIcon={<HandCoins aria-hidden="true" />}
          >
            Retiro
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setCloseOpen(true)}
            leftIcon={<DoorClosed aria-hidden="true" />}
          >
            Cerrar caja
          </Button>
        </div>
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-1 lg:grid-cols-[minmax(0,1fr)_400px] xl:grid-cols-[minmax(0,1fr)_440px]">
        {/* Izquierda: escanear / buscar */}
        <section aria-label="Buscar productos" className="flex min-h-0 min-w-0 flex-col">
          <div className="flex flex-col gap-3 border-b border-border bg-background px-4 pb-3 pt-4 sm:px-5">
            <form
              className="relative"
              onSubmit={(event) => {
                event.preventDefault();
                const text = query.trim();
                if (!text) return;
                const exact = results.find((item) => item.barcode === text);
                if (exact) {
                  addProduct(exact);
                } else if (/^[A-Za-z0-9]{6,}$/.test(text)) {
                  lookup.mutate(text);
                } else if (results.length) {
                  addProduct(results[0]);
                } else {
                  setNotice({ kind: 'notfound', query: text });
                }
              }}
            >
              <ScanBarcode
                className="pointer-events-none absolute left-3.5 top-1/2 h-5 w-5 -translate-y-1/2 text-primary"
                aria-hidden="true"
              />
              <label htmlFor="pos-search" className="sr-only">
                Escaneá o buscá un producto
              </label>
              <input
                id="pos-search"
                ref={searchRef}
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="Escaneá o buscá (F2)"
                autoComplete="off"
                enterKeyHint="search"
                className="h-14 w-full rounded-control border-2 border-primary/50 bg-card pl-12 pr-[104px] text-md font-medium text-foreground placeholder:font-normal placeholder:text-muted-foreground focus-visible:border-primary focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-primary/15"
              />
              <div className="absolute right-2 top-1/2 flex -translate-y-1/2 items-center gap-1">
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  aria-label="Escanear con la cámara"
                  onClick={() => setScannerOpen(true)}
                >
                  <Camera aria-hidden="true" />
                </Button>
                <Kbd>F2</Kbd>
              </div>
            </form>

            {categories.length ? (
              <div className="gd-scroll -mx-4 overflow-x-auto px-4 sm:-mx-5 sm:px-5">
                <div className="flex w-max gap-1.5" role="group" aria-label="Filtrar por categoría">
                  {[{ id: null, name: 'Todos', productCount: 0 }, ...categories].map((category) => {
                    const active = categoryId === category.id;
                    return (
                      <button
                        key={category.id ?? 'all'}
                        type="button"
                        aria-pressed={active}
                        onClick={() => setCategoryId(category.id)}
                        className={cn(
                          'h-8 whitespace-nowrap rounded-control border px-3 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                          active
                            ? 'border-foreground bg-foreground text-background'
                            : 'border-input bg-card text-foreground hover:bg-muted',
                        )}
                      >
                        {category.name}
                      </button>
                    );
                  })}
                </div>
              </div>
            ) : null}

            {notice ? (
              <PosNotice
                notice={notice}
                onClose={() => setNotice(null)}
                onSellAnyway={
                  notice.kind === 'out' || notice.kind === 'limit'
                    ? () => {
                        const target = results.find((item) => item.name === notice.productName);
                        if (target) addProduct(target, { force: true });
                        else setAllowShortage(true);
                        setNotice(null);
                      }
                    : undefined
                }
              />
            ) : null}

            {allowShortage ? (
              <Alert tone="warn" title="Venta con faltante habilitada">
                Vas a vender más unidades de las que figuran en el sistema. Queda registrado como faltante para que el
                encargado ajuste el stock.
              </Alert>
            ) : null}
          </div>

          <div className="gd-scroll min-h-0 flex-1 px-4 py-4 sm:px-5 lg:overflow-y-auto">
            {searchQuery.isPending ? (
              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-[repeat(auto-fill,minmax(168px,1fr))]">
                {Array.from({ length: 8 }, (_, index) => (
                  <Skeleton key={index} className="h-[124px] rounded-panel" />
                ))}
              </div>
            ) : searchQuery.isError ? (
              <ErrorState
                error={searchQuery.error}
                onRetry={() => void searchQuery.refetch()}
                title="No pudimos traer los productos"
              />
            ) : results.length ? (
              <div className="grid grid-cols-2 gap-2.5 sm:grid-cols-[repeat(auto-fill,minmax(168px,1fr))]">
                {results.map((product) => (
                  <PosProductTile
                    key={product.productId}
                    product={product}
                    inCart={cart.find((line) => line.product.productId === product.productId)?.quantity ?? 0}
                    onAdd={() => addProduct(product)}
                  />
                ))}
              </div>
            ) : (
              <EmptyState
                icon={ScanBarcode}
                title={query.trim() ? 'Sin resultados' : 'Escaneá el primer producto'}
                description={
                  query.trim()
                    ? 'Probá con otra palabra, la marca o el código de barras completo.'
                    : 'Pasá el lector por el código o buscá por nombre. También podés tocar un producto de la lista.'
                }
              />
            )}
          </div>
        </section>

        {/* Derecha: ticket en curso */}
        <aside
          aria-label="Venta en curso"
          className="flex min-h-0 flex-col border-t border-border bg-card lg:border-l lg:border-t-0"
        >
          <div className="flex items-center justify-between gap-3 border-b border-border px-4 py-3 sm:px-5">
            <div>
              <h2 className="text-md font-semibold leading-6">Venta en curso</h2>
              <p className="text-xs text-muted-foreground">
                {units} {units === 1 ? 'unidad' : 'unidades'}
              </p>
            </div>
            {cart.length ? (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  resetSale();
                  toast('Cancelaste la venta.', { description: 'Se vació el carrito.' });
                  requestAnimationFrame(() => searchRef.current?.focus());
                }}
                leftIcon={<Trash2 aria-hidden="true" />}
              >
                Cancelar venta
              </Button>
            ) : null}
          </div>

          <div className="gd-scroll min-h-0 flex-1 lg:overflow-y-auto">
            {lines.length ? (
              <ul className="divide-y divide-border">
                {lines.map((line) => {
                  const lot = line.product.nextLot;
                  const isSelected = selected === line.product.productId;
                  return (
                    <li
                      key={line.product.productId}
                      className={cn(
                        'px-4 py-3 sm:px-5',
                        isSelected ? 'gd-stripe-info bg-info-soft/40' : 'gd-stripe-none',
                      )}
                      onClick={() => setSelected(line.product.productId)}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div className="min-w-0">
                          <div className="text-base font-semibold leading-5 text-foreground">
                            {line.product.name}
                          </div>
                          <div className="text-xs text-muted-foreground">{line.product.brand ?? ''}</div>
                        </div>
                        <Button
                          variant="ghost"
                          size="icon-sm"
                          className="-mr-1.5 -mt-1 shrink-0 text-muted-foreground"
                          aria-label={`Quitar ${line.product.name}`}
                          onClick={() => removeLine(line.product.productId)}
                        >
                          <X aria-hidden="true" />
                        </Button>
                      </div>

                      {lot?.expiryDate || line.pct > 0 ? (
                        <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
                          {lot?.expiryDate ? <ExpiryChip expiry={lot.expiryDate} lot={lot.lotNumber} /> : null}
                          {line.pct > 0 ? (
                            <span className="inline-flex h-[22px] items-center rounded-tag bg-crit px-1.5 font-mono text-[11px] font-semibold uppercase text-crit-foreground">
                              -{Math.round(line.pct)}% VTO CERCANO
                            </span>
                          ) : null}
                        </div>
                      ) : null}

                      <div className="mt-2 flex items-center justify-between gap-3">
                        <QtyStepper
                          id={`qty-${line.product.productId}`}
                          label={`Cantidad de ${line.product.name}`}
                          size="sm"
                          value={line.quantity}
                          min={1}
                          max={allowShortage ? 9999 : Math.max(line.product.sellableStock, line.quantity)}
                          onChange={(value) =>
                            setCart((prev) =>
                              prev.map((item) =>
                                item.product.productId === line.product.productId
                                  ? { ...item, quantity: value }
                                  : item,
                              ),
                            )
                          }
                        />
                        <div className="text-right">
                          <div className="text-xs tabular-nums text-muted-foreground">
                            {line.pct > 0 ? (
                              <span className="mr-1 line-through">
                                {formatMoney(line.product.listPrice, { decimals: 2 })}
                              </span>
                            ) : null}
                            {formatMoney(line.unitPrice, { decimals: 2 })} c/u
                          </div>
                          <div className="text-md font-semibold tabular-nums text-foreground">
                            {formatMoney(line.lineTotal, { decimals: 2 })}
                          </div>
                        </div>
                      </div>
                    </li>
                  );
                })}
              </ul>
            ) : (
              <div className="px-4 py-6 sm:px-5">
                <EmptyState
                  icon={ShoppingBasket}
                  title="Escaneá el primer producto"
                  description="El ticket se arma acá. También podés tocar un producto de la lista."
                />
              </div>
            )}
          </div>

          <div id="pos-totales" className="border-t border-border bg-card px-4 pb-4 pt-3 sm:px-5">
            <dl className="grid grid-cols-2 gap-y-0.5 text-base">
              <dt className="text-muted-foreground">Subtotal · {units} u.</dt>
              <dd className="text-right tabular-nums">{formatMoney(subtotal, { decimals: 2 })}</dd>
              <dt className="text-muted-foreground">Descuentos por vencimiento</dt>
              <dd className="text-right tabular-nums text-crit-ink">
                {discounts > 0 ? `−${formatMoney(discounts, { decimals: 2 })}` : formatMoney(0, { decimals: 2 })}
              </dd>
            </dl>
            <div className="mt-3 flex items-end justify-between gap-3">
              <PriceTag size="lg" price={total} label="Total" className="hidden sm:inline-flex" />
              <PriceTag size="md" price={total} label="Total" className="sm:hidden" />
              <span className="pb-1 text-right text-xs text-muted-foreground">
                IVA incluido
                <br />
                Ticket no fiscal
              </span>
            </div>
            <Button
              size="xl"
              className="mt-3 w-full justify-between"
              disabled={!cart.length}
              onClick={() => setPayOpen(true)}
            >
              <span>Cobrar</span>
              <Kbd className="border-primary-foreground/30 bg-transparent text-primary-foreground">F4</Kbd>
            </Button>
          </div>
        </aside>
      </div>

      {/* Móvil: total y cobro al alcance del pulgar */}
      <div
        className="sticky bottom-0 z-10 flex items-center gap-3 border-t-2 border-border bg-card px-4 pt-3 lg:hidden"
        style={{ paddingBottom: 'calc(12px + env(safe-area-inset-bottom, 0px))' }}
      >
        <button
          type="button"
          onClick={() => document.getElementById('pos-totales')?.scrollIntoView({ block: 'end' })}
          className="min-w-0 flex-1 rounded-control text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <span className="block text-xs text-muted-foreground">{units} u. · ver ticket</span>
          <span className="block font-display text-lg font-semibold leading-6 tabular-nums text-foreground">
            {formatMoney(total, { decimals: 2 })}
          </span>
        </button>
        <Button size="lg" disabled={!cart.length} onClick={() => setPayOpen(true)}>
          Cobrar
        </Button>
      </div>

      <PaymentSheet
        open={payOpen}
        onOpenChange={(next) => {
          if (!next && lastSale) newSale();
          else setPayOpen(next);
        }}
        total={total}
        units={units}
        onConfirm={(payments) => createSale.mutate(payments)}
        pending={createSale.isPending}
        sale={lastSale}
        onNewSale={newSale}
        onPrint={printTicket}
        storeName={me?.tenant?.name ?? 'GondolIA'}
        // El CUIT no viene en `/auth/me`: la vista previa lo omite y la página de impresión
        // lo trae de `GET /sales/{id}/ticket`.
        taxId={null}
      />

      <Modal
        open={scannerOpen}
        onClose={() => {
          setScannerOpen(false);
          requestAnimationFrame(() => searchRef.current?.focus());
        }}
        title="Escanear con la cámara"
        description="Apuntá al código de barras del producto."
        size="md"
      >
        <BarcodeScanner
          active={scannerOpen}
          onDetected={(code) => {
            setScannerOpen(false);
            lookup.mutate(code);
            requestAnimationFrame(() => searchRef.current?.focus());
          }}
          hint="También podés pasar el lector USB por el código."
          fallback={
            <Button
              variant="outline"
              onClick={() => {
                setScannerOpen(false);
                requestAnimationFrame(() => searchRef.current?.focus());
              }}
            >
              Ingresar el código a mano
            </Button>
          }
        />
      </Modal>

      {cashMode ? (
        <CashMovementDialog
          open
          type={cashMode}
          onClose={() => setCashMode(null)}
          onSubmit={(values) => cashMovement.mutate(values)}
          pending={cashMovement.isPending}
        />
      ) : null}

      <CloseSessionDialog
        open={closeOpen}
        session={session}
        onClose={() => setCloseOpen(false)}
        onSubmit={(values) => closeSession.mutate(values)}
        pending={closeSession.isPending}
      />
    </div>
  );
}
