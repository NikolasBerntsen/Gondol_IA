package com.gondolia.realtime;

/**
 * Rechazo de un frame STOMP (CONNECT sin token válido, SUBSCRIBE o SEND no autorizado). Se responde con un frame
 * {@code ERROR} con encabezados {@code message} (en español) y {@code code}, y se cierra la conexión.
 */
public class StompAuthorizationException extends RuntimeException {

    private final String code;

    public StompAuthorizationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
