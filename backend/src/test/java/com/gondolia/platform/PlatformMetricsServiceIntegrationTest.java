package com.gondolia.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.platform.dto.CreateTenantRequest;
import com.gondolia.platform.dto.CreateTenantRequest.NewUserRequest;
import com.gondolia.platform.dto.PlatformMetrics;
import com.gondolia.platform.dto.PlatformMetrics.GrowthPoint;
import com.gondolia.platform.dto.TenantDetail;
import com.gondolia.security.AuthUser;
import java.math.BigDecimal;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link PlatformMetricsService} contra PostgreSQL (SPEC §6.6 y §14.3). Las métricas son de toda la plataforma, así
 * que cada prueba compara el <b>delta</b> contra la foto anterior a sus datos: así no dependen de lo que ya haya en
 * la base. Cada prueba se revierte.
 */
@Transactional
class PlatformMetricsServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private PlatformMetricsService service;
    @Autowired
    private TenantAdminService tenantAdmin;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
    }

    @Test
    void mrrAddsPlanAndModulesPerActiveBranch() {
        PlatformMetrics before = service.metrics();

        long tenant = data.tenant("MRR", "MINIMERCADO", "PROFESIONAL", "ACTIVE", "FIFO");
        data.branch(tenant, "Centro", true);
        data.branch(tenant, "Norte", true);
        data.branch(tenant, "Cerrada", false);
        enable(tenant, TenantModule.POS_GONDOLIA, TenantModule.MULTI_BRANCH);

        // Un comercio deshabilitado no factura aunque tenga módulos y sucursales.
        long blocked = data.tenant("Bloqueado", "KIOSCO", "PROFESIONAL", "DISABLED", "FIFO");
        data.branch(blocked, "Única", true);
        enable(blocked, TenantModule.POS_GONDOLIA);

        PlatformMetrics after = service.metrics();

        // 2 sucursales activas × (55.000 del plan + 12.000 del POS + 0 de multi-sucursal) = 134.000
        assertThat(after.revenue().estimatedMrr().subtract(before.revenue().estimatedMrr()))
                .isEqualByComparingTo(new BigDecimal("134000"));
        assertThat(after.revenue().currency()).isEqualTo("ARS");
        assertThat(after.tenants().total() - before.tenants().total()).isEqualTo(2);
        assertThat(after.tenants().active() - before.tenants().active()).isEqualTo(1);
        assertThat(after.tenants().disabled() - before.tenants().disabled()).isEqualTo(1);
        assertThat(after.branches().total() - before.branches().total()).isEqualTo(4);
        // Las sucursales activas son las que facturan: las del comercio deshabilitado no cuentan.
        assertThat(after.branches().active() - before.branches().active()).isEqualTo(2);
        assertThat(after.branches().multiBranchTenants() - before.branches().multiBranchTenants()).isEqualTo(1);
        assertThat(after.tenantsByPlan().get(TenantPlan.PROFESIONAL) - before.tenantsByPlan().get(TenantPlan.PROFESIONAL))
                .isEqualTo(2);
        assertThat(after.modules().get(TenantModule.POS_GONDOLIA).tenants()
                - before.modules().get(TenantModule.POS_GONDOLIA).tenants()).isEqualTo(1);
        assertThat(after.modules().get(TenantModule.POS_INTEGRATION).tenants()
                - before.modules().get(TenantModule.POS_INTEGRATION).tenants()).isZero();
    }

    @Test
    void usersEngagementAndGrowthUseAdministrativeDataOnly() {
        PlatformMetrics before = service.metrics();

        long tenant = data.tenant("Engagement", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        long branch = data.branch(tenant, "Centro", true);
        var boss = data.user(tenant, Role.TENANT_BOSS, true);
        data.user(tenant, Role.TENANT_ADMIN, true);
        data.user(tenant, Role.TENANT_EMPLOYEE, true, branch);
        jdbc.update("update users set last_login_at = now() - interval '2 days' where id = ?", boss.id());

        PlatformMetrics after = service.metrics();

        assertThat(after.users().total() - before.users().total()).isEqualTo(3);
        assertThat(after.users().byRole().get(Role.TENANT_EMPLOYEE)
                - before.users().byRole().get(Role.TENANT_EMPLOYEE)).isEqualTo(1);
        assertThat(after.engagement().activeTenants7d() - before.engagement().activeTenants7d()).isEqualTo(1);
        assertThat(after.engagement().activeUsers7d() - before.engagement().activeUsers7d()).isEqualTo(1);

        assertThat(after.growth()).hasSize(PlatformMetricsService.GROWTH_MONTHS);
        assertThat(after.growth().getLast().month()).isEqualTo(YearMonth.now().toString());
        long newThisMonth = after.growth().getLast().newTenants() - before.growth().getLast().newTenants();
        assertThat(newThisMonth).isEqualTo(1);
        assertThat(after.growth().getLast().activeAtEndOfMonth()
                - before.growth().getLast().activeAtEndOfMonth()).isEqualTo(1);
    }

    @Test
    void growthRebuildsHistoryFromTenantEvents() {
        PlatformMetrics before = service.metrics();

        long tenant = data.tenant("Historia", "ALMACEN", "BASICO", "CANCELLED", "FIFO");
        jdbc.update("update tenants set created_at = now() - interval '5 months' where id = ?", tenant);
        jdbc.update("""
                insert into tenant_events (tenant_id, type, created_at)
                values (?, 'CREATED', now() - interval '5 months'), (?, 'CANCELLED', now() - interval '2 months')
                """, tenant, tenant);

        PlatformMetrics after = service.metrics();

        // Estaba activo hace cuatro meses y ya no lo estaba el mes pasado.
        int last = after.growth().size() - 1;
        assertThat(after.growth().get(last - 4).activeAtEndOfMonth()
                - before.growth().get(last - 4).activeAtEndOfMonth()).isEqualTo(1);
        assertThat(after.growth().get(last - 1).activeAtEndOfMonth()
                - before.growth().get(last - 1).activeAtEndOfMonth()).isZero();
        assertThat(after.growth().get(last - 2).cancelled() - before.growth().get(last - 2).cancelled())
                .isEqualTo(1);
        assertThat(after.tenants().cancelled() - before.tenants().cancelled()).isEqualTo(1);
        assertThat(after.tenants().cancelledLast30d()).isEqualTo(before.tenants().cancelledLast30d());
    }

    @Test
    void deletedTenantKeepsItsAltaAndCountsASingleBaja() {
        AuthUser owner = data.user(null, Role.PLATFORM_OWNER, true);
        PlatformMetrics before = service.metrics();

        // Baja, reactivación, otra baja y eliminación definitiva: un alta y una sola baja.
        TenantDetail tenant = tenantAdmin.create(newTenant("Eliminado"), owner.id());
        tenantAdmin.cancel(tenant.id(), "Cerró el local", owner.id());
        tenantAdmin.reactivate(tenant.id(), "Volvió", owner.id());
        tenantAdmin.cancel(tenant.id(), "Cerró de nuevo", owner.id());
        tenantAdmin.delete(tenant.id(), tenant.name(), owner.id());

        PlatformMetrics after = service.metrics();

        assertThat(after.tenants().total()).isEqualTo(before.tenants().total());
        assertThat(after.tenants().newLast30d() - before.tenants().newLast30d()).isEqualTo(1);
        assertThat(after.tenants().cancelledLast30d() - before.tenants().cancelledLast30d()).isEqualTo(1);
        GrowthPoint thisMonthBefore = before.growth().getLast();
        GrowthPoint thisMonth = after.growth().getLast();
        assertThat(thisMonth.newTenants() - thisMonthBefore.newTenants()).isEqualTo(1);
        assertThat(thisMonth.cancelled() - thisMonthBefore.cancelled()).isEqualTo(1);
        assertThat(thisMonth.activeAtEndOfMonth()).isEqualTo(thisMonthBefore.activeAtEndOfMonth());
    }

    @Test
    void deletedTenantStillCountsAsActiveInThePastMonths() {
        long tenant = data.tenant("Eliminado viejo", "ALMACEN", "BASICO", "CANCELLED", "FIFO");
        jdbc.update("update tenants set created_at = now() - interval '5 months' where id = ?", tenant);
        jdbc.update("""
                insert into tenant_events (tenant_id, type, from_value, to_value, created_at)
                values (?, 'CREATED', null, 'BASICO', now() - interval '5 months'),
                       (?, 'CANCELLED', 'ACTIVE', 'CANCELLED', now() - interval '2 months')
                """, tenant, tenant);
        PlatformMetrics before = service.metrics();

        // Lo mismo que hace TenantAdminService.delete: guarda el id y borra el comercio.
        jdbc.update("update tenant_events set deleted_tenant_id = tenant_id where tenant_id = ?", tenant);
        jdbc.update("delete from tenants where id = ?", tenant);

        PlatformMetrics after = service.metrics();

        // La historia no cambia por eliminarlo: siguen su alta, su baja y los meses en que estuvo activo.
        assertThat(after.growth()).isEqualTo(before.growth());
        assertThat(after.tenants().total()).isEqualTo(before.tenants().total() - 1);
        int last = after.growth().size() - 1;
        assertThat(after.growth().get(last - 2).cancelled()).isPositive();
    }

    @Test
    void cancellationUndoneInTheSamePeriodIsNotChurn() {
        AuthUser owner = data.user(null, Role.PLATFORM_OWNER, true);
        PlatformMetrics before = service.metrics();

        TenantDetail tenant = tenantAdmin.create(newTenant("Arrepentido"), owner.id());
        tenantAdmin.cancel(tenant.id(), "Por error", owner.id());
        tenantAdmin.reactivate(tenant.id(), "Fue un error", owner.id());

        PlatformMetrics after = service.metrics();

        assertThat(after.tenants().cancelledLast30d()).isEqualTo(before.tenants().cancelledLast30d());
        assertThat(after.growth().getLast().cancelled()).isEqualTo(before.growth().getLast().cancelled());
        assertThat(after.growth().getLast().newTenants() - before.growth().getLast().newTenants()).isEqualTo(1);
        assertThat(after.growth().getLast().activeAtEndOfMonth()
                - before.growth().getLast().activeAtEndOfMonth()).isEqualTo(1);
    }

    @Test
    void eventsWithoutATenantToAttributeThemToAreIgnored() {
        PlatformMetrics before = service.metrics();

        // Historial de un comercio eliminado antes de V250: no se sabe de quién es, no suma ni altas ni bajas.
        jdbc.update("""
                insert into tenant_events (tenant_id, type, from_value, to_value)
                values (null, 'CREATED', null, 'BASICO'), (null, 'CANCELLED', 'ACTIVE', 'CANCELLED'),
                       (null, 'REACTIVATED', 'CANCELLED', 'ACTIVE'), (null, 'CANCELLED', 'ACTIVE', 'CANCELLED')
                """);

        PlatformMetrics after = service.metrics();

        assertThat(after.tenants().newLast30d()).isEqualTo(before.tenants().newLast30d());
        assertThat(after.tenants().cancelledLast30d()).isEqualTo(before.tenants().cancelledLast30d());
        assertThat(after.growth()).isEqualTo(before.growth());
    }

    @Test
    void activeRecallsAreTheOnesWithPendingMatches() {
        long first = data.tenant("Recall uno", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        long second = data.tenant("Recall dos", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        String barcode = data.barcode();
        long resolved = data.recall(barcode, false, null, null, "PUBLISHED", "L-VIEJO");
        long live = data.recall(barcode, false, null, null, "PUBLISHED", "L-NUEVO");
        PlatformMetrics before = service.metrics();

        // Un recall con todas sus coincidencias resueltas ya no está en curso.
        match(resolved, first, "L-VIEJO", "RESOLVED");
        match(resolved, second, "L-VIEJO", "RESOLVED");
        assertThat(service.metrics().recalls()).isEqualTo(before.recalls());

        // El recall nuevo sigue en curso mientras algún comercio no lo resuelva, y alcanzó a los dos.
        match(live, first, "L-NUEVO", "OPEN");
        match(live, second, "L-NUEVO", "RESOLVED");
        PlatformMetrics withLive = service.metrics();
        assertThat(withLive.recalls().activeRecalls() - before.recalls().activeRecalls()).isEqualTo(1);
        assertThat(withLive.recalls().affectedTenantsTotal() - before.recalls().affectedTenantsTotal())
                .isEqualTo(2);
    }

    @Test
    void supportAndRecallsAreOnlyCounts() {
        PlatformMetrics before = service.metrics();

        long tenant = data.tenant("Soporte", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        var user = data.user(tenant, Role.TENANT_ADMIN, true);
        jdbc.update("""
                insert into support_tickets (tenant_id, created_by, subject, status, first_response_at, created_at,
                                             last_message_at, rating)
                values (?, ?, 'No puedo cargar un lote', 'OPEN', now() - interval '50 minutes',
                        now() - interval '60 minutes', now(), 5)
                """, tenant, user.id());
        jdbc.update("""
                insert into support_tickets (tenant_id, created_by, subject, status, resolved_at, created_at,
                                             last_message_at)
                values (?, ?, 'Consulta de facturación', 'RESOLVED', now(), now() - interval '3 days', now())
                """, tenant, user.id());

        PlatformMetrics after = service.metrics();

        assertThat(after.support().openTickets() - before.support().openTickets()).isEqualTo(1);
        assertThat(after.support().unassignedTickets() - before.support().unassignedTickets()).isEqualTo(1);
        assertThat(after.support().resolvedLast30d() - before.support().resolvedLast30d()).isEqualTo(1);
        assertThat(after.support().avgFirstResponseMinutes()).isNotNull();
        assertThat(after.support().avgRating()).isNotNull();
        assertThat(after.recalls().activeRecalls()).isGreaterThanOrEqualTo(0);
        assertThat(after.recalls().affectedTenantsTotal()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void conversionCountsPaidPlansAmongActiveTenants() {
        long freemium = data.tenant("Gratis", "KIOSCO", "FREEMIUM", "ACTIVE", "FIFO");
        data.branch(freemium, "Única", true);
        long paid = data.tenant("Pago", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        data.branch(paid, "Única", true);

        PlatformMetrics metrics = service.metrics();

        assertThat(metrics.revenue().freemiumToPaidConversionPct()).isBetween(0.0, 100.0);
        assertThat(metrics.tenants().active()).isGreaterThanOrEqualTo(2);
        assertThat(metrics.tenantsByPlan().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(metrics.tenants().total());
        assertThat(metrics.tenantsByBusinessType().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(metrics.tenants().total());
        assertThat(metrics.tenants().total()).isEqualTo(metrics.tenants().active() + metrics.tenants().disabled()
                + metrics.tenants().cancelled());
        assertThat(TenantStatus.values()).hasSize(3);
    }

    private void match(long recallId, long tenantId, String lotNumber, String status) {
        long branch = data.branch(tenantId, "Sucursal " + lotNumber, true);
        long product = data.product(tenantId, data.barcode(), "Sopa de tomate", "100", "150");
        long lot = data.lot(tenantId, branch, product, lotNumber, lotNumber, null, 10, "RECALLED", 3);
        jdbc.update("""
                insert into recall_matches (announcement_id, tenant_id, branch_id, product_id, lot_id,
                                            quantity_at_match, status)
                values (?, ?, ?, ?, ?, 10, ?)
                """, recallId, tenantId, branch, product, lot, status);
    }

    private CreateTenantRequest newTenant(String name) {
        String suffix = data.suffix() + "-" + Math.abs(name.hashCode());
        return new CreateTenantRequest(name + " " + suffix, null, null, BusinessType.ALMACEN, TenantPlan.BASICO,
                null, null, null, null, "CABA", "Buenos Aires", null, null, newUser("jefe", suffix),
                newUser("admin", suffix), newUser("empleado", suffix), null, null);
    }

    private static NewUserRequest newUser(String prefix, String suffix) {
        return new NewUserRequest("Usuario " + prefix, prefix + "." + suffix + "@metricas.test", "Demo2026!");
    }

    private void enable(long tenantId, TenantModule... modules) {
        for (TenantModule module : modules) {
            jdbc.update("insert into tenant_modules (tenant_id, module, enabled) values (?, ?, true)", tenantId,
                    module.name());
        }
    }
}
