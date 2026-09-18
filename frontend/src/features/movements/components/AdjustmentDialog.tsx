import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { PackageSearch, SlidersHorizontal } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';
import { getErrorMessage, getFieldErrors } from '@/api/client';
import { useAuth } from '@/auth/AuthContext';
import { ExpiryChip, LotRankChip } from '@/components/gondola';
import {
  Badge,
  Button,
  Field,
  Input,
  Modal,
  Select,
  Spinner,
  Textarea,
} from '@/components/ui';
import { LOT_STATUS_LABELS } from '@/api/types';
import { formatDate, formatNumber } from '@/lib/format';
import { lotPickApi, movementKeys, movementsApi, type LotPick, type ProductPick } from '../api';
import { ProductPicker } from './ProductPicker';
import {
  ADJUSTMENT_HINTS,
  ADJUSTMENT_TYPES,
  EMPLOYEE_ADJUSTMENTS,
  MOVEMENT_TYPE_LABELS_EXT,
  type AdjustmentType,
} from '../types';

export interface AdjustmentDialogProps {
  open: boolean;
  onClose: () => void;
  /** Lote pre-elegido (p. ej. desde la fila de un movimiento). */
  initialLot?: LotPick | null;
}

/**
 * Registra un ajuste de stock sobre un lote. El administrador puede usar cualquier tipo; el empleado,
 * solo mermas por vencimiento o daño (SPEC §3.3): el `<select>` se limita según el rol.
 */
