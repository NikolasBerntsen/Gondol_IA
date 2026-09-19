import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Banknote,
  CheckCircle2,
  CreditCard,
  Landmark,
  Printer,
  QrCode,
  type LucideIcon,
} from 'lucide-react';
import { PriceTag, Ticket80mm, type Ticket80mmData } from '@/components/gondola';
import {
  Button,
  Dialog,
  DialogContent,
  DialogTitle,
  Input,
  Kbd,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatMoney } from '@/lib/format';
import { formatArsInput, parseArs, QUICK_BILLS, subtractMoney, sumMoney } from '../money';
import { PAYMENT_METHOD_LABELS, type PaymentMethod, type PosSale } from '../types';
import { saleToTicketData } from '../ticket';

const METHOD_ICONS: Record<PaymentMethod, LucideIcon> = {
  CASH: Banknote,
  DEBIT: CreditCard,
  CREDIT: CreditCard,
  TRANSFER: Landmark,
  QR: QrCode,
};

const METHOD_ORDER: PaymentMethod[] = ['CASH', 'DEBIT', 'CREDIT', 'TRANSFER', 'QR'];

interface PaymentLine {
  id: number;
  method: PaymentMethod;
  amount: string;
}

export interface PaymentSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  total: number;
  units: number;
  /** Cobra la venta. Si falla, el error ya se muestra en el mostrador y la hoja queda abierta. */
  onConfirm: (payments: Array<{ method: PaymentMethod; amount: number }>) => void;
  pending: boolean;
  /**
   * El mostrador está volviendo a pedir el carrito al servidor (precios por lote y recalls al día): no se puede
   * confirmar hasta tener el total que va a cobrar el núcleo.
   */
  refreshing?: boolean;
  /** Venta cobrada: la hoja pasa a mostrar el ticket. */
  sale: PosSale | null;
  onNewSale: () => void;
  onPrint: (sale: PosSale) => void;
  storeName: string;
  taxId: string | null;
}

/**
 * Hoja de cobro (SPEC §15.3): medios combinables, billetes rápidos, vuelto en vivo y, al confirmar,
 * el ticket con "Imprimir" y "Nueva venta".
 */
