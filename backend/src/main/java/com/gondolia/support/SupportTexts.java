package com.gondolia.support;

import com.gondolia.domain.support.TicketStatus;

/** Textos en español de los mensajes del sistema y de las notificaciones del módulo de soporte. */
public final class SupportTexts {

    private SupportTexts() {
    }

    /** Etiqueta de estado, igual que en la interfaz ({@code TICKET_STATUS_LABELS}). */
    public static String statusLabel(TicketStatus status) {
        return switch (status) {
            case OPEN -> "Abierto";
            case IN_PROGRESS -> "En curso";
            case WAITING_CUSTOMER -> "Esperando respuesta";
            case RESOLVED -> "Resuelto";
            case CLOSED -> "Cerrado";
        };
    }

    /** Mensaje del sistema que queda en la conversación cuando soporte cambia el estado. */
    public static String statusChanged(String actorName, TicketStatus status) {
        return "%s cambió el estado a «%s».".formatted(actorName, statusLabel(status));
    }

    /**
     * Mensaje del sistema al asignar el ticket. Si el agente se lo asigna a sí mismo, "tomó la consulta"; si se lo
     * pasa a otro, queda quién lo hizo y a quién ("Sofía asignó la consulta a Tomás").
     */
    public static String assigned(String actorName, String agentName, boolean selfAssigned) {
        return selfAssigned
                ? "%s tomó la consulta.".formatted(agentName)
                : "%s asignó la consulta a %s.".formatted(actorName, agentName);
    }

    public static String closedByCustomer(String actorName) {
        return "%s cerró la conversación.".formatted(actorName);
    }

    public static String rated(String actorName, int rating) {
        return "%s calificó la atención con %d de 5.".formatted(actorName, rating);
    }
}
