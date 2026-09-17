package com.gondolia.support;

/** Códigos y mensajes propios del módulo de soporte (SPEC §5.2: cada módulo puede definir los suyos). */
public final class SupportErrors {

    /** 409 — el ticket está cerrado y no acepta más mensajes. */
    public static final String TICKET_CLOSED = "TICKET_CLOSED";
    /** 409 — todavía no se puede calificar la atención. */
    public static final String TICKET_NOT_RATEABLE = "TICKET_NOT_RATEABLE";
    /** 400 — el mensaje llegó sin texto y sin imagen. */
    public static final String EMPTY_MESSAGE = "EMPTY_MESSAGE";
    /** 400 — el agente elegido no existe o no está activo. */
    public static final String AGENT_NOT_FOUND = "AGENT_NOT_FOUND";

    public static final String MSG_TICKET_NOT_FOUND = "El ticket no existe";
    public static final String MSG_TICKET_CLOSED = "El ticket está cerrado. Abrí uno nuevo para seguir la conversación.";
    public static final String MSG_NOT_RATEABLE = "Vas a poder calificar la atención cuando el ticket esté resuelto.";
    public static final String MSG_EMPTY_MESSAGE = "Escribí un mensaje o adjuntá una imagen.";
    public static final String MSG_AGENT_NOT_FOUND = "El agente elegido no existe o no está activo.";
    public static final String MSG_NOTHING_TO_UPDATE = "Indicá la prioridad o la categoría que querés cambiar.";

    private SupportErrors() {
    }
}
