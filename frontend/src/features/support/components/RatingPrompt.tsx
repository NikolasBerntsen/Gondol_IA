import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Star } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';
import { Button, Input } from '@/components/ui';
import { cn } from '@/lib/cn';
import { supportKeys, tenantSupportApi } from '../api';
import { invalidateSupportLists } from '../hooks/useTicketConversation';
import type { TicketDetail } from '../types';
import { RatingStars } from './TicketBadges';

/**
 * Cierre de la atención del lado del comercio: calificar de 1 a 5 con un comentario opcional. Solo aparece cuando la
 * consulta ya está resuelta o cerrada.
 */
export function RatingPrompt({ ticket, dense = false }: { ticket: TicketDetail; dense?: boolean }) {
  const queryClient = useQueryClient();
  const [rating, setRating] = useState(ticket.rating ?? 0);
  const [hover, setHover] = useState(0);
  const [comment, setComment] = useState(ticket.ratingComment ?? '');

  const rate = useMutation({
    mutationFn: (value: number) => tenantSupportApi.rate(ticket.id, { rating: value, comment: comment || null }),
    onSuccess: (updated) => {
      toast.success('¡Gracias por calificar la atención!');
      queryClient.setQueryData(supportKeys.ticket('customer', ticket.id), updated);
      invalidateSupportLists(queryClient);
    },
  });

  if (ticket.rating != null) {
    return (
      <div className={cn('border-t border-border bg-muted/50', dense ? 'px-3 py-2.5' : 'px-4 py-3 sm:px-6')}>
        <p className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          Calificaste la atención con <RatingStars rating={ticket.rating} />
          {ticket.ratingComment && <span>“{ticket.ratingComment}”</span>}
        </p>
      </div>
    );
  }

  return (
    <div className={cn('space-y-2 border-t border-border bg-muted/50', dense ? 'px-3 py-3' : 'px-4 py-4 sm:px-6')}>
      <p className="text-base font-medium">¿Cómo te atendimos?</p>
      <div className="flex items-center gap-1" onMouseLeave={() => setHover(0)}>
        {[1, 2, 3, 4, 5].map((value) => (
          <button
            key={value}
            type="button"
            aria-label={`${value} de 5`}
            className="rounded-control p-1 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            onMouseEnter={() => setHover(value)}
            onFocus={() => setHover(value)}
            onClick={() => setRating(value)}
          >
            <Star
              className={cn(
                'h-6 w-6 transition-transform',
                value <= (hover || rating) ? 'fill-accent text-accent' : 'text-muted-foreground/40',
                value === hover && 'scale-110',
              )}
              aria-hidden="true"
            />
          </button>
        ))}
      </div>
      <div className="flex flex-col gap-2 sm:flex-row">
        <Input
          value={comment}
          maxLength={500}
          placeholder="Contanos algo más (opcional)"
          aria-label="Comentario sobre la atención"
          onChange={(event) => setComment(event.target.value)}
        />
        <Button disabled={rating === 0} loading={rate.isPending} onClick={() => rate.mutate(rating)}>
          Enviar calificación
        </Button>
      </div>
    </div>
  );
}
