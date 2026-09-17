import { BrowserCodeReader, BrowserMultiFormatReader, type IScannerControls } from '@zxing/browser';
import { BarcodeFormat, DecodeHintType } from '@zxing/library';
import { Camera, CameraOff, Flashlight, FlashlightOff, RefreshCw } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { Button } from '@/components/ui/Button';
import { cn } from '@/lib/cn';
import { getSecureUrl, isCameraSupported, needsSecureContextForCamera } from '@/lib/secureContext';
import { scanFeedback } from './feedback';

/** Formatos aceptados por GondolIA (SPEC §4.2): EAN-13/EAN-8/UPC-A/UPC-E y Code 128/39. */
const FORMATS = [
  BarcodeFormat.EAN_13,
  BarcodeFormat.EAN_8,
  BarcodeFormat.UPC_A,
  BarcodeFormat.UPC_E,
  BarcodeFormat.CODE_128,
  BarcodeFormat.CODE_39,
  BarcodeFormat.ITF,
];

const HINTS = new Map<DecodeHintType, unknown>([
  [DecodeHintType.POSSIBLE_FORMATS, FORMATS],
  [DecodeHintType.TRY_HARDER, true],
]);

export interface BarcodeScannerProps {
  /** Se llama con el código leído (ya normalizado: sin espacios). */
  onDetected: (code: string) => void;
  /** Apagá la cámara sin desmontar el componente (p. ej. mientras hay un diálogo abierto). */
  active?: boolean;
  /** Milisegundos que ignora lecturas repetidas del mismo código (defecto 1500). */
  cooldownMs?: number;
  /** Texto de ayuda debajo del visor. */
  hint?: ReactNode;
  /** Acción alternativa cuando la cámara no está disponible (p. ej. "Ingresar el código a mano"). */
  fallback?: ReactNode;
  /** Alto del visor. Por defecto 260 px (320 en `lg`). */
  className?: string;
  onError?: (message: string) => void;
}

function cameraErrorMessage(error: unknown): string {
  const name = (error as { name?: string } | null)?.name ?? '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'No nos diste permiso para usar la cámara. Habilitala en el navegador y volvé a intentar.';
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'No encontramos ninguna cámara en este dispositivo.';
  }
  if (name === 'NotReadableError') {
    return 'La cámara está ocupada por otra aplicación. Cerrala y probá de nuevo.';
  }
  return 'No pudimos abrir la cámara. Probá de nuevo o ingresá el código a mano.';
}

/**
 * Escáner de códigos de barras con la cámara (zxing). Cámara trasera por defecto, botón para cambiar
 * de cámara, linterna cuando el dispositivo la soporta, y pitido + vibración en cada lectura.
 *
 * Requiere contexto seguro (HTTPS o localhost): si no lo hay muestra el aviso con el link HTTPS.
 *
 * ```tsx
 * <BarcodeScanner onDetected={(code) => lookup(code)} hint="Apuntá al código de barras del producto" />
 * ```
 */
