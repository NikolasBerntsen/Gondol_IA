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

    public static String assigned(String agentName) {
        return "%s tomó la consulta.".formatted(agentName);
    }

    public static String closedByCustomer(String actorName) {
        return "%s cerró la conversación.".formatted(actorName);
    }

    public static String rated(String actorName, int rating) {
        return "%s calificó la atención con %d de 5.".formatted(actorName, rating);
    }
}
