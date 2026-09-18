import { AlertCircle, Check, CheckCheck, ImageOff, RotateCcw, X } from 'lucide-react';
import { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useCurrentUser } from '@/auth/AuthContext';
import { AuthImage, Avatar, Button, Modal, Spinner } from '@/components/ui';
import { cn } from '@/lib/cn';
import { formatDate, formatTime, todayLocalDate } from '@/lib/format';
import type { PendingMessage, SupportMessage } from '../types';
import type { TypingPeer } from '../hooks/useTicketConversation';

export interface ChatThreadProps {
  messages: SupportMessage[];
  pending: PendingMessage[];
  typingPeer: TypingPeer | null;
  /** Instante en que el otro lado leyó por última vez: marca "Visto" en el último mensaje propio. */
  peerReadAt: string | null;
  onRetry: (tempId: string) => void;
  onDiscard: (tempId: string) => void;
  /** Más compacto en el widget flotante. */
  dense?: boolean;
  className?: string;
}

function dayLabel(iso: string): string {
  const date = formatDate(iso);
  const today = formatDate(todayLocalDate());
  if (date === today) return 'Hoy';
  const yesterday = new Date();
  yesterday.setDate(yesterday.getDate() - 1);
  if (date === formatDate(yesterday)) return 'Ayer';
  return date;
}

/**
 * Conversación de soporte: burbujas por autor, separadores por día, imágenes adjuntas (ampliables), mensajes del
 * sistema, envíos en curso y "Visto". Se desplaza sola al final cuando llega algo nuevo, salvo que el usuario esté
 * leyendo más arriba.
 */
export function ChatThread({
  messages,
  pending,
  typingPeer,
  peerReadAt,
  onRetry,
  onDiscard,
  dense = false,
  className,
}: ChatThreadProps) {
  const me = useCurrentUser();
  const scroller = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);
  const [zoom, setZoom] = useState<{ url: string; alt: string } | null>(null);

  const onScroll = () => {
    const node = scroller.current;
    if (!node) return;
    stickToBottom.current = node.scrollHeight - node.scrollTop - node.clientHeight < 80;
  };

  useLayoutEffect(() => {
    const node = scroller.current;
    if (node && stickToBottom.current) node.scrollTop = node.scrollHeight;
  }, [messages.length, pending.length, typingPeer]);

  // Al abrir una conversación siempre se arranca abajo.
  useEffect(() => {
    stickToBottom.current = true;
  }, [messages[0]?.ticketId]);

  const lastOwn = [...messages].reverse().find((message) => message.senderId === me.id);
  const seen = !!peerReadAt && !!lastOwn && peerReadAt >= lastOwn.createdAt;
  let previousDay = '';

  return (
    <>
      <div
        ref={scroller}
        onScroll={onScroll}
        className={cn('gd-scroll flex-1 overflow-y-auto', dense ? 'px-3 py-3' : 'px-4 py-5 sm:px-6', className)}
      >
        <ol className="flex flex-col gap-2">
          {messages.map((message) => {
            const day = dayLabel(message.createdAt);
            const separator = day !== previousDay ? day : null;
            previousDay = day;
            const own = message.senderId === me.id;
            return (
              <li key={message.id} className="contents">
                {separator && <DaySeparator label={separator} />}
                {message.senderType === 'SYSTEM' ? (
                  <SystemLine text={message.body ?? ''} at={message.createdAt} />
                ) : (
                  <Bubble
                    own={own}
                    name={message.senderName}
                    agent={message.senderType === 'AGENT'}
                    body={message.body}
                    at={message.createdAt}
                    dense={dense}
                    attachment={
                      message.attachment && (
                        <button
                          type="button"
                          onClick={() =>
                            setZoom({
                              url: message.attachment!.url,
                              alt: message.attachment!.originalName ?? 'Imagen adjunta',
                            })
                          }
                          className="block overflow-hidden rounded-control border border-border/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                          aria-label="Ampliar la imagen"
                        >
                          <AuthImage
                            src={message.attachment.url}
                            alt={message.attachment.originalName ?? 'Imagen adjunta'}
                            className="max-h-56 w-auto max-w-full object-contain"
                            placeholderClassName="h-32 w-44"
                            fallback={
                              <span className="flex items-center gap-2 p-3 text-xs text-muted-foreground">
                                <ImageOff className="h-4 w-4" aria-hidden="true" /> No se pudo cargar la imagen
                              </span>
                            }
                          />
                        </button>
                      )
                    }
                  />
                )}
              </li>
            );
          })}

          {pending.map((item) => (
            <li key={item.tempId} className="contents">
              <PendingBubble item={item} dense={dense} onRetry={onRetry} onDiscard={onDiscard} />
            </li>
          ))}
        </ol>

        {seen && pending.length === 0 && (
          <p className="mt-1 flex items-center justify-end gap-1 pr-1 text-xs text-muted-foreground">
            <CheckCheck className="h-3.5 w-3.5" aria-hidden="true" /> Visto
          </p>
        )}

        {typingPeer && (
          <p className="mt-2 flex items-center gap-2 text-xs text-muted-foreground" aria-live="polite">
            <span className="flex gap-0.5" aria-hidden="true">
              {[0, 150, 300].map((delay) => (
                <span
                  key={delay}
                  className="h-1.5 w-1.5 animate-bounce rounded-full bg-muted-foreground/70"
                  style={{ animationDelay: `${delay}ms` }}
                />
              ))}
            </span>
            {typingPeer.name} está escribiendo…
          </p>
        )}
      </div>

      <Modal open={!!zoom} onClose={() => setZoom(null)} title={zoom?.alt ?? 'Imagen'} size="lg">
        {zoom && (
          <AuthImage src={zoom.url} alt={zoom.alt} className="mx-auto max-h-[70vh] w-auto max-w-full object-contain" />
        )}
      </Modal>
    </>
  );
}

