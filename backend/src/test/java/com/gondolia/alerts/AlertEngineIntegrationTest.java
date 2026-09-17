package com.gondolia.alerts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.alerts.AlertEvaluator.Evaluation;
import com.gondolia.alerts.AlertService.AlertQuery;
import com.gondolia.alerts.dto.AlertCountsDto;
import com.gondolia.alerts.dto.AlertDto;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.domain.alert.AlertStatus;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Motor de alertas y bandeja (SPEC §6.5) contra PostgreSQL real.
 * <p>
 * {@link AlertEvaluator#evaluateBranch} corre en su propia transacción (la llaman tareas programadas y listeners
 * posteriores al commit), así que esta prueba <strong>no</strong> se revierte sola: borra sus comercios al final.
 */
class AlertEngineIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AlertEngine alertEngine;
    @Autowired
    private AlertService alertService;
    @Autowired
    private Clock clock;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long norte;
    private long otherBranch;
    private long leche;
    private long yogur;
    private AuthUser admin;
    private AuthUser otherAdmin;

    private Scope scopeAll;
    private Scope scopeCentro;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Alertas");
        otherTenant = data.tenant("Alertas ajenas");
        centro = data.branch(tenant, "Sucursal Centro", true);
        norte = data.branch(tenant, "Sucursal Norte", true);
        otherBranch = data.branch(otherTenant, "Sucursal Ajena", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);

        leche = data.product(tenant, data.barcode(), "Leche entera", "1000", "1600");
        yogur = data.product(tenant, data.barcode(), "Yogur bebible", "800", "1400");
        jdbc.update("update products set min_stock = 10 where id in (?, ?)", leche, yogur);

        LocalDate today = LocalDate.now(clock);
        // Centro: un lote vencido con remanente y otro por vencer; la leche queda sin stock.
        data.lot(tenant, centro, yogur, "Y0", "Y0", today.minusDays(3), 8, "ACTIVE", 40);
        data.lot(tenant, centro, yogur, "Y1", "Y1", today.plusDays(2), 12, "ACTIVE", 5);
        data.lot(tenant, centro, leche, "L0", "L0", today.plusDays(60), 0, "DEPLETED", 20);

        scopeAll = new Scope(List.of(centro, norte), Map.of(centro, "Sucursal Centro", norte, "Sucursal Norte"), true);
        scopeCentro = new Scope(List.of(centro), Map.of(centro, "Sucursal Centro"), false);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        jdbc.update("delete from tenants where id in (?, ?)", tenant, otherTenant);
    }

    // ------------------------------------------------------------------ motor

    @Test
    void engineOpensExpiryAndStockAlertsPerBranch() {
        Evaluation result = alertEngine.evaluateBranch(tenant, centro);

        assertThat(result.opened()).isPositive();
        List<String> types = alertTypes(centro);
        assertThat(types).contains(AlertType.EXPIRED.name(), AlertType.EXPIRING_SOON.name(),
                AlertType.OUT_OF_STOCK.name());
        // Toda alerta del motor queda atada a su sucursal.
        assertThat(jdbc.queryForObject("select count(*) from alerts where tenant_id = ? and branch_id is null",
                Long.class, tenant)).isZero();
    }

    @Test
    void engineIsIdempotentAndClosesWhatNoLongerApplies() {
        alertEngine.evaluateBranch(tenant, centro);
        long afterFirst = openAlerts(centro);

        Evaluation second = alertEngine.evaluateBranch(tenant, centro);
        assertThat(second.opened()).isZero();
        assertThat(openAlerts(centro)).isEqualTo(afterFirst);

        // Se descarta el lote vencido y se repone la leche: esas alertas se resuelven solas.
        jdbc.update("update lots set quantity = 0, status = 'EXPIRED_DISCARDED' where tenant_id = ? and lot_number = 'Y0'",
                tenant);
        jdbc.update("update lots set quantity = 40, status = 'ACTIVE' where tenant_id = ? and lot_number = 'L0'",
                tenant);
        Evaluation third = alertEngine.evaluateBranch(tenant, centro);

        assertThat(third.resolved()).isPositive();
        assertThat(alertTypes(centro)).doesNotContain(AlertType.EXPIRED.name(), AlertType.OUT_OF_STOCK.name());
    }

    @Test
    void dismissedAlertsAreNotReopenedDuringTheQuietPeriod() {
        alertEngine.evaluateBranch(tenant, centro);
        as(admin, String.valueOf(centro));
        AlertDto expired = firstOfType(AlertType.EXPIRED);
        alertService.dismiss(tenant, expired.id(), admin.id(), scopeCentro);

        alertEngine.evaluateBranch(tenant, centro);

        assertThat(jdbc.queryForObject("""
                select count(*) from alerts
                where tenant_id = ? and branch_id = ? and type = 'EXPIRED' and status in ('OPEN', 'ACKNOWLEDGED')
                """, Long.class, tenant, centro)).isZero();
    }

    // ------------------------------------------------------------------ bandeja

    @Test
    void trayFiltersByScopeStatusAndType() {
        alertEngine.evaluateBranch(tenant, centro);
        as(admin, null);

        PageResponse<AlertDto> open = alertService.list(tenant, scopeAll,
                new AlertQuery(AlertStatus.OPEN, null, null, null, null, 0, 20));
        assertThat(open.totalElements()).isPositive();
        assertThat(open.content()).allSatisfy(row -> {
            assertThat(row.branchId()).isEqualTo(centro);
            assertThat(row.branchName()).isEqualTo("Sucursal Centro");
        });

        PageResponse<AlertDto> soloVencidas = alertService.list(tenant, scopeAll,
                new AlertQuery(null, AlertType.EXPIRED, null, null, null, 0, 20));
        assertThat(soloVencidas.content()).extracting(AlertDto::type).containsOnly(AlertType.EXPIRED);

        // El alcance de Norte no ve las alertas de Centro.
        Scope soloNorte = new Scope(List.of(norte), Map.of(norte, "Sucursal Norte"), false);
        assertThat(alertService.list(tenant, soloNorte,
                new AlertQuery(AlertStatus.OPEN, null, null, null, null, 0, 20)).totalElements()).isZero();

        AlertCountsDto counts = alertService.counts(tenant, scopeAll);
        assertThat(counts.open()).isEqualTo(open.totalElements());
        assertThat(counts.byType()).containsKey(AlertType.EXPIRED.name());
    }

    @Test
    void transitionsRecordWhoHandledTheAlert() {
        alertEngine.evaluateBranch(tenant, centro);
        as(admin, null);
        AlertDto alert = firstOfType(AlertType.EXPIRING_SOON);

        AlertDto acknowledged = alertService.acknowledge(tenant, alert.id(), admin.id(), scopeAll);
        assertThat(acknowledged.status()).isEqualTo(AlertStatus.ACKNOWLEDGED);
        assertThat(acknowledged.handledByName()).isNotBlank();
        assertThat(acknowledged.resolvedAt()).isNull();

        AlertDto resolved = alertService.resolve(tenant, alert.id(), admin.id(), scopeAll);
        assertThat(resolved.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(resolved.resolvedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void anotherTenantCannotSeeOrHandleTheseAlerts() {
        alertEngine.evaluateBranch(tenant, centro);
        as(admin, null);
        AlertDto alert = firstOfType(AlertType.EXPIRED);

        as(otherAdmin, null);
        Scope otherScope = new Scope(List.of(otherBranch), Map.of(otherBranch, "Sucursal Ajena"), false);

        assertThat(alertService.list(otherTenant, otherScope,
                new AlertQuery(null, null, null, null, null, 0, 20)).totalElements()).isZero();
        assertThat(alertService.counts(otherTenant, otherScope).open()).isZero();

        // Por id: la alerta es de otro comercio, así que no existe.
        assertApiError(() -> alertService.acknowledge(otherTenant, alert.id(), otherAdmin.id(), otherScope), 404);
        assertApiError(() -> alertService.resolve(otherTenant, alert.id(), otherAdmin.id(), otherScope), 404);
    }

    @Test
    void anEmployeeWithoutAccessToTheBranchCannotHandleItsAlerts() {
        alertEngine.evaluateBranch(tenant, centro);
        as(admin, null);
        AlertDto alert = firstOfType(AlertType.EXPIRED);

        // Empleado asignado solo a Norte: la alerta es de Centro.
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, norte);
        as(employee, null);

        assertThatThrownBy(() -> alertService.acknowledge(tenant, alert.id(), employee.id(), scopeAll))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo("BRANCH_FORBIDDEN"));
    }

    // ------------------------------------------------------------------ apoyo

    private AlertDto firstOfType(AlertType type) {
        return alertService.list(tenant, scopeAll, new AlertQuery(null, type, null, null, null, 0, 1))
                .content().getFirst();
    }

    private List<String> alertTypes(long branchId) {
        return jdbc.queryForList("""
                select type from alerts where tenant_id = ? and branch_id = ? and status in ('OPEN', 'ACKNOWLEDGED')
                """, String.class, tenant, branchId);
    }

    private long openAlerts(long branchId) {
        Long count = jdbc.queryForObject("""
                select count(*) from alerts where tenant_id = ? and branch_id = ? and status = 'OPEN'
                """, Long.class, tenant, branchId);
        return count == null ? 0 : count;
    }

    private static void as(AuthUser user, String branchHeader) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/alerts");
        if (branchHeader != null) {
            request.addHeader(BranchAccessService.HEADER, branchHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus().value()).isEqualTo(status));
    }
}