export function PaymentSheet({
  open,
  onOpenChange,
  total,
  units,
  onConfirm,
  pending,
  refreshing = false,
  sale,
  onNewSale,
  onPrint,
  storeName,
  taxId,
}: PaymentSheetProps) {
  const [lines, setLines] = useState<PaymentLine[]>([{ id: 1, method: 'CASH', amount: '' }]);
  const [active, setActive] = useState(1);
  const nextId = useRef(2);

  useEffect(() => {
    if (open && !sale) {
      setLines([{ id: 1, method: 'CASH', amount: '' }]);
      setActive(1);
      nextId.current = 2;
    }
  }, [open, sale]);

  const amountOf = useCallback((line: PaymentLine) => parseArs(line.amount) ?? 0, []);

  const totals = useMemo(() => {
    const paid = sumMoney(lines.map(amountOf));
    const cash = sumMoney(lines.filter((line) => line.method === 'CASH').map(amountOf));
    const nonCash = subtractMoney(paid, cash);
    const remaining = Math.max(0, subtractMoney(total, paid));
    const over = Math.max(0, subtractMoney(paid, total));
    const nonCashOver = nonCash > total;
    const change = nonCashOver ? 0 : Math.min(over, cash);
    const invalid = lines.some((line) => line.amount.trim() !== '' && parseArs(line.amount) === null);
    return { paid, cash, nonCash, remaining, nonCashOver, change, invalid };
  }, [lines, total, amountOf]);

  const canConfirm = totals.paid >= total && !totals.nonCashOver && !totals.invalid && !pending && !refreshing;

  const focusLine = (id: number) => {
    setActive(id);
    requestAnimationFrame(() => {
      const el = document.getElementById(`pay-${id}`) as HTMLInputElement | null;
      el?.focus();
      el?.select();
    });
  };

  const addMethod = (method: PaymentMethod) => {
    const existing = lines.find((line) => line.method === method);
    if (existing) {
      focusLine(existing.id);
      return;
    }
    const id = nextId.current++;
    const onlyEmpty = lines.length === 1 && lines[0].amount.trim() === '';
    const line: PaymentLine = {
      id,
      method,
      amount: method === 'CASH' ? '' : totals.remaining ? formatArsInput(totals.remaining) : '',
    };
    setLines(onlyEmpty ? [line] : [...lines, line]);
    focusLine(id);
  };

  const setCashAmount = (value: number) => {
    const cashLine = lines.find((line) => line.method === 'CASH');
    if (cashLine) {
      setLines(lines.map((line) => (line.id === cashLine.id ? { ...line, amount: formatArsInput(value) } : line)));
      focusLine(cashLine.id);
    } else {
      const id = nextId.current++;
      setLines([...lines, { id, method: 'CASH', amount: formatArsInput(value) }]);
      focusLine(id);
    }
  };

  const addBill = (bill: number) => {
    const cashLine = lines.find((line) => line.method === 'CASH');
    setCashAmount(sumMoney([cashLine ? amountOf(cashLine) : 0, bill]));
  };

  const exactAmount = () => {
    const need = subtractMoney(total, totals.nonCash);
    if (need <= 0) return;
    setCashAmount(need);
  };

  const submit = () => {
    if (!canConfirm) return;
    const payments = lines
      .map((line) => ({ method: line.method, amount: amountOf(line) }))
      .filter((payment) => payment.amount > 0);
    if (!payments.length) return;
    onConfirm(payments);
  };

  const ticketData: Ticket80mmData | null = sale ? saleToTicketData(sale, storeName, taxId) : null;

  return (
    <Dialog open={open} onOpenChange={(next) => (!next && pending ? undefined : onOpenChange(next))}>
      <DialogContent
        className={cn(
          'max-w-[680px] gap-0 overflow-hidden p-0 sm:p-0',
          'max-sm:bottom-0 max-sm:top-auto max-sm:w-full max-sm:max-w-none max-sm:translate-y-0 max-sm:rounded-b-none',
        )}
        aria-describedby={undefined}
      >
        {sale && ticketData ? (
          <div className="flex min-h-0 flex-col">
            <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border py-4 pl-5 pr-14 sm:pl-6">
              <div className="flex items-start gap-3">
                <CheckCircle2 className="mt-0.5 h-6 w-6 shrink-0 text-ok" aria-hidden="true" />
                <div>
                  <DialogTitle>Venta registrada</DialogTitle>
                  <p className="font-mono text-sm text-muted-foreground">Ticket {sale.ticketCode}</p>
                </div>
              </div>
              <div className="text-right">
                <div className="text-sm text-muted-foreground">Vuelto</div>
                <div className="font-display text-xl font-semibold leading-8 tabular-nums text-foreground">
                  {formatMoney(sale.changeAmount, { decimals: 2 })}
                </div>
              </div>
            </div>
            <div className="gd-scroll grid max-h-[min(58vh,560px)] place-items-start justify-center overflow-y-auto bg-muted px-4 py-6">
              <Ticket80mm data={ticketData} />
            </div>
            <div className="flex flex-col-reverse gap-2 border-t border-border px-5 py-4 sm:flex-row sm:justify-end sm:px-6">
              <Button variant="outline" onClick={() => onPrint(sale)} leftIcon={<Printer aria-hidden="true" />}>
                Imprimir
              </Button>
              <Button onClick={onNewSale} autoFocus data-autofocus>
                Nueva venta
                <Kbd className="ml-2 border-primary-foreground/30 bg-transparent text-primary-foreground">Enter</Kbd>
              </Button>
            </div>
          </div>
        ) : (
          <form
            className="flex min-h-0 flex-col"
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
          >
            <div className="flex flex-wrap items-center justify-between gap-4 border-b border-border py-4 pl-5 pr-14 sm:pl-6">
              <div>
                <DialogTitle>Cobrar</DialogTitle>
                <p className="text-sm text-muted-foreground" aria-live="polite">
                  {units} {units === 1 ? 'unidad' : 'unidades'} en el ticket
                  {refreshing ? ' · actualizando precios…' : ''}
                </p>
              </div>
              <PriceTag size="md" price={total} label="Total" />
            </div>

            <div className="gd-scroll grid max-h-[min(62vh,600px)] gap-5 overflow-y-auto px-5 py-5 sm:px-6">
              <fieldset>
                <legend className="gd-eyebrow mb-2">Medio de pago · se pueden combinar</legend>
                <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
                  {METHOD_ORDER.map((method) => {
                    const Icon = METHOD_ICONS[method];
                    const used = lines.some(
                      (line) => line.method === method && (line.amount.trim() !== '' || line.id === active),
                    );
                    return (
                      <button
                        key={method}
                        type="button"
                        onClick={() => addMethod(method)}
                        aria-pressed={used}
                        className={cn(
                          'flex h-16 flex-col items-center justify-center gap-1 rounded-control border text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                          used
                            ? 'border-primary bg-primary/[0.08] text-foreground'
                            : 'border-input bg-card text-foreground hover:bg-muted',
                        )}
                      >
                        <Icon className="h-5 w-5" aria-hidden="true" />
                        {PAYMENT_METHOD_LABELS[method]}
                      </button>
                    );
                  })}
                </div>
              </fieldset>

              <div className="grid gap-2">
                {lines.map((line) => {
                  const Icon = METHOD_ICONS[line.method];
                  const bad = line.amount.trim() !== '' && parseArs(line.amount) === null;
                  return (
                    <div
                      key={line.id}
                      className={cn(
                        'flex items-center gap-3 rounded-control border px-3 py-2',
                        line.id === active ? 'border-primary/60 bg-primary/[0.04]' : 'border-border',
                      )}
                    >
                      <span className="flex w-[118px] shrink-0 items-center gap-2 text-base font-semibold">
                        <Icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                        {PAYMENT_METHOD_LABELS[line.method]}
                      </span>
                      <label htmlFor={`pay-${line.id}`} className="sr-only">
                        Monto en {PAYMENT_METHOD_LABELS[line.method]}
                      </label>
                      <div className="relative min-w-0 flex-1">
                        <span
                          className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                          aria-hidden="true"
                        >
                          $
                        </span>
                        <Input
                          id={`pay-${line.id}`}
                          inputMode="decimal"
                          autoComplete="off"
                          value={line.amount}
                          invalid={bad}
                          placeholder={line.method === 'CASH' ? 'Recibido' : '0'}
                          onFocus={() => setActive(line.id)}
                          onChange={(event) =>
                            setLines(
                              lines.map((item) =>
                                item.id === line.id ? { ...item, amount: event.target.value } : item,
                              ),
                            )
                          }
                          className="pl-7 text-right font-semibold tabular-nums"
                        />
                      </div>
                      {lines.length > 1 ? (
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-sm"
                          aria-label={`Quitar ${PAYMENT_METHOD_LABELS[line.method]}`}
                          onClick={() => setLines(lines.filter((item) => item.id !== line.id))}
                        >
                          ×
                        </Button>
                      ) : null}
                    </div>
                  );
                })}
              </div>

              <div>
                <div className="gd-eyebrow mb-2">Billetes rápidos (efectivo)</div>
                <div className="flex flex-wrap gap-2">
                  {QUICK_BILLS.map((bill) => (
                    <Button
                      key={bill}
                      type="button"
                      variant="outline"
                      className="min-w-[92px] font-display text-md tabular-nums"
                      onClick={() => addBill(bill)}
                    >
                      {formatMoney(bill)}
                    </Button>
                  ))}
                  <Button type="button" variant="secondary" onClick={exactAmount}>
                    Monto justo
                  </Button>
                </div>
              </div>

              <dl className="grid grid-cols-2 gap-y-1 border-t border-border pt-4 text-base sm:grid-cols-4 sm:gap-x-6">
                <div>
                  <dt className="text-sm text-muted-foreground">Total</dt>
                  <dd className="font-semibold tabular-nums">{formatMoney(total, { decimals: 2 })}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Pagado</dt>
                  <dd className="font-semibold tabular-nums">{formatMoney(totals.paid, { decimals: 2 })}</dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Falta</dt>
                  <dd
                    className={cn(
                      'font-semibold tabular-nums',
                      totals.remaining > 0 ? 'text-crit-ink' : 'text-muted-foreground',
                    )}
                  >
                    {formatMoney(totals.remaining, { decimals: 2 })}
                  </dd>
                </div>
                <div>
                  <dt className="text-sm text-muted-foreground">Vuelto</dt>
                  <dd
                    className="font-display text-xl font-semibold leading-8 tabular-nums text-ok-ink"
                    aria-live="polite"
                  >
                    {formatMoney(totals.change, { decimals: 2 })}
                  </dd>
                </div>
              </dl>
              {totals.nonCashOver ? (
                <p role="alert" className="flex items-start gap-2 text-sm text-crit-ink">
                  <AlertTriangle className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
                  El vuelto solo se da en efectivo: bajá el monto con tarjeta, transferencia o QR.
                </p>
              ) : null}
            </div>

            <div className="flex flex-col-reverse gap-2 border-t border-border bg-muted/40 px-5 py-4 sm:flex-row sm:items-center sm:justify-end sm:px-6">
              <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={pending}>
                Volver a la venta
                <Kbd className="ml-2">Esc</Kbd>
              </Button>
              <Button type="submit" size="lg" disabled={!canConfirm} loading={pending}>
                Confirmar cobro
                <Kbd className="ml-2 border-primary-foreground/30 bg-transparent text-primary-foreground">Enter</Kbd>
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
