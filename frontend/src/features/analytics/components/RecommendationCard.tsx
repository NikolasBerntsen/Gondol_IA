import { useState } from 'react';
import { CalendarClock, Check, Lightbulb, Package, Trash2, TrendingDown } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { Button, Field, Input, Modal, Textarea } from '@/components/ui';
import { StatusPill } from '@/components/gondola';
import { RECOMMENDATION_STATUS_LABELS, RECOMMENDATION_TYPE_LABELS } from '@/api/types';
import type { RecommendationType } from '@/api/types';
import { cn } from '@/lib/cn';
import { formatDate, formatMoney, formatNumber, formatRatio } from '@/lib/format';
import type { RecommendationRow } from '../types';

const TYPE_ICON: Record<RecommendationType, LucideIcon> = {
  REORDER: Package,
  DISCOUNT: CalendarClock,
  REMOVE_EXPIRED: Trash2,
  REVIEW_ANOMALY: Lightbulb,
  REDUCE_PURCHASE: TrendingDown,
};

export interface AcceptValues {
  note?: string;
  quantity?: number;
  discountPct?: number;
}

export interface RecommendationCardProps {
  recommendation: RecommendationRow;
  /** El jefe solo lee (SPEC §3.3). */
  readOnly: boolean;
  showBranch: boolean;
  busy?: boolean;
  onAccept: (values: AcceptValues) => void;
  onDiscard: (note?: string) => void;
  className?: string;
}

/**
 * Una recomendación de la IA con su explicación, su impacto y las dos acciones del administrador.
 * "Aceptar" abre un diálogo cuando hay algo que confirmar (cantidad a pedir, descuento a aplicar).
 */
