import { useRef, useState, type DragEvent } from 'react';
import { FileSpreadsheet, Upload } from 'lucide-react';
import { Button, Spinner } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatBytes } from '@/lib/format';

const ACCEPT = '.xlsx,.xls,.csv,.txt';
const MAX_BYTES = 10 * 1024 * 1024;

export interface FileDropzoneProps {
  onFile: (file: File) => void;
  busy?: boolean;
  /** Progreso de subida 0..100 (se muestra debajo del botón). */
  progress?: number;
  label?: string;
  hint?: string;
  className?: string;
}

/**
 * Zona de arrastrar y soltar para la planilla (SPEC §16.4, paso «Archivo»). Siempre deja el camino manual:
 * el recuadro entero es un botón que abre el explorador de archivos.
 */
export function FileDropzone({ onFile, busy, progress, label, hint, className }: FileDropzoneProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const accept = (file: File | undefined) => {
    if (!file) return;
    const name = file.name.toLowerCase();
    if (!/\.(xlsx|xls|csv|txt)$/.test(name)) {
      setError('Solo se aceptan planillas Excel (.xlsx, .xls) o archivos CSV.');
      return;
    }
    if (file.size > MAX_BYTES) {
      setError(`El archivo pesa ${formatBytes(file.size)} y el máximo es 10 MB. Dividilo en varias importaciones.`);
      return;
    }
    setError(null);
    onFile(file);
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    if (busy) return;
    accept(event.dataTransfer.files?.[0]);
  };

  return (
    <div className={className}>
      <div
        onDragOver={(event) => {
          event.preventDefault();
          if (!busy) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        className={cn(
          'flex flex-col items-center gap-3 rounded-panel border border-dashed border-input bg-muted/40 px-4 py-8 text-center transition-colors sm:px-8 sm:py-10',
          dragging && 'border-primary bg-primary/[0.06]',
          busy && 'opacity-70',
        )}
      >
        <span className="grid h-11 w-11 place-items-center rounded-full border border-border bg-card text-primary">
          {busy ? <Spinner /> : <FileSpreadsheet className="h-5 w-5" aria-hidden="true" />}
        </span>
        <div>
          <p className="font-display text-md font-semibold text-foreground">
            {label ?? 'Arrastrá tu planilla acá'}
          </p>
          <p className="mt-1 text-base text-muted-foreground">
            {hint ?? 'Excel (.xlsx, .xls) o CSV · hasta 10 MB y 10.000 filas'}
          </p>
        </div>
        <input
          ref={inputRef}
          type="file"
          accept={ACCEPT}
          className="sr-only"
          onChange={(event) => {
            accept(event.target.files?.[0]);
            event.target.value = '';
          }}
        />
        <Button
          variant="outline"
          leftIcon={<Upload aria-hidden="true" />}
          disabled={busy}
          onClick={() => inputRef.current?.click()}
        >
          Elegí un archivo
        </Button>
        {busy && typeof progress === 'number' && progress < 100 ? (
          <p className="text-sm tabular-nums text-muted-foreground" aria-live="polite">
            Subiendo… {progress}%
          </p>
        ) : null}
      </div>
      {error ? (
        <p role="alert" className="mt-2 text-base text-crit-ink">
          {error}
        </p>
      ) : null}
    </div>
  );
}
