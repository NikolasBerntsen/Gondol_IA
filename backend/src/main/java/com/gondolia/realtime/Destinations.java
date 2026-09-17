package com.gondolia.realtime;

/**
 * Destinos STOMP de GondolIA (SPEC §7). Las colas de usuario se publican con {@link RealtimePublisher#toUser} usando la
 * forma sin prefijo ({@code /queue/...}); el cliente se suscribe con el prefijo {@code /user}.
 */
public final class Destinations {

    public static final String ENDPOINT = "/ws";
    public static final String APP_PREFIX = "/app";
    public static final String USER_PREFIX = "/user";

    /** {@code NotificationDto}. */
    public static final String QUEUE_NOTIFICATIONS = "/queue/notifications";
    /** {@code RecallAlertMessage}. */
    public static final String QUEUE_SECURITY_ALERTS = "/queue/security-alerts";
    /** {@code {"type":"FORCE_LOGOUT","code":"...","message":"..."}}. */
    public static final String QUEUE_SESSION = "/queue/session";

    /** {@code {"agentsOnline":2}}. */
    public static final String TOPIC_SUPPORT_PRESENCE = "/topic/support/presence";
    /** {@code {"event":"TICKET_CREATED"|"TICKET_UPDATED","ticket":TicketSummary}} (solo SUPPORT_AGENT). */
    public static final String TOPIC_SUPPORT_QUEUE = "/topic/support/queue";
    /** Prefijo de {@code /topic/tickets/{ticketId}}. */
    public static final String TOPIC_TICKETS_PREFIX = "/topic/tickets/";

    private Destinations() {
    }

    /** {@code /topic/tickets/{ticketId}}. */
    public static String ticketTopic(Long ticketId) {
        return TOPIC_TICKETS_PREFIX + ticketId;
    }

    /** Destino de suscripción del cliente para una cola propia: {@code /user/queue/...}. */
    public static String userSubscription(String queue) {
        return USER_PREFIX + queue;
    }
}
