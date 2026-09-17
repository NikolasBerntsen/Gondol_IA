import { ScanBarcode } from 'lucide-react';
import { useState } from 'react';
import { BarcodeScanner } from '@/components/scanner';
import { Button, Field, Input, Modal } from '@/components/ui';
import { normalizeBarcode } from '../lib';

export interface BarcodeFieldProps {
  id?: string;
  value: string;
  onChange: (value: string) => void;
  label?: string;
  hint?: string;
  error?: string;
  optional?: boolean;
  disabled?: boolean;
  /** Se llama solo cuando el código llega del escáner (para disparar la búsqueda al instante). */
  onScanned?: (code: string) => void;
}

/**
 * Campo de código de barras con botón de escáner: abre la cámara en un diálogo y escribe la lectura
 * en el campo. Siempre queda el camino manual (SPEC §9.5: nunca dependemos de la cámara).
 */
export function BarcodeField({
  id = 'barcode',
  value,
  onChange,
  label = 'Código de barras',
  hint,
  error,
  optional,
  disabled,
  onScanned,
}: BarcodeFieldProps) {
  const [scanning, setScanning] = useState(false);

  const handleDetected = (code: string) => {
    const normalized = normalizeBarcode(code);
    onChange(normalized);
    setScanning(false);
    onScanned?.(normalized);
  };

  return (
    <>
      <Field label={label} hint={hint} error={error} optional={optional} htmlFor={id}>
        <div className="flex items-center gap-2">
          <Input
            id={id}
            value={value}
            inputMode="numeric"
            autoComplete="off"
            placeholder="7791234500017"
            disabled={disabled}
            onChange={(event) => onChange(normalizeBarcode(event.target.value))}
            className="font-mono tabular-nums"
            invalid={!!error}
          />
          <Button
            variant="outline"
            size="icon"
            aria-label="Escanear el código con la cámara"
            title="Escanear con la cámara"
            disabled={disabled}
            onClick={() => setScanning(true)}
          >
            <ScanBarcode className="h-4 w-4" aria-hidden="true" />
          </Button>
        </div>
      </Field>

      <Modal
        open={scanning}
        onClose={() => setScanning(false)}
        title="Escaneá el código"
        description="Apuntá la cámara al código de barras del producto."
        size="md"
      >
        {scanning && (
          <BarcodeScanner
            onDetected={handleDetected}
            hint="Sostené el envase a unos 15 cm de la cámara."
            fallback={
              <Button variant="outline" onClick={() => setScanning(false)}>
                Ingresar el código a mano
              </Button>
            }
          />
        )}
      </Modal>
    </>
  );
}
