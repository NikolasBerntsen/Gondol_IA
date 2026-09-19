// DTOs del módulo de soporte (SPEC §6.8, §7).
import type {
  BusinessType,
  MessageSenderType,
  Role,
  TenantPlan,
  TicketCategory,
  TicketChannel,
  TicketPriority,
  TicketStatus,
} from '@/api/types';

export interface SupportAttachment {
  id: number;
  /** Ruta autenticada del núcleo; se muestra con `AuthImage`. */
  url: string;
  contentType: string;
  originalName: string | null;
  sizeBytes: number;
}

export interface SupportMessage {
  id: number;
  ticketId: number;
  senderId: number | null;
  senderName: string | null;
  senderType: MessageSenderType;
  body: string | null;
  attachment: SupportAttachment | null;
  createdAt: string;
}

export interface TicketSummary {
  id: number;
  tenantId: number;
  tenantName: string;
  tenantPlan: TenantPlan | null;
  tenantBusinessType: BusinessType | null;
  subject: string;
  category: TicketCategory;
  priority: TicketPriority;
  status: TicketStatus;
  channel: TicketChannel;
  createdById: number | null;
  createdByName: string | null;
  createdByRole: Role | null;
  assignedToId: number | null;
  assignedToName: string | null;
  lastMessageAt: string;
  lastMessagePreview: string | null;
  unreadCount: number;
  createdAt: string;
  resolvedAt: string | null;
  rating: number | null;
}

export interface TicketDetail extends TicketSummary {
  messages: SupportMessage[];
  ratingComment: string | null;
  firstResponseAt: string | null;
}

export interface SupportStats {
  open: number;
  inProgress: number;
  waitingCustomer: number;
  unassigned: number;
  mine: number;
  resolvedToday: number;
  avgFirstResponseMinutes: number | null;
}

export interface SupportAgent {
  id: number;
  fullName: string;
  online: boolean;
}

// --------------------------------------------------------------------------
// Requests
// --------------------------------------------------------------------------

export interface CreateTicketRequest {
  subject: string;
  category: TicketCategory;
  priority: TicketPriority;
  channel: TicketChannel;
  message: string;
}

export interface RateTicketRequest {
  rating: number;
  comment?: string | null;
}

export interface PatchTicketRequest {
  priority?: TicketPriority;
  category?: TicketCategory;
}

/** Texto y/o imagen: el backend exige al menos uno de los dos. */
export interface SendMessagePayload {
  body?: string | null;
  file?: Blob | null;
  fileName?: string;
}

/** `ALL` y `ACTIVE` son atajos del backend además de cada estado. */
export type TicketStatusFilter = 'ALL' | 'ACTIVE' | TicketStatus;
export type AssignedFilter = 'all' | 'me' | 'unassigned';

export interface AgentQueueParams {
  status?: TicketStatusFilter;
  assigned?: AssignedFilter;
  q?: string;
  page?: number;
  size?: number;
}

// --------------------------------------------------------------------------
// Eventos STOMP (SPEC §7)
// --------------------------------------------------------------------------

export type TicketEvent =
  | { event: 'MESSAGE'; message: SupportMessage }
  | {
      event: 'TICKET_UPDATED';
      ticket: TicketSummary;
      /**
       * Datos del detalle que no están en el resumen y cambian sin mensaje nuevo (calificación corregida, primera
       * respuesta). Solo vienen en `/topic/tickets/{id}`; en la bandeja no.
       */
      ratingComment?: string | null;
      firstResponseAt?: string | null;
    }
  | { event: 'TYPING'; userId: number; name: string; senderType: MessageSenderType; typing: boolean }
  | { event: 'READ'; senderType: MessageSenderType; readAt: string };

export type QueueEvent = { event: 'TICKET_CREATED' | 'TICKET_UPDATED'; ticket: TicketSummary };

/** Un mensaje propio que todavía está viajando (envío optimista). */
export interface PendingMessage {
  tempId: string;
  body: string | null;
  previewUrl: string | null;
  fileName: string | null;
  createdAt: string;
  failed: boolean;
  error?: string;
  /** `false` si el servidor lo rechazó por el contenido (p. ej. archivo inválido): reintentar no sirve. */
  retryable?: boolean;
}
