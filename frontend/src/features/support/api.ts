import { apiGet, apiPatch, apiPost, uploadFile } from '@/api/client';
import type { PageResponse, PresenceDto } from '@/api/types';
import type {
  AgentQueueParams,
  CreateTicketRequest,
  PatchTicketRequest,
  RateTicketRequest,
  SendMessagePayload,
  SupportAgent,
  SupportMessage,
  SupportStats,
  TicketDetail,
  TicketStatusFilter,
  TicketSummary,
} from './types';
import type { TicketStatus } from '@/api/types';

/** Lado de la conversación desde el que se mira: define la ruta de la API y el contador de no leídos. */
export type SupportSide = 'customer' | 'agent';

const TENANT_BASE = '/tenant/support/tickets';
const AGENT_BASE = '/support';

function messageForm(id: number, base: string, payload: SendMessagePayload) {
  return uploadFile<SupportMessage>(`${base}/${id}/messages`, payload.file ?? null, {
    fields: payload.body ? { body: payload.body } : undefined,
    fileName: payload.fileName,
  });
}

/** Soporte del lado del comercio (SPEC §6.8). */
export const tenantSupportApi = {
  list: (status?: TicketStatusFilter) => apiGet<TicketSummary[]>(TENANT_BASE, { status }),
  get: (id: number) => apiGet<TicketDetail>(`${TENANT_BASE}/${id}`),
  create: (body: CreateTicketRequest) => apiPost<TicketDetail>(TENANT_BASE, body),
  sendMessage: (id: number, payload: SendMessagePayload) => messageForm(id, TENANT_BASE, payload),
  markRead: (id: number) => apiPost<void>(`${TENANT_BASE}/${id}/read`),
  close: (id: number) => apiPost<TicketDetail>(`${TENANT_BASE}/${id}/close`),
  rate: (id: number, body: RateTicketRequest) => apiPost<TicketDetail>(`${TENANT_BASE}/${id}/rate`, body),
};

/** Bandeja del equipo de soporte (SPEC §6.8). */
export const supportAgentApi = {
  list: (params: AgentQueueParams) => apiGet<PageResponse<TicketSummary>>(`${AGENT_BASE}/tickets`, params),
  get: (id: number) => apiGet<TicketDetail>(`${AGENT_BASE}/tickets/${id}`),
  sendMessage: (id: number, payload: SendMessagePayload) => messageForm(id, `${AGENT_BASE}/tickets`, payload),
  markRead: (id: number) => apiPost<void>(`${AGENT_BASE}/tickets/${id}/read`),
  assign: (id: number, agentId?: number | null) =>
    apiPost<TicketSummary>(`${AGENT_BASE}/tickets/${id}/assign`, { agentId: agentId ?? null }),
  changeStatus: (id: number, status: TicketStatus) =>
    apiPost<TicketSummary>(`${AGENT_BASE}/tickets/${id}/status`, { status }),
  patch: (id: number, body: PatchTicketRequest) => apiPatch<TicketSummary>(`${AGENT_BASE}/tickets/${id}`, body),
  stats: () => apiGet<SupportStats>(`${AGENT_BASE}/stats`),
  agents: () => apiGet<SupportAgent[]>(`${AGENT_BASE}/agents`),
};

/** Presencia del equipo de soporte (núcleo, SPEC §6.2). */
export const supportPresenceApi = {
  get: () => apiGet<PresenceDto>('/presence/support'),
};

/** Las conversaciones no dependen de la sucursal: las keys no llevan el segmento de sucursal. */
export const supportKeys = {
  all: ['support'] as const,
  tenantList: (status: TicketStatusFilter) => ['support', 'tenant', 'list', status] as const,
  agentQueue: (params: AgentQueueParams) => ['support', 'agent', 'list', params] as const,
  ticket: (side: SupportSide, id: number) => ['support', side, 'ticket', id] as const,
  stats: () => ['support', 'agent', 'stats'] as const,
  agents: () => ['support', 'agents'] as const,
  presence: () => ['support', 'presence'] as const,
};

/** API de la conversación según el lado, para compartir componentes y hooks. */
export function conversationApi(side: SupportSide) {
  return side === 'agent'
    ? { get: supportAgentApi.get, sendMessage: supportAgentApi.sendMessage, markRead: supportAgentApi.markRead }
    : { get: tenantSupportApi.get, sendMessage: tenantSupportApi.sendMessage, markRead: tenantSupportApi.markRead };
}
