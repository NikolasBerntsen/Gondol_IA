// Tonos y textos del módulo de soporte. Las etiquetas de los enums viven en `@/api/types`
// (TICKET_STATUS_LABELS, TICKET_PRIORITY_LABELS, TICKET_CATEGORY_LABELS, TICKET_CHANNEL_LABELS).
import type { TicketPriority, TicketStatus } from '@/api/types';
import type { BadgeTone } from '@/components/ui';
import type { StripeSeverity } from '@/components/gondola';

export const TICKET_STATUS_TONE: Record<TicketStatus, BadgeTone> = {
  OPEN: 'warn',
  IN_PROGRESS: 'info',
  WAITING_CUSTOMER: 'primary',
  RESOLVED: 'ok',
  CLOSED: 'neutral',
};

export const TICKET_PRIORITY_TONE: Record<TicketPriority, BadgeTone> = {
  BAJA: 'neutral',
  MEDIA: 'info',
  ALTA: 'warn',
  URGENTE: 'crit',
};

/** Franja de severidad de la fila: la prioridad manda, salvo que el ticket ya esté terminado. */
export function ticketStripe(priority: TicketPriority, status: TicketStatus): StripeSeverity {
  if (status === 'RESOLVED' || status === 'CLOSED') return 'none';
  switch (priority) {
    case 'URGENTE':
      return 'crit';
    case 'ALTA':
      return 'warn';
    case 'MEDIA':
      return 'info';
    default:
      return 'none';
  }
}

/** Estados en los que el comercio todavía puede escribir. */
export function isTicketWritable(status: TicketStatus): boolean {
  return status !== 'CLOSED';
}

export const CATEGORY_OPTIONS = [
  { value: 'TECNICO', label: 'Problema técnico' },
  { value: 'USO', label: 'Consulta de uso' },
  { value: 'FACTURACION', label: 'Facturación' },
  { value: 'SUGERENCIA', label: 'Sugerencia' },
  { value: 'OTRO', label: 'Otro' },
] as const;

export const PRIORITY_OPTIONS = [
  { value: 'BAJA', label: 'Baja' },
  { value: 'MEDIA', label: 'Media' },
  { value: 'ALTA', label: 'Alta' },
  { value: 'URGENTE', label: 'Urgente' },
] as const;

export const STATUS_OPTIONS = [
  { value: 'OPEN', label: 'Abierto' },
  { value: 'IN_PROGRESS', label: 'En curso' },
  { value: 'WAITING_CUSTOMER', label: 'Esperando respuesta' },
  { value: 'RESOLVED', label: 'Resuelto' },
  { value: 'CLOSED', label: 'Cerrado' },
] as const;

/** "12,4 min" / "1 h 05 min" para el promedio de primera respuesta. */
export function formatMinutes(minutes: number | null | undefined): string {
  if (minutes === null || minutes === undefined) return '—';
  if (minutes < 60) return `${minutes.toFixed(1).replace('.', ',')} min`;
  const hours = Math.floor(minutes / 60);
  const rest = Math.round(minutes % 60);
  return `${hours} h ${String(rest).padStart(2, '0')} min`;
}
