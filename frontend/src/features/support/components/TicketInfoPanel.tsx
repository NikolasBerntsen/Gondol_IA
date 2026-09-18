import { Building2, Clock3, Store, UserRound } from 'lucide-react';
import {
  BUSINESS_TYPE_LABELS,
  PLAN_LABELS,
  ROLE_LABELS,
  TICKET_CHANNEL_LABELS,
  type TicketCategory,
  type TicketPriority,
  type TicketStatus,
} from '@/api/types';
import { Alert, Badge, Field, Select } from '@/components/ui';
import { formatDateTime, formatRelative } from '@/lib/format';
import { CATEGORY_OPTIONS, PRIORITY_OPTIONS, STATUS_OPTIONS } from '../labels';
import type { SupportAgent, TicketDetail } from '../types';
import { PresenceDot, RatingStars } from './TicketBadges';

export interface TicketInfoPanelProps {
  ticket: TicketDetail;
  agents: SupportAgent[];
  onStatus: (status: TicketStatus) => void;
  onPriority: (priority: TicketPriority) => void;
  onCategory: (category: TicketCategory) => void;
  onAssign: (agentId: number) => void;
  busy?: boolean;
}

/**
 * Panel derecho de la consola: quién pregunta y desde qué comercio, y los controles de estado, prioridad, categoría
 * y asignación. Solo datos administrativos del comercio: el equipo de soporte nunca ve stock, ventas ni alertas.
 */
export function TicketInfoPanel({
  ticket,
  agents,
  onStatus,
  onPriority,
  onCategory,
  onAssign,
  busy,
}: TicketInfoPanelProps) {
  const agentOptions = agents.map((agent) => ({
    value: String(agent.id),
    label: `${agent.fullName}${agent.online ? ' · en línea' : ''}`,
  }));
  if (ticket.assignedToId && !agents.some((agent) => agent.id === ticket.assignedToId)) {
    agentOptions.unshift({ value: String(ticket.assignedToId), label: ticket.assignedToName ?? 'Agente' });
  }

  return (
    <div className="gd-scroll flex h-full flex-col gap-4 overflow-y-auto p-4">
      <section className="space-y-2">
        <h3 className="gd-eyebrow text-muted-foreground">Comercio</h3>
        <p className="flex items-center gap-2 font-display text-lg font-semibold leading-tight">
          <Store className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          {ticket.tenantName}
        </p>
        <div className="flex flex-wrap gap-1.5">
          {ticket.tenantPlan && <Badge tone="primary">{PLAN_LABELS[ticket.tenantPlan]}</Badge>}
          {ticket.tenantBusinessType && (
            <Badge tone="neutral" icon={Building2}>
              {BUSINESS_TYPE_LABELS[ticket.tenantBusinessType]}
            </Badge>
          )}
          <Badge tone="neutral">#{ticket.tenantId}</Badge>
        </div>
      </section>

      <section className="space-y-1">
        <h3 className="gd-eyebrow text-muted-foreground">Quién escribe</h3>
        <p className="flex items-center gap-2 text-base font-medium">
          <UserRound className="h-4 w-4 shrink-0 text-muted-foreground" aria-hidden="true" />
          {ticket.createdByName ?? 'Usuario dado de baja'}
        </p>
        <p className="text-sm text-muted-foreground">
          {ticket.createdByRole ? ROLE_LABELS[ticket.createdByRole] : 'Rol desconocido'} ·{' '}
          {TICKET_CHANNEL_LABELS[ticket.channel]}
        </p>
      </section>

      <section className="space-y-3">
        <h3 className="gd-eyebrow text-muted-foreground">Gestión</h3>
        <Field label="Estado">
          <Select
            value={ticket.status}
            disabled={busy}
            options={STATUS_OPTIONS.map((option) => ({ ...option }))}
            onChange={(event) => onStatus(event.target.value as TicketStatus)}
          />
        </Field>
        <Field label="Prioridad">
          <Select
            value={ticket.priority}
            disabled={busy}
            options={PRIORITY_OPTIONS.map((option) => ({ ...option }))}
            onChange={(event) => onPriority(event.target.value as TicketPriority)}
          />
        </Field>
        <Field label="Tipo de consulta">
          <Select
            value={ticket.category}
            disabled={busy}
            options={CATEGORY_OPTIONS.map((option) => ({ ...option }))}
            onChange={(event) => onCategory(event.target.value as TicketCategory)}
          />
        </Field>
        <Field
          label="Agente asignado"
          hint={
            ticket.assignedToId ? undefined : 'Nadie lo tomó todavía: al responder queda asignado automáticamente.'
          }
        >
          <Select
            value={ticket.assignedToId ? String(ticket.assignedToId) : ''}
            disabled={busy}
            placeholder="Sin asignar"
            options={agentOptions}
            onChange={(event) => onAssign(Number(event.target.value))}
          />
        </Field>
        {ticket.assignedToId != null && (
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <PresenceDot online={agents.find((agent) => agent.id === ticket.assignedToId)?.online ?? false} />
            {ticket.assignedToName}
          </p>
        )}
      </section>

      <section className="space-y-1">
        <h3 className="gd-eyebrow text-muted-foreground">Tiempos</h3>
        <dl className="space-y-1 text-sm">
          <Row label="Abierto" value={`${formatDateTime(ticket.createdAt)} (${formatRelative(ticket.createdAt)})`} />
          <Row
            label="Primera respuesta"
            value={ticket.firstResponseAt ? formatRelative(ticket.firstResponseAt) : 'Todavía sin responder'}
          />
          <Row label="Último mensaje" value={formatRelative(ticket.lastMessageAt)} />
          {ticket.resolvedAt && <Row label="Resuelto" value={formatDateTime(ticket.resolvedAt)} />}
        </dl>
      </section>

      {ticket.rating != null && (
        <section className="space-y-1 rounded-panel border border-border bg-muted/50 p-3">
          <h3 className="gd-eyebrow text-muted-foreground">Calificación del comercio</h3>
          <RatingStars rating={ticket.rating} size="md" />
          {ticket.ratingComment && <p className="text-sm text-muted-foreground">“{ticket.ratingComment}”</p>}
        </section>
      )}

      <Alert tone="info">
        Solo ves lo que el comercio te manda en la conversación y sus datos de contacto: nunca su stock, sus ventas ni
        sus alertas.
      </Alert>

      <p className="mt-auto flex items-center gap-1.5 text-xs text-muted-foreground">
        <Clock3 className="h-3.5 w-3.5" aria-hidden="true" /> Ticket #{ticket.id}
      </p>
    </div>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-baseline justify-between gap-3">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="text-right tabular-nums">{value}</dd>
    </div>
  );
}
