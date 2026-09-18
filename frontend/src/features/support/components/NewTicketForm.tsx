import { useMutation, useQueryClient } from '@tanstack/react-query';
import { MessageSquarePlus } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';
import { getFieldErrors } from '@/api/client';
import type { TicketCategory, TicketPriority } from '@/api/types';
import { Button, Field, Input, Select, Textarea } from '@/components/ui';
import { cn } from '@/lib/cn';
import { tenantSupportApi } from '../api';
import { CATEGORY_OPTIONS, PRIORITY_OPTIONS } from '../labels';
import { invalidateSupportLists } from '../hooks/useTicketConversation';
import type { TicketDetail } from '../types';

export interface NewTicketFormProps {
  /** `CHAT` desde el widget flotante, `TICKET` desde la pantalla de soporte. */
  channel: 'CHAT' | 'TICKET';
  onCreated: (ticket: TicketDetail) => void;
  onCancel?: () => void;
  dense?: boolean;
}

/** Alta de una consulta: asunto, categoría, prioridad y primer mensaje. */
export function NewTicketForm({ channel, onCreated, onCancel, dense = false }: NewTicketFormProps) {
  const queryClient = useQueryClient();
  const [subject, setSubject] = useState('');
  const [category, setCategory] = useState<TicketCategory>('USO');
  const [priority, setPriority] = useState<TicketPriority>('MEDIA');
  const [message, setMessage] = useState('');
  const [errors, setErrors] = useState<Record<string, string>>({});

  const create = useMutation({
    mutationFn: () =>
      tenantSupportApi.create({ subject: subject.trim(), category, priority, channel, message: message.trim() }),
    onSuccess: (ticket) => {
      toast.success('Enviamos tu consulta a soporte.');
      queryClient.setQueryData(['support', 'customer', 'ticket', ticket.id], ticket);
      invalidateSupportLists(queryClient);
      onCreated(ticket);
    },
    onError: (error) => setErrors(getFieldErrors(error)),
  });

  const submit = () => {
    const nextErrors: Record<string, string> = {};
    if (!subject.trim()) nextErrors.subject = 'Contanos en pocas palabras qué pasa.';
    if (!message.trim()) nextErrors.message = 'Escribí el detalle de tu consulta.';
    setErrors(nextErrors);
    if (Object.keys(nextErrors).length > 0) return;
    create.mutate();
  };

  return (
    <form
      className={cn('flex flex-col gap-3', dense ? 'p-3' : 'p-4 sm:p-5')}
      onSubmit={(event) => {
        event.preventDefault();
        submit();
      }}
    >
      <Field label="Asunto" error={errors.subject}>
        <Input
          value={subject}
          maxLength={200}
          placeholder="Ej.: No puedo cargar un lote"
          onChange={(event) => setSubject(event.target.value)}
        />
      </Field>

      <div className={cn('grid gap-3', dense ? 'grid-cols-2' : 'sm:grid-cols-2')}>
        <Field label="Tipo de consulta" error={errors.category}>
          <Select
            value={category}
            options={CATEGORY_OPTIONS.map((option) => ({ ...option }))}
            onChange={(event) => setCategory(event.target.value as TicketCategory)}
          />
        </Field>
        <Field label="Prioridad" error={errors.priority}>
          <Select
            value={priority}
            options={PRIORITY_OPTIONS.map((option) => ({ ...option }))}
            onChange={(event) => setPriority(event.target.value as TicketPriority)}
          />
        </Field>
      </div>

      <Field label="Contanos qué pasa" error={errors.message} hint="Después vas a poder adjuntar imágenes en el chat.">
        <Textarea
          rows={dense ? 3 : 4}
          value={message}
          maxLength={4000}
          placeholder="Detallá el problema, qué estabas haciendo y qué esperabas que pase."
          onChange={(event) => setMessage(event.target.value)}
        />
      </Field>

      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        {onCancel && (
          <Button type="button" variant="ghost" onClick={onCancel}>
            Cancelar
          </Button>
        )}
        <Button type="submit" loading={create.isPending} leftIcon={<MessageSquarePlus className="h-4 w-4" />}>
          Enviar consulta
        </Button>
      </div>
    </form>
  );
}
