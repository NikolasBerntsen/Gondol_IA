import { FileSpreadsheet } from 'lucide-react';
import { Card, CardHeader, Field, Select } from '@/components/ui';
import { formatDateTime, formatNumber } from '@/lib/format';
import { FILE_FORMAT_LABELS } from '../labels';
import type { ImportJob } from '../types';
import { FileDropzone } from './FileDropzone';

export interface FileStepProps {
  job: ImportJob | null;
  busy: boolean;
  progress: number;
  /** `true` si el archivo original sigue en memoria (se puede cambiar de hoja sin volver a elegirlo). */
  canSwitchSheet: boolean;
  onFile: (file: File) => void;
  onSheet: (sheetName: string) => void;
  error?: string | null;
}

/** Paso 1 «Archivo»: arrastrar y soltar, archivo detectado y elección de hoja (SPEC §16.4). */
export function FileStep({ job, busy, progress, canSwitchSheet, onFile, onSheet, error }: FileStepProps) {
  return (
    <div className="flex max-w-[860px] flex-col gap-4">
      {job ? (
        <Card padding="lg">
          <CardHeader
            title="Archivo detectado"
            description="Leímos el encabezado de la primera fila con datos y todas las filas de abajo."
            icon={FileSpreadsheet}
          />
          <dl className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
            <div className="col-span-2">
              <dt className="text-sm text-muted-foreground">Archivo</dt>
              <dd className="truncate font-medium text-foreground">{job.fileName}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">Formato</dt>
              <dd className="font-medium text-foreground">{FILE_FORMAT_LABELS[job.fileFormat]}</dd>
            </div>
            <div>
              <dt className="text-sm text-muted-foreground">Filas con datos</dt>
              <dd className="font-display text-lg font-semibold tabular-nums text-foreground">
                {formatNumber(job.totalRows)}
              </dd>
            </div>
            <div className="col-span-2">
              <dt className="text-sm text-muted-foreground">Columnas</dt>
              <dd className="text-base text-foreground">
                {job.headers.slice(0, 8).join(' · ')}
                {job.headers.length > 8 ? ` y ${job.headers.length - 8} más` : ''}
              </dd>
            </div>
            <div className="col-span-2">
              <dt className="text-sm text-muted-foreground">Subido</dt>
              <dd className="text-base text-foreground">
                {formatDateTime(job.createdAt)}
                {job.createdByName ? ` por ${job.createdByName}` : ''}
              </dd>
            </div>
          </dl>

          {job.sheetNames.length > 1 ? (
            <Field
              className="mt-4 max-w-[360px]"
              label="Hoja del libro"
              hint={
                canSwitchSheet
                  ? 'El archivo tiene varias hojas: elegí la que tiene los productos.'
                  : 'Para leer otra hoja, volvé a elegir el archivo acá abajo después de cambiarla.'
              }
            >
              <Select
                value={job.sheetName ?? ''}
                disabled={!canSwitchSheet || busy}
                options={job.sheetNames.map((name) => ({ value: name, label: name }))}
                onChange={(event) => onSheet(event.target.value)}
              />
            </Field>
          ) : null}
        </Card>
      ) : null}

      <Card padding="lg">
        <CardHeader
          title={job ? 'Cambiar el archivo' : 'Elegí tu planilla'}
          description={
            job
              ? 'Si subís otro archivo, esta importación se cancela y empezás de nuevo con el nuevo.'
              : 'Aceptamos la plantilla de GondolIA o la planilla que ya usás: después elegís qué columna es cada dato.'
          }
        />
        <FileDropzone className="mt-4" busy={busy} progress={progress} onFile={onFile} />
        {error ? (
          <p role="alert" className="mt-2 text-base text-crit-ink">
            {error}
          </p>
        ) : null}
      </Card>
    </div>
  );
}
