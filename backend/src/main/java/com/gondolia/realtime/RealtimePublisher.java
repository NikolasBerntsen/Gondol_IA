package com.gondolia.realtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Envío de mensajes STOMP desde el servidor.
 * <p>
 * Si hay una transacción activa, el envío se difiere hasta que la transacción <b>confirme</b> (si se revierte, no se
 * envía nada); si no, se envía en el momento. Un error de envío nunca se propaga: se registra en el log.
 * <p>
 * {@code queue} es la cola sin prefijo de usuario (p. ej. {@link Destinations#QUEUE_NOTIFICATIONS}); el cliente se
 * suscribe a {@code /user/queue/...} (si se pasa esa forma, se le quita el {@code /user}). Los mensajes a usuarios sin sesión abierta en este nodo se descartan (al conectar,
 * el frontend lee el estado por REST).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimePublisher {

    private final SimpMessagingTemplate messagingTemplate;
    private final StompSessionRegistry sessionRegistry;

    /** Mensaje a todas las sesiones de un usuario. */
    public void toUser(Long userId, String queue, Object payload) {
        if (userId == null) {
            return;
        }
        afterCommit(() -> sendToUserNow(userId, queue, payload));
    }

    /** El mismo mensaje a varios usuarios (una sola sincronización de transacción). */
    public void toUsers(Collection<Long> userIds, String queue, Object payload) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        List<Long> recipients = userIds.stream().filter(Objects::nonNull).distinct().toList();
        afterCommit(() -> recipients.forEach(userId -> sendToUserNow(userId, queue, payload)));
    }

    /** Un mensaje distinto por usuario (p. ej. notificaciones, que tienen id propio). */
    public void toEachUser(String queue, Map<Long, ?> payloadByUser) {
        if (payloadByUser == null || payloadByUser.isEmpty()) {
            return;
        }
        Map<Long, Object> copy = new LinkedHashMap<>(payloadByUser);
        afterCommit(() -> copy.forEach((userId, payload) -> {
            if (userId != null && payload != null) {
                sendToUserNow(userId, queue, payload);
            }
        }));
    }

    /** Mensaje a un tópico ({@code /topic/...}). */
    public void toTopic(String destination, Object payload) {
        afterCommit(() -> sendToTopicNow(destination, payload));
    }

    /**
     * Ejecuta la acción cuando la transacción actual confirme, o ya mismo si no hay transacción. Se engancha en
     * {@code afterCompletion} (con la sincronización ya liberada), así la acción puede volver a publicar sin perderse.
     */
    public void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_COMMITTED) {
                        runSafely(action);
                    }
                }
            });
        } else {
            runSafely(action);
        }
    }

    void sendToUserNow(Long userId, String queue, Object payload) {
        if (!sessionRegistry.isConnected(userId)) {
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(String.valueOf(userId), withoutUserPrefix(queue), payload);
        } catch (RuntimeException ex) {
            log.warn("No se pudo enviar {} al usuario {}: {}", queue, userId, ex.getMessage());
        }
    }

    void sendToTopicNow(String destination, Object payload) {
        try {
            messagingTemplate.convertAndSend(destination, payload);
        } catch (RuntimeException ex) {
            log.warn("No se pudo publicar en {}: {}", destination, ex.getMessage());
        }
    }

    /** Acepta también la forma de suscripción ({@code /user/queue/...}) y la lleva a {@code /queue/...}. */
    static String withoutUserPrefix(String queue) {
        String prefix = Destinations.USER_PREFIX + "/";
        return queue != null && queue.startsWith(prefix) ? queue.substring(Destinations.USER_PREFIX.length()) : queue;
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Error enviando mensajes en tiempo real: {}", ex.getMessage(), ex);
        }
    }
}
