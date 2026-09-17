import { useEffect, useState } from 'react';
import { Button, Field, Input, Modal, Select } from '@/components/ui';
import { formatMoney } from '@/lib/format';
import { CASH_IN_REASONS, CASH_OUT_REASONS, type CashMovementType } from '../types';
import { parseArs } from '../money';

export interface CashMovementDialogProps {
  open: boolean;
  type: CashMovementType;
  onClose: () => void;
  onSubmit: (values: { amount: number; reason: string }) => void;
  pending: boolean;
}

/** Ingreso o retiro de efectivo del turno: queda en el arqueo (SPEC §15.2). */
export function CashMovementDialog({ open, type, onClose, onSubmit, pending }: CashMovementDialogProps) {
  const isOut = type === 'CASH_OUT';
  const reasons = isOut ? CASH_OUT_REASONS : CASH_IN_REASONS;
  const [amount, setAmount] = useState('');
  const [reason, setReason] = useState<string>(reasons[0]);
  const [other, setOther] = useState('');
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (open) {
      setAmount('');
      setReason(reasons[0]);
      setOther('');
      setTouched(false);
    }
  }, [open, reasons]);

  const value = parseArs(amount);
  const finalReason = reason === 'Otro' ? other.trim() : reason;
  const amountError = touched && (value === null || value <= 0) ? 'Escribí un monto mayor a cero.' : undefined;
  const reasonError = touched && !finalReason ? 'Contanos el motivo.' : undefined;
  const valid = value !== null && value > 0 && finalReason.length > 0;

  const submit = () => {
    setTouched(true);
    if (!valid || value === null) return;
    onSubmit({ amount: value, reason: finalReason });
  };

  return (
    <Modal
      open={open}
      onClose={onClose}
      preventClose={pending}
      size="sm"
      title={isOut ? 'Retiro de efectivo' : 'Ingreso de efectivo'}
      description={
        isOut
          ? 'Registrá el efectivo que sale del cajón. Queda en el arqueo del turno.'
          : 'Registrá el efectivo que entra al cajón fuera de una venta.'
      }
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={pending}>
            Cancelar
          </Button>
          <Button onClick={submit} loading={pending}>
            {isOut ? 'Registrar retiro' : 'Registrar ingreso'}
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
        <Field
          label={isOut ? 'Monto a retirar' : 'Monto que entra'}
          hint="Usá punto para los miles: 15.000"
          error={amountError}
        >
          <Input
            data-autofocus
            inputMode="decimal"
            autoComplete="off"
            value={amount}
            onChange={(event) => setAmount(event.target.value)}
            placeholder="$ 0"
            className="text-md tabular-nums"
          />
        </Field>
        <Field label="Motivo" error={reasonError}>
          <Select
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            options={reasons.map((item) => ({ value: item, label: item }))}
          />
        </Field>
        {reason === 'Otro' ? (
          <Field label="Contanos el motivo">
            <Input
              value={other}
              onChange={(event) => setOther(event.target.value)}
              maxLength={300}
              placeholder="Ej.: compra de bolsas"
            />
          </Field>
        ) : null}
        {value !== null && value > 0 ? (
          <p className="text-base text-muted-foreground" aria-live="polite">
            {isOut ? 'Salen' : 'Entran'}{' '}
            <span className="font-semibold tabular-nums text-foreground">{formatMoney(value, { decimals: 2 })}</span>{' '}
            del efectivo del turno.
          </p>
        ) : null}
      </form>
    </Modal>
  );
}
