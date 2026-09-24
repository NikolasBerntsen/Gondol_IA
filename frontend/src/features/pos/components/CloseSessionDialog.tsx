import { useEffect, useState } from 'react';
import { Button, ConfirmDialog, Field, Input, Modal, Textarea } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatMoney, formatTime } from '@/lib/format';
import { parseArs, subtractMoney } from '../money';
import type { PosSessionReport } from '../types';

export interface CloseSessionDialogProps {
  open: boolean;
  session: PosSessionReport;
  onClose: () => void;
  onSubmit: (values: { countedCash: number; note: string | null }) => void;
  pending: boolean;
}

/** Diferencia entre lo contado y lo esperado, como la lee el cajero. */
function differenceText(difference: number): string {
  if (difference === 0) return 'La caja cierra justa.';
  return difference > 0
    ? `Sobran ${formatMoney(difference, { decimals: 2 })}.`
    : `Faltan ${formatMoney(-difference, { decimals: 2 })}.`;
}

/** Por qué el turno no tiene ventas: nunca se cobró nada o se anularon todas. */
function noSalesReason(voidedCount: number): string {
  if (voidedCount === 0) return 'En este turno no se cobró ninguna venta.';
  if (voidedCount === 1) return 'La única venta de este turno está anulada.';
  return `Las ${voidedCount} ventas de este turno están anuladas.`;
}

/**
 * Arqueo y cierre del turno (SPEC §15.2): mostramos de dónde sale el efectivo esperado antes de
 * pedir lo contado, así el cajero entiende la diferencia.
 *
 * Un turno sin ventas (nunca se cobró nada o se anularon todas) se cierra igual: el cajero pudo abrir la
 * caja por error o no vender nada en el día. Como último paso le avisamos que va a cerrar la caja sin ventas y
 * tiene que confirmarlo; el turno queda registrado como "Cerrado sin ventas".
 *
 * El aviso sale del turno que muestra el mostrador (lo vuelve a traer al abrir este diálogo); la marca del turno la
 * decide el servidor con el arqueo del cierre, no este aviso.
 */
