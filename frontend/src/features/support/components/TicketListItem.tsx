import { Paperclip, UserRound } from 'lucide-react';
import { ROLE_LABELS, TICKET_CHANNEL_LABELS } from '@/api/types';
import { SeverityItem } from '@/components/gondola';
import { cn } from '@/lib/cn';
import { formatRelative } from '@/lib/format';
import { ticketStripe } from '../labels';
import type { TicketSummary } from '../types';
import { TicketPriorityBadge, TicketStatusBadge, UnreadBadge } from './TicketBadges';

export interface TicketListItemProps {
  ticket: TicketSummary;
  active?: boolean;
  /** La consola muestra el comercio y quién escribió; el comercio no lo necesita. */
  showTenant?: boolean;
  onSelect: (ticket: TicketSummary) => void;
  dense?: boolean;
}

/** Fila de la bandeja: franja de prioridad, asunto, vista previa del último mensaje y no leídos. */
export function TicketListItem({ ticket, active, showTenant, onSelect, dense }: TicketListItemProps) {
  return (
    <SeverityItem
      as="li"
      severity={ticketStripe(ticket.priority, ticket.status)}
      className={cn('border-b border-border last:border-b-0', active && 'bg-muted')}
    >
      <button
        type="button"
        onClick={() => onSelect(ticket)}
        aria-current={active ? 'true' : undefined}
        className={cn(
          'flex w-full flex-col gap-1 text-left transition-colors hover:bg-muted/70',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring',
          dense ? 'py-2.5 pl-2 pr-3' : 'py-3 pl-2 pr-4',
        )}
      >
        <span className="flex items-start justify-between gap-2">
          <span className="min-w-0 flex-1">
            {showTenant && (
              <span className="block truncate text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                {ticket.tenantName}
              </span>
            )}
            <span className="block truncate font-medium text-foreground">{ticket.subject}</span>
          </span>
          <span className="flex shrink-0 items-center gap-1.5">
            <UnreadBadge count={ticket.unreadCount} />
            <span className="whitespace-nowrap font-mono text-xs tabular-nums text-muted-foreground">
              {formatRelative(ticket.lastMessageAt)}
            </span>
          </span>
        </span>

        <span className="flex items-center gap-1 truncate text-sm text-muted-foreground">
          {ticket.lastMessagePreview === 'Imagen adjunta' && (
            <Paperclip className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
          )}
          <span className="truncate">{ticket.lastMessagePreview ?? 'Sin mensajes todavía'}</span>
        </span>

        <span className="flex flex-wrap items-center gap-1.5 pt-0.5">
          <TicketStatusBadge status={ticket.status} size="sm" />
          <TicketPriorityBadge priority={ticket.priority} size="sm" />
          {showTenant && ticket.createdByName && (
            <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
              <UserRound className="h-3 w-3" aria-hidden="true" />
              {ticket.createdByName}
              {ticket.createdByRole && ` · ${ROLE_LABELS[ticket.createdByRole]}`}
            </span>
          )}
          {!showTenant && (
            <span className="text-xs text-muted-foreground">{TICKET_CHANNEL_LABELS[ticket.channel]}</span>
          )}
          {showTenant && !ticket.assignedToId && (
            <span className="text-xs font-medium text-warn-ink">Sin asignar</span>
          )}
        </span>
      </button>
    </SeverityItem>
  );
}
