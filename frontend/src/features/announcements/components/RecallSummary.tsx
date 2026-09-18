import { BarcodeDigits } from '@/components/gondola';
import { formatDate } from '@/lib/format';
import type { RecallDetail } from '../types';

/**
 * Ficha del producto retirado: marca, código, lotes alcanzados y rango de vencimiento.
 * La usan el detalle del aviso (comercio) y el listado de la consola de dueños.
 */
export function RecallSummary({ recall, compact = false }: { recall: RecallDetail; compact?: boolean }) {
  return (
    <div className="rounded-panel border border-crit/40 bg-crit-soft/50 p-4">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 flex-1 basis-[220px]">
          <div className="gd-eyebrow">Producto retirado</div>
          <div className="mt-1 font-display text-md font-semibold leading-6 text-foreground">
            {recall.productName}
          </div>
          {recall.brand ? <div className="text-sm text-muted-foreground">{recall.brand}</div> : null}
        </div>
        {compact ? null : <BarcodeDigits code={recall.barcode} width={130} />}
      </div>

      <dl className="mt-3 grid gap-x-6 gap-y-2 sm:grid-cols-2">
        {compact ? (
          <div>
            <dt className="gd-eyebrow">Código</dt>
            <dd className="font-mono text-sm tabular-nums text-foreground">{recall.barcode}</dd>
          </div>
        ) : null}
        <div>
          <dt className="gd-eyebrow">Lotes alcanzados</dt>
          <dd className="mt-0.5 flex flex-wrap gap-1.5">
            {recall.allLots ? (
              <span className="inline-flex h-[22px] items-center rounded-tag border border-crit/40 bg-crit-soft px-1.5 font-mono text-xs font-semibold text-crit-ink">
                TODOS LOS LOTES
              </span>
            ) : (
              recall.lotNumbers.map((lot) => (
                <span
                  key={lot}
                  className="inline-flex h-[22px] items-center rounded-tag border border-crit/40 bg-crit-soft px-1.5 font-mono text-xs font-semibold text-crit-ink"
                >
                  {lot.toUpperCase()}
                </span>
              ))
            )}
          </dd>
        </div>
        {recall.expiryFrom || recall.expiryTo ? (
          <div>
            <dt className="gd-eyebrow">Vencimientos alcanzados</dt>
            <dd className="font-mono text-sm tabular-nums text-foreground">
              {recall.expiryFrom ? formatDate(recall.expiryFrom) : 'sin límite'} —{' '}
              {recall.expiryTo ? formatDate(recall.expiryTo) : 'sin límite'}
            </dd>
          </div>
        ) : null}
      </dl>

      <div className="mt-3 space-y-2 border-t border-crit/25 pt-3">
        <div>
          <div className="gd-eyebrow">Motivo</div>
          <p className="text-read text-foreground">{recall.reason}</p>
        </div>
        <div>
          <div className="gd-eyebrow">Qué tenés que hacer</div>
          <p className="text-read text-foreground">{recall.instructions}</p>
        </div>
      </div>
    </div>
  );
}
