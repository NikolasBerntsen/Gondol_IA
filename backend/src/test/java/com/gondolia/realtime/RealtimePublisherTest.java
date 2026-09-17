package com.gondolia.realtime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class RealtimePublisherTest {

    private final SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
    private StompSessionRegistry registry;
    private RealtimePublisher publisher;

    @BeforeEach
    void setUp() throws Exception {
        registry = new StompSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        registry.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(session);
        registry.authenticated("s1", new AuthUser(7L, "a@b.com", "A", Role.TENANT_ADMIN, 1L));
        publisher = new RealtimePublisher(template, registry);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void sendsImmediatelyWithoutTransactionAndSkipsDisconnectedUsers() {
        publisher.toUser(7L, Destinations.QUEUE_NOTIFICATIONS, "hola");
        publisher.toUsers(List.of(7L, 8L, 7L), Destinations.QUEUE_SECURITY_ALERTS, "alerta");
        publisher.toTopic(Destinations.TOPIC_SUPPORT_PRESENCE, "presencia");

        verify(template).convertAndSendToUser("7", Destinations.QUEUE_NOTIFICATIONS, "hola");
        verify(template).convertAndSendToUser("7", Destinations.QUEUE_SECURITY_ALERTS, "alerta");
        verify(template, never()).convertAndSendToUser("8", Destinations.QUEUE_SECURITY_ALERTS, "alerta");
        verify(template).convertAndSend(Destinations.TOPIC_SUPPORT_PRESENCE, (Object) "presencia");
    }

    @Test
    void acceptsTheSubscriptionFormOfAUserQueue() {
        publisher.toUser(7L, "/user/queue/security-alerts", "alerta");

        verify(template).convertAndSendToUser("7", Destinations.QUEUE_SECURITY_ALERTS, "alerta");
    }

    @Test
    void waitsForCommitAndDropsOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        publisher.toEachUser(Destinations.QUEUE_NOTIFICATIONS, Map.of(7L, "commit"));
        verifyNoInteractions(template);

        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(template).convertAndSendToUser("7", Destinations.QUEUE_NOTIFICATIONS, "commit");

        TransactionSynchronizationManager.initSynchronization();
        publisher.toUser(7L, Destinations.QUEUE_NOTIFICATIONS, "rollback");
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(template, never()).convertAndSendToUser("7", Destinations.QUEUE_NOTIFICATIONS, "rollback");
    }

    @Test
    void sendErrorsAreNotPropagated() {
        doThrow(new MessageDeliveryException("canal lleno")).when(template)
                .convertAndSendToUser(anyString(), anyString(), any());

        publisher.toUser(7L, Destinations.QUEUE_SESSION, "x");
    }

    private static void complete(int status) {
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations, status);
    }
}
