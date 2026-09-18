import { useEffect, useState } from 'react';
import { Button, Field, Input, Modal, Textarea } from '@/components/ui';
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

/**
 * Arqueo y cierre del turno (SPEC §15.2): mostramos de dónde sale el efectivo esperado antes de
 * pedir lo contado, así el cajero entiende la diferencia.
 */
export function CloseSessionDialog({ open, session, onClose, onSubmit, pending }: CloseSessionDialogProps) {
  const [counted, setCounted] = useState('');
  const [note, setNote] = useState('');
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (open) {
      setCounted('');
      setNote('');
      setTouched(false);
    }
  }, [open]);

  const value = parseArs(counted);
  const difference = value === null ? null : subtractMoney(value, session.expectedCash);
  const error = touched && value === null ? 'Escribí cuánto efectivo contaste.' : undefined;

  const submit = () => {
    setTouched(true);
    if (value === null) return;
    onSubmit({ countedCash: value, note: note.trim() ? note.trim() : null });
  };

  return (
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
          <Button variant="destructive" onClick={submit} loading={pending} disabled={value === null}>
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

        <Field label="Efectivo contado" hint="Usá punto para los miles: 184.350" error={error}>
          <Input
            data-autofocus
            inputMode="decimal"
            autoComplete="off"
            value={counted}
            onChange={(event) => setCounted(event.target.value)}
            placeholder="$ 0"
            className="text-md tabular-nums"
          />
        </Field>

        {difference !== null ? (
          <p
            className={cn('text-base font-semibold', difference === 0 ? 'text-ok-ink' : 'text-warn-ink')}
            aria-live="polite"
          >
            {difference === 0
              ? 'La caja cierra justa.'
              : difference > 0
                ? `Sobran ${formatMoney(difference, { decimals: 2 })}.`
                : `Faltan ${formatMoney(-difference, { decimals: 2 })}.`}
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
  );
}
