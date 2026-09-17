package com.gondolia.realtime;

import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/**
 * Presencia de soporte: registra las sesiones STOMP de usuarios {@code SUPPORT_AGENT} y publica
 * {@code /topic/support/presence} {@code {"agentsOnline":n}} cada vez que cambia la cantidad de agentes conectados
 * (un agente con varias pestañas cuenta una vez). Los eventos de desconexión pueden repetirse: se procesan de forma
 * idempotente.
 */
@Component
@RequiredArgsConstructor
public class PresenceTracker {

    /** Payload de {@code /topic/support/presence} y de {@code GET /api/presence/support}. */
    public record PresenceDto(int agentsOnline) {
    }

    private final RealtimePublisher publisher;

    /** sessionId → id del agente. */
    private final Map<String, Long> agentSessions = new ConcurrentHashMap<>();
    private int lastPublished;

    @EventListener
    public void onConnected(SessionConnectedEvent event) {
        AuthUser user = authUser(event.getUser());
        if (user == null || user.role() != Role.SUPPORT_AGENT) {
            return;
        }
        String sessionId = SimpMessageHeaderAccessor.getSessionId(event.getMessage().getHeaders());
        if (sessionId != null) {
            agentSessions.put(sessionId, user.id());
            publishIfChanged();
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        if (event.getSessionId() != null && agentSessions.remove(event.getSessionId()) != null) {
            publishIfChanged();
        }
    }

    /** Agentes de soporte distintos con al menos una sesión abierta. */
    public int agentsOnline() {
        return onlineAgentIds().size();
    }

    public boolean isOnline(Long userId) {
        return userId != null && agentSessions.containsValue(userId);
    }

    public Set<Long> onlineAgentIds() {
        return Set.copyOf(agentSessions.values());
    }

    public PresenceDto snapshot() {
        return new PresenceDto(agentsOnline());
    }

    private synchronized void publishIfChanged() {
        int current = agentsOnline();
        if (current != lastPublished) {
            lastPublished = current;
            publisher.toTopic(Destinations.TOPIC_SUPPORT_PRESENCE, new PresenceDto(current));
        }
    }

    private static AuthUser authUser(Principal principal) {
        return principal instanceof AuthUser user ? user : null;
    }
}
