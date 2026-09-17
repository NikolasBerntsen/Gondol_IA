import { ShieldAlert } from 'lucide-react';
import { Link } from 'react-router-dom';
import { cn } from '@/lib/cn';
import type { RecallInfo } from '../types';

export interface RecallPanelProps {
  recalls: readonly RecallInfo[];
  /** `true` cuando el lote ya quedó retenido en cuarentena (después de cargarlo). */
  quarantined?: boolean;
  className?: string;
}

/**
 * Aviso de seguridad alimentaria: el código que se está cargando coincide con un recall publicado
 * (SPEC §6.3). Bloquea la carga hasta que la persona revise el producto.
 */
export function RecallPanel({ recalls, quarantined, className }: RecallPanelProps) {
  if (recalls.length === 0) return null;
  return (
    <div
      role="alert"
      className={cn('gd-stripe-crit rounded-panel border border-crit/40 bg-crit-soft p-4 text-crit-ink', className)}
    >
      <div className="flex items-start gap-3">
        <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="text-md font-semibold">
            {quarantined ? 'Este lote quedó en cuarentena' : 'No cargues este producto'}
          </p>
          <p className="mt-0.5 text-base">
            {quarantined
              ? 'Coincide con una alerta de seguridad alimentaria: no se puede vender hasta que lo retires del stock.'
              : 'Hay una alerta de seguridad alimentaria vigente para este código. Revisá el envase antes de seguir.'}
          </p>

          <ul className="mt-3 space-y-3">
            {recalls.map((recall) => (
              <li key={recall.announcementId} className="rounded-control border border-crit/30 bg-card/60 p-3">
                <p className="text-base font-semibold text-foreground">{recall.title}</p>
                {recall.reason && <p className="mt-1 text-base text-muted-foreground">{recall.reason}</p>}
                {recall.instructions && (
                  <p className="mt-1.5 text-base text-foreground">
                    <span className="gd-eyebrow block">Qué hacer</span>
                    {recall.instructions}
                  </p>
                )}
                <p className="mt-1.5 text-sm text-muted-foreground">
                  {recall.allLots ? 'Alcanza a todos los lotes del producto.' : 'Alcanza a lotes puntuales.'}
                </p>
              </li>
            ))}
          </ul>

          <Link
            to="/app/recalls"
            className="mt-3 inline-block text-base font-semibold text-crit-ink underline underline-offset-2"
          >
            Ver Seguridad alimentaria
          </Link>
        </div>
      </div>
    </div>
  );
}
