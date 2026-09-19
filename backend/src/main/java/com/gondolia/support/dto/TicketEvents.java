package com.gondolia.support.dto;

import com.gondolia.domain.support.MessageSenderType;
import java.time.Instant;

/**
 * Cargas útiles de {@code /topic/tickets/{id}} y {@code /topic/support/queue} (SPEC §7). Todas llevan el campo
 * {@code event} para que el cliente las discrimine.
 */
public final class TicketEvents {

    public static final String MESSAGE = "MESSAGE";
    public static final String TICKET_UPDATED = "TICKET_UPDATED";
    public static final String TICKET_CREATED = "TICKET_CREATED";
    public static final String TYPING = "TYPING";
    public static final String READ = "READ";

    private TicketEvents() {
    }

    /** {@code {"event":"MESSAGE","message":MessageDto}}. */
    public record MessageEvent(String event, MessageDto message) {
        public MessageEvent(MessageDto message) {
            this(MESSAGE, message);
        }
    }

    /** {@code {"event":"TICKET_UPDATED"|"TICKET_CREATED","ticket":TicketSummary}}. */
    public record TicketEvent(String event, TicketSummary ticket) {
        public static TicketEvent updated(TicketSummary ticket) {
            return new TicketEvent(TICKET_UPDATED, ticket);
        }

        public static TicketEvent created(TicketSummary ticket) {
            return new TicketEvent(TICKET_CREATED, ticket);
        }
    }

    /**
     * {@code {"event":"TICKET_UPDATED","ticket":TicketSummary,"ratingComment":…,"firstResponseAt":…}} en la
     * conversación ({@code /topic/tickets/{id}}). Además del resumen lleva los dos datos del {@code TicketDetail} que
     * no están en él y que cambian sin un mensaje nuevo (el comentario de la calificación y la primera respuesta), así
     * las dos partes ven el ticket completo al instante sin volver a pedirlo. Los dos van siempre, aunque sean
     * {@code null} (una calificación corregida puede quedar sin comentario).
     */
    public record TicketUpdatedEvent(String event, TicketSummary ticket, String ratingComment,
                                     Instant firstResponseAt) {
        public TicketUpdatedEvent(TicketSummary ticket, String ratingComment, Instant firstResponseAt) {
            this(TICKET_UPDATED, ticket, ratingComment, firstResponseAt);
        }
    }

    /** {@code {"event":"TYPING","userId":7,"name":"Ana","senderType":"AGENT","typing":true}}. */
    public record TypingEvent(String event, Long userId, String name, MessageSenderType senderType, boolean typing) {
        public TypingEvent(Long userId, String name, MessageSenderType senderType, boolean typing) {
            this(TYPING, userId, name, senderType, typing);
        }
    }

    /** {@code {"event":"READ","senderType":"CUSTOMER","readAt":"..."}} — quién leyó y hasta cuándo. */
    public record ReadEvent(String event, MessageSenderType senderType, Instant readAt) {
        public ReadEvent(MessageSenderType senderType, Instant readAt) {
            this(READ, senderType, readAt);
        }
    }

    /** Cuerpo de {@code /app/tickets/{id}/typing}. */
    public record TypingRequest(boolean typing) {
    }
}