export function RecommendationCard({
  recommendation,
  readOnly,
  showBranch,
  busy = false,
  onAccept,
  onDiscard,
  className,
}: RecommendationCardProps) {
  const [acceptOpen, setAcceptOpen] = useState(false);
  const [discardOpen, setDiscardOpen] = useState(false);
  const [note, setNote] = useState('');
  const [quantity, setQuantity] = useState('');
  const [discountPct, setDiscountPct] = useState('');

  const Icon = TYPE_ICON[recommendation.type];
  const decided = recommendation.status !== 'PENDING';
  const asksQuantity = recommendation.type === 'REORDER' || recommendation.type === 'REMOVE_EXPIRED';
  const asksDiscount = recommendation.type === 'DISCOUNT';

  const openAccept = () => {
    setNote('');
    setQuantity(recommendation.suggestedQuantity != null ? String(recommendation.suggestedQuantity) : '');
    setDiscountPct(recommendation.suggestedDiscountPct != null ? String(recommendation.suggestedDiscountPct) : '');
    setAcceptOpen(true);
  };

  const confirmAccept = () => {
    const values: AcceptValues = {};
    if (note.trim()) values.note = note.trim();
    if (asksQuantity && quantity.trim()) values.quantity = Number(quantity);
    if (asksDiscount && discountPct.trim()) values.discountPct = Number(discountPct);
    setAcceptOpen(false);
    onAccept(values);
  };

  const confirmDiscard = () => {
    const value = note.trim() ? note.trim() : undefined;
    setDiscardOpen(false);
    onDiscard(value);
  };

  return (
    <li className={cn('flex min-w-0 flex-col gap-3 p-4 sm:p-5', decided && 'opacity-70', className)}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="gd-eyebrow flex items-center gap-1.5 text-muted-foreground">
          <Icon className="size-3.5" aria-hidden="true" />
          {RECOMMENDATION_TYPE_LABELS[recommendation.type]}
        </span>
        {showBranch && recommendation.branchName ? (
          <span className="text-xs text-muted-foreground">{recommendation.branchName}</span>
        ) : null}
      </div>

      <h3 className="text-md font-semibold leading-6 text-foreground">{recommendation.title}</h3>
      <p className="text-base text-muted-foreground">{recommendation.explanation}</p>

      <dl className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5 text-sm">
        {recommendation.confidence != null ? (
          <>
            <dt className="text-muted-foreground">Confianza</dt>
            <dd className="flex items-center gap-2">
              <span className="h-1.5 w-full max-w-[120px] overflow-hidden rounded-full bg-muted" aria-hidden="true">
                <span
                  className="block h-full rounded-full bg-primary"
                  style={{ width: `${Math.round(recommendation.confidence * 100)}%` }}
                />
              </span>
              <span className="font-semibold tabular-nums">{formatRatio(recommendation.confidence)}</span>
            </dd>
          </>
        ) : null}
        {recommendation.expectedImpact != null ? (
          <>
            <dt className="text-muted-foreground">Impacto</dt>
            <dd className="font-medium tabular-nums text-foreground">{formatMoney(recommendation.expectedImpact)}</dd>
          </>
        ) : null}
        {recommendation.suggestedQuantity != null ? (
          <>
            <dt className="text-muted-foreground">Cantidad</dt>
            <dd className="font-medium tabular-nums text-foreground">
              {formatNumber(recommendation.suggestedQuantity)} u.
            </dd>
          </>
        ) : null}
        {recommendation.suggestedDiscountPct != null ? (
          <>
            <dt className="text-muted-foreground">Descuento</dt>
            <dd className="font-medium tabular-nums text-foreground">{recommendation.suggestedDiscountPct}%</dd>
          </>
        ) : null}
        {recommendation.lotNumber ? (
          <>
            <dt className="text-muted-foreground">Lote</dt>
            <dd className="font-mono text-sm text-foreground">
              {recommendation.lotNumber}
              {recommendation.lotExpiryDate ? ` · vence ${formatDate(recommendation.lotExpiryDate)}` : ''}
            </dd>
          </>
        ) : null}
        {recommendation.suggestedDate ? (
          <>
            <dt className="text-muted-foreground">Para el</dt>
            <dd className="font-medium text-foreground">{formatDate(recommendation.suggestedDate)}</dd>
          </>
        ) : null}
      </dl>

      <div className="mt-auto flex flex-wrap items-center gap-2 pt-1">
        {decided ? (
          <>
            <StatusPill tone={recommendation.status === 'ACCEPTED' ? 'ok' : 'neutral'}>
              {RECOMMENDATION_STATUS_LABELS[recommendation.status]}
            </StatusPill>
            {recommendation.decidedByName ? (
              <span className="text-sm text-muted-foreground">por {recommendation.decidedByName}</span>
            ) : null}
          </>
        ) : readOnly ? (
          <span className="text-sm text-muted-foreground">Solo el jefe o el administrador pueden aceptarla o descartarla.</span>
        ) : (
          <>
            <Button size="sm" leftIcon={<Check aria-hidden="true" />} loading={busy} onClick={openAccept}>
              Aceptar
            </Button>
            <Button
              size="sm"
              variant="ghost"
              disabled={busy}
              onClick={() => {
                setNote('');
                setDiscardOpen(true);
              }}
            >
              Descartar
            </Button>
          </>
        )}
      </div>

      <Modal
        open={acceptOpen}
        onClose={() => setAcceptOpen(false)}
        title="Aceptar la recomendación"
        description={recommendation.title}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setAcceptOpen(false)}>
              Cancelar
            </Button>
            <Button onClick={confirmAccept}>Aceptar y aplicar</Button>
          </>
        }
      >
        <div className="flex flex-col gap-4">
          {asksQuantity ? (
            <Field
              label={recommendation.type === 'REORDER' ? 'Unidades a pedir' : 'Unidades a descartar'}
              hint={
                recommendation.type === 'REORDER'
                  ? 'Te armamos el texto del pedido para mandarle al proveedor.'
                  : 'Se registran como merma por vencimiento y salen del stock.'
              }
            >
              <Input
                type="number"
                min={1}
                inputMode="numeric"
                value={quantity}
                onChange={(event) => setQuantity(event.target.value)}
                data-autofocus
              />
            </Field>
          ) : null}
          {asksDiscount ? (
            <Field
              label="Descuento a aplicar (%)"
              hint="El lote pasa a venderse primero y la caja cobra con el descuento."
            >
              <Input
                type="number"
                min={1}
                max={100}
                inputMode="numeric"
                value={discountPct}
                onChange={(event) => setDiscountPct(event.target.value)}
                data-autofocus
              />
            </Field>
          ) : null}
          <Field label="Nota" optional>
            <Textarea
              rows={2}
              value={note}
              placeholder="Por qué la aceptás (lo ve el resto del equipo)"
              onChange={(event) => setNote(event.target.value)}
            />
          </Field>
        </div>
      </Modal>

      <Modal
        open={discardOpen}
        onClose={() => setDiscardOpen(false)}
        title="Descartar la recomendación"
        description="La IA no vuelve a sugerir lo mismo durante 7 días."
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setDiscardOpen(false)}>
              Cancelar
            </Button>
            <Button variant="destructive" onClick={confirmDiscard}>
              Descartar
            </Button>
          </>
        }
      >
        <Field label="Motivo" optional>
          <Textarea
            rows={2}
            value={note}
            placeholder="Por ejemplo: fue una promo puntual"
            onChange={(event) => setNote(event.target.value)}
            data-autofocus
          />
        </Field>
      </Modal>
    </li>
  );
}
