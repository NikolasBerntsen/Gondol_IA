package com.gondolia.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.support.SupportTicketRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtClaims;
import com.gondolia.security.JwtService;
import com.gondolia.security.UserAccessValidator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class StompAuthChannelInterceptorTest {

    private static final Instant NOW = Instant.parse("2026-09-17T15:00:00Z");
    private static final AuthUser ADMIN = new AuthUser(10L, "admin@prueba.com", "Admin", Role.TENANT_ADMIN, 5L);
    private static final AuthUser AGENT = new AuthUser(2L, "soporte@gondolia.app", "Soporte", Role.SUPPORT_AGENT, null);
    private static final AuthUser OWNER = new AuthUser(1L, "dueno@gondolia.app", "Dueño", Role.PLATFORM_OWNER, null);

    private final JwtService jwtService = mock(JwtService.class);
    private final UserAccessValidator validator = mock(UserAccessValidator.class);
    private final SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    private final MessageChannel channel = mock(MessageChannel.class);
    private StompSessionRegistry registry;
    private StompAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        registry = new StompSessionRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        registry.decorate(mock(WebSocketHandler.class)).afterConnectionEstablished(session);
        interceptor = new StompAuthChannelInterceptor(jwtService, validator, tickets, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(tickets.findTenantIdById(100L)).thenReturn(Optional.of(5L));
        when(tickets.findTenantIdById(200L)).thenReturn(Optional.of(6L));
    }

    @Test
    void connectWithoutBearerTokenIsRejected() {
        assertRejected(() -> send(connect(null)), "UNAUTHORIZED", StompAuthChannelInterceptor.MSG_LOGIN_REQUIRED);
        assertRejected(() -> send(connect("Basic abc")), "UNAUTHORIZED", StompAuthChannelInterceptor.MSG_LOGIN_REQUIRED);
    }

    @Test
    void connectWithInvalidTokenIsRejected() {
        when(jwtService.parse("garbage")).thenReturn(Optional.empty());

        assertRejected(() -> send(connect("Bearer garbage")), "UNAUTHORIZED", UserAccessValidator.MSG_SESSION_INVALID);
        verify(validator, never()).validate(anyLong(), anyInt());
    }

    @Test
    void connectKeepsTheCodeOfBlockedUsersAndTenants() {
        givenToken("tok", ADMIN, 3);
        when(validator.validate(ADMIN.id(), 3)).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "TENANT_DISABLED",
                UserAccessValidator.MSG_TENANT_DISABLED));

        assertRejected(() -> send(connect("Bearer tok")), "TENANT_DISABLED", UserAccessValidator.MSG_TENANT_DISABLED);
    }

    @Test
    void connectSetsTheAuthUserAsSessionPrincipal() {
        givenToken("tok", ADMIN, 3);
        Message<byte[]> connect = connect("bearer tok");

        send(connect);

        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(connect, StompHeaderAccessor.class);
        assertThat(accessor.getUser()).isInstanceOfSatisfying(AuthUser.class, principal -> {
            assertThat(principal).isEqualTo(ADMIN);
            assertThat(principal.getName()).isEqualTo("10");
        });
        assertThat(accessor.getSessionAttributes()).containsEntry(StompAuthChannelInterceptor.ATTR_TOKEN_VERSION, 3);
        assertThat(registry.isConnected(ADMIN.id())).isTrue();
        assertThat(registry.sessionIdsOfUser(ADMIN.id())).containsExactly("s1");
    }

    @Test
    void subscribeFollowsTheDestinationTable() {
        for (String open : new String[] {"/user/queue/notifications", "/user/queue/security-alerts",
                "/user/queue/session", "/topic/support/presence"}) {
            send(subscribe(ADMIN, open));
            send(subscribe(OWNER, open));
        }
        send(subscribe(AGENT, "/topic/support/queue"));
        send(subscribe(AGENT, "/topic/tickets/200"));
        send(subscribe(ADMIN, "/topic/tickets/100"));

        assertRejected(() -> send(subscribe(ADMIN, "/topic/support/queue")), "FORBIDDEN",
                StompAuthChannelInterceptor.MSG_DESTINATION_FORBIDDEN);
        assertRejected(() -> send(subscribe(ADMIN, "/topic/tickets/200")), "FORBIDDEN",
                StompAuthChannelInterceptor.MSG_TICKET_FORBIDDEN);
        assertRejected(() -> send(subscribe(ADMIN, "/topic/tickets/999")), "FORBIDDEN",
                StompAuthChannelInterceptor.MSG_TICKET_FORBIDDEN);
        assertRejected(() -> send(subscribe(OWNER, "/topic/tickets/100")), "FORBIDDEN",
                StompAuthChannelInterceptor.MSG_TICKET_FORBIDDEN);
        for (String denied : new String[] {"/user/11/queue/notifications", "/queue/notifications",
                "/topic/tickets/100/extra", "/topic/anything", "/app/tickets/100/typing"}) {
            assertRejected(() -> send(subscribe(ADMIN, denied)), "FORBIDDEN",
                    StompAuthChannelInterceptor.MSG_DESTINATION_FORBIDDEN);
        }
        assertRejected(() -> send(subscribe(null, "/user/queue/notifications")), "UNAUTHORIZED",
                StompAuthChannelInterceptor.MSG_LOGIN_REQUIRED);
    }

    @Test
    void ticketAuthorizationIsCachedPerSession() {
        Map<String, Object> attributes = freshAttributes();
        send(frame(StompCommand.SUBSCRIBE, ADMIN, "/topic/tickets/100", attributes));
        send(frame(StompCommand.SEND, ADMIN, "/app/tickets/100/typing", attributes));
        send(frame(StompCommand.SEND, ADMIN, "/app/tickets/100/typing", attributes));

        verify(tickets, times(1)).findTenantIdById(100L);
    }

    @Test
    void sendIsOnlyAllowedToTicketTyping() {
        send(frame(StompCommand.SEND, ADMIN, "/app/tickets/100/typing", freshAttributes()));
        send(frame(StompCommand.SEND, AGENT, "/app/tickets/200/typing", freshAttributes()));

        assertRejected(() -> send(frame(StompCommand.SEND, ADMIN, "/app/tickets/200/typing", freshAttributes())),
                "FORBIDDEN", StompAuthChannelInterceptor.MSG_TICKET_FORBIDDEN);
        for (String denied : new String[] {"/topic/tickets/100", "/topic/support/presence", "/queue/notifications",
                "/user/10/queue/notifications", "/app/other"}) {
            assertRejected(() -> send(frame(StompCommand.SEND, AGENT, denied, freshAttributes())), "FORBIDDEN",
                    StompAuthChannelInterceptor.MSG_SEND_FORBIDDEN);
        }
    }

    @Test
    void staleSessionsAreRevalidatedAgainstTheDatabase() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(StompAuthChannelInterceptor.ATTR_TOKEN_VERSION, 4);
        attributes.put(StompAuthChannelInterceptor.ATTR_VALIDATED_AT, NOW.minusSeconds(31));
        when(validator.validate(ADMIN.id(), 4)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "USER_DISABLED",
                UserAccessValidator.MSG_USER_DISABLED));

        assertRejected(() -> send(frame(StompCommand.SUBSCRIBE, ADMIN, "/user/queue/notifications", attributes)),
                "USER_DISABLED", UserAccessValidator.MSG_USER_DISABLED);

        attributes.put(StompAuthChannelInterceptor.ATTR_VALIDATED_AT, NOW.minusSeconds(10));
        send(frame(StompCommand.SUBSCRIBE, ADMIN, "/user/queue/notifications", attributes));
        verify(validator, times(1)).validate(eq(ADMIN.id()), anyInt());
    }

    @Test
    void heartbeatsAndDisconnectPassWithoutUser() {
        StompHeaderAccessor heartbeat = StompHeaderAccessor.createForHeartbeat();
        send(MessageBuilder.createMessage(new byte[0], heartbeat.getMessageHeaders()));
        send(frame(StompCommand.DISCONNECT, null, null, freshAttributes()));
        assertRejected(() -> send(frame(StompCommand.UNSUBSCRIBE, null, null, freshAttributes())), "UNAUTHORIZED",
                StompAuthChannelInterceptor.MSG_LOGIN_REQUIRED);
    }

    // ------------------------------------------------------------------ helpers

    private void givenToken(String token, AuthUser user, int tokenVersion) {
        when(jwtService.parse(token)).thenReturn(Optional.of(new JwtClaims(user.id(), user.email(), user.role(),
                user.tenantId(), tokenVersion, NOW, NOW.plusSeconds(3600))));
        when(validator.validate(user.id(), tokenVersion)).thenReturn(user);
    }

    private void send(Message<?> message) {
        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    private Message<byte[]> connect(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(new HashMap<>());
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribe(AuthUser user, String destination) {
        return frame(StompCommand.SUBSCRIBE, user, destination, freshAttributes());
    }

    private static Map<String, Object> freshAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(StompAuthChannelInterceptor.ATTR_TOKEN_VERSION, 0);
        attributes.put(StompAuthChannelInterceptor.ATTR_VALIDATED_AT, NOW);
        return attributes;
    }

    private static Message<byte[]> frame(StompCommand command, AuthUser user, String destination,
                                         Map<String, Object> attributes) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("s1");
        accessor.setSessionAttributes(attributes);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        if (command == StompCommand.SUBSCRIBE || command == StompCommand.UNSUBSCRIBE) {
            accessor.setSubscriptionId("sub-0");
        }
        if (user != null) {
            accessor.setUser(user);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static void assertRejected(ThrowingCallable call, String code, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StompAuthorizationException.class, ex -> {
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }
}
