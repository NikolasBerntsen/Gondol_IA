import { AlertTriangle, Ban, X } from 'lucide-react';
import { cn } from '@/lib/cn';

/** Avisos del mostrador: bloquean (recall) o advierten sin bloquear (sin stock, tope, no encontrado). */
export type PosNoticeData =
  | { kind: 'recall'; productName: string }
  | { kind: 'out'; productName: string; branchName: string | null }
  | { kind: 'limit'; productName: string; available: number }
  | { kind: 'notfound'; query: string };

export interface PosNoticeProps {
  notice: PosNoticeData;
  onClose: () => void;
  /** "Vender igual": solo para el aviso de sin stock (venta con faltante, SPEC §15.2). */
  onSellAnyway?: () => void;
}

/** Aviso en línea del mostrador, siempre a la vista del cajero y con salida clara. */
export function PosNotice({ notice, onClose, onSellAnyway }: PosNoticeProps) {
  const crit = notice.kind === 'recall';
  return (
    <div
      role="alert"
      className={cn(
        'flex items-start gap-3 rounded-control border px-3 py-2.5',
        crit ? 'border-crit/50 bg-crit-soft text-crit-ink' : 'border-warn/40 bg-warn-soft text-warn-ink',
      )}
    >
      {crit ? (
        <Ban className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
      ) : (
        <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
      )}
      <div className="min-w-0 flex-1 text-base">
        {notice.kind === 'recall' ? (
          <>
            <strong className="font-semibold">
              {notice.productName} · En cuarentena por recall · no se puede vender.
            </strong>
            <span className="block text-sm">
              Hay una alerta de seguridad alimentaria activa para ese lote. Sacalo de la bolsa y avisá al encargado.
            </span>
          </>
        ) : notice.kind === 'out' ? (
          <>
            <strong className="font-semibold">
              {notice.productName} figura sin stock{notice.branchName ? ` en ${notice.branchName}` : ''}.
            </strong>
            <span className="block text-sm">
              Si lo tenés en la mano podés venderlo igual: queda registrado como faltante para que el encargado
              ajuste el stock.
            </span>
            {onSellAnyway ? (
              <button
                type="button"
                onClick={onSellAnyway}
                className="mt-1.5 rounded-control px-2 py-1 text-sm font-semibold underline underline-offset-2 hover:bg-card/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                Vender igual
              </button>
            ) : null}
          </>
        ) : notice.kind === 'limit' ? (
          <>
            <strong className="font-semibold">Stock insuficiente de {notice.productName}.</strong>
            <span className="block text-sm">
              Quedan {notice.available} u. vendibles en esta sucursal.
              {onSellAnyway ? ' Podés vender igual y registrar el faltante.' : ''}
            </span>
            {onSellAnyway ? (
              <button
                type="button"
                onClick={onSellAnyway}
                className="mt-1.5 rounded-control px-2 py-1 text-sm font-semibold underline underline-offset-2 hover:bg-card/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                Vender igual
              </button>
            ) : null}
          </>
        ) : (
          <>
            <strong className="font-semibold">No encontramos «{notice.query}».</strong>
            <span className="block text-sm">Revisá el código o buscá por nombre o marca.</span>
          </>
        )}
      </div>
      <button
        type="button"
        onClick={onClose}
        className="grid h-7 w-7 shrink-0 place-items-center rounded-control hover:bg-card/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        aria-label="Cerrar aviso"
      >
        <X className="h-4 w-4" aria-hidden="true" />
      </button>
    </div>
  );
}
