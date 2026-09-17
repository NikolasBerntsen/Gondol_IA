import { Ban, ShieldAlert } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog"
import { BarcodeDigits } from "../components/BarcodeDigits"
import { ExpiryChip } from "../components/ExpiryChip"
import { RECALL, branchName, product } from "../data"

/**
 * Alerta de seguridad alimentaria (recall): diálogo de atención total.
 * No se cierra tocando afuera: exige "Entendido" o ir al detalle.
 */
export function RecallAlert({
  open,
  onOpenChange,
  onAcknowledge,
  onRemove,
}: {
  open: boolean
  onOpenChange: (o: boolean) => void
  onAcknowledge: () => void
  onRemove: () => void
}) {
  const p = product(RECALL.productId)
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        hideClose
        overlayClassName="bg-scrim/75"
        className="max-w-[560px] gap-0 overflow-hidden border-crit/40 p-0 sm:p-0"
        onInteractOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={() => onAcknowledge()}
        role="alertdialog"
      >
        <div className="flex items-start gap-3 bg-crit px-5 py-4 text-crit-foreground sm:px-6">
          <ShieldAlert className="mt-0.5 h-6 w-6 shrink-0" aria-hidden="true" />
          <div className="min-w-0">
            <DialogTitle className="text-lg leading-7 text-crit-foreground">Alerta de seguridad alimentaria</DialogTitle>
            <p className="text-sm opacity-90">
              Publicada por GondolIA {RECALL.publishedAt} · {RECALL.reference}
            </p>
          </div>
        </div>

        <div className="gd-scroll flex max-h-[calc(100dvh-220px)] flex-col gap-4 overflow-y-auto px-5 py-5 sm:px-6">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="min-w-0 flex-1 basis-[220px]">
              <div className="gd-eyebrow">Producto retirado</div>
              <div className="mt-1 font-display text-lg font-semibold leading-7 tracking-[-0.01em] text-foreground">{RECALL.productName}</div>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <span className="inline-flex h-[22px] items-center rounded-tag border border-crit/40 bg-crit-soft px-1.5 font-mono text-xs font-semibold text-crit-ink">
                  LOTE {RECALL.lot}
                </span>
                <ExpiryChip expiry={RECALL.expiry} bucket="OK" longYear />
              </div>
            </div>
            <BarcodeDigits code={p.ean} width={138} />
          </div>

          <div className="flex items-center gap-3 rounded-control border border-crit/40 bg-crit-soft px-3 py-2.5 text-crit-ink">
            <Ban className="h-5 w-5 shrink-0" aria-hidden="true" />
            <p className="text-base">
              <strong className="font-semibold">Sucursal {branchName(RECALL.branch)} · {RECALL.units} unidades en cuarentena.</strong> La venta de este lote ya
              está bloqueada en todas tus cajas.
            </p>
          </div>

          <DialogDescription asChild>
            <div className="text-read text-foreground">
              <div className="gd-eyebrow mb-1">Motivo</div>
              <p>{RECALL.reason}</p>
            </div>
          </DialogDescription>

          <div>
            <div className="gd-eyebrow mb-1.5">Qué tenés que hacer</div>
            <ol className="space-y-1.5">
              {RECALL.steps.map((s, i) => (
                <li key={i} className="flex gap-2.5 text-base text-foreground">
                  <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full bg-muted text-xs font-bold tabular-nums text-muted-foreground" aria-hidden="true">
                    {i + 1}
                  </span>
                  <span>{s}</span>
                </li>
              ))}
            </ol>
          </div>
        </div>

        <div className="flex flex-col-reverse gap-2 border-t bg-muted/40 px-5 py-4 sm:flex-row sm:justify-end sm:px-6">
          <Button variant="outline" onClick={onAcknowledge}>
            Entendido
          </Button>
          <Button variant="destructive" onClick={onRemove} autoFocus>
            Ver detalle y retirar del stock
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
