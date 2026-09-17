package com.gondolia.realtime;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.support.SupportTicketRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtClaims;
import com.gondolia.security.JwtService;
import com.gondolia.security.UserAccessValidator;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Autenticación y autorización de los frames STOMP entrantes (SPEC §7).
 * <ul>
 *   <li>{@code CONNECT}: exige {@code Authorization: Bearer <jwt>}; valida firma, vigencia, usuario activo, versión de
 *       token y comercio habilitado. El {@code Principal} de la sesión es el {@link AuthUser} (nombre = id de usuario,
 *       para el ruteo de {@code /user/**}): en un {@code @MessageMapping} se obtiene con un parámetro
 *       {@code Principal} y un cast a {@code AuthUser}.</li>
 *   <li>{@code SUBSCRIBE}: solo a los destinos de la tabla de §7; cualquier otro se rechaza.</li>
 *   <li>{@code SEND}: solo a {@code /app/tickets/{ticketId}/typing}, con la misma regla que {@code /topic/tickets/{id}}.
 *       Nunca directo a {@code /topic}, {@code /queue} o {@code /user}.</li>
 * </ul>
 * Cada 30 segundos como máximo se vuelve a validar al usuario en SUBSCRIBE/SEND, así una sesión de un usuario
 * desactivado o de un comercio bloqueado no puede suscribirse a nada nuevo.
 */
@Component
@RequiredArgsConstructor
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    static final String ATTR_TOKEN_VERSION = "gondolia.tokenVersion";
    static final String ATTR_VALIDATED_AT = "gondolia.validatedAt";
    static final String ATTR_AUTHORIZED_TICKETS = "gondolia.authorizedTickets";
    static final Duration REVALIDATE_AFTER = Duration.ofSeconds(30);

    static final String MSG_LOGIN_REQUIRED = "Necesitás iniciar sesión para conectarte";
    static final String MSG_DESTINATION_FORBIDDEN = "No tenés permiso para suscribirte a este destino";
    static final String MSG_SEND_FORBIDDEN = "No podés enviar mensajes a este destino";
    static final String MSG_TICKET_FORBIDDEN = "No tenés acceso a este ticket";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final Pattern TICKET_TOPIC = Pattern.compile("^/topic/tickets/(\\d{1,18})$");
    private static final Pattern TICKET_TYPING = Pattern.compile("^/app/tickets/(\\d{1,18})/typing$");
    private static final Set<String> OPEN_DESTINATIONS = Set.of(
            Destinations.userSubscription(Destinations.QUEUE_NOTIFICATIONS),
            Destinations.userSubscription(Destinations.QUEUE_SECURITY_ALERTS),
            Destinations.userSubscription(Destinations.QUEUE_SESSION),
            Destinations.TOPIC_SUPPORT_PRESENCE);

    private final JwtService jwtService;
    private final UserAccessValidator userAccessValidator;
    private final SupportTicketRepository ticketRepository;
    private final StompSessionRegistry sessionRegistry;
    private final Clock clock;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message; // heartbeats
        }
        StompCommand command = accessor.getCommand();
        switch (command) {
            case CONNECT, STOMP -> connect(accessor);
            case SUBSCRIBE -> authorizeSubscribe(accessor);
            case SEND -> authorizeSend(accessor);
            case DISCONNECT -> {
                // siempre permitido
            }
            default -> sessionUser(accessor);
        }
        return message;
    }

    private void connect(StompHeaderAccessor accessor) {
        String token = bearerToken(accessor);
        if (token == null) {
            throw new StompAuthorizationException(ErrorCodes.UNAUTHORIZED, MSG_LOGIN_REQUIRED);
        }
        JwtClaims claims = jwtService.parse(token).orElseThrow(() ->
                new StompAuthorizationException(ErrorCodes.UNAUTHORIZED, UserAccessValidator.MSG_SESSION_INVALID));
        AuthUser user = validate(claims.userId(), claims.tokenVersion());

        accessor.setUser(user);
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes != null) {
            attributes.put(ATTR_TOKEN_VERSION, claims.tokenVersion());
            attributes.put(ATTR_VALIDATED_AT, clock.instant());
        }
        sessionRegistry.authenticated(accessor.getSessionId(), user);
    }

    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        AuthUser user = sessionUser(accessor);
        String destination = accessor.getDestination();
        if (destination == null) {
            throw forbidden(MSG_DESTINATION_FORBIDDEN);
        }
        if (OPEN_DESTINATIONS.contains(destination)) {
            return;
        }
        if (Destinations.TOPIC_SUPPORT_QUEUE.equals(destination)) {
            if (user.role() == Role.SUPPORT_AGENT) {
                return;
            }
            throw forbidden(MSG_DESTINATION_FORBIDDEN);
        }
        Matcher ticket = TICKET_TOPIC.matcher(destination);
        if (ticket.matches()) {
            authorizeTicket(accessor, user, Long.valueOf(ticket.group(1)));
            return;
        }
        throw forbidden(MSG_DESTINATION_FORBIDDEN);
    }

    private void authorizeSend(StompHeaderAccessor accessor) {
        AuthUser user = sessionUser(accessor);
        String destination = accessor.getDestination();
        Matcher typing = destination == null ? null : TICKET_TYPING.matcher(destination);
        if (typing == null || !typing.matches()) {
            throw forbidden(MSG_SEND_FORBIDDEN);
        }
        authorizeTicket(accessor, user, Long.valueOf(typing.group(1)));
    }

    /** SUPPORT_AGENT: cualquier ticket. Usuario de comercio: solo tickets de su tenant. Resto: nunca. */
    private void authorizeTicket(StompHeaderAccessor accessor, AuthUser user, Long ticketId) {
        if (user.role() == Role.SUPPORT_AGENT) {
            return;
        }
        if (!user.isTenantUser()) {
            throw forbidden(MSG_TICKET_FORBIDDEN);
        }
        Set<Long> authorized = authorizedTickets(accessor);
        if (authorized != null && authorized.contains(ticketId)) {
            return;
        }
        boolean ownTicket = ticketRepository.findTenantIdById(ticketId)
                .map(tenantId -> Objects.equals(tenantId, user.tenantId()))
                .orElse(false);
        if (!ownTicket) {
            throw forbidden(MSG_TICKET_FORBIDDEN);
        }
        if (authorized != null) {
            authorized.add(ticketId);
        }
    }

    /** Usuario autenticado de la sesión, revalidado contra la base si pasó el intervalo. */
    private AuthUser sessionUser(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (!(principal instanceof AuthUser user)) {
            throw new StompAuthorizationException(ErrorCodes.UNAUTHORIZED, MSG_LOGIN_REQUIRED);
        }
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return user;
        }
        Instant validatedAt = attributes.get(ATTR_VALIDATED_AT) instanceof Instant instant ? instant : null;
        Instant now = clock.instant();
        if (validatedAt != null && validatedAt.plus(REVALIDATE_AFTER).isAfter(now)) {
            return user;
        }
        int tokenVersion = attributes.get(ATTR_TOKEN_VERSION) instanceof Integer version ? version : -1;
        AuthUser fresh = validate(user.id(), tokenVersion);
        attributes.put(ATTR_VALIDATED_AT, now);
        return fresh;
    }

    private AuthUser validate(Long userId, int tokenVersion) {
        try {
            return userAccessValidator.validate(userId, tokenVersion);
        } catch (ApiException ex) {
            throw new StompAuthorizationException(ex.getCode(), ex.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static Set<Long> authorizedTickets(StompHeaderAccessor accessor) {
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes == null) {
            return null;
        }
        return (Set<Long>) attributes.computeIfAbsent(ATTR_AUTHORIZED_TICKETS, key -> ConcurrentHashMap.newKeySet());
    }

    private static String bearerToken(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            header = accessor.getFirstNativeHeader("authorization");
        }
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).strip();
        return token.isEmpty() ? null : token;
    }

    private static StompAuthorizationException forbidden(String message) {
        return new StompAuthorizationException(ErrorCodes.FORBIDDEN, message);
    }
}
