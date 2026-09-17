import { ArrowDownLeft, ArrowUpRight } from 'lucide-react';
import { StatusPill } from '@/components/gondola';
import { Card } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatDateTime, formatMoney, formatTime } from '@/lib/format';
import {
  CASH_MOVEMENT_TYPE_LABELS,
  PAYMENT_METHODS,
  PAYMENT_METHOD_LABELS,
  type PosSessionReport as PosSessionReportData,
} from '../types';

export interface SessionReportProps {
  session: PosSessionReportData;
  className?: string;
}

function Line({ label, value, strong, tone }: { label: string; value: string; strong?: boolean; tone?: 'crit' | 'ok' }) {
  return (
    <>
      <dt className={cn('text-muted-foreground', strong && 'font-semibold text-foreground')}>{label}</dt>
      <dd
        className={cn(
          'text-right tabular-nums',
          strong && 'font-semibold',
          tone === 'crit' && 'text-crit-ink',
          tone === 'ok' && 'text-ok-ink',
        )}
      >
        {value}
      </dd>
    </>
  );
}

/** Reporte Z de un turno (SPEC §15.2): arqueo, medios de pago, movimientos de efectivo y top de productos. */
export function SessionReport({ session, className }: SessionReportProps) {
  const closed = session.status === 'CLOSED';
  const difference = session.difference;

  return (
    <div className={cn('grid gap-4 lg:grid-cols-2', className)}>
      <Card padding="md">
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div>
            <h3 className="text-md font-semibold leading-6">Arqueo del turno</h3>
            <p className="text-sm text-muted-foreground">
              {session.registerName ?? 'Caja'} · {session.branchName ?? 'Sucursal'} ·{' '}
              {session.openedByName ?? 'Cajero'}
            </p>
          </div>
          <StatusPill tone={closed ? 'neutral' : 'ok'} solid={closed}>
            {closed ? 'Cerrado' : 'Abierto'}
          </StatusPill>
        </div>

        <dl className="mt-3 grid grid-cols-2 gap-y-1 text-base">
          <Line label={`Apertura ${formatTime(session.openedAt)}`} value={formatMoney(session.openingCash, { decimals: 2 })} />
          <Line
            label="Ventas en efectivo"
            value={formatMoney(session.totalsByMethod.CASH ?? 0, { decimals: 2 })}
          />
          <Line label="Vuelto entregado" value={`−${formatMoney(session.changeGiven, { decimals: 2 })}`} />
          <Line label="Ingresos de efectivo" value={formatMoney(session.cashIn, { decimals: 2 })} />
          <Line label="Retiros" value={`−${formatMoney(session.cashOut, { decimals: 2 })}`} />
          <Line
            label="Efectivo esperado"
            value={formatMoney(session.expectedCash, { decimals: 2 })}
            strong
          />
          {session.countedCash !== null ? (
            <>
              <Line label="Efectivo contado" value={formatMoney(session.countedCash, { decimals: 2 })} />
              <Line
                label="Diferencia"
                value={
                  difference === null
                    ? '—'
                    : difference === 0
                      ? 'Cierra justa'
                      : difference > 0
                        ? `Sobran ${formatMoney(difference, { decimals: 2 })}`
                        : `Faltan ${formatMoney(-difference, { decimals: 2 })}`
                }
                strong
                tone={difference === 0 ? 'ok' : 'crit'}
              />
            </>
          ) : null}
        </dl>

        {closed && session.closedAt ? (
          <p className="mt-3 border-t border-border pt-2 text-sm text-muted-foreground">
            Cerrado el {formatDateTime(session.closedAt)}
            {session.closedByName ? ` por ${session.closedByName}` : ''}.
            {session.closingNote ? ` «${session.closingNote}»` : ''}
          </p>
        ) : null}
      </Card>

      <Card padding="md">
        <h3 className="text-md font-semibold leading-6">Ventas del turno</h3>
        <dl className="mt-3 grid grid-cols-2 gap-y-1 text-base">
          <Line label="Tickets cobrados" value={String(session.salesCount)} />
          <Line label="Unidades vendidas" value={String(session.units)} />
          <Line label="Total vendido" value={formatMoney(session.salesTotal, { decimals: 2 })} strong />
          {session.voidedCount > 0 ? (
            <Line
              label={`Anuladas (${session.voidedCount})`}
              value={`−${formatMoney(session.voidedTotal, { decimals: 2 })}`}
              tone="crit"
            />
          ) : null}
        </dl>

        <h4 className="gd-eyebrow mt-4">Por medio de pago</h4>
        <dl className="mt-1.5 grid grid-cols-2 gap-y-1 text-base">
          {PAYMENT_METHODS.map((method) => (
            <Line
              key={method}
              label={PAYMENT_METHOD_LABELS[method]}
              value={formatMoney(session.totalsByMethod[method] ?? 0, { decimals: 2 })}
            />
          ))}
        </dl>
      </Card>

      {session.topProducts.length ? (
        <Card padding="md">
          <h3 className="text-md font-semibold leading-6">Lo que más salió</h3>
          <ul className="mt-2 divide-y divide-border">
            {session.topProducts.map((product, index) => (
              <li
                key={product.productId ?? `${product.productName}-${index}`}
                className="flex items-baseline justify-between gap-3 py-1.5 text-base"
              >
                <span className="min-w-0 truncate">{product.productName}</span>
                <span className="shrink-0 tabular-nums text-muted-foreground">
                  {product.units} u. · {formatMoney(product.total, { decimals: 2 })}
                </span>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}

      {session.cashMovements.length ? (
        <Card padding="md">
          <h3 className="text-md font-semibold leading-6">Movimientos de efectivo</h3>
          <ul className="mt-2 divide-y divide-border">
            {session.cashMovements.map((movement) => {
              const isOut = movement.type === 'CASH_OUT';
              const Icon = isOut ? ArrowUpRight : ArrowDownLeft;
              return (
                <li key={movement.id} className="flex items-start justify-between gap-3 py-2 text-base">
                  <span className="flex min-w-0 items-start gap-2">
                    <Icon
                      className={cn('mt-0.5 h-4 w-4 shrink-0', isOut ? 'text-crit-ink' : 'text-ok-ink')}
                      aria-hidden="true"
                    />
                    <span className="min-w-0">
                      <span className="block truncate">{movement.reason}</span>
                      <span className="block text-xs text-muted-foreground">
                        {CASH_MOVEMENT_TYPE_LABELS[movement.type]} · {formatTime(movement.createdAt)}
                        {movement.userName ? ` · ${movement.userName}` : ''}
                      </span>
                    </span>
                  </span>
                  <span className={cn('shrink-0 font-semibold tabular-nums', isOut ? 'text-crit-ink' : 'text-ok-ink')}>
                    {isOut ? '−' : '+'}
                    {formatMoney(movement.amount, { decimals: 2 })}
                  </span>
                </li>
              );
            })}
          </ul>
        </Card>
      ) : null}
    </div>
  );
}
