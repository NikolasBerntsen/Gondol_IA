import { Star } from 'lucide-react';
import {
  TICKET_CATEGORY_LABELS,
  TICKET_PRIORITY_LABELS,
  TICKET_STATUS_LABELS,
  type TicketCategory,
  type TicketPriority,
  type TicketStatus,
} from '@/api/types';
import { Badge } from '@/components/ui';
import { cn } from '@/lib/cn';
import { TICKET_PRIORITY_TONE, TICKET_STATUS_TONE } from '../labels';

export function TicketStatusBadge({ status, size }: { status: TicketStatus; size?: 'sm' | 'md' }) {
  return (
    <Badge tone={TICKET_STATUS_TONE[status]} size={size} dot>
      {TICKET_STATUS_LABELS[status]}
    </Badge>
  );
}

export function TicketPriorityBadge({ priority, size }: { priority: TicketPriority; size?: 'sm' | 'md' }) {
  return (
    <Badge tone={TICKET_PRIORITY_TONE[priority]} size={size} solid={priority === 'URGENTE'}>
      {TICKET_PRIORITY_LABELS[priority]}
    </Badge>
  );
}

export function TicketCategoryBadge({ category, size }: { category: TicketCategory; size?: 'sm' | 'md' }) {
  return (
    <Badge tone="neutral" size={size}>
      {TICKET_CATEGORY_LABELS[category]}
    </Badge>
  );
}

/** Contador de mensajes sin leer del lado que mira. */
export function UnreadBadge({ count, className }: { count: number; className?: string }) {
  if (count <= 0) return null;
  return (
    <span
      className={cn(
        'inline-flex min-w-[1.35rem] items-center justify-center rounded-full bg-primary px-1.5 py-0.5',
        'text-xs font-semibold tabular-nums text-primary-foreground',
        className,
      )}
      aria-label={`${count} ${count === 1 ? 'mensaje sin leer' : 'mensajes sin leer'}`}
    >
      {count > 99 ? '99+' : count}
    </span>
  );
}

/** Calificación ya hecha (solo lectura). */
export function RatingStars({ rating, size = 'sm' }: { rating: number; size?: 'sm' | 'md' }) {
  const box = size === 'md' ? 'h-4 w-4' : 'h-3.5 w-3.5';
  return (
    <span className="inline-flex items-center gap-0.5" aria-label={`${rating} de 5`}>
      {[1, 2, 3, 4, 5].map((value) => (
        <Star
          key={value}
          className={cn(box, value <= rating ? 'fill-accent text-accent' : 'text-muted-foreground/40')}
          aria-hidden="true"
        />
      ))}
    </span>
  );
}

/** Punto verde / gris de presencia del equipo de soporte. */
export function PresenceDot({ online, className }: { online: boolean; className?: string }) {
  return (
    <span
      className={cn('inline-block h-2 w-2 shrink-0 rounded-full', online ? 'bg-ok' : 'bg-muted-foreground/50', className)}
      aria-hidden="true"
    />
  );
}
