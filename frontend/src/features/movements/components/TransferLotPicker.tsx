import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { PackageSearch } from 'lucide-react';
import { BarcodeDigits, ExpiryChip, LotRankChip } from '@/components/gondola';
import type { StockRotation } from '@/api/types';
import {
  Badge,
  Button,
  Card,
  ErrorState,
  Pagination,
  QtyStepper,
  SearchInput,
  Skeleton,
  Truncate,
  pageInfo,
} from '@/components/ui';
import { formatDate, formatNumber } from '@/lib/format';
import { transfersApi } from '../api';
import type { TransferableLot } from '../types';

export interface TransferLotPickerProps {
  fromBranchId: number;
  rotation: StockRotation;
  /** Cantidad elegida por lote (`lotId → unidades`). */
  selected: Record<number, number>;
  onChange: (lotId: number, quantity: number, lot: TransferableLot) => void;
  search: string;
  onSearchChange: (value: string) => void;
  page: number;
  onPageChange: (page: number) => void;
  disabled?: boolean;
}

/**
 * Lotes vendibles de la sucursal de origen, en el orden en que se venderían, con el contador de
 * unidades a transferir. Los lotes vencidos y en cuarentena no aparecen: no se pueden transferir
 * (SPEC §4.2, 409 `LOT_NOT_TRANSFERABLE`).
 */
export function TransferLotPicker({
  fromBranchId,
  rotation,
  selected,
  onChange,
  search,
  onSearchChange,
  page,
  onPageChange,
  disabled,
}: TransferLotPickerProps) {
  const params = { branchId: fromBranchId, q: search || undefined, page, size: 20 };
  const lots = useQuery({
    queryKey: ['transfers', 'lots', params],
    queryFn: () => transfersApi.availableLots(params),
    placeholderData: keepPreviousData,
  });

  const rows = lots.data?.content ?? [];

  return (
    <Card padding="none" className="overflow-hidden">
      <div className="border-b border-border p-3 sm:p-4">
        <SearchInput
          value={search}
          onValueChange={onSearchChange}
          label="Buscar lotes por producto, código o número de lote"
          placeholder="Buscá por producto, código o lote…"
          inputSize="sm"
          disabled={disabled}
        />
      </div>

      {lots.isPending ? (
        <div className="flex flex-col gap-2 p-4">
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
          <Skeleton className="h-14 w-full" />
        </div>
      ) : lots.isError ? (
        <ErrorState error={lots.error} onRetry={() => void lots.refetch()} size="sm" />
      ) : rows.length === 0 ? (
        <div className="flex flex-col items-center gap-1.5 px-4 py-10 text-center">
          <PackageSearch className="h-6 w-6 text-muted-foreground" aria-hidden="true" />
          <p className="text-base font-semibold text-foreground">No hay lotes para transferir</p>
          <p className="text-sm text-muted-foreground">
            {search
              ? 'Probá con otra búsqueda.'
              : 'La sucursal de origen no tiene lotes vendibles. Los vencidos y los que están en cuarentena no se pueden transferir.'}
          </p>
        </div>
      ) : (
        <ul className="divide-y divide-border">
          {rows.map((lot) => {
            const quantity = selected[lot.lotId] ?? 0;
            return (
              <li
                key={lot.lotId}
                className={`flex flex-col gap-2 p-3 sm:flex-row sm:items-center sm:justify-between sm:p-4 ${
                  quantity > 0 ? 'bg-muted/60' : ''
                }`}
              >
                <div className="min-w-0">
                  <Truncate as="p" className="text-base font-semibold text-foreground">
                    {lot.productName}
                  </Truncate>
                  <span className="mt-1 flex flex-wrap items-center gap-1.5">
                    {lot.barcode ? <BarcodeDigits code={lot.barcode} digitsOnly /> : null}
                    {lot.expiryDate ? (
                      <ExpiryChip expiry={lot.expiryDate} lot={lot.lotNumber} />
                    ) : lot.lotNumber ? (
                      <Badge tone="neutral" className="font-mono">
                        {lot.lotNumber}
                      </Badge>
                    ) : null}
                    <LotRankChip rank={lot.rotationRank} rotation={rotation} discounted={!!lot.discountPct} />
                    <span className="text-xs tabular-nums text-muted-foreground">
                      {formatNumber(lot.quantity)} u. · ingresó {formatDate(lot.receivedAt)}
                    </span>
                  </span>
                </div>

                <div className="flex shrink-0 items-center gap-2">
                  <QtyStepper
                    value={quantity}
                    onChange={(value) => onChange(lot.lotId, value, lot)}
                    label={`Unidades a transferir de ${lot.productName}`}
                    min={0}
                    max={lot.quantity}
                    disabled={disabled}
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={disabled || quantity === lot.quantity}
                    onClick={() => onChange(lot.lotId, lot.quantity, lot)}
                  >
                    Todo
                  </Button>
                </div>
              </li>
            );
          })}
        </ul>
      )}

      {(lots.data?.totalPages ?? 0) > 1 ? (
        <div className="border-t border-border p-3">
          <Pagination {...pageInfo(lots.data)} onPageChange={onPageChange} disabled={lots.isFetching} />
        </div>
      ) : null}
    </Card>
  );
}
