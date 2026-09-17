package com.gondolia.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

class PresenceTrackerTest {

    private static final AuthUser AGENT_1 = new AuthUser(2L, "s1@gondolia.app", "Soporte 1", Role.SUPPORT_AGENT, null);
    private static final AuthUser AGENT_2 = new AuthUser(3L, "s2@gondolia.app", "Soporte 2", Role.SUPPORT_AGENT, null);
    private static final AuthUser ADMIN = new AuthUser(10L, "admin@prueba.com", "Admin", Role.TENANT_ADMIN, 5L);

    private final RealtimePublisher publisher = mock(RealtimePublisher.class);
    private final PresenceTracker tracker = new PresenceTracker(publisher);

    @Test
    void countsDistinctAgentsAndPublishesOnlyOnChanges() {
        tracker.onConnected(connected("a", AGENT_1));
        tracker.onConnected(connected("b", AGENT_1));
        tracker.onConnected(connected("c", ADMIN));
        tracker.onConnected(connected("d", AGENT_2));

        assertThat(tracker.agentsOnline()).isEqualTo(2);
        assertThat(tracker.onlineAgentIds()).containsExactlyInAnyOrder(2L, 3L);
        assertThat(tracker.isOnline(ADMIN.id())).isFalse();

        tracker.onDisconnect(disconnected("a"));
        tracker.onDisconnect(disconnected("a"));
        tracker.onDisconnect(disconnected("c"));
        assertThat(tracker.agentsOnline()).isEqualTo(2);

        tracker.onDisconnect(disconnected("b"));
        tracker.onDisconnect(disconnected("d"));
        assertThat(tracker.snapshot()).isEqualTo(new PresenceTracker.PresenceDto(0));

        InOrder order = inOrder(publisher);
        order.verify(publisher).toTopic(Destinations.TOPIC_SUPPORT_PRESENCE, new PresenceTracker.PresenceDto(1));
        order.verify(publisher).toTopic(Destinations.TOPIC_SUPPORT_PRESENCE, new PresenceTracker.PresenceDto(2));
        order.verify(publisher).toTopic(Destinations.TOPIC_SUPPORT_PRESENCE, new PresenceTracker.PresenceDto(1));
        order.verify(publisher).toTopic(Destinations.TOPIC_SUPPORT_PRESENCE, new PresenceTracker.PresenceDto(0));
        verify(publisher, times(4)).toTopic(anyString(), any());
    }

    private SessionConnectedEvent connected(String sessionId, AuthUser user) {
        return new SessionConnectedEvent(this, message(sessionId), user);
    }

    private SessionDisconnectEvent disconnected(String sessionId) {
        return new SessionDisconnectEvent(this, message(sessionId), sessionId, CloseStatus.NORMAL);
    }

    private static Message<byte[]> message(String sessionId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
        accessor.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
