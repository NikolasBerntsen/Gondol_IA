import { Camera, ImagePlus, Monitor, SendHorizonal, X } from 'lucide-react';
import { useCallback, useEffect, useRef, useState, type ClipboardEvent, type DragEvent } from 'react';
import { toast } from 'sonner';
import { Button, Kbd, Spinner, Textarea } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatBytes } from '@/lib/format';
import type { SendMessagePayload } from '../types';

/** Igual que `AttachmentStorageService`: PNG, JPG, WEBP o GIF de hasta 10 MB. */
const MAX_BYTES = 10 * 1024 * 1024;
const ACCEPTED = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

export interface MessageComposerProps {
  onSend: (payload: SendMessagePayload) => void;
  onTyping?: () => void;
  onStopTyping?: () => void;
  disabled?: boolean;
  /** Texto que reemplaza al editor cuando no se puede escribir (ticket cerrado). */
  disabledReason?: string;
  placeholder?: string;
  /** Muestra "Capturar pantalla" (solo tiene sentido dentro de la app: lo usa el widget flotante). */
  allowScreenshot?: boolean;
  dense?: boolean;
  autoFocus?: boolean;
}

/**
 * Editor de mensajes de soporte: texto + imagen desde el selector de archivos, **pegando con Ctrl+V**, arrastrando
 * el archivo, con la **cámara del celular** o con **"Capturar pantalla"** (html-to-image sobre la app, sin el propio
 * widget). Siempre se ve la vista previa antes de enviar. Enter envía; Shift+Enter hace un salto de línea.
 */
