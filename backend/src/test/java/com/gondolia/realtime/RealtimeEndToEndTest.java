package com.gondolia.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import com.gondolia.security.AuthUser;
import com.gondolia.security.JwtService;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.ReceiveLotCommand;
import java.lang.reflect.Type;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.converter.CompositeMessageConverter;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Prueba de punta a punta del tiempo real con un servidor real y clientes STOMP sobre WebSocket: autenticación del
 * CONNECT, autorización de SUBSCRIBE, push de notificaciones y alertas de recall después del commit (solo a usuarios con
 * acceso a la sucursal), presencia de soporte y cierre forzado de sesión. Los datos se confirman y se borran al final.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@PostgresIntegrationTest.EnabledWhenRequested
class RealtimeEndToEndTest {

    private static final long WAIT_SECONDS = 5;

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerDatasource(registry);
    }

    @LocalServerPort
    private int port;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private StockService stockService;
    @Autowired
    private NotificationService notificationService;
    @Autowired
    private SessionTerminationService sessionTerminationService;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PresenceTracker presenceTracker;
    @Autowired
    private Clock clock;

    private final List<StompSession> sessions = new CopyOnWriteArrayList<>();
    private WebSocketStompClient stompClient;
    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long product;
    private String barcode;
    private long otherTicket;
    private AuthUser admin;
    private AuthUser employeeNorte;
    private AuthUser agent;
    private Long recall;

    @BeforeEach
    void setUp() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter json = new MappingJackson2MessageConverter();
        json.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(new CompositeMessageConverter(List.of(new StringMessageConverter(), json)));
        stompClient.setDefaultHeartbeat(new long[] {0, 0});

        data = new TestData(jdbc);
        tenant = data.tenant("Tiempo real");
        centro = data.branch(tenant, "Centro", true);
        long norte = data.branch(tenant, "Norte", true);
        barcode = data.barcode();
        product = data.product(tenant, barcode, "Sopa de tomate", "800", "1200");
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        employeeNorte = data.user(tenant, Role.TENANT_EMPLOYEE, true, norte);
        otherTenant = data.tenant("Tiempo real B");
        AuthUser otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);
        otherTicket = jdbc.queryForObject("""
                insert into support_tickets (tenant_id, created_by, subject) values (?, ?, 'Consulta') returning id
                """, Long.class, otherTenant, otherAdmin.id());
        agent = data.user(null, Role.SUPPORT_AGENT, true);
    }

    @AfterEach
    void tearDown() {
        sessions.stream().filter(StompSession::isConnected).forEach(StompSession::disconnect);
        stompClient.stop();
        if (recall != null) {
            jdbc.update("delete from announcements where id = ?", recall);
        }
        jdbc.update("delete from tenants where id in (?, ?)", tenant, otherTenant);
        jdbc.update("delete from users where id = ?", agent.id());
    }

    @Test
    void connectRequiresAValidToken() throws Exception {
        Recorder invalid = new Recorder();
        CompletableFuture<StompSession> rejected = connect("Bearer esto-no-es-un-jwt", invalid);

        StompHeaders error = invalid.nextError();
        assertThat(error.getFirst("code")).isEqualTo("UNAUTHORIZED");
        assertThat(error.getFirst("message")).contains("sesión");
        assertThat(completesSuccessfully(rejected)).isFalse();

        Recorder missing = new Recorder();
        connect(null, missing);
        assertThat(missing.nextError().getFirst("message")).isEqualTo(StompAuthChannelInterceptor.MSG_LOGIN_REQUIRED);

        assertThat(connectAs(admin, new Recorder()).isConnected()).isTrue();
    }

    @Test
    void subscriptionsOutsideTheTableAreRejected() throws Exception {
        Recorder supportQueue = new Recorder();
        StompSession tenantSession = connectAs(admin, supportQueue);
        tenantSession.subscribe(Destinations.TOPIC_SUPPORT_QUEUE, new Collector());
        assertThat(supportQueue.nextError().getFirst("code")).isEqualTo("FORBIDDEN");

        Recorder foreignTicket = new Recorder();
        connectAs(admin, foreignTicket).subscribe(Destinations.ticketTopic(otherTicket), new Collector());
        assertThat(foreignTicket.nextError().getFirst("message"))
                .isEqualTo(StompAuthChannelInterceptor.MSG_TICKET_FORBIDDEN);

        Recorder otherUserQueue = new Recorder();
        connectAs(admin, otherUserQueue).subscribe("/user/" + agent.id() + "/queue/notifications", new Collector());
        assertThat(otherUserQueue.nextError().getFirst("code")).isEqualTo("FORBIDDEN");

        Recorder agentRecorder = new Recorder();
        StompSession agentSession = connectAs(agent, agentRecorder);
        agentSession.subscribe(Destinations.TOPIC_SUPPORT_QUEUE, new Collector());
        agentSession.subscribe(Destinations.ticketTopic(otherTicket), new Collector());
        assertThat(agentRecorder.errors.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(agentSession.isConnected()).isTrue();
    }

    @Test
    void recallAlertIsPushedAfterCommitOnlyToUsersWithAccessToTheBranch() throws Exception {
        StompSession adminSession = connectAs(admin, new Recorder());
        Collector adminNotifications = subscribe(adminSession, "/user/queue/notifications");
        Collector adminAlerts = subscribe(adminSession, "/user/queue/security-alerts");
        StompSession norteSession = connectAs(employeeNorte, new Recorder());
        Collector norteNotifications = subscribe(norteSession, "/user/queue/notifications");
        Collector norteAlerts = subscribe(norteSession, "/user/queue/security-alerts");
        Thread.sleep(500);

        recall = data.recall(barcode, false, null, null, "PUBLISHED", "L2409A");
        LocalDate today = LocalDate.now(clock);
        transactionTemplate.executeWithoutResult(status -> stockService.receiveLot(new ReceiveLotCommand(tenant,
                centro, product, "L2409A", today.plusDays(60), 6, null, null, null, MovementSource.MANUAL, admin.id(),
                null)));

        Map<String, Object> notification = adminNotifications.poll();
        assertThat(notification).containsEntry("type", "RECALL_ALERT").containsEntry("severity", "CRITICAL")
                .containsEntry("referenceType", "RECALL_MATCH").containsEntry("read", false);
        assertThat(notification.get("createdAt")).asString().endsWith("Z");
        Map<String, Object> alert = adminAlerts.poll();
        assertThat(alert).containsEntry("branchName", "Centro").containsEntry("lotNumber", "L2409A")
                .containsEntry("barcode", barcode).containsEntry("quantity", 6)
                .containsEntry("expiryDate", today.plusDays(60).toString());
        assertThat(((Number) alert.get("branchId")).longValue()).isEqualTo(centro);
        // El link de la campana lleva a la coincidencia, igual que el del diálogo: sin `?match=` caería en la
        // sucursal elegida en el topbar, que puede no ser la del lote alcanzado.
        long pushedMatchId = ((Number) alert.get("matchId")).longValue();
        assertThat(((Number) notification.get("referenceId")).longValue()).isEqualTo(pushedMatchId);
        assertThat(notification).containsEntry("link", "/app/recalls?match=" + pushedMatchId);

        assertThat(norteNotifications.messages.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(norteAlerts.messages.poll(100, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void notificationsOfARolledBackTransactionAreNeverPushed() throws Exception {
        StompSession adminSession = connectAs(admin, new Recorder());
        Collector notifications = subscribe(adminSession, "/user/queue/notifications");
        Thread.sleep(500);
        NotificationDraft draft = NotificationDraft.of(NotificationType.SYSTEM, Severity.INFO, "Hola", "Prueba", null);

        transactionTemplate.executeWithoutResult(status -> {
            notificationService.notifyUser(admin.id(), draft);
            status.setRollbackOnly();
        });
        assertThat(notifications.messages.poll(1, TimeUnit.SECONDS)).isNull();

        notificationService.notifyUser(admin.id(), draft);
        assertThat(notifications.poll()).containsEntry("title", "Hola").containsEntry("type", "SYSTEM");
    }

    @Test
    void supportPresenceIsBroadcastWhenAgentsConnectAndDisconnect() throws Exception {
        for (int i = 0; i < 50 && presenceTracker.agentsOnline() > 0; i++) {
            Thread.sleep(100); // sesiones de agentes de pruebas anteriores terminando de cerrarse
        }
        assertThat(presenceTracker.agentsOnline()).isZero();
        StompSession tenantSession = connectAs(admin, new Recorder());
        Collector presence = subscribe(tenantSession, Destinations.TOPIC_SUPPORT_PRESENCE);
        Thread.sleep(500);

        StompSession agentSession = connectAs(agent, new Recorder());
        assertThat(presence.poll()).containsEntry("agentsOnline", 1);

        assertThat(presenceTracker.isOnline(agent.id())).isTrue();

        agentSession.disconnect();
        assertThat(presence.poll()).containsEntry("agentsOnline", 0);
        assertThat(presenceTracker.isOnline(agent.id())).isFalse();
    }

    @Test
    void forcedLogoutNotifiesAndClosesTheSession() throws Exception {
        Recorder recorder = new Recorder();
        StompSession adminSession = connectAs(admin, recorder);
        Collector sessionEvents = subscribe(adminSession, "/user/queue/session");
        Thread.sleep(500);

        sessionTerminationService.forceLogoutTenant(tenant, "TENANT_DISABLED", "El acceso de tu comercio está "
                + "deshabilitado. Comunicate con GondolIA.");

        assertThat(sessionEvents.poll()).containsEntry("type", "FORCE_LOGOUT").containsEntry("code", "TENANT_DISABLED");
        assertThat(recorder.transportErrors.poll(WAIT_SECONDS, TimeUnit.SECONDS)).isNotNull();
        assertThat(adminSession.isConnected()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private StompSession connectAs(AuthUser user, Recorder recorder) throws Exception {
        String token = jwtService.issue(userRepository.findById(user.id()).orElseThrow());
        return connect("Bearer " + token, recorder).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private CompletableFuture<StompSession> connect(String authorization, Recorder recorder) {
        StompHeaders headers = new StompHeaders();
        if (authorization != null) {
            headers.add("Authorization", authorization);
        }
        CompletableFuture<StompSession> future = stompClient.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), headers, recorder);
        future.thenAccept(sessions::add);
        return future;
    }

    private Collector subscribe(StompSession session, String destination) {
        Collector collector = new Collector();
        session.subscribe(destination, collector);
        return collector;
    }

    private static boolean completesSuccessfully(CompletableFuture<StompSession> future) throws InterruptedException {
        try {
            future.get(2, TimeUnit.SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException ex) {
            return false;
        }
    }

    /** Registra frames ERROR y errores de transporte de una sesión. */
    private static final class Recorder extends StompSessionHandlerAdapter {
        private final BlockingQueue<StompHeaders> errors = new LinkedBlockingQueue<>();
        private final BlockingQueue<Throwable> transportErrors = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return String.class;
        }

        @Override
        public void handleFrame(StompHeaders headers, Object payload) {
            errors.add(headers);
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            transportErrors.add(exception);
        }

        StompHeaders nextError() throws InterruptedException {
            StompHeaders headers = errors.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(headers).as("frame ERROR esperado").isNotNull();
            return headers;
        }
    }

    /** Junta los mensajes JSON recibidos en una suscripción. */
    private static final class Collector implements StompFrameHandler {
        private final BlockingQueue<Map<String, Object>> messages = new LinkedBlockingQueue<>();

        @Override
        public Type getPayloadType(StompHeaders headers) {
            return Map.class;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void handleFrame(StompHeaders headers, Object payload) {
            messages.add((Map<String, Object>) payload);
        }

        Map<String, Object> poll() throws InterruptedException {
            Map<String, Object> message = messages.poll(WAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(message).as("mensaje STOMP esperado").isNotNull();
            return message;
        }
    }
}
