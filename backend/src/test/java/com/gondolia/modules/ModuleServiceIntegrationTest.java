package com.gondolia.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.realtime.Destinations;
import com.gondolia.security.AuthUser;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ModuleService} contra PostgreSQL: presets por plan, validación de {@code MULTI_BRANCH} en uso, eventos de
 * tenant, push {@code MODULES_CHANGED} y máximo efectivo de sucursales. Cada prueba se revierte.
 */
@Transactional
class ModuleServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ModuleService moduleService;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenant;
    private long centro;
    private AuthUser admin;
    private AuthUser owner;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Módulos", "ALMACEN", "PROFESIONAL", "ACTIVE", "FIFO");
        centro = data.branch(tenant, "Centro", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true, centro);
        data.user(tenant, Role.TENANT_CASHIER, true, centro);
        data.user(tenant, Role.TENANT_EMPLOYEE, false, centro);
        owner = data.user(null, Role.PLATFORM_OWNER, true);
        reset(realtimePublisher);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void planPresetsFollowTheSpec() {
        moduleService.applyPlanPreset(tenant, TenantPlan.FREEMIUM);
        assertThat(moduleService.enabledModules(tenant)).containsExactly(TenantModule.POS_GONDOLIA);

        moduleService.applyPlanPreset(tenant, TenantPlan.BASICO);
        assertThat(moduleService.enabledModules(tenant)).containsExactlyInAnyOrder(TenantModule.POS_GONDOLIA,
                TenantModule.POS_INTEGRATION, TenantModule.MULTI_BRANCH);

        moduleService.applyPlanPreset(tenant, TenantPlan.PROFESIONAL);
        assertThat(moduleService.enabledModules(tenant)).hasSize(3);
    }

    @Test
    void enablingRecordsTheEventAndPushesToEveryActiveUser() {
        TenantModuleStatus status = moduleService.setEnabled(tenant, TenantModule.POS_GONDOLIA, true, owner.id());

        assertThat(status.enabled()).isTrue();
        assertThat(status.module()).isEqualTo(TenantModule.POS_GONDOLIA);
        assertThat(status.name()).isEqualTo("Punto de venta GondolIA");
        assertThat(status.monthlyPricePerBranch()).isEqualByComparingTo("12000");
        assertThat(status.updatedByName()).isEqualTo(owner.fullName());
        assertThat(status.updatedAt()).isNotNull();
        assertThat(moduleService.isEnabled(tenant, TenantModule.POS_GONDOLIA)).isTrue();
        assertThat(moduleService.isEnabled(tenant, TenantModule.POS_INTEGRATION)).isFalse();

        assertThat(events()).singleElement().satisfies(row -> {
            assertThat(row.get("type")).isEqualTo("MODULE_ENABLED");
            assertThat(row.get("from_value")).as("from_value = nombre del módulo").isEqualTo("POS_GONDOLIA");
            assertThat(row.get("actor_user_id")).isEqualTo(owner.id());
        });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> recipients = ArgumentCaptor.forClass(List.class);
        verify(realtimePublisher).toUsers(recipients.capture(), eq(Destinations.QUEUE_SESSION),
                eq(new ModulesChangedMessage()));
        assertThat(recipients.getValue())
                .as("todos los usuarios activos del comercio, incluido el cajero; no los inactivos")
                .hasSize(2)
                .contains(admin.id())
                .doesNotContain(owner.id());
    }

    @Test
    void repeatingTheSameStateDoesNotRecordEventsOrPush() {
        moduleService.setEnabled(tenant, TenantModule.POS_INTEGRATION, true, owner.id());
        reset(realtimePublisher);

        TenantModuleStatus again = moduleService.setEnabled(tenant, TenantModule.POS_INTEGRATION, true, owner.id());

        assertThat(again.enabled()).isTrue();
        assertThat(events()).hasSize(1);
        verify(realtimePublisher, never()).toUsers(any(), any(), any());
    }

    @Test
    void disablingRecordsTheEventAndBlocksMultiBranchInUse() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, owner.id());
        long norte = data.branch(tenant, "Norte", true);

        assertApiError(() -> moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, false, owner.id()),
                409, "MODULE_IN_USE");
        assertThat(moduleService.isEnabled(tenant, TenantModule.MULTI_BRANCH)).isTrue();
        assertThatThrownBy(() -> moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, false, owner.id()))
                .hasMessageContaining("2 sucursales activas");

        jdbc.update("update branches set active = false where id = ?", norte);
        TenantModuleStatus disabled = moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, false, owner.id());

        assertThat(disabled.enabled()).isFalse();
        assertThat(moduleService.enabledModules(tenant)).isEmpty();
        assertThat(events()).extracting(row -> row.get("type"))
                .containsExactly("MODULE_ENABLED", "MODULE_DISABLED");
    }

    @Test
    void presetLeavesMultiBranchAloneWhenItIsInUse() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, owner.id());
        data.branch(tenant, "Norte", true);

        moduleService.applyPlanPreset(tenant, TenantPlan.FREEMIUM);

        assertThat(moduleService.enabledModules(tenant))
                .as("el preset no puede dejar huérfanas las sucursales activas")
                .containsExactlyInAnyOrder(TenantModule.POS_GONDOLIA, TenantModule.MULTI_BRANCH);
    }

    @Test
    void statusesAndEffectiveMaxBranches() {
        assertThat(moduleService.statuses(tenant)).extracting(TenantModuleStatus::module, TenantModuleStatus::enabled)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TenantModule.POS_GONDOLIA, false),
                        org.assertj.core.groups.Tuple.tuple(TenantModule.POS_INTEGRATION, false),
                        org.assertj.core.groups.Tuple.tuple(TenantModule.MULTI_BRANCH, false));
        assertThat(moduleService.effectiveMaxBranches(tenant)).as("sin MULTI_BRANCH, una sola sucursal").isEqualTo(1);

        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, owner.id());

        assertThat(moduleService.effectiveMaxBranches(tenant)).isEqualTo(TenantPlan.PROFESIONAL.maxBranches());
        assertThat(moduleService.statuses(tenant)).filteredOn(TenantModuleStatus::enabled)
                .extracting(TenantModuleStatus::module).containsExactly(TenantModule.MULTI_BRANCH);
    }

    @Test
    void requireUsesTheTenantOfTheCurrentUser() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(admin, null, admin.authorities()));

        assertApiError(() -> moduleService.require(TenantModule.POS_GONDOLIA), 403, "MODULE_DISABLED");
        assertThatThrownBy(() -> moduleService.require(TenantModule.POS_GONDOLIA))
                .hasMessage(ModuleCatalog.MSG_MODULE_DISABLED);

        moduleService.setEnabled(tenant, TenantModule.POS_GONDOLIA, true, owner.id());
        moduleService.require(TenantModule.POS_GONDOLIA);
    }

    @Test
    void unknownTenantIsRejected() {
        assertApiError(() -> moduleService.setEnabled(-1L, TenantModule.POS_GONDOLIA, true, owner.id()), 404,
                "NOT_FOUND");
        assertThat(moduleService.enabledModules(null)).isEmpty();
        assertThat(moduleService.isEnabled(null, TenantModule.POS_GONDOLIA)).isFalse();
        assertThat(moduleService.statuses(null)).extracting(TenantModuleStatus::enabled).containsOnly(false);
        assertThat(moduleService.effectiveMaxBranches(null)).isEqualTo(1);
        assertThat(moduleService.effectiveMaxBranches(-1L)).isEqualTo(1);
    }

    private List<Map<String, Object>> events() {
        return jdbc.queryForList("""
                select type, from_value, actor_user_id from tenant_events
                where tenant_id = ? and type in ('MODULE_ENABLED', 'MODULE_DISABLED') order by id
                """, tenant);
    }

    private static void assertApiError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }
}
