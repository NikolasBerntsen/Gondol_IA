package com.gondolia.support;

import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.support.MessageSenderType;
import com.gondolia.domain.support.SupportTicket;
import com.gondolia.domain.support.TicketStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import com.gondolia.realtime.Destinations;
import com.gondolia.realtime.RealtimePublisher;
import com.gondolia.support.dto.MessageDto;
import com.gondolia.support.dto.TicketEvents;
import com.gondolia.support.dto.TicketSummary;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Avisos del módulo de soporte: notificaciones in-app (SPEC §6.8) y mensajes STOMP (§7).
 * <p>
 * Los push se hacen con {@link RealtimePublisher}, así que salen <b>después</b> de que la transacción confirme: nadie
 * ve un mensaje que terminó revertido. Las notificaciones viajan en las dos direcciones: mensaje del cliente → agente
 * asignado (o todo el equipo si no hay), mensaje o cambio de estado del agente → quien creó el ticket.
 */
@Component
@RequiredArgsConstructor
public class SupportNotifier {

    /** Ruta del frontend al ticket, para el comercio y para el agente. */
    static final String TENANT_LINK = "/app/support/";
    static final String AGENT_LINK = "/support/tickets/";
    static final String REFERENCE_TYPE = "SUPPORT_TICKET";

    private final NotificationService notificationService;
    private final RealtimePublisher realtimePublisher;

    // ------------------------------------------------------------------ tiempo real

    /** {@code {"event":"MESSAGE","message":…}} a los dos lados de la conversación. */
    public void broadcastMessage(Long ticketId, MessageDto message) {
        realtimePublisher.toTopic(Destinations.ticketTopic(ticketId), new TicketEvents.MessageEvent(message));
    }

    /**
     * {@code {"event":"TICKET_UPDATED","ticket":…}} a la conversación. El destino es compartido por el comercio y el
     * agente, así que el contador de no leídos viaja en cero y cada cliente conserva el suyo.
     */
    public void broadcastTicket(TicketSummary ticket) {
        realtimePublisher.toTopic(Destinations.ticketTopic(ticket.id()),
                TicketEvents.TicketEvent.updated(ticket.withoutUnread()));
    }

    public void broadcastRead(Long ticketId, MessageSenderType reader, Instant readAt) {
        realtimePublisher.toTopic(Destinations.ticketTopic(ticketId), new TicketEvents.ReadEvent(reader, readAt));
    }

    public void broadcastTyping(Long ticketId, Long userId, String name, MessageSenderType senderType,
                               boolean typing) {
        realtimePublisher.toTopic(Destinations.ticketTopic(ticketId),
                new TicketEvents.TypingEvent(userId, name, senderType, typing));
    }

    /** Novedad para la bandeja de todos los agentes ({@code /topic/support/queue}); lleva el no leído del agente. */
    public void queueCreated(TicketSummary agentView) {
        realtimePublisher.toTopic(Destinations.TOPIC_SUPPORT_QUEUE, TicketEvents.TicketEvent.created(agentView));
    }

    public void queueUpdated(TicketSummary agentView) {
        realtimePublisher.toTopic(Destinations.TOPIC_SUPPORT_QUEUE, TicketEvents.TicketEvent.updated(agentView));
    }

    // ------------------------------------------------------------------ notificaciones

    /** Mensaje del comercio: avisa al agente asignado o, si el ticket no tiene dueño, a todo el equipo de soporte. */
    public void notifySupportOfCustomerMessage(SupportTicket ticket, String tenantName, String senderName,
                                               String preview) {
        NotificationDraft draft = new NotificationDraft(NotificationType.TICKET_MESSAGE, severityOf(ticket),
                "%s · %s".formatted(tenantName, ticket.getSubject()),
                "%s escribió: %s".formatted(senderName, preview),
                AGENT_LINK + ticket.getId(), REFERENCE_TYPE, ticket.getId());
        if (ticket.getAssignedTo() != null) {
            notificationService.notifyUser(ticket.getAssignedTo(), draft);
        } else {
            notificationService.notifyPlatformRole(Role.SUPPORT_AGENT, draft);
        }
    }

    /** Ticket nuevo: el equipo de soporte se entera aunque no tenga la consola abierta. */
    public void notifySupportOfNewTicket(SupportTicket ticket, String tenantName, String createdByName) {
        notificationService.notifyPlatformRole(Role.SUPPORT_AGENT, new NotificationDraft(
                NotificationType.TICKET_MESSAGE, severityOf(ticket),
                "Nuevo ticket · %s".formatted(tenantName),
                "%s abrió \"%s\"".formatted(createdByName, ticket.getSubject()),
                AGENT_LINK + ticket.getId(), REFERENCE_TYPE, ticket.getId()));
    }

    /** Respuesta del agente: le llega a quien abrió el ticket. */
    public void notifyCustomerOfAgentMessage(SupportTicket ticket, String agentName, String preview) {
        notificationService.notifyUser(ticket.getCreatedBy(), new NotificationDraft(
                NotificationType.TICKET_MESSAGE, Severity.INFO,
                "Soporte respondió: %s".formatted(ticket.getSubject()),
                "%s escribió: %s".formatted(agentName, preview),
                TENANT_LINK + ticket.getId(), REFERENCE_TYPE, ticket.getId()));
    }

    /** Cambio de estado hecho por soporte. */
    public void notifyCustomerOfStatus(SupportTicket ticket, TicketStatus status, String actorName) {
        notificationService.notifyUser(ticket.getCreatedBy(), new NotificationDraft(
                NotificationType.TICKET_STATUS, Severity.INFO,
                "Tu consulta ahora está %s".formatted(SupportTexts.statusLabel(status).toLowerCase(java.util.Locale.ROOT)),
                "%s actualizó \"%s\".".formatted(actorName, ticket.getSubject()),
                TENANT_LINK + ticket.getId(), REFERENCE_TYPE, ticket.getId()));
    }

    /** Aviso al agente que recibe un ticket que no se asignó él mismo. */
    public void notifyAgentAssigned(Long agentId, SupportTicket ticket, String tenantName, String actorName) {
        notificationService.notifyUser(agentId, new NotificationDraft(
                NotificationType.TICKET_STATUS, severityOf(ticket),
                "Te asignaron un ticket · %s".formatted(tenantName),
                "%s te asignó \"%s\".".formatted(actorName, ticket.getSubject()),
                AGENT_LINK + ticket.getId(), REFERENCE_TYPE, ticket.getId()));
    }

    private static Severity severityOf(SupportTicket ticket) {
        return switch (ticket.getPriority()) {
            case URGENTE -> Severity.CRITICAL;
            case ALTA -> Severity.WARNING;
            default -> Severity.INFO;
        };
    }
}
