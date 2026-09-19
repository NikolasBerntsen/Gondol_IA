import { PackagePlus, ShieldAlert } from 'lucide-react';
import { Card, CardHeader, Progress } from '@/components/ui';
import { formatNumber } from '@/lib/format';
import { summarizeNames } from '../labels';
import type { ImportJob } from '../types';

/** Cuántos nombres se muestran antes de resumir el resto como «y N más». */
const MAX_NAMES = 3;

/** Cantidad y nombres de lo que se va a crear, sin esconder ninguno sin decirlo. */
function namesItem(label: string, names: readonly string[], none: string) {
  if (!names.length) return { label, value: none };
  return {
    label,
    value: `${formatNumber(names.length)} · ${summarizeNames(names, MAX_NAMES)}`,
    title: names.join(', '),
  };
}

export interface ConfirmStepProps {
  job: ImportJob;
  /** `true` mientras la importación se está aplicando (barra de progreso). */
  applying: boolean;
}

/** Paso 4 «Confirmar»: qué va a pasar con el inventario y la barra de progreso (SPEC §16.4). */
export function ConfirmStep({ job, applying }: ConfirmStepProps) {
  const preview = job.preview;
  const items: Array<{ label: string; value: string; title?: string }> = preview
    ? [
        { label: 'Productos nuevos', value: formatNumber(preview.productsToCreate) },
        { label: 'Productos a actualizar', value: formatNumber(preview.productsToUpdate) },
        { label: 'Lotes a crear', value: formatNumber(preview.lotsToCreate) },
        { label: 'Unidades a cargar', value: formatNumber(preview.unitsToLoad) },
        namesItem('Categorías nuevas', preview.categoriesToCreate, 'Ninguna'),
        namesItem('Proveedores nuevos', preview.suppliersToCreate, 'Ninguno'),
        { label: 'Filas omitidas', value: formatNumber(preview.rowsToSkip + preview.rowsWithErrors) },
      ]
    : [];
  const recallWarnings = preview?.recallWarnings ?? [];
  const visibleRecallWarnings =
    recallWarnings.length <= MAX_NAMES + 1 ? recallWarnings : recallWarnings.slice(0, MAX_NAMES);
  const hiddenRecallWarnings = recallWarnings.length - visibleRecallWarnings.length;

  return (
    <Card padding="lg" className="max-w-[760px]">
      <CardHeader
        title="Qué va a pasar"
        description="Revisá el impacto antes de tocar el inventario. Todavía no se modificó nada."
        icon={PackagePlus}
      />
      {preview ? (
        <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
          {items.map(({ label, value, title }) => (
            <div key={label} className="min-w-0">
              <dt className="text-sm text-muted-foreground">{label}</dt>
              <dd
                className="break-words font-display text-lg font-semibold tabular-nums text-foreground"
                title={title}
              >
                {value}
              </dd>
            </div>
          ))}
        </dl>
      ) : (
        <p className="mt-4 text-base text-muted-foreground">
          El resumen se calcula cuando terminás de revisar las filas.
        </p>
      )}

      {recallWarnings.length ? (
        <div className="mt-4 flex items-start gap-2 rounded-control border border-crit/35 bg-crit-soft px-3 py-2 text-base text-crit-ink">
          <ShieldAlert className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
          <div>
            <p className="font-medium">
              {recallWarnings.length}{' '}
              {recallWarnings.length === 1
                ? 'lote coincide con una alerta de recall'
                : 'lotes coinciden con una alerta de recall'}
              : entran en cuarentena y no se van a poder vender.
            </p>
            <ul className="mt-1 space-y-0.5 text-sm">
              {visibleRecallWarnings.map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
              {hiddenRecallWarnings > 0 ? <li>y {formatNumber(hiddenRecallWarnings)} más</li> : null}
            </ul>
          </div>
        </div>
      ) : null}

      <p className="mt-4 text-base text-muted-foreground">
        Las fechas de ingreso respetan el orden del archivo, así la rotación FIFO funciona desde el primer día.
      </p>

      {applying ? (
        <div className="mt-5" aria-live="polite">
          <div className="mb-1.5 flex justify-between text-base">
            <span>Importando filas…</span>
            <span className="tabular-nums">{job.progressPct}%</span>
          </div>
          <Progress value={job.progressPct} aria-label="Progreso de la importación" />
          <p className="mt-1.5 text-sm text-muted-foreground">
            Van {formatNumber(job.processedRows)} filas. Podés dejar esta pantalla abierta: te avisamos por
            notificación cuando termine.
          </p>
        </div>
      ) : null}
    </Card>
  );
}