export function AdjustmentDialog({ open, onClose, initialLot = null }: AdjustmentDialogProps) {
  const queryClient = useQueryClient();
  const { hasRole } = useAuth();
  const isAdmin = hasRole('TENANT_ADMIN');
  const allowedTypes = isAdmin ? ADJUSTMENT_TYPES : EMPLOYEE_ADJUSTMENTS;

  const [product, setProduct] = useState<ProductPick | null>(null);
  const [lot, setLot] = useState<LotPick | null>(initialLot);
  const [type, setType] = useState<AdjustmentType>(allowedTypes[0]);
  const [quantity, setQuantity] = useState('1');
  const [reason, setReason] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const lots = useQuery({
    queryKey: ['movements', 'lots', product?.id],
    queryFn: () => lotPickApi.byProduct(product?.id as number),
    enabled: open && !!product,
  });

  const reset = () => {
    setProduct(null);
    setLot(initialLot);
    setType(allowedTypes[0]);
    setQuantity('1');
    setReason('');
    setErrors({});
  };

  const close = () => {
    reset();
    onClose();
  };

  const adjust = useMutation({
    mutationFn: () =>
      movementsApi.adjust({
        lotId: lot?.id as number,
        type,
        quantity: Number(quantity),
        reason: reason.trim() || undefined,
      }),
    onSuccess: (movement) => {
      toast.success(`Registraste el ajuste: ${movement.typeLabel} de ${formatNumber(movement.quantity)} u.`);
      queryClient.invalidateQueries({ queryKey: movementKeys.movements });
      queryClient.invalidateQueries({ queryKey: movementKeys.expirations });
      queryClient.invalidateQueries({ queryKey: ['products'] });
      close();
    },
    onError: (error) => {
      const fieldErrors = getFieldErrors(error);
      setErrors(fieldErrors);
      if (Object.keys(fieldErrors).length === 0) {
        toast.error(getErrorMessage(error, 'No pudimos registrar el ajuste.'));
      }
    },
  });

  const parsedQuantity = Number(quantity);
  const isOutbound = type !== 'ADJUSTMENT_IN';
  const exceedsStock = !!lot && isOutbound && parsedQuantity > lot.quantity;
  const canSubmit = !!lot && Number.isFinite(parsedQuantity) && parsedQuantity >= 1 && !exceedsStock;

  return (
    <Modal
      open={open}
      onClose={close}
      title="Registrar un ajuste de stock"
      description={
        isAdmin
          ? 'Corregí el remanente de un lote o dalo de baja por vencimiento, daño o recall.'
          : 'Podés dar de baja mercadería vencida o dañada de tus sucursales.'
      }
      size="lg"
      preventClose={adjust.isPending}
      footer={
        <>
          <Button variant="outline" onClick={close} disabled={adjust.isPending}>
            Cancelar
          </Button>
          <Button
            loading={adjust.isPending}
            disabled={!canSubmit}
            onClick={() => {
              setErrors({});
              adjust.mutate();
            }}
          >
            Registrar ajuste
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        {!lot ? (
          <>
            <Field label="Producto" hint="Buscá o escaneá el producto del lote que querés ajustar.">
              {() => <ProductPicker onPick={setProduct} branchId={null} />}
            </Field>

            {product ? (
              <Field label={`Lote de ${product.name}`}>
                {() =>
                  lots.isPending ? (
                    <p className="flex items-center gap-2 text-sm text-muted-foreground">
                      <Spinner className="h-4 w-4" /> Buscando los lotes…
                    </p>
                  ) : lots.isError ? (
                    <p className="text-sm text-crit-ink">{getErrorMessage(lots.error)}</p>
                  ) : (lots.data?.length ?? 0) === 0 ? (
                    <p className="flex items-center gap-2 text-sm text-muted-foreground">
                      <PackageSearch className="h-4 w-4" aria-hidden="true" />
                      Este producto no tiene lotes cargados en tus sucursales.
                    </p>
                  ) : (
                    <ul className="flex max-h-64 flex-col gap-1.5 overflow-y-auto gd-scroll">
                      {lots.data?.map((row) => (
                        <li key={row.id}>
                          <button
                            type="button"
                            onClick={() => setLot(row)}
                            className="flex w-full flex-wrap items-center justify-between gap-2 rounded-control border border-input px-3 py-2 text-left transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                          >
                            <span className="flex flex-wrap items-center gap-1.5">
                              {row.expiryDate ? (
                                <ExpiryChip
                                  expiry={row.expiryDate}
                                  lot={row.lotNumber}
                                  bucket={row.expiryBucket ?? undefined}
                                />
                              ) : (
                                <Badge tone="neutral" className="font-mono">
                                  {row.lotNumber ?? `Lote #${row.id}`}
                                </Badge>
                              )}
                              {row.rotationRank ? (
                                <LotRankChip rank={row.rotationRank} discounted={!!row.discountPct} />
                              ) : null}
                              <Badge tone="neutral">{LOT_STATUS_LABELS[row.status]}</Badge>
                            </span>
                            <span className="text-sm tabular-nums text-muted-foreground">
                              {row.branchName} · {formatNumber(row.quantity)} u.
                            </span>
                          </button>
                        </li>
                      ))}
                    </ul>
                  )
                }
              </Field>
            ) : null}
          </>
        ) : (
          <div className="flex flex-wrap items-center justify-between gap-2 rounded-panel border border-border px-3 py-2.5">
            <span className="flex flex-wrap items-center gap-1.5">
              {lot.expiryDate ? (
                <ExpiryChip expiry={lot.expiryDate} lot={lot.lotNumber} bucket={lot.expiryBucket ?? undefined} />
              ) : (
                <Badge tone="neutral" className="font-mono">
                  {lot.lotNumber ?? `Lote #${lot.id}`}
                </Badge>
              )}
              <span className="text-sm tabular-nums text-muted-foreground">
                {lot.branchName} · {formatNumber(lot.quantity)} u. · ingresó {formatDate(lot.receivedAt)}
              </span>
            </span>
            {!initialLot ? (
              <Button variant="ghost" size="sm" onClick={() => setLot(null)}>
                Cambiar lote
              </Button>
            ) : null}
          </div>
        )}

        {lot ? (
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Tipo de ajuste" hint={ADJUSTMENT_HINTS[type]} error={errors.type}>
              <Select
                value={type}
                options={allowedTypes.map((value) => ({ value, label: MOVEMENT_TYPE_LABELS_EXT[value] }))}
                onChange={(event) => setType(event.target.value as AdjustmentType)}
              />
            </Field>

            <Field
              label="Cantidad"
              error={
                errors.quantity ??
                (exceedsStock ? `El lote tiene ${formatNumber(lot.quantity)} u. disponibles.` : undefined)
              }
            >
              <Input
                type="number"
                min={1}
                step={1}
                inputMode="numeric"
                value={quantity}
                onChange={(event) => setQuantity(event.target.value)}
                className="tabular-nums"
                data-autofocus
              />
            </Field>

            <Field label="Motivo" optional error={errors.reason} className="sm:col-span-2">
              <Textarea
                rows={2}
                value={reason}
                maxLength={300}
                placeholder="Rotura en depósito, conteo corregido…"
                onChange={(event) => setReason(event.target.value)}
              />
            </Field>
          </div>
        ) : null}

        {!isAdmin ? (
          <p className="flex items-start gap-2 text-sm text-muted-foreground">
            <SlidersHorizontal className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
            Los ajustes de conteo y los retiros por recall los registra el administrador.
          </p>
        ) : null}
      </div>
    </Modal>
  );
}
