import { PackagePlus, ShieldAlert } from 'lucide-react';
import { Card, CardHeader, Progress } from '@/components/ui';
import { formatNumber } from '@/lib/format';
import type { ImportJob } from '../types';

export interface ConfirmStepProps {
  job: ImportJob;
  /** `true` mientras la importación se está aplicando (barra de progreso). */
  applying: boolean;
}

/** Paso 4 «Confirmar»: qué va a pasar con el inventario y la barra de progreso (SPEC §16.4). */
export function ConfirmStep({ job, applying }: ConfirmStepProps) {
  const preview = job.preview;
  const items: Array<[string, string]> = preview
    ? [
        ['Productos nuevos', formatNumber(preview.productsToCreate)],
        ['Productos a actualizar', formatNumber(preview.productsToUpdate)],
        ['Lotes a crear', formatNumber(preview.lotsToCreate)],
        ['Unidades a cargar', formatNumber(preview.unitsToLoad)],
        [
          'Categorías nuevas',
          preview.categoriesToCreate.length
            ? `${preview.categoriesToCreate.length} · ${preview.categoriesToCreate.slice(0, 3).join(', ')}`
            : 'Ninguna',
        ],
        [
          'Proveedores nuevos',
          preview.suppliersToCreate.length
            ? `${preview.suppliersToCreate.length} · ${preview.suppliersToCreate.slice(0, 3).join(', ')}`
            : 'Ninguno',
        ],
        ['Filas omitidas', formatNumber(preview.rowsToSkip + preview.rowsWithErrors)],
      ]
    : [];

  return (
    <Card padding="lg" className="max-w-[760px]">
      <CardHeader
        title="Qué va a pasar"
        description="Revisá el impacto antes de tocar el inventario. Todavía no se modificó nada."
        icon={PackagePlus}
      />
      {preview ? (
        <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
          {items.map(([label, value]) => (
            <div key={label}>
              <dt className="text-sm text-muted-foreground">{label}</dt>
              <dd className="font-display text-lg font-semibold tabular-nums text-foreground">{value}</dd>
            </div>
          ))}
        </dl>
      ) : (
        <p className="mt-4 text-base text-muted-foreground">
          El resumen se calcula cuando terminás de revisar las filas.
        </p>
      )}

      {preview && preview.recallWarnings.length ? (
        <div className="mt-4 flex items-start gap-2 rounded-control border border-crit/35 bg-crit-soft px-3 py-2 text-base text-crit-ink">
          <ShieldAlert className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
          <div>
            <p className="font-medium">
              {preview.recallWarnings.length}{' '}
              {preview.recallWarnings.length === 1
                ? 'lote coincide con una alerta de recall'
                : 'lotes coinciden con una alerta de recall'}
              : entran en cuarentena y no se van a poder vender.
            </p>
            <ul className="mt-1 space-y-0.5 text-sm">
              {preview.recallWarnings.slice(0, 3).map((warning) => (
                <li key={warning}>{warning}</li>
              ))}
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