export function CloseSessionDialog({ open, session, onClose, onSubmit, pending }: CloseSessionDialogProps) {
  const [counted, setCounted] = useState('');
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);
  const [confirmingNoSales, setConfirmingNoSales] = useState(false);

  useEffect(() => {
    if (open) {
      setCounted('');
      setNote('');
      setTouched(false);
      setConfirmingNoSales(false);
    }
  }, [open]);

  const noSales = session.salesCount === 0;
  const value = parseArs(counted);
  const difference = value === null ? null : subtractMoney(value, session.expectedCash);
  // Lo del cajón vacío ("escribí 0") ya lo dice la ayuda del campo: el error no lo repite.
  const error =
    !touched || value !== null
      ? undefined
      : counted.trim()
        ? 'No entendimos el importe: escribilo como 184.350 o 184350.'
        : 'Escribí cuánto efectivo contaste.';

  const send = (countedCash: number) => onSubmit({ countedCash, note: note.trim() ? note.trim() : null });

  // "Cerrar caja" no se deshabilita con el campo vacío: si falta el importe, al tocarlo explicamos qué escribir
  // (antes quedaba gris sin ningún mensaje y, en un turno sin ventas, parecía que la caja no se podía cerrar).
  const submit = () => {
    setTouched(true);
    if (value === null) return;
    if (noSales) {
      setConfirmingNoSales(true);
      return;
    }
    send(value);
  };

  return (
    <>
      <Modal
        open={open}
        onClose={onClose}
        preventClose={pending}
        size="md"
        title="Cerrar caja"
        description="Contá el efectivo del cajón. Lo comparamos con lo que debería haber según el turno."
        footer={
          <>
            <Button variant="outline" onClick={onClose} disabled={pending}>
              Seguir vendiendo
            </Button>
            <Button variant="destructive" onClick={submit} loading={pending}>
              Cerrar caja
            </Button>
          </>
        }
      >
        <form
          className="grid gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            submit();
          }}
        >
          <dl className="grid grid-cols-2 gap-y-1.5 rounded-control bg-muted/60 p-3 text-base">
            <dt className="text-muted-foreground">Apertura {formatTime(session.openedAt)}</dt>
            <dd className="text-right tabular-nums">{formatMoney(session.openingCash, { decimals: 2 })}</dd>
            <dt className="text-muted-foreground">Ventas en efectivo</dt>
            <dd className="text-right tabular-nums">
              {formatMoney(session.totalsByMethod.CASH ?? 0, { decimals: 2 })}
            </dd>
            <dt className="text-muted-foreground">Vuelto entregado</dt>
            <dd className="text-right tabular-nums">−{formatMoney(session.changeGiven, { decimals: 2 })}</dd>
            {session.cashIn > 0 ? (
              <>
                <dt className="text-muted-foreground">Ingresos de efectivo</dt>
                <dd className="text-right tabular-nums">{formatMoney(session.cashIn, { decimals: 2 })}</dd>
              </>
            ) : null}
            {session.cashOut > 0 ? (
              <>
                <dt className="text-muted-foreground">Retiros</dt>
                <dd className="text-right tabular-nums">−{formatMoney(session.cashOut, { decimals: 2 })}</dd>
              </>
            ) : null}
            {session.voidedCount > 0 ? (
              <>
                <dt className="text-muted-foreground">Ventas anuladas</dt>
                <dd className="text-right tabular-nums">{session.voidedCount}</dd>
              </>
            ) : null}
            <dt className="mt-1 border-t border-border pt-1.5 font-semibold">Efectivo esperado</dt>
            <dd className="mt-1 border-t border-border pt-1.5 text-right font-semibold tabular-nums">
              {formatMoney(session.expectedCash, { decimals: 2 })}
            </dd>
          </dl>

          {noSales ? (
            <p className="text-base text-muted-foreground">
              {noSalesReason(session.voidedCount)} Igual podés cerrar la caja: antes te pedimos que lo confirmes.
            </p>
          ) : null}

          <Field
            label="Efectivo contado"
            hint="Usá punto para los miles: 184.350. Si el cajón quedó vacío, escribí 0."
            error={error}
          >
            <Input
              data-autofocus
              inputMode="decimal"
              autoComplete="off"
              value={counted}
              onChange={(event) => setCounted(event.target.value)}
              // Sin "$ 0": parecía un importe ya cargado y el cajero no escribía nada.
              placeholder="Total del cajón"
              className="text-md tabular-nums"
            />
          </Field>

          {difference !== null ? (
            <p
              className={cn('text-base font-semibold', difference === 0 ? 'text-ok-ink' : 'text-warn-ink')}
              aria-live="polite"
            >
              {differenceText(difference)}
            </p>
          ) : null}

          <Field label="Nota del cierre" optional>
            <Textarea
              rows={2}
              value={note}
              onChange={(event) => setNote(event.target.value)}
              maxLength={300}
              placeholder="Ej.: faltó vuelto chico toda la tarde"
            />
          </Field>
        </form>
      </Modal>

      <ConfirmDialog
        open={open && confirmingNoSales}
        onClose={() => setConfirmingNoSales(false)}
        onConfirm={() => {
          if (value !== null) send(value);
        }}
        title="Estás a punto de cerrar la caja sin ventas"
        description={`${noSalesReason(session.voidedCount)} Podés cerrar la caja igual: el turno queda registrado en Mis turnos de caja como «Cerrado sin ventas», con quién lo cerró y a qué hora.`}
        confirmLabel="Cerrar sin ventas"
        cancelLabel="Volver"
        tone="danger"
        loading={pending}
      >
        {value !== null && difference !== null ? (
          <div className="rounded-control bg-muted/60 p-3 text-base">
            <dl className="grid grid-cols-2 gap-y-1">
              <dt className="text-muted-foreground">{session.registerName ?? 'Caja'}</dt>
              <dd className="text-right text-muted-foreground">Abierta {formatTime(session.openedAt)}</dd>
              <dt className="text-muted-foreground">Efectivo contado</dt>
              <dd className="text-right tabular-nums">{formatMoney(value, { decimals: 2 })}</dd>
            </dl>
            <p className={cn('mt-1 font-semibold', difference === 0 ? 'text-ok-ink' : 'text-warn-ink')}>
              {differenceText(difference)}
            </p>
          </div>
        ) : null}
      </ConfirmDialog>
    </>
  );
}
