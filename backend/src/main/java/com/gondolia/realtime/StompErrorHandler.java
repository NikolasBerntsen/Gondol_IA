package com.gondolia.realtime;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.web.socket.messaging.StompSubProtocolErrorHandler;

/**
 * Frame {@code ERROR} en español cuando se rechaza un frame del cliente. Encabezados: {@code message} (texto para el
 * usuario) y {@code code} ({@code UNAUTHORIZED}, {@code USER_DISABLED}, {@code TENANT_DISABLED},
 * {@code TENANT_CANCELLED}, {@code FORBIDDEN} o {@code STOMP_ERROR}); el cuerpo repite el mensaje. Spring cierra la
 * conexión después de enviarlo.
 */
@Slf4j
@Component
public class StompErrorHandler extends StompSubProtocolErrorHandler {

    public static final String CODE_HEADER = "code";
    static final String GENERIC_CODE = "STOMP_ERROR";
    static final String GENERIC_MESSAGE = "No se pudo procesar el mensaje. Volvé a conectarte";

    private static final MimeType TEXT_PLAIN_UTF8 = new MimeType("text", "plain", StandardCharsets.UTF_8);

    @Override
    public Message<byte[]> handleClientMessageProcessingError(@Nullable Message<byte[]> clientMessage, Throwable ex) {
        StompAuthorizationException rejection = findRejection(ex);
        String code;
        String text;
        if (rejection != null) {
            code = rejection.getCode();
            text = rejection.getMessage();
        } else {
            code = GENERIC_CODE;
            text = GENERIC_MESSAGE;
            log.warn("Error procesando un frame STOMP: {}", ex.getMessage());
        }

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.ERROR);
        accessor.setMessage(text);
        accessor.setNativeHeader(CODE_HEADER, code);
        accessor.setContentType(TEXT_PLAIN_UTF8);
        if (clientMessage != null) {
            StompHeaderAccessor clientAccessor =
                    MessageHeaderAccessor.getAccessor(clientMessage, StompHeaderAccessor.class);
            if (clientAccessor != null && clientAccessor.getReceipt() != null) {
                accessor.setReceiptId(clientAccessor.getReceipt());
            }
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(text.getBytes(StandardCharsets.UTF_8), accessor.getMessageHeaders());
    }

    private static StompAuthorizationException findRejection(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof StompAuthorizationException rejection) {
                return rejection;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return null;
    }
}
