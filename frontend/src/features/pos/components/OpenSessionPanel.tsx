import { useEffect, useMemo, useState } from 'react';
import { Lock, MonitorSmartphone, Store } from 'lucide-react';
import { StatusPill } from '@/components/gondola';
import {
  Alert,
  Button,
  Card,
  EmptyState,
  ErrorState,
  Field,
  Input,
  Skeleton,
  Truncate,
} from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatMoney, formatTime } from '@/lib/format';
import { parseArs } from '../money';
import type { PosRegister } from '../types';

export interface OpenSessionPanelProps {
  registers: PosRegister[] | undefined;
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  onOpen: (values: { registerId: number; openingCash: number }) => void;
  pending: boolean;
  /** Mensaje del backend (409 REGISTER_BUSY, BRANCH_REQUIRED…). */
  openError?: string | null;
}

/**
 * Pantalla previa del mostrador cuando el usuario no tiene un turno abierto (SPEC §15.3):
 * elegir caja libre y declarar el efectivo inicial.
 */
export function OpenSessionPanel({
  registers,
  loading,
  error,
  onRetry,
  onOpen,
  pending,
  openError,
}: OpenSessionPanelProps) {
  const available = useMemo(() => (registers ?? []).filter((register) => register.active), [registers]);
  const [registerId, setRegisterId] = useState<number | null>(null);
  const [cash, setCash] = useState('');
  const [touched, setTouched] = useState(false);

  useEffect(() => {
    if (registerId === null) {
      const free = available.find((register) => !register.openSession);
      if (free) setRegisterId(free.id);
    }
  }, [available, registerId]);

  const openingCash = cash.trim() === '' ? 0 : parseArs(cash);
  const selected = available.find((register) => register.id === registerId) ?? null;
  const busy = !!selected?.openSession;
  const cashError = touched && openingCash === null ? 'Escribí el efectivo inicial (o dejalo vacío si arrancás en 0).' : undefined;

  const submit = () => {
    setTouched(true);
    if (registerId === null || openingCash === null || busy) return;
    onOpen({ registerId, openingCash });
  };

  if (loading) {
    return (
      <Card padding="lg" className="mx-auto w-full max-w-xl">
        <Skeleton className="h-6 w-48" />
        <Skeleton className="mt-3 h-4 w-full max-w-sm" />
        <div className="mt-6 grid gap-2">
          <Skeleton className="h-16 w-full" />
          <Skeleton className="h-16 w-full" />
        </div>
        <Skeleton className="mt-6 h-12 w-full" />
      </Card>
    );
  }

  if (error) {
    return (
      <Card padding="lg" className="mx-auto w-full max-w-xl">
        <ErrorState error={error} onRetry={onRetry} title="No pudimos traer las cajas" />
      </Card>
    );
  }

  if (!available.length) {
    return (
      <Card padding="lg" className="mx-auto w-full max-w-xl">
        <EmptyState
          icon={MonitorSmartphone}
          title="No hay cajas activas en esta sucursal"
          description="Pedile al administrador que cree una caja en Punto de venta · Cajas para poder abrir tu turno."
        />
      </Card>
    );
  }

  return (
    <Card padding="lg" className="mx-auto w-full max-w-xl">
      <div className="flex items-start gap-3">
        <span className="grid h-10 w-10 shrink-0 place-items-center rounded-panel bg-primary/10 text-primary">
          <Store className="h-5 w-5" aria-hidden="true" />
        </span>
        <div>
          <h2 className="font-display text-lg font-semibold leading-7 tracking-[-0.01em]">Abrí tu caja</h2>
          <p className="text-base text-muted-foreground">
            Elegí la caja y contá el efectivo con el que arrancás. Todo lo que cobres queda en este turno.
          </p>
        </div>
      </div>

      <form
        className="mt-6 grid gap-5"
        onSubmit={(event) => {
          event.preventDefault();
          submit();
        }}
      >
        <fieldset>
          <legend className="gd-eyebrow mb-2">Caja</legend>
          <div className="grid gap-2">
            {available.map((register) => {
              const occupied = !!register.openSession;
              const active = register.id === registerId;
              return (
                <button
                  key={register.id}
                  type="button"
                  onClick={() => setRegisterId(register.id)}
                  aria-pressed={active}
                  disabled={occupied}
                  className={cn(
                    'flex items-center justify-between gap-3 rounded-control border px-3 py-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                    occupied
                      ? 'cursor-not-allowed border-border bg-muted/50 text-muted-foreground'
                      : active
                        ? 'border-primary bg-primary/[0.06]'
                        : 'border-input bg-card hover:bg-muted',
                  )}
                >
                  <span className="min-w-0">
                    <span className="block text-base font-semibold text-foreground">{register.name}</span>
                    <Truncate className="block text-sm text-muted-foreground">
                      {register.branchName ?? 'Sucursal'}
                    </Truncate>
                  </span>
                  {occupied ? (
                    <span className="flex shrink-0 items-center gap-2">
                      <Lock className="h-4 w-4" aria-hidden="true" />
                      <span className="text-sm">
                        Ocupada por {register.openSession?.openedByName ?? 'otro turno'}
                        {register.openSession ? ` · ${formatTime(register.openSession.openedAt)}` : ''}
                      </span>
                    </span>
                  ) : (
                    <StatusPill tone="ok">Libre</StatusPill>
                  )}
                </button>
              );
            })}
          </div>
        </fieldset>

        <Field label="Efectivo inicial" hint="Lo que hay en el cajón para dar vuelto." error={cashError} optional>
          <Input
            data-autofocus
            inputMode="decimal"
            autoComplete="off"
            value={cash}
            onChange={(event) => setCash(event.target.value)}
            placeholder="$ 0"
            inputSize="lg"
            className="text-md tabular-nums"
          />
        </Field>

        {openingCash !== null && openingCash > 0 ? (
          <p className="text-base text-muted-foreground" aria-live="polite">
            Arrancás con{' '}
            <span className="font-semibold tabular-nums text-foreground">
              {formatMoney(openingCash, { decimals: 2 })}
            </span>{' '}
            en el cajón.
          </p>
        ) : null}

        {openError ? (
          <Alert tone="warn" title="No pudimos abrir el turno">
            {openError}
          </Alert>
        ) : null}

        <Button
          type="submit"
          size="xl"
          fullWidth
          loading={pending}
          disabled={registerId === null || busy}
        >
          Abrir caja y empezar a vender
        </Button>
      </form>
    </Card>
  );
}
