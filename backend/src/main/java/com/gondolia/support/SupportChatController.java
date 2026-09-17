package com.gondolia.support;

import com.gondolia.security.AuthUser;
import com.gondolia.support.dto.TicketEvents;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * Señal de "está escribiendo…" del chat en vivo (SPEC §7): el cliente publica en
 * {@code /app/tickets/{ticketId}/typing} y el servidor la reenvía a {@code /topic/tickets/{ticketId}}.
 * <p>
 * La autorización del destino ya la resolvió {@code StompAuthChannelInterceptor} en el frame SEND (solo agentes de
 * soporte o usuarios del comercio dueño del ticket), así que acá solo se traduce el aviso y se reenvía. No toca la
 * base: si algo falla, el chat sigue funcionando sin el indicador.
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class SupportChatController {

    private final SupportService supportService;

    @MessageMapping("/tickets/{ticketId}/typing")
    public void typing(@DestinationVariable Long ticketId,
                       @Payload(required = false) TicketEvents.TypingRequest request,
                       Principal principal) {
        if (!(principal instanceof AuthUser user)) {
            return;
        }
        supportService.typing(user, ticketId, request != null && request.typing());
    }
}
