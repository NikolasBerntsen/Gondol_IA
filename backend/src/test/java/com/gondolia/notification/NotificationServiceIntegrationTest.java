package com.gondolia.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.realtime.Destinations;
import com.gondolia.security.AuthUser;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link NotificationService} contra PostgreSQL: destinatarios de cada tipo de envío, filtros de usuario activo y
 * tenant activo, push y bandeja del usuario. Cada prueba se revierte.
 */
@Transactional
class NotificationServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private NotificationService notificationService;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenant;
    private long centro;
    private long norte;
    private AuthUser admin;
    private AuthUser boss;
    private AuthUser employeeCentro;
    private AuthUser employeeNorte;
    private AuthUser inactiveEmployee;
    private NotificationDraft draft;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Avisos", "DIETETICA", "BASICO", "ACTIVE", "FIFO");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Norte", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        boss = data.user(tenant, Role.TENANT_BOSS, true);
        employeeCentro = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);
        employeeNorte = data.user(tenant, Role.TENANT_EMPLOYEE, true, norte);
        inactiveEmployee = data.user(tenant, Role.TENANT_EMPLOYEE, false, centro);
        draft = new NotificationDraft(NotificationType.ALERT, Severity.WARNING, "Stock bajo " + data.suffix(),
                "Quedan 3 u.", "/app/alerts", "ALERT", 99L);
    }

    @Test
    void notifyUserPersistsAndPushesOnlyForActiveUsers() {
        NotificationDto dto = notificationService.notifyUser(admin.id(), draft);

        assertThat(dto.id()).isNotNull();
        assertThat(dto).extracting(NotificationDto::type, NotificationDto::severity, NotificationDto::title,
                        NotificationDto::link, NotificationDto::referenceType, NotificationDto::referenceId,
                        NotificationDto::read)
                .containsExactly(NotificationType.ALERT, Severity.WARNING, draft.title(), "/app/alerts", "ALERT", 99L,
                        false);
        assertThat(jdbc.queryForMap("select user_id, tenant_id, created_at from notifications where id = ?", dto.id()))
                .containsEntry("user_id", admin.id()).containsEntry("tenant_id", tenant);
        verify(realtimePublisher).toEachUser(Destinations.QUEUE_NOTIFICATIONS, Map.of(admin.id(), dto));

        assertThat(notificationService.notifyUser(inactiveEmployee.id(), draft)).isNull();
        assertThat(notificationService.notifyUser(-1L, draft)).isNull();
    }

    @Test
    void tenantAndBranchFanOutRespectRolesAccessAndTenantStatus() {
        assertThat(notificationService.notifyTenantUsers(tenant, null, draft)).isEqualTo(4);
        assertThat(recipients()).containsExactlyInAnyOrder(admin.id(), boss.id(), employeeCentro.id(),
                employeeNorte.id());

        clear();
        assertThat(notificationService.notifyTenantUsers(tenant, EnumSet.of(Role.TENANT_ADMIN, Role.SUPPORT_AGENT),
                draft)).isEqualTo(1);
        assertThat(recipients()).containsExactly(admin.id());

        clear();
        assertThat(notificationService.notifyBranchUsers(tenant, centro, null, draft)).isEqualTo(3);
        assertThat(recipients()).containsExactlyInAnyOrder(admin.id(), boss.id(), employeeCentro.id());
        assertThat(notificationService.branchRecipients(tenant, norte, Set.of(Role.TENANT_EMPLOYEE)))
                .containsExactly(employeeNorte.id());

        long otherTenant = data.tenant("Ajeno");
        assertThat(notificationService.notifyBranchUsers(otherTenant, centro, null, draft))
                .as("sucursal de otro tenant").isZero();

        jdbc.update("update tenants set status = 'DISABLED' where id = ?", tenant);
        assertThat(notificationService.notifyTenantUsers(tenant, null, draft)).isZero();
        assertThat(notificationService.notifyBranchUsers(tenant, centro, null, draft)).isZero();
        assertThat(notificationService.notifyUsers(List.of(admin.id(), inactiveEmployee.id()), draft))
                .as("envío directo a usuarios: solo filtra activos").isEqualTo(1);
    }

    @Test
    void broadcastsFilterByBusinessTypeAndPlatformRole() {
        long kiosco = data.tenant("Kiosco", "KIOSCO", "FREEMIUM", "ACTIVE", "FIFO");
        AuthUser kioscoAdmin = data.user(kiosco, Role.TENANT_ADMIN, true);
        long disabled = data.tenant("Bloqueado", "KIOSCO", "FREEMIUM", "DISABLED", "FIFO");
        AuthUser disabledAdmin = data.user(disabled, Role.TENANT_ADMIN, true);
        AuthUser agent = data.user(null, Role.SUPPORT_AGENT, true);
        AuthUser inactiveAgent = data.user(null, Role.SUPPORT_AGENT, false);

        int sent = notificationService.notifyAllActiveTenants(draft, EnumSet.of(BusinessType.KIOSCO));
        assertThat(sent).isPositive();
        assertThat(recipients()).contains(kioscoAdmin.id()).doesNotContain(disabledAdmin.id(), admin.id(), agent.id());

        clear();
        notificationService.notifyAllActiveTenants(draft, null);
        assertThat(recipients()).contains(kioscoAdmin.id(), admin.id(), employeeNorte.id())
                .doesNotContain(disabledAdmin.id(), inactiveEmployee.id(), agent.id());

        clear();
        notificationService.notifyPlatformRole(Role.SUPPORT_AGENT, draft);
        assertThat(recipients()).contains(agent.id()).doesNotContain(inactiveAgent.id(), admin.id());
        assertThatThrownBy(() -> notificationService.notifyPlatformRole(Role.TENANT_ADMIN, draft))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inboxListingCountAndReadMarks() {
        NotificationDto first = notificationService.notifyUser(employeeCentro.id(), draft);
        NotificationDto second = notificationService.notifyUser(employeeCentro.id(), new NotificationDraft(
                NotificationType.SYSTEM, null, "Segunda", null, null, null, null));
        NotificationDto foreign = notificationService.notifyUser(employeeNorte.id(), draft);

        PageResponse<NotificationDto> page = notificationService.list(employeeCentro.id(), false, 0, 20);
        assertThat(page.content()).extracting(NotificationDto::id).containsExactly(second.id(), first.id());
        assertThat(page.content().getFirst().severity()).isEqualTo(Severity.INFO);
        assertThat(notificationService.unreadCount(employeeCentro.id())).isEqualTo(2);

        notificationService.markRead(employeeCentro.id(), first.id());
        notificationService.markRead(employeeCentro.id(), first.id());
        assertThat(notificationService.unreadCount(employeeCentro.id())).isEqualTo(1);
        assertThat(notificationService.list(employeeCentro.id(), true, 0, 20).content()).extracting(NotificationDto::id)
                .containsExactly(second.id());
        assertThatThrownBy(() -> notificationService.markRead(employeeCentro.id(), foreign.id()))
                .isInstanceOf(NotFoundException.class);

        assertThat(notificationService.markAllRead(employeeCentro.id())).isEqualTo(1);
        assertThat(notificationService.unreadCount(employeeCentro.id())).isZero();
        assertThat(notificationService.unreadCount(employeeNorte.id())).isEqualTo(1);
    }

    @Test
    void largeFanOutIsPushedOnceWithOnePayloadPerUser() {
        notificationService.notifyTenantUsers(tenant, null, draft);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<Long, NotificationDto>> captor = ArgumentCaptor.forClass(Map.class);
        verify(realtimePublisher).toEachUser(eq(Destinations.QUEUE_NOTIFICATIONS), captor.capture());
        assertThat(captor.getValue()).containsOnlyKeys(admin.id(), boss.id(), employeeCentro.id(), employeeNorte.id());
        assertThat(captor.getValue().values()).extracting(NotificationDto::id).doesNotHaveDuplicates();
    }

    private List<Long> recipients() {
        return jdbc.queryForList("select user_id from notifications where title = ?", Long.class, draft.title());
    }

    private void clear() {
        jdbc.update("delete from notifications where title = ?", draft.title());
    }
}