export function BarcodeScanner({
  onDetected,
  active = true,
  cooldownMs = 1500,
  hint,
  fallback,
  className,
  onError,
}: BarcodeScannerProps) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const controlsRef = useRef<IScannerControls | null>(null);
  const lastRef = useRef<{ code: string; at: number }>({ code: '', at: 0 });
  const onDetectedRef = useRef(onDetected);
  onDetectedRef.current = onDetected;
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;
  const cooldownRef = useRef(cooldownMs);
  cooldownRef.current = cooldownMs;
  /** Cámara que el navegador eligió con `facingMode` (para saber cuál sigue al cambiar). */
  const activeDeviceIdRef = useRef<string | null>(null);

  const [devices, setDevices] = useState<MediaDeviceInfo[]>([]);
  /** Cámara elegida a mano; `null` = la trasera que elige el navegador. */
  const [deviceId, setDeviceId] = useState<string | null>(null);
  const [torchOn, setTorchOn] = useState(false);
  const [torchAvailable, setTorchAvailable] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(true);

  const supported = isCameraSupported();
  const insecure = needsSecureContextForCamera();

  const stop = useCallback(() => {
    controlsRef.current?.stop();
    controlsRef.current = null;
    setTorchOn(false);
  }, []);

  useEffect(() => {
    if (!active || !supported) {
      stop();
      return;
    }

    let cancelled = false;
    const reader = new BrowserMultiFormatReader(HINTS as Map<DecodeHintType, never>, {
      delayBetweenScanAttempts: 120,
      delayBetweenScanSuccess: 400,
    });

    const start = async () => {
      setStarting(true);
      setError(null);
      try {
        const video = videoRef.current;
        if (!video) return;
        // Sin elección explícita se pide la cámara trasera: nunca un `deviceId` adivinado de la lista
        // (el primero suele ser el frontal).
        const constraints: MediaStreamConstraints = deviceId
          ? { video: { deviceId: { exact: deviceId } } }
          : { video: { facingMode: { ideal: 'environment' } } };

        const controls = await reader.decodeFromConstraints(constraints, video, (result) => {
          if (!result) return;
          const code = result.getText().trim().replace(/\s+/g, '');
          if (!code) return;
          const now = Date.now();
          if (lastRef.current.code === code && now - lastRef.current.at < cooldownRef.current) return;
          lastRef.current = { code, at: now };
          scanFeedback();
          onDetectedRef.current(code);
        });
        if (cancelled) {
          controls.stop();
          return;
        }
        controlsRef.current = controls;
        setStarting(false);

        // Linterna: solo si la pista de video la soporta (Android/Chrome).
        const stream = video.srcObject as MediaStream | null;
        activeDeviceIdRef.current = deviceId ?? stream?.getVideoTracks()[0]?.getSettings().deviceId ?? null;
        setTorchAvailable(
          !!stream && !!controls.switchTorch && BrowserCodeReader.mediaStreamIsTorchCompatible(stream),
        );

        // La lista de cámaras solo trae etiquetas después de aceptar el permiso.
        // Guardarla NO reinicia la cámara: el efecto solo depende de la elección del usuario.
        const list = await BrowserCodeReader.listVideoInputDevices().catch(() => []);
        if (!cancelled && list.length > 1) setDevices(list);
      } catch (err) {
        if (cancelled) return;
        const message = cameraErrorMessage(err);
        setError(message);
        setStarting(false);
        onErrorRef.current?.(message);
      }
    };

    void start();
    return () => {
      cancelled = true;
      stop();
    };
  }, [active, supported, deviceId, stop]);

  /** Pasa a la siguiente cámara de la lista, arrancando desde la que se está usando. */
  const switchCamera = () => {
    if (devices.length < 2) return;
    const current = activeDeviceIdRef.current;
    const index = devices.findIndex((device) => device.deviceId === current);
    setDeviceId(devices[index >= 0 ? (index + 1) % devices.length : 0].deviceId);
  };

  const toggleTorch = async () => {
    try {
      await controlsRef.current?.switchTorch?.(!torchOn);
      setTorchOn((on) => !on);
    } catch {
      setTorchAvailable(false);
    }
  };

  if (insecure || !supported) {
    return (
      <div className={cn('rounded-panel border border-dashed border-input bg-muted/50 p-5', className)}>
        <div className="flex items-start gap-3">
          <span className="grid h-10 w-10 shrink-0 place-items-center rounded-control bg-warn-soft text-warn-ink">
            <CameraOff className="h-5 w-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <p className="font-semibold text-foreground">La cámara no está disponible</p>
            <p className="mt-1 text-base text-muted-foreground">
              {insecure ? (
                <>
                  Para usar la cámara abrí <span className="break-all font-semibold">{getSecureUrl()}</span>.
                </>
              ) : (
                'Este navegador no permite usar la cámara. Ingresá el código a mano.'
              )}
            </p>
          </div>
        </div>
        {fallback && <div className="mt-3">{fallback}</div>}
      </div>
    );
  }

  return (
    <div className={cn('flex flex-col gap-3', className)}>
      <div className="relative overflow-hidden rounded-panel bg-camera text-camera-foreground">
        <video
          ref={videoRef}
          className="block h-[260px] w-full object-cover lg:h-[320px]"
          muted
          playsInline
          aria-label="Vista de la cámara para escanear códigos"
        />

        {/* Marco del visor: esquinas amarillas + línea de escaneo (se frena con prefers-reduced-motion). */}
        <div className="pointer-events-none absolute inset-0" aria-hidden="true">
          <div className="absolute inset-x-[12%] inset-y-[22%]">
            <span className="gd-viewfinder-corner left-0 top-0 rounded-tl-[6px] border-l-[3px] border-t-[3px]" />
            <span className="gd-viewfinder-corner right-0 top-0 rounded-tr-[6px] border-r-[3px] border-t-[3px]" />
            <span className="gd-viewfinder-corner bottom-0 left-0 rounded-bl-[6px] border-b-[3px] border-l-[3px]" />
            <span className="gd-viewfinder-corner bottom-0 right-0 rounded-br-[6px] border-b-[3px] border-r-[3px]" />
            <span className="absolute inset-x-2 h-0.5 animate-scanline bg-accent/80" />
          </div>
        </div>

        {(starting || error) && (
          <div className="absolute inset-0 grid place-items-center bg-camera/80 px-6 text-center">
            {error ? (
              <p role="alert" className="max-w-[40ch] text-base font-medium text-camera-foreground">
                {error}
              </p>
            ) : (
              <p className="flex items-center gap-2 text-base font-medium text-camera-foreground">
                <Camera className="h-4 w-4 animate-pulse" aria-hidden="true" />
                Abriendo la cámara…
              </p>
            )}
          </div>
        )}

        <div className="absolute inset-x-0 bottom-0 flex items-center justify-between gap-2 p-2">
          {devices.length > 1 ? (
            <Button variant="secondary" size="sm" onClick={switchCamera} leftIcon={<RefreshCw aria-hidden="true" />}>
              Cambiar cámara
            </Button>
          ) : (
            <span />
          )}
          {torchAvailable && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => void toggleTorch()}
              aria-pressed={torchOn}
              leftIcon={torchOn ? <FlashlightOff aria-hidden="true" /> : <Flashlight aria-hidden="true" />}
            >
              {torchOn ? 'Apagar luz' : 'Encender luz'}
            </Button>
          )}
        </div>
      </div>

      {hint && <p className="text-base text-muted-foreground">{hint}</p>}
      {error && fallback}
    </div>
  );
}