function DaySeparator({ label }: { label: string }) {
  return (
    <div className="my-2 flex items-center gap-3" role="separator" aria-label={label}>
      <span className="h-px flex-1 bg-border" />
      <span className="gd-eyebrow text-muted-foreground">{label}</span>
      <span className="h-px flex-1 bg-border" />
    </div>
  );
}

function SystemLine({ text, at }: { text: string; at: string }) {
  return (
    <p className="my-1 text-center text-xs text-muted-foreground">
      {text} <span className="font-mono tabular-nums">· {formatTime(at)}</span>
    </p>
  );
}

interface BubbleProps {
  own: boolean;
  name: string | null;
  agent: boolean;
  body: string | null;
  at: string;
  dense: boolean;
  attachment?: React.ReactNode;
}

function Bubble({ own, name, agent, body, at, dense, attachment }: BubbleProps) {
  return (
    <div className={cn('flex w-full items-end gap-2', own ? 'justify-end' : 'justify-start')}>
      {!own && <Avatar name={name ?? (agent ? 'Soporte' : 'Cliente')} size="sm" />}
      <div className={cn('flex max-w-[85%] flex-col gap-1', dense && 'max-w-[92%]')}>
        {!own && (
          <span className="px-1 text-xs font-medium text-muted-foreground">
            {name ?? 'Soporte'}
            {agent && ' · GondolIA'}
          </span>
        )}
        <div
          className={cn(
            'rounded-panel border px-3 py-2 text-base',
            own
              ? 'border-primary bg-primary text-primary-foreground'
              : 'border-border bg-card text-foreground',
          )}
        >
          {attachment}
          {body && (
            <p className={cn('whitespace-pre-wrap break-words', attachment ? 'mt-2' : undefined)}>{body}</p>
          )}
          <span
            className={cn(
              'mt-1 block text-right font-mono text-xs tabular-nums',
              own ? 'text-primary-foreground/70' : 'text-muted-foreground',
            )}
          >
            {formatTime(at)}
          </span>
        </div>
      </div>
    </div>
  );
}

function PendingBubble({
  item,
  dense,
  onRetry,
  onDiscard,
}: {
  item: PendingMessage;
  dense: boolean;
  onRetry: (tempId: string) => void;
  onDiscard: (tempId: string) => void;
}) {
  return (
    <div className="flex w-full justify-end">
      <div className={cn('flex max-w-[85%] flex-col items-end gap-1', dense && 'max-w-[92%]')}>
        <div
          className={cn(
            'rounded-panel border px-3 py-2 text-base',
            item.failed
              ? 'border-crit bg-crit-soft text-crit-ink'
              : 'border-primary/40 bg-primary/70 text-primary-foreground',
          )}
        >
          {item.previewUrl && (
            <img
              src={item.previewUrl}
              alt={item.fileName ?? 'Imagen que se está enviando'}
              className="max-h-56 w-auto max-w-full rounded-control object-contain"
            />
          )}
          {item.body && <p className={cn('whitespace-pre-wrap break-words', item.previewUrl && 'mt-2')}>{item.body}</p>}
          <span className="mt-1 flex items-center justify-end gap-1 text-xs">
            {item.failed ? (
              <>
                <AlertCircle className="h-3.5 w-3.5" aria-hidden="true" /> No se envió
              </>
            ) : (
              <>
                <Spinner className="h-3 w-3" /> Enviando
              </>
            )}
          </span>
        </div>
        {item.failed && (
          <div className="flex items-center gap-2">
            <p className="text-xs text-crit-ink">{item.error}</p>
            <Button size="sm" variant="outline" leftIcon={<RotateCcw className="h-3.5 w-3.5" />} onClick={() => onRetry(item.tempId)}>
              Reintentar
            </Button>
            <Button
              size="icon-sm"
              variant="ghost"
              aria-label="Descartar el mensaje"
              onClick={() => onDiscard(item.tempId)}
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}

/** Acuse de entrega para el último mensaje propio cuando todavía no fue leído. */
export function DeliveredMark() {
  return (
    <span className="flex items-center gap-1 text-xs text-muted-foreground">
      <Check className="h-3.5 w-3.5" aria-hidden="true" /> Enviado
    </span>
  );
}
