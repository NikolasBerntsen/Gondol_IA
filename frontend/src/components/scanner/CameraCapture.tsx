import { Camera, CameraOff, ImageUp, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ChangeEvent, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';
import { getSecureUrl, isCameraSupported, needsSecureContextForCamera } from '@/lib/secureContext';
import { vibrate } from './feedback';

export interface CameraCaptureProps {
  /** Recibe la foto como `Blob` (JPEG) para mandarla al OCR (`/api/tenant/ocr/label`). */
  onCapture: (photo: Blob) => void;
  /** Texto del botón principal. */
  captureLabel?: string;
  /** Ayuda debajo del visor ("Enfocá la etiqueta con el vencimiento"). */
  hint?: ReactNode;
  /** Calidad del JPEG (0..1, defecto 0.9). */
  quality?: number;
  /** Ancho máximo de la foto en px (defecto 1600): suficiente para el OCR y liviano para subir. */
  maxWidth?: number;
  /** Se muestra mientras se procesa la foto (deshabilita los botones). */
  busy?: boolean;
  className?: string;
}

/**
 * Toma una foto con la cámara y la entrega como `Blob` para el OCR de etiquetas.
 * Muestra la vista previa con "Repetir" / "Usar esta foto". Si no hay cámara (HTTP o escritorio sin
 * webcam) ofrece subir un archivo desde el dispositivo.
 */
export function CameraCapture({
  onCapture,
  captureLabel = 'Sacar foto',
  hint,
  quality = 0.9,
  maxWidth = 1600,
  busy = false,
  className,
}: CameraCaptureProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const [preview, setPreview] = useState<{ url: string; blob: Blob } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ready, setReady] = useState(false);

  const supported = isCameraSupported();
  const insecure = needsSecureContextForCamera();

  const stop = useCallback(() => {
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setReady(false);
  }, []);

  useEffect(() => {
    if (!supported || preview) {
      if (preview) stop();
      return;
    }
    let cancelled = false;
    const start = async () => {
      try {
        const stream = await navigator.mediaDevices.getUserMedia({
          video: { facingMode: { ideal: 'environment' }, width: { ideal: 1920 } },
        });
        if (cancelled) {
          stream.getTracks().forEach((track) => track.stop());
          return;
        }
        streamRef.current = stream;
        setError(null);
        if (videoRef.current) {
          videoRef.current.srcObject = stream;
          await videoRef.current.play().catch(() => undefined);
          setReady(true);
        }
      } catch (err) {
        if (cancelled) return;
        const name = (err as { name?: string }).name;
        setError(
          name === 'NotAllowedError'
            ? 'No nos diste permiso para usar la cámara. Habilitala en el navegador o subí una foto.'
            : 'No pudimos abrir la cámara. Subí una foto desde el dispositivo.',
        );
      }
    };
    void start();
    return () => {
      cancelled = true;
      stop();
    };
  }, [supported, preview, stop]);

  useEffect(() => () => stop(), [stop]);
  useEffect(() => () => {
    if (preview) URL.revokeObjectURL(preview.url);
  }, [preview]);

  const takePhoto = () => {
    const video = videoRef.current;
    if (!video || !video.videoWidth) return;
    const scale = Math.min(1, maxWidth / video.videoWidth);
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    const context = canvas.getContext('2d');
    if (!context) return;
    context.drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(
      (blob) => {
        if (!blob) return;
        vibrate(40);
        setPreview({ url: URL.createObjectURL(blob), blob });
      },
      'image/jpeg',
      quality,
    );
  };

  const onFile = (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setPreview({ url: URL.createObjectURL(file), blob: file });
  };

  const fileInput = (
    <label className="inline-flex">
      {/* Sin `capture`: en el celular deja elegir entre sacar la foto y usar una de la galería. */}
      <input type="file" accept="image/*" className="sr-only" onChange={onFile} />
      <span className="inline-flex h-9 cursor-pointer select-none items-center justify-center gap-2 whitespace-nowrap rounded-control border border-input bg-card px-3.5 text-base font-semibold text-foreground transition-colors hover:bg-muted">
        <ImageUp className="h-4 w-4" aria-hidden="true" />
        Subir una foto
      </span>
    </label>
  );

  if (preview) {
    return (
      <div className={cn('flex flex-col gap-3', className)}>
        <img
          src={preview.url}
          alt="Foto de la etiqueta tomada con la cámara"
          className="w-full rounded-panel border border-border object-contain"
        />
        <div className="flex flex-col-reverse gap-2 sm:flex-row">
          <Button
            variant="outline"
            size="lg"
            disabled={busy}
            onClick={() => {
              URL.revokeObjectURL(preview.url);
              setPreview(null);
            }}
            leftIcon={<RefreshCw aria-hidden="true" />}
          >
            Repetir
          </Button>
          <Button size="lg" fullWidth loading={busy} onClick={() => onCapture(preview.blob)}>
            Usar esta foto
          </Button>
        </div>
      </div>
    );
  }

  if (insecure || !supported || error) {
    return (
      <div className={cn('rounded-panel border border-dashed border-input bg-muted/50 p-5', className)}>
        <div className="flex items-start gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-control bg-warn-soft text-warn-ink">
            <CameraOff className="h-5 w-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <p className="font-semibold text-foreground">No podemos usar la cámara acá</p>
            <p className="mt-1 text-base text-muted-foreground">
              {error ??
                (insecure ? (
                  <>
                    Abrí <span className="break-all font-semibold">{getSecureUrl()}</span> para sacar la foto, o subila
                    desde el dispositivo.
                  </>
                ) : (
                  'Subí una foto de la etiqueta desde el dispositivo.'
                ))}
            </p>
          </div>
        </div>
        <div className="mt-3">{fileInput}</div>
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <div className="relative overflow-hidden rounded-panel bg-camera">
        <video
          ref={videoRef}
          className="block h-[260px] w-full object-cover lg:h-[320px]"
          muted
          playsInline
          aria-label="Vista de la cámara para sacar la foto"
        />
        {!ready && (
          <div className="absolute inset-0 grid place-items-center bg-camera/80">
            <p className="flex items-center gap-2 text-base font-medium text-camera-foreground">
              <Camera className="h-4 w-4 animate-pulse" aria-hidden="true" />
              Abriendo la cámara…
            </p>
          </div>
        )}
      </div>
      {hint && <p className="text-base text-muted-foreground">{hint}</p>}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:items-center sm:justify-between">
        {fileInput}
        <Button size="xl" fullWidth className="sm:w-auto" disabled={!ready || busy} onClick={takePhoto}>
          <Camera aria-hidden="true" />
          {captureLabel}
        </Button>
      </div>
    </div>
  );
}
