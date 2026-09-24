import { useEffect, useMemo, useRef, useState } from 'react';
import {
  AlertTriangle,
  Banknote,
  Check,
  CheckCircle2,
  CreditCard,
  Landmark,
  Printer,
  QrCode,
  X,
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
import { QUICK_BILLS } from '../money';
import {
  addBill,
  canCharge,
  exactCash,
  hasMethod,
  isInvalidAmount,
  paymentTotals,
  removeLine,
  setLineAmount,
  toggleMethod,
  toPayments,
  type PaymentLine,
} from '../payments';
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

const methodButtonId = (method: PaymentMethod) => `pay-method-${method}`;
const lineInputId = (id: number) => `pay-${id}`;

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
 *
 * Arranca sin pagos: el cajero toca los medios con los que paga el cliente (quedan iluminados) y cada uno suma una
 * línea abajo con lo que falta; volver a tocar un medio iluminado quita su línea (reglas en `../payments`). Los
 * billetes rápidos aparecen solo con una línea de efectivo. El resumen (Total, Pagado, Falta, Vuelto) y los botones
 * quedan fijos abajo: lo único que se desplaza es la lista de pagos.
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
  const [lines, setLines] = useState<PaymentLine[]>([]);
  const nextId = useRef(1);

  useEffect(() => {
    if (open && !sale) {
      setLines([]);
      nextId.current = 1;
    }
  }, [open, sale]);

  const totals = useMemo(() => paymentTotals(lines, total), [lines, total]);
  const canConfirm = canCharge(totals, total) && !pending && !refreshing;

  /** Enfoca después del render que agrega o quita la línea (el elemento todavía no existe o está por irse). */
  const focusLater = (elementId: string, select = false) => {
    requestAnimationFrame(() => {
      const el = document.getElementById(elementId);
      el?.focus();
      if (select && el instanceof HTMLInputElement) el.select();
    });
  };

  const toggle = (method: PaymentMethod) => {
    const adding = !hasMethod(lines, method);
    const id = nextId.current;
    if (adding) nextId.current += 1;
    setLines(toggleMethod(lines, method, id, total));
    // Al agregar, el cursor va al monto (Enter cobra); al quitar, se queda en el botón que se apagó.
    if (adding) focusLater(lineInputId(id), true);
  };

  const remove = (line: PaymentLine) => {
    setLines(removeLine(lines, line.id));
    focusLater(methodButtonId(line.method));
  };

  const cashLine = lines.find((line) => line.method === 'CASH');
  const focusCash = () => {
    if (cashLine) focusLater(lineInputId(cashLine.id), true);
  };

  const submit = () => {
    if (!canConfirm) return;
    const payments = toPayments(lines);
    if (!payments.length) return;
    onConfirm(payments);
  };

  const ticketData: Ticket80mmData | null = sale ? saleToTicketData(sale, storeName, taxId) : null;

  return (
    <Dialog open={open} onOpenChange={(next) => (!next && pending ? undefined : onOpenChange(next))}>
      <DialogContent
        className={cn(
          // Columna: encabezado y pie fijos, el medio se achica y desplaza. Si la pantalla es tan baja que ni la
          // parte fija entra, el diálogo entero se desplaza (overflow-y-auto del DialogContent) en vez de cortarse.
          'flex max-w-[680px] flex-col gap-0 p-0 sm:p-0',
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
            className="flex min-h-0 flex-1 flex-col"
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
          >
            {/* Sin flex-wrap: en un celular angosto baja el texto de al lado y no la etiqueta entera, que se
                comería el alto que necesita la lista de pagos. */}
            <div className="flex shrink-0 items-center justify-between gap-3 border-b border-border py-4 pl-5 pr-14 sm:gap-4 sm:pl-6">
              <div className="min-w-0">
                <DialogTitle>Cobrar</DialogTitle>
                <p className="text-sm text-muted-foreground" aria-live="polite">
                  {units} {units === 1 ? 'unidad' : 'unidades'} en el ticket
                  {refreshing ? ' · actualizando precios…' : ''}
                </p>
              </div>
              <PriceTag size="md" price={total} label="Total" className="shrink-0" />
            </div>

            <div className="shrink-0 border-b border-border px-5 py-3 sm:px-6 sm:py-4">
              <fieldset>
                <legend className="gd-eyebrow mb-2">Medios de pago · tocá uno o varios</legend>
                <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
                  {METHOD_ORDER.map((method) => {
                    const Icon = METHOD_ICONS[method];
                    const on = hasMethod(lines, method);
                    return (
                      <button
                        key={method}
                        id={methodButtonId(method)}
                        type="button"
                        onClick={() => toggle(method)}
                        aria-pressed={on}
                        className={cn(
                          'relative flex h-12 flex-col items-center justify-center gap-1 rounded-control border text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:h-16',
                          on
                            ? 'border-primary bg-primary/[0.12] text-foreground hover:bg-primary/[0.18]'
                            : 'border-input bg-card text-foreground hover:bg-muted',
                        )}
                      >
                        {on ? (
                          <Check
                            className="absolute right-1.5 top-1.5 h-3.5 w-3.5 text-primary"
                            strokeWidth={3}
                            aria-hidden="true"
                          />
                        ) : null}
                        <Icon className={cn('h-5 w-5', on && 'text-primary')} aria-hidden="true" />
                        {PAYMENT_METHOD_LABELS[method]}
                      </button>
                    );
                  })}
                </div>
              </fieldset>
            </div>

            {/* `relative`: las etiquetas `sr-only` (absolutas) quedan dentro de la lista y no estiran el diálogo. */}
            <div className="gd-scroll relative min-h-[5.5rem] flex-1 overflow-y-auto px-5 py-4 sm:px-6">
              {lines.length ? (
                <ul className="grid gap-2" aria-label="Pagos cargados">
                  {lines.map((line) => {
                    const Icon = METHOD_ICONS[line.method];
                    const label = PAYMENT_METHOD_LABELS[line.method];
                    return (
                      <li
                        key={line.id}
                        className="rounded-control border border-border px-3 py-2 transition-colors focus-within:border-primary/60 focus-within:bg-primary/[0.04]"
                      >
                        <div className="flex items-center gap-3">
                          <span className="flex w-[118px] shrink-0 items-center gap-2 text-base font-semibold">
                            <Icon className="h-4 w-4 text-muted-foreground" aria-hidden="true" />
                            {label}
                          </span>
                          <label htmlFor={lineInputId(line.id)} className="sr-only">
                            Monto en {label}
                          </label>
                          <div className="relative min-w-0 flex-1">
                            <span
                              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground"
                              aria-hidden="true"
                            >
                              $
                            </span>
                            <Input
                              id={lineInputId(line.id)}
                              inputMode="decimal"
                              autoComplete="off"
                              value={line.amount}
                              invalid={isInvalidAmount(line)}
                              placeholder={line.method === 'CASH' ? 'Recibido' : '0'}
                              onChange={(event) => setLines(setLineAmount(lines, line.id, event.target.value))}
                              className="pl-7 text-right font-semibold tabular-nums"
                            />
                          </div>
                          <Button
                            type="button"
                            variant="ghost"
                            size="icon-sm"
                            aria-label={`Quitar ${label}`}
                            onClick={() => remove(line)}
                          >
                            <X aria-hidden="true" />
                          </Button>
                        </div>
                        {line.method === 'CASH' ? (
                          <div className="mt-2 border-t border-border pt-2">
                            <div className="gd-eyebrow mb-1.5" id="pay-quick-bills">
                              Billetes rápidos
                            </div>
                            <div
                              className="grid grid-cols-3 gap-2 sm:grid-cols-5"
                              role="group"
                              aria-labelledby="pay-quick-bills"
                            >
                              {QUICK_BILLS.map((bill) => (
                                <Button
                                  key={bill}
                                  type="button"
                                  variant="outline"
                                  className="px-2 font-display text-md tabular-nums"
                                  onClick={() => {
                                    setLines(addBill(lines, bill));
                                    focusCash();
                                  }}
                                >
                                  {formatMoney(bill)}
                                </Button>
                              ))}
                              <Button
                                type="button"
                                variant="secondary"
                                className="col-span-2 sm:col-span-1"
                                onClick={() => {
                                  setLines(exactCash(lines, total));
                                  focusCash();
                                }}
                              >
                                Monto justo
                              </Button>
                            </div>
                          </div>
                        ) : null}
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p className="grid min-h-[3.5rem] place-items-center rounded-control border border-dashed border-border px-4 py-3 text-center text-sm text-muted-foreground">
                  Tocá cómo paga el cliente. Si combina medios, tocá cada uno: se suman acá abajo.
                </p>
              )}
            </div>

            {/* Pie fijo con el resumen y los botones. `sticky` cubre la pantalla tan baja que ni la parte fija entra:
                ahí se desplaza el diálogo entero y el pie igual queda pegado abajo. */}
            <div className="sticky bottom-0 z-10 shrink-0 bg-card">
              <section aria-label="Resumen del cobro" className="border-t border-border px-5 py-2 sm:px-6 sm:py-3">
                {totals.nonCashOver ? (
                  <p role="alert" className="mb-2 flex items-start gap-2 text-sm text-crit-ink">
                    <AlertTriangle className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
                    El vuelto solo se da en efectivo: bajá el monto con tarjeta, transferencia o QR.
                  </p>
                ) : null}
                <dl className="grid grid-cols-2 gap-y-1 text-base sm:grid-cols-4 sm:gap-x-6">
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
              </section>

              {/* En el celular van en una sola fila ("Volver" corto) para dejarle el alto a la lista de pagos. */}
              <div className="flex gap-2 border-t border-border bg-muted/40 px-5 py-3 sm:items-center sm:justify-end sm:px-6 sm:py-4">
                <Button
                  type="button"
                  variant="outline"
                  className="max-sm:h-11"
                  onClick={() => onOpenChange(false)}
                  disabled={pending}
                >
                  <span>
                    Volver<span className="max-sm:hidden"> a la venta</span>
                  </span>
                  <Kbd className="ml-2">Esc</Kbd>
                </Button>
                <Button type="submit" size="lg" className="max-sm:flex-1" disabled={!canConfirm} loading={pending}>
                  Confirmar cobro
                  <Kbd className="ml-2 border-primary-foreground/30 bg-transparent text-primary-foreground">Enter</Kbd>
                </Button>
              </div>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
