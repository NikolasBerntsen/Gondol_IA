import { useState } from 'react';
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui';
import { cn } from '@/lib/cn';
import type { ImportRowMessage } from '../types';

export interface EditableCellProps {
  value: string;
  /** Etiqueta del campo, para el nombre accesible ("Precio de venta, fila 12"). */
  label: string;
  rowNumber: number;
  messages: ImportRowMessage[];
  mono?: boolean;
  align?: 'right';
  disabled?: boolean;
  saving?: boolean;
  /** Sugerencias del campo (sucursales, categorías conocidas). */
  suggestionsId?: string;
  onCommit: (value: string) => void;
}

/**
 * Celda editable de la grilla de revisión (SPEC §16.4): se abre con un clic o con Enter, guarda al salir y
 * muestra el motivo de cada error o advertencia en un tooltip.
 */
export function EditableCell({
  value,
  label,
  rowNumber,
  messages,
  mono,
  align,
  disabled,
  saving,
  suggestionsId,
  onCommit,
}: EditableCellProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const level = messages.some((m) => m.level === 'ERROR') ? 'ERROR' : messages.length ? 'WARNING' : null;

  if (editing) {
    return (
      <div className="min-w-0">
        <input
          autoFocus
          value={draft}
          list={suggestionsId}
          aria-label={`${label}, fila ${rowNumber}`}
          onChange={(event) => setDraft(event.target.value)}
          onBlur={() => {
            setEditing(false);
            if (draft !== value) onCommit(draft);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault();
              event.currentTarget.blur();
            } else if (event.key === 'Escape') {
              event.preventDefault();
              event.stopPropagation();
              setDraft(value);
              setEditing(false);
            }
          }}
          className={cn(
            'h-8 w-full rounded-tag border border-ring bg-card px-2 text-base text-foreground outline-none ring-2 ring-ring/25',
            mono && 'font-mono text-sm',
            align === 'right' && 'text-right tabular-nums',
          )}
        />
        {/* En pantallas táctiles no hay tooltip: mientras se edita, el motivo queda a la vista. */}
        {messages.map((message, index) => (
          <p
            key={index}
            className={cn(
              'mt-1 max-w-[260px] whitespace-normal text-xs',
              message.level === 'ERROR' ? 'text-crit-ink' : 'text-warn-ink',
            )}
          >
            {message.message}
          </p>
        ))}
      </div>
    );
  }

  const description = messages.length
    ? `. ${messages.map((m) => `${m.level === 'ERROR' ? 'Error' : 'Advertencia'}: ${m.message}`).join(' ')}`
    : '';

  const trigger = (
    <button
      type="button"
      disabled={disabled}
      onClick={() => {
        setDraft(value);
        setEditing(true);
      }}
      aria-label={`${label}, fila ${rowNumber}: ${value || 'vacío'}${description}. Editar`}
      className={cn(
        'flex h-8 w-full min-w-0 items-center rounded-tag px-2 text-left text-base text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:hover:bg-transparent',
        mono && 'font-mono text-sm',
        align === 'right' && 'justify-end tabular-nums',
        saving && 'opacity-60',
        level === 'ERROR' &&
          'bg-crit-soft/70 text-crit-ink shadow-[inset_0_0_0_1.5px_hsl(var(--crit))] hover:bg-crit-soft',
        level === 'WARNING' && 'bg-warn-soft/60 shadow-[inset_0_0_0_1px_hsl(var(--warn))] hover:bg-warn-soft',
      )}
    >
      <span className="truncate">{value || <span className="text-muted-foreground">—</span>}</span>
    </button>
  );

  if (!messages.length) return trigger;

  return (
    <Tooltip delayDuration={80}>
      <TooltipTrigger asChild>{trigger}</TooltipTrigger>
      <TooltipContent side="bottom" align="start" className={cn(level === 'ERROR' && 'bg-crit text-white')}>
        {messages.map((message, index) => (
          <p key={index} className="font-medium">
            {message.level === 'ERROR' ? 'Error: ' : 'Advertencia: '}
            {message.message}
          </p>
        ))}
      </TooltipContent>
    </Tooltip>
  );
}
