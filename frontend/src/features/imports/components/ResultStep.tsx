import { Link } from 'react-router-dom';
import { AlertTriangle, ShieldAlert } from 'lucide-react';
import { Button, ButtonLink, Card, StatCard } from '@/components/ui';
import { StatusPill } from '@/components/gondola';
import { formatDateTime, formatNumber } from '@/lib/format';
import { importsApi } from '../api';
import type { ImportJob } from '../types';

export interface ResultStepProps {
  job: ImportJob;
  onReview: () => void;
}

/** Paso 5 «Resultado»: contadores, links y aviso de recalls (SPEC §16.4). */
export function ResultStep({ job, onReview }: ResultStepProps) {
  const result = job.result;
  const failed = job.status === 'FAILED';
  const cancelled = job.status === 'CANCELLED';

  return (
    <div className="flex max-w-[860px] flex-col gap-4">
      <Card padding="lg">
        <StatusPill tone={failed ? 'crit' : cancelled ? 'neutral' : 'ok'}>
          {failed ? 'La importación no terminó' : cancelled ? 'Importación cancelada' : 'Importación aplicada'}
        </StatusPill>
        <h2 className="mt-2 font-display text-xl font-semibold text-foreground">
          {result
            ? `${formatNumber(result.productsCreated + result.productsUpdated)} productos y ${formatNumber(result.lotsCreated)} lotes`
            : 'Sin cambios en el inventario'}
        </h2>
        <p className="mt-1 text-read text-muted-foreground">
          {job.errorMessage ??
            'Las fechas de ingreso respetan el orden del archivo, así la rotación FIFO funciona desde el primer día.'}
        </p>
        {job.appliedAt ? (
          <p className="mt-1 text-sm text-muted-foreground">Terminó el {formatDateTime(job.appliedAt)}.</p>
        ) : null}

        {result ? (
          <div className="mt-4 grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <StatCard label="Productos nuevos" value={formatNumber(result.productsCreated)} tone="primary" />
            <StatCard label="Productos actualizados" value={formatNumber(result.productsUpdated)} tone="info" />
            <StatCard label="Lotes creados" value={formatNumber(result.lotsCreated)} tone="ok" />
            <StatCard label="Unidades cargadas" value={formatNumber(result.unitsLoaded)} tone="ok" />
          </div>
        ) : null}

        {result ? (
          <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
            {[
              ['Categorías nuevas', result.categoriesCreated],
              ['Proveedores nuevos', result.suppliersCreated],
              ['Filas omitidas', result.rowsSkipped],
              ['Filas que fallaron', result.rowsFailed],
            ].map(([label, value]) => (
              <div key={String(label)}>
                <dt className="text-sm text-muted-foreground">{label}</dt>
                <dd className="font-display text-md font-semibold tabular-nums text-foreground">
                  {formatNumber(Number(value))}
                </dd>
              </div>
            ))}
          </dl>
        ) : null}

        <div className="mt-5 flex flex-wrap gap-2">
          <ButtonLink to="/app/inventory">Ir al inventario</ButtonLink>
          <ButtonLink to="/app/expirations" variant="outline">
            Ver vencimientos
          </ButtonLink>
          <Button variant="outline" onClick={onReview}>
            Ver la revisión
          </Button>
          {job.errorRows + job.skippedRows > 0 ? (
            <Button variant="ghost" onClick={() => importsApi.downloadErrors(job.id)}>
              Descargar las filas con observaciones
            </Button>
          ) : null}
        </div>
      </Card>

      {result && result.recallMatches > 0 ? (
        <div className="flex items-start gap-2 rounded-panel border border-crit/35 bg-crit-soft px-4 py-3 text-base text-crit-ink">
          <ShieldAlert className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
          <p>
            {formatNumber(result.recallMatches)}{' '}
            {result.recallMatches === 1 ? 'lote quedó en cuarentena' : 'lotes quedaron en cuarentena'} por una alerta
            de recall: revisalos en{' '}
            <Link to="/app/recalls" className="font-medium underline underline-offset-2">
              Seguridad alimentaria
            </Link>
            .
          </p>
        </div>
      ) : null}

      {result && result.rowsFailed > 0 ? (
        <div className="flex items-start gap-2 rounded-panel border border-warn/35 bg-warn-soft px-4 py-3 text-base text-warn-ink">
          <AlertTriangle className="mt-px h-4 w-4 shrink-0" aria-hidden="true" />
          <p>
            {formatNumber(result.rowsFailed)}{' '}
            {result.rowsFailed === 1 ? 'fila no se pudo aplicar' : 'filas no se pudieron aplicar'}. Filtrá por «No se
            aplicó» en la revisión o descargá el CSV para ver el motivo.
          </p>
        </div>
      ) : null}
    </div>
  );
}
