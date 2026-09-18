import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { Ban, ShieldAlert } from 'lucide-react';
import type { RecallAlertMessage } from '@/api/types';
import { useAuth } from '@/auth/AuthContext';
import { BarcodeDigits, ExpiryChip } from '@/components/gondola';
import { Button, Dialog, DialogContent, DialogDescription, DialogTitle } from '@/components/ui';
import { beep, vibrate } from '@/components/scanner';
import { formatDateTime, formatNumber } from '@/lib/format';
import { useStompSubscription } from '@/realtime/useStompSubscription';
import { announcementKeys, recallsApi } from '../api';
import type { RecallMatch } from '../types';

/**
 * Alerta de seguridad alimentaria (SPEC §7, design-system §7.9). Se monta una sola vez en el `AppShell` para los
 * usuarios de comercio: al entrar trae las coincidencias todavía pendientes y después escucha
 * `/user/queue/security-alerts` en vivo.
 * <p>
 * El diálogo es de atención total (`alertdialog`, no se cierra tocando afuera ni con Esc) y encola varias alertas:
 * "Entendido" confirma y muestra la siguiente, "Ver detalle y retirar del stock" lleva a Seguridad alimentaria.
 */
export default function SecurityAlertHost() {
  const { isTenantUser } = useAuth();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [queue, setQueue] = useState<RecallAlertMessage[]>([]);
  const [busy, setBusy] = useState(false);
  const seen = useRef(new Set<number>());
  const alerted = useRef(false);

  const enqueue = useCallback((alerts: RecallAlertMessage[]) => {
    const fresh = alerts.filter((alert) => !seen.current.has(alert.matchId));
    if (fresh.length === 0) return;
    fresh.forEach((alert) => seen.current.add(alert.matchId));
    setQueue((current) => [...current, ...fresh]);
  }, []);

  // Al montar: lo que quedó pendiente de sesiones anteriores (el push solo llega mientras la app está abierta).
  useEffect(() => {
    if (!isTenantUser) return undefined;
    let cancelled = false;
    void recallsApi
      .list('OPEN')
      .then((matches) => {
        if (!cancelled) enqueue(matches.map(toAlert));
      })
      .catch(() => {
        // Sin conexión o sin permisos de sucursal: la pantalla de Seguridad alimentaria muestra el error.
      });
    return () => {
      cancelled = true;
    };
  }, [enqueue, isTenantUser]);

  useStompSubscription<RecallAlertMessage>(
    isTenantUser ? '/user/queue/security-alerts' : null,
    (alert) => {
      enqueue([alert]);
      void queryClient.invalidateQueries({ queryKey: announcementKeys.recalls });
      void queryClient.invalidateQueries({ queryKey: announcementKeys.notices });
    },
  );

  const current = queue[0] ?? null;

  // Sonido + vibración una sola vez por alerta (docs/design-system.md §7.9).
  useEffect(() => {
    if (!current || alerted.current) return;
    alerted.current = true;
    beep(220, 740);
    window.setTimeout(() => beep(220, 980), 280);
    vibrate([180, 90, 180]);
  }, [current]);

  const dismiss = useCallback(() => {
    alerted.current = false;
    setQueue((rest) => rest.slice(1));
  }, []);

  const acknowledge = useCallback(async () => {
    if (!current) return;
    setBusy(true);
    try {
      await recallsApi.acknowledge(current.matchId);
      void queryClient.invalidateQueries({ queryKey: announcementKeys.recalls });
    } catch {
      // Si falla (sin conexión) igual seguimos: la alerta queda pendiente en Seguridad alimentaria.
    } finally {
      setBusy(false);
      dismiss();
    }
  }, [current, dismiss, queryClient]);

  const goToDetail = useCallback(() => {
    if (!current) return;
    dismiss();
    navigate(`/app/recalls?match=${current.matchId}`);
  }, [current, dismiss, navigate]);

  const steps = useMemo(() => buildSteps(current), [current]);

  if (!isTenantUser || !current) return null;

  return (
    <Dialog open onOpenChange={() => undefined}>
      <DialogContent
        hideClose
        overlayClassName="bg-scrim/75"
        className="max-w-[560px] gap-0 overflow-hidden border-crit/40 p-0 sm:p-0"
        onInteractOutside={(event) => event.preventDefault()}
        onEscapeKeyDown={(event) => event.preventDefault()}
        role="alertdialog"
      >
        <div className="flex items-start gap-3 bg-crit px-5 py-4 text-crit-foreground sm:px-6">
          <ShieldAlert className="mt-0.5 h-6 w-6 shrink-0" aria-hidden="true" />
          <div className="min-w-0">
            <DialogTitle className="text-lg leading-7 text-crit-foreground">
              Alerta de seguridad alimentaria
            </DialogTitle>
            <p className="text-sm opacity-90">
              Publicada por GondolIA · {formatDateTime(current.matchedAt)}
              {queue.length > 1 ? ` · 1 de ${queue.length}` : ''}
            </p>
          </div>
        </div>

        <div className="gd-scroll flex max-h-[calc(100dvh-240px)] flex-col gap-4 overflow-y-auto px-5 py-5 sm:px-6">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 flex-1 basis-[220px]">
              <div className="gd-eyebrow">Producto retirado</div>
              <div className="mt-1 font-display text-lg font-semibold leading-7 tracking-[-0.01em] text-foreground">
                {current.productName}
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                {current.lotNumber ? (
                  <span className="inline-flex h-[22px] items-center rounded-tag border border-crit/40 bg-crit-soft px-1.5 font-mono text-xs font-semibold text-crit-ink">
                    LOTE {current.lotNumber.toUpperCase()}
                  </span>
                ) : null}
                {current.expiryDate ? <ExpiryChip expiry={current.expiryDate} longYear /> : null}
              </div>
            </div>
            {current.barcode ? <BarcodeDigits code={current.barcode} width={138} /> : null}
          </div>

          <div className="flex items-center gap-3 rounded-control border border-crit/40 bg-crit-soft px-3 py-2.5 text-crit-ink">
            <Ban className="h-5 w-5 shrink-0" aria-hidden="true" />
            <p className="text-base">
              <strong className="font-semibold">
                {current.branchName} · {formatNumber(current.quantity)} u. en cuarentena.
              </strong>{' '}
              La venta de este lote ya está bloqueada en todas tus cajas.
            </p>
          </div>

          {current.reason ? (
            <DialogDescription asChild>
              <div className="text-read text-foreground">
                <div className="gd-eyebrow mb-1">Motivo</div>
                <p>{current.reason}</p>
              </div>
            </DialogDescription>
          ) : (
            <DialogDescription className="sr-only">{current.title}</DialogDescription>
          )}

          <div>
            <div className="gd-eyebrow mb-1.5">Qué tenés que hacer</div>
            <ol className="space-y-1.5">
              {steps.map((step, index) => (
                <li key={step} className="flex gap-2.5 text-base text-foreground">
                  <span
                    className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-muted text-xs font-bold tabular-nums text-muted-foreground"
                    aria-hidden="true"
                  >
                    {index + 1}
                  </span>
                  <span>{step}</span>
                </li>
              ))}
            </ol>
          </div>
        </div>

        <div className="flex flex-col-reverse gap-2 border-t bg-muted/40 px-5 py-4 sm:flex-row sm:justify-end sm:px-6">
          <Button variant="outline" onClick={() => void acknowledge()} loading={busy}>
            Entendido
          </Button>
          <Button variant="destructive" onClick={goToDetail} autoFocus>
            Ver detalle y retirar del stock
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Pasos numerados: los del recall más los fijos del producto en cuarentena. */
function buildSteps(alert: RecallAlertMessage | null): string[] {
  if (!alert) return [];
  const steps = [
    `Sacá de la góndola el lote ${alert.lotNumber ?? 'alcanzado'} de ${alert.productName} en ${alert.branchName}.`,
    'Separalo del resto de la mercadería para que nadie lo venda.',
  ];
  if (alert.instructions) steps.push(alert.instructions);
  steps.push('Cuando lo hayas retirado, marcalo como resuelto en Seguridad alimentaria.');
  return steps;
}

/** Coincidencia abierta traída al montar, con la misma forma que el push en vivo. */
function toAlert(match: RecallMatch): RecallAlertMessage {
  return {
    matchId: match.id,
    announcementId: match.announcementId,
    branchId: match.branchId,
    branchName: match.branchName,
    title: match.title,
    severity: match.severity,
    reason: match.reason,
    instructions: match.instructions,
    productId: match.productId,
    productName: match.productName,
    barcode: match.barcode,
    lotId: match.lotId,
    lotNumber: match.lotNumber,
    expiryDate: match.expiryDate,
    quantity: match.currentQuantity || match.quantityAtMatch,
    matchedAt: match.matchedAt,
  };
}
