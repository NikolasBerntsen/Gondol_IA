package com.gondolia.realtime;

import com.gondolia.security.AuthUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;

/**
 * Cierre forzado de sesiones: envía {@code /user/queue/session}
 * {@code {"type":"FORCE_LOGOUT","code":"TENANT_DISABLED","message":"..."}} a cada sesión abierta y, un instante después
 * (para que el mensaje llegue), cierra esas conexiones WebSocket. Si se llama dentro de una transacción, actúa recién
 * cuando confirma. Los requests HTTP posteriores ya fallan por {@code UserAccessValidator}.
 */
@Slf4j
@Service
public class SessionTerminationService {

    public static final String FORCE_LOGOUT = "FORCE_LOGOUT";
    static final Duration CLOSE_DELAY = Duration.ofMillis(1500);
    static final CloseStatus CLOSE_STATUS = new CloseStatus(4001, "Sesión finalizada");

    /** Payload de {@code /user/queue/session}. */
    public record SessionEvent(String type, String code, String message) {
    }

    private final RealtimePublisher publisher;
    private final StompSessionRegistry sessionRegistry;
    private final TaskScheduler taskScheduler;

    public SessionTerminationService(RealtimePublisher publisher, StompSessionRegistry sessionRegistry,
                                     @Qualifier("taskScheduler") TaskScheduler taskScheduler) {
        this.publisher = publisher;
        this.sessionRegistry = sessionRegistry;
        this.taskScheduler = taskScheduler;
    }

    /** Cierra las sesiones de todos los usuarios conectados de un tenant (p. ej. al deshabilitarlo). */
    public void forceLogoutTenant(Long tenantId, String code, String message) {
        if (tenantId == null) {
            return;
        }
        SessionEvent event = new SessionEvent(FORCE_LOGOUT, code, message);
        publisher.afterCommit(() -> {
            Map<String, AuthUser> sessions = sessionRegistry.sessionsOfTenant(tenantId);
            Set<Long> userIds = sessions.values().stream().map(AuthUser::id).collect(Collectors.toSet());
            userIds.forEach(userId -> publisher.sendToUserNow(userId, Destinations.QUEUE_SESSION, event));
            scheduleClose(List.copyOf(sessions.keySet()));
            if (!userIds.isEmpty()) {
                log.info("Cierre forzado de {} sesiones del tenant {} ({})", sessions.size(), tenantId, code);
            }
        });
    }

    /** Cierra las sesiones de un usuario (p. ej. al desactivarlo o resetear su contraseña). */
    public void forceLogoutUser(Long userId, String code, String message) {
        if (userId == null) {
            return;
        }
        SessionEvent event = new SessionEvent(FORCE_LOGOUT, code, message);
        publisher.afterCommit(() -> {
            List<String> sessionIds = sessionRegistry.sessionIdsOfUser(userId);
            if (sessionIds.isEmpty()) {
                return;
            }
            publisher.sendToUserNow(userId, Destinations.QUEUE_SESSION, event);
            scheduleClose(sessionIds);
            log.info("Cierre forzado de {} sesiones del usuario {} ({})", sessionIds.size(), userId, code);
        });
    }

    private void scheduleClose(List<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return;
        }
        taskScheduler.schedule(() -> sessionIds.forEach(id -> sessionRegistry.close(id, CLOSE_STATUS)),
                Instant.now().plus(CLOSE_DELAY));
    }
}
