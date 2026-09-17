package com.gondolia.realtime;

import com.gondolia.security.AuthUser;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

/**
 * Sesiones WebSocket abiertas en este nodo y el usuario autenticado en el CONNECT de cada una. Permite saber si un
 * usuario está conectado y cerrar sus sesiones (cierre forzado de sesión).
 */
@Slf4j
@Component
public class StompSessionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AuthUser> users = new ConcurrentHashMap<>();
    /** userId → ids de sesión (índice para consultas por usuario en envíos masivos). */
    private final Map<Long, Set<String>> sessionsByUser = new ConcurrentHashMap<>();

    /** Decorador del handler WebSocket que registra la apertura y el cierre de cada conexión. */
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sessions.remove(session.getId());
                AuthUser user = users.remove(session.getId());
                if (user != null) {
                    sessionsByUser.computeIfPresent(user.id(), (id, ids) -> {
                        ids.remove(session.getId());
                        return ids.isEmpty() ? null : ids;
                    });
                }
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    /** Asocia el usuario validado en el CONNECT a la sesión. */
    void authenticated(String sessionId, AuthUser user) {
        if (sessionId != null && user != null && sessions.containsKey(sessionId)) {
            users.put(sessionId, user);
            sessionsByUser.computeIfAbsent(user.id(), id -> ConcurrentHashMap.newKeySet()).add(sessionId);
        }
    }

    /** {@code true} si el usuario tiene al menos una sesión STOMP autenticada en este nodo. */
    public boolean isConnected(Long userId) {
        return userId != null && sessionsByUser.containsKey(userId);
    }

    /** Ids de sesión autenticados de un usuario. */
    public List<String> sessionIdsOfUser(Long userId) {
        Set<String> ids = userId == null ? null : sessionsByUser.get(userId);
        return ids == null ? List.of() : List.copyOf(ids);
    }

    /** Sesiones autenticadas de usuarios de un tenant, con su usuario. */
    public Map<String, AuthUser> sessionsOfTenant(Long tenantId) {
        Map<String, AuthUser> result = new ConcurrentHashMap<>();
        users.forEach((sessionId, user) -> {
            if (tenantId != null && tenantId.equals(user.tenantId())) {
                result.put(sessionId, user);
            }
        });
        return result;
    }

    /** Cantidad de sesiones WebSocket abiertas (autenticadas o no). */
    public int openSessionCount() {
        return sessions.size();
    }

    /** Cierra la sesión si sigue abierta. */
    public void close(String sessionId, CloseStatus status) {
        WebSocketSession session = sessions.get(sessionId);
        if (session == null || !session.isOpen()) {
            return;
        }
        try {
            session.close(status);
        } catch (IOException | RuntimeException ex) {
            log.debug("No se pudo cerrar la sesión WebSocket {}: {}", sessionId, ex.getMessage());
        }
    }
}