export function MessageComposer({
  onSend,
  onTyping,
  onStopTyping,
  disabled = false,
  disabledReason,
  placeholder = 'Escribí tu mensaje…',
  allowScreenshot = false,
  dense = false,
  autoFocus = false,
}: MessageComposerProps) {
  const [text, setText] = useState('');
  const [file, setFile] = useState<Blob | null>(null);
  const [fileName, setFileName] = useState<string | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);
  const [capturing, setCapturing] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);
  const cameraRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const [touchDevice, setTouchDevice] = useState(false);

  useEffect(() => {
    setTouchDevice(typeof window !== 'undefined' && window.matchMedia?.('(pointer: coarse)').matches === true);
  }, []);

  useEffect(() => () => { if (previewUrl) URL.revokeObjectURL(previewUrl); }, [previewUrl]);

  const clearFile = useCallback(() => {
    setPreviewUrl((url) => {
      if (url) URL.revokeObjectURL(url);
      return null;
    });
    setFile(null);
    setFileName(null);
    if (inputRef.current) inputRef.current.value = '';
    if (cameraRef.current) cameraRef.current.value = '';
  }, []);

  const pickFile = useCallback(
    (candidate: Blob | null | undefined, name?: string) => {
      if (!candidate) return;
      if (!ACCEPTED.includes(candidate.type)) {
        toast.error('Solo se aceptan imágenes PNG, JPG, WEBP o GIF.');
        return;
      }
      if (candidate.size > MAX_BYTES) {
        toast.error(`La imagen pesa ${formatBytes(candidate.size)} y el máximo es 10 MB.`);
        return;
      }
      setPreviewUrl((url) => {
        if (url) URL.revokeObjectURL(url);
        return URL.createObjectURL(candidate);
      });
      setFile(candidate);
      setFileName(name ?? (candidate instanceof File ? candidate.name : 'imagen.png'));
    },
    [],
  );

  const submit = () => {
    const body = text.trim();
    if (disabled || (!body && !file)) return;
    onSend({ body: body || null, file, fileName: fileName ?? undefined });
    setText('');
    clearFile();
    onStopTyping?.();
    textareaRef.current?.focus();
  };

  const onPaste = (event: ClipboardEvent<HTMLTextAreaElement>) => {
    const item = Array.from(event.clipboardData?.items ?? []).find((entry) => entry.type.startsWith('image/'));
    if (!item) return;
    const pasted = item.getAsFile();
    if (pasted) {
      event.preventDefault();
      pickFile(pasted, pasted.name || 'captura-pegada.png');
      toast.success('Pegaste una imagen.');
    }
  };

  const onDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setDragging(false);
    if (disabled) return;
    const dropped = event.dataTransfer?.files?.[0];
    if (dropped) pickFile(dropped, dropped.name);
  };

  /** Captura la app (sin el widget) con html-to-image y la deja lista para enviar. */
  const captureScreen = async () => {
    const root = document.getElementById('root');
    if (!root) return;
    setCapturing(true);
    try {
      const { toBlob } = await import('html-to-image');
      const blob = await toBlob(root, {
        pixelRatio: 1,
        cacheBust: true,
        backgroundColor: getComputedStyle(document.body).backgroundColor || '#ffffff',
        filter: (node) => !(node instanceof HTMLElement && node.dataset.supportWidget === 'true'),
      });
      if (!blob) throw new Error('sin imagen');
      pickFile(blob, 'captura-de-pantalla.png');
      toast.success('Listo: revisá la captura antes de enviarla.');
    } catch {
      toast.error('No se pudo capturar la pantalla. Probá con una captura del sistema y adjuntala.');
    } finally {
      setCapturing(false);
    }
  };

  if (disabled && disabledReason) {
    return (
      <div className={cn('border-t border-border bg-muted/50 px-4 py-3 text-base text-muted-foreground', dense && 'px-3 py-2.5')}>
        {disabledReason}
      </div>
    );
  }

  return (
    <div
      onDragOver={(event) => {
        event.preventDefault();
        setDragging(true);
      }}
      onDragLeave={() => setDragging(false)}
      onDrop={onDrop}
      className={cn(
        'relative border-t border-border bg-card',
        dense ? 'px-3 py-2.5' : 'px-4 py-3 sm:px-6',
        dragging && 'outline-dashed outline-2 -outline-offset-4 outline-primary',
      )}
    >
      {dragging && (
        <p className="pointer-events-none absolute inset-0 z-10 flex items-center justify-center bg-card/90 text-base font-medium text-primary">
          Soltá la imagen para adjuntarla
        </p>
      )}

      {previewUrl && (
        <div className="mb-2 flex items-start gap-3 rounded-control border border-border bg-muted/60 p-2">
          <img src={previewUrl} alt="Vista previa de la imagen" className="h-16 w-16 rounded-tag object-cover" />
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium">{fileName}</p>
            <p className="text-xs text-muted-foreground">{file ? formatBytes(file.size) : null}</p>
          </div>
          <Button size="icon-sm" variant="ghost" aria-label="Quitar la imagen" onClick={clearFile}>
            <X className="h-4 w-4" />
          </Button>
        </div>
      )}

      <div className="flex items-end gap-2">
        <Textarea
          ref={textareaRef}
          rows={dense ? 1 : 2}
          value={text}
          autoFocus={autoFocus}
          placeholder={placeholder}
          aria-label="Mensaje"
          className="max-h-40 min-h-[2.5rem] flex-1 resize-none"
          onChange={(event) => {
            setText(event.target.value);
            onTyping?.();
          }}
          onBlur={() => onStopTyping?.()}
          onPaste={onPaste}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              submit();
            }
          }}
        />
        <Button
          size={dense ? 'default' : 'lg'}
          aria-label="Enviar mensaje"
          leftIcon={<SendHorizonal className="h-4 w-4" />}
          disabled={disabled || (!text.trim() && !file)}
          onClick={submit}
        >
          Enviar
        </Button>
      </div>

      <div className="mt-2 flex flex-wrap items-center gap-1.5">
        <Button size="sm" variant="ghost" leftIcon={<ImagePlus className="h-4 w-4" />} onClick={() => inputRef.current?.click()}>
          Imagen
        </Button>
        {touchDevice && (
          <Button size="sm" variant="ghost" leftIcon={<Camera className="h-4 w-4" />} onClick={() => cameraRef.current?.click()}>
            Cámara
          </Button>
        )}
        {allowScreenshot && (
          <Button
            size="sm"
            variant="ghost"
            leftIcon={capturing ? <Spinner className="h-4 w-4" /> : <Monitor className="h-4 w-4" />}
            disabled={capturing}
            onClick={captureScreen}
          >
            {capturing ? 'Capturando…' : 'Capturar pantalla'}
          </Button>
        )}
        <span className="ml-auto hidden items-center gap-1 text-xs text-muted-foreground sm:flex">
          <Kbd>Enter</Kbd> envía · <Kbd>Ctrl</Kbd>+<Kbd>V</Kbd> pega una imagen
        </span>
      </div>

      <input
        ref={inputRef}
        type="file"
        accept="image/png,image/jpeg,image/webp,image/gif"
        className="sr-only"
        onChange={(event) => pickFile(event.target.files?.[0])}
      />
      <input
        ref={cameraRef}
        type="file"
        accept="image/*"
        capture="environment"
        className="sr-only"
        onChange={(event) => pickFile(event.target.files?.[0], 'foto.jpg')}
      />
    </div>
  );
}
