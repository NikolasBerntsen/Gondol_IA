import { useQuery } from '@tanstack/react-query';
import { PackageSearch, ScanLine, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage } from '@/api/client';
import { BarcodeDigits, StockStatusPill } from '@/components/gondola';
import { BarcodeScanner } from '@/components/scanner';
import { Button, Card, SearchInput, SecureContextWarning, Spinner } from '@/components/ui';
import { isCameraSupported } from '@/lib/secureContext';
import { useDebounce } from '@/lib/useDebounce';
import { formatMoney } from '@/lib/format';
import { productPickApi, type ProductPick } from '../api';

export interface ProductPickerProps {
  /** Se llama con el producto elegido (por búsqueda, por escáner o por lector USB). */
  onPick: (product: ProductPick) => void;
  /** Sucursal del alcance de la venta: se usa para mostrar el stock de esa sucursal. */
  branchId: number | null;
  disabled?: boolean;
}

/** Stock vendible del producto en la sucursal elegida (o el total del alcance si es consolidado). */
export function stockInBranch(product: ProductPick, branchId: number | null): number {
  if (branchId == null) return product.sellableStock;
  const row = product.stockByBranch?.find((b) => b.branchId === branchId);
  return row ? row.sellableStock : (product.stockByBranch ? 0 : product.sellableStock);
}

/**
 * Buscador de productos para el carrito de la venta manual: texto, cámara y lector USB de caja.
 * Siempre deja el camino sin cámara (SPEC §9, guía del frontend §8.3).
 */
export function ProductPicker({ onPick, branchId, disabled }: ProductPickerProps) {
  const [search, setSearch] = useState('');
  const [scanning, setScanning] = useState(false);
  const q = useDebounce(search.trim(), 300);
  const inputRef = useRef<HTMLInputElement>(null);

  const results = useQuery({
    queryKey: ['movements', 'product-search', q],
    queryFn: () => productPickApi.search(q),
    enabled: q.length >= 2,
  });

  const handleCode = (code: string) => {
    setSearch(code);
    inputRef.current?.focus();
  };

  // El lector USB de caja escribe en el buscador: alcanza con dejarlo enfocado.
  useEffect(() => {
    if (!disabled) inputRef.current?.focus();
  }, [disabled]);

  const items = results.data?.content ?? [];
  const showResults = q.length >= 2;

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <SearchInput
          ref={inputRef}
          value={search}
          onValueChange={setSearch}
          label="Buscar un producto por nombre, marca o código"
          placeholder="Buscá por nombre, marca o código…"
          disabled={disabled}
          containerClassName="flex-1"
        />
        <Button
          variant={scanning ? 'secondary' : 'outline'}
          size="icon"
          aria-label={scanning ? 'Cerrar el escáner' : 'Escanear un código de barras'}
          aria-pressed={scanning}
          disabled={disabled}
          onClick={() => setScanning((open) => !open)}
        >
          {scanning ? <X className="h-4 w-4" aria-hidden="true" /> : <ScanLine className="h-4 w-4" aria-hidden="true" />}
        </Button>
      </div>

      {scanning ? (
        <div className="flex flex-col gap-2">
          <SecureContextWarning />
          {isCameraSupported() ? (
            <BarcodeScanner
              onDetected={handleCode}
              active={scanning && !disabled}
              hint="Apuntá al código de barras del producto"
              fallback={
                <Button variant="outline" onClick={() => setScanning(false)}>
                  Buscar por nombre
                </Button>
              }
            />
          ) : (
            <p className="text-sm text-muted-foreground">
              Este dispositivo no tiene cámara disponible. Buscá el producto por nombre o código.
            </p>
          )}
        </div>
      ) : null}

      {showResults ? (
        <Card padding="none" className="overflow-hidden">
          {results.isPending ? (
            <div className="flex items-center gap-2 px-4 py-6 text-sm text-muted-foreground">
              <Spinner className="h-4 w-4" /> Buscando productos…
            </div>
          ) : results.isError ? (
            <div className="flex flex-col items-start gap-2 px-4 py-6">
              <p className="text-sm text-crit-ink">{getErrorMessage(results.error)}</p>
              <Button size="sm" variant="outline" onClick={() => void results.refetch()}>
                Reintentar
              </Button>
            </div>
          ) : items.length === 0 ? (
            <div className="flex items-center gap-2 px-4 py-6 text-sm text-muted-foreground">
              <PackageSearch className="h-4 w-4" aria-hidden="true" />
              No encontramos productos con «{q}».
            </div>
          ) : (
            <ul className="divide-y divide-border">
              {items.map((product) => {
                const stock = stockInBranch(product, branchId);
                return (
                  <li key={product.id}>
                    <button
                      type="button"
                      disabled={disabled}
                      onClick={() => {
                        if (stock <= 0) {
                          toast.warning(`${product.name} no tiene stock vendible en esta sucursal.`);
                        }
                        onPick(product);
                        setSearch('');
                        setScanning(false);
                        inputRef.current?.focus();
                      }}
                      className="flex w-full items-center gap-3 px-4 py-2.5 text-left transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none disabled:opacity-50"
                    >
                      <span className="min-w-0 flex-1">
                        <span className="block truncate text-base font-semibold text-foreground">{product.name}</span>
                        <span className="mt-0.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted-foreground">
                          {product.brand ? <span className="truncate">{product.brand}</span> : null}
                          {product.barcode ? <BarcodeDigits code={product.barcode} digitsOnly /> : null}
                        </span>
                      </span>
                      <span className="flex shrink-0 items-center gap-2">
                        <span className="text-right">
                          <span className="block text-base font-semibold tabular-nums text-foreground">
                            {formatMoney(product.salePrice)}
                          </span>
                          <span className="block text-xs tabular-nums text-muted-foreground">
                            {stock} u. vendibles
                          </span>
                        </span>
                        <StockStatusPill status={stock <= 0 ? 'OUT' : product.stockStatus} />
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
      ) : null}
    </div>
  );
}
