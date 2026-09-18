import { useQuery } from '@tanstack/react-query';
import { Receipt, Store, TriangleAlert, User } from 'lucide-react';
import { Link } from 'react-router-dom';
import { BarcodeDigits, ExpiryChip, StatusPill } from '@/components/gondola';
import {
  Badge,
  Button,
  ErrorState,
  Separator,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Skeleton,
} from '@/components/ui';
import { formatDateTime, formatMoney, formatNumber } from '@/lib/format';
import { salesApi } from '../api';
import type { SaleLine } from '../types';

export interface SaleDetailSheetProps {
  batchRef: string | null;
  onClose: () => void;
}

function LineCard({ line }: { line: SaleLine }) {
  return (
    <li className="rounded-panel border border-border p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-base font-semibold text-foreground">{line.productName}</p>
          {line.barcode ? (
            <span className="mt-0.5 block">
              <BarcodeDigits code={line.barcode} digitsOnly />
            </span>
          ) : null}
        </div>
        <div className="shrink-0 text-right">
          <p className="text-base font-semibold tabular-nums text-foreground">{formatMoney(line.total)}</p>
          <p className="text-xs tabular-nums text-muted-foreground">
            {formatNumber(line.quantity)} u. × {formatMoney(line.unitPrice)}
          </p>
        </div>
      </div>

      {line.shortage > 0 ? (
        <p className="mt-2 flex items-center gap-1.5 text-sm text-crit-ink">
          <TriangleAlert className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          {formatNumber(line.shortage)} u. se vendieron sin stock disponible.
        </p>
      ) : null}

      {line.lots.length > 0 ? (
        <ul className="mt-2 flex flex-col gap-1.5 border-t border-border pt-2">
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
                {lot.discountPct ? (
                  <Badge tone="warn">−{formatNumber(lot.discountPct)}% liquidación</Badge>
                ) : null}
              </span>
              <span className="text-sm tabular-nums text-muted-foreground">
                {formatNumber(lot.quantity)} u. × {formatMoney(lot.unitPrice)}
              </span>
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  );
}

/** Detalle de una venta del historial: cabecera, líneas y los lotes de los que salió cada unidad. */
export function SaleDetailSheet({ batchRef, onClose }: SaleDetailSheetProps) {
  const detail = useQuery({
    queryKey: ['sales', 'detail', batchRef],
    queryFn: () => salesApi.detail(batchRef as string),
    enabled: !!batchRef,
  });

  const sale = detail.data?.sale;

  return (
    <Sheet open={!!batchRef} onOpenChange={(open) => !open && onClose()}>
      <SheetContent side="right" className="w-full gap-0 overflow-y-auto sm:max-w-lg">
        <SheetHeader>
          <SheetTitle className="font-mono text-md">{batchRef}</SheetTitle>
          <SheetDescription>Detalle de la venta y lotes consumidos.</SheetDescription>
        </SheetHeader>

        {detail.isPending ? (
          <div className="flex flex-col gap-3 py-4">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : detail.isError ? (
          <ErrorState error={detail.error} onRetry={() => void detail.refetch()} size="sm" />
        ) : detail.data && sale ? (
          <div className="flex flex-col gap-4 py-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={sale.source === 'POS_GONDOLIA' ? 'primary' : 'neutral'}>{sale.sourceLabel}</Badge>
              {sale.voided ? (
                <StatusPill tone="crit" solid>
                  Anulada ({formatNumber(sale.voidedUnits)} u.)
                </StatusPill>
              ) : (
                <StatusPill tone="ok">Vigente</StatusPill>
              )}
              {sale.ticketCode && sale.posSaleId ? (
                <Button asChild variant="outline" size="sm" leftIcon={<Receipt className="h-3.5 w-3.5" />}>
                  <Link to={`/app/pos/sales/${sale.posSaleId}/ticket`}>Ticket {sale.ticketCode}</Link>
                </Button>
              ) : null}
            </div>

            <dl className="grid grid-cols-2 gap-3 text-sm">
              <div>
                <dt className="gd-eyebrow">Fecha</dt>
                <dd className="tabular-nums text-foreground">{formatDateTime(sale.occurredAt)}</dd>
              </div>
              <div>
                <dt className="gd-eyebrow">Sucursal</dt>
                <dd className="flex items-center gap-1.5 text-foreground">
                  <Store className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
                  {sale.branchName}
                </dd>
              </div>
              <div>
                <dt className="gd-eyebrow">Registró</dt>
                <dd className="flex items-center gap-1.5 text-foreground">
                  <User className="h-3.5 w-3.5 text-muted-foreground" aria-hidden="true" />
                  {sale.userName ?? '—'}
                </dd>
              </div>
              <div>
                <dt className="gd-eyebrow">Unidades netas</dt>
                <dd className="tabular-nums text-foreground">{formatNumber(sale.units)}</dd>
              </div>
            </dl>

            {sale.voided && detail.data.reason ? (
              <p className="rounded-panel border border-border bg-crit-soft px-3 py-2 text-sm text-crit-ink">
                Motivo de la anulación: {detail.data.reason}
              </p>
            ) : null}

            <Separator />

            <div>
              <h3 className="gd-eyebrow mb-2">Productos</h3>
              <ul className="flex flex-col gap-2">
                {detail.data.lines.map((line) => (
                  <LineCard key={line.productId} line={line} />
                ))}
              </ul>
            </div>

            <div className="flex items-center justify-between rounded-panel border border-border px-3 py-2.5">
              <span className="text-base font-semibold text-foreground">
                {sale.voided ? 'Total neto' : 'Total'}
              </span>
              <span className="font-display text-lg tabular-nums text-foreground">{formatMoney(sale.total)}</span>
            </div>
            {sale.voided ? (
              <p className="-mt-2 text-right text-xs tabular-nums text-muted-foreground">
                Total facturado antes de la anulación: {formatMoney(detail.data.grossTotal)}
              </p>
            ) : null}
          </div>
        ) : null}
      </SheetContent>
    </Sheet>
  );
}
