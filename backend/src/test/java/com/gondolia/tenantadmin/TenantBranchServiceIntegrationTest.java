package com.gondolia.tenantadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.modules.ModuleService;
import com.gondolia.tenantadmin.dto.BranchDto;
import com.gondolia.tenantadmin.dto.BranchLimitsDto;
import com.gondolia.tenantadmin.dto.BranchRequest;
import com.gondolia.security.AuthUser;
import java.time.LocalDate;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantBranchService} contra PostgreSQL: límite efectivo por plan y módulo {@code MULTI_BRANCH}, bloqueos al
 * desactivar (stock y última sucursal activa), alcance del listado por rol y aislamiento entre comercios.
 */
@Transactional
class TenantBranchServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TenantBranchService branchService;
    @Autowired
    private ModuleService moduleService;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenant;
    private long centro;
    private AuthUser admin;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Sucursales", "ALMACEN", "BASICO", "ACTIVE", "FIFO");
        centro = data.branch(tenant, "Centro", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        authenticate(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ límite

    @Test
    void withoutMultiBranchTheEffectiveLimitIsOne() {
        BranchLimitsDto limits = branchService.limits();
        assertThat(limits.plan()).isEqualTo(TenantPlan.BASICO);
        assertThat(limits.planMaxBranches()).isEqualTo(3);
        assertThat(limits.multiBranchEnabled()).isFalse();
        assertThat(limits.maxBranches()).isEqualTo(1);
        assertThat(limits.activeBranches()).isEqualTo(1);

        assertApiError(() -> branchService.create(request("Norte")), 409, ErrorCodes.BRANCH_LIMIT_REACHED);
    }

    @Test
    void withMultiBranchTheLimitIsThePlanLimit() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, admin.id());

        assertThat(branchService.limits().maxBranches()).isEqualTo(3);
        BranchDto norte = branchService.create(request("Norte"));
        assertThat(norte.active()).isTrue();
        assertThat(norte.code()).isEqualTo("NOR");
        branchService.create(request("Sur"));

        assertThat(branchService.limits().activeBranches()).isEqualTo(3);
        assertApiError(() -> branchService.create(request("Oeste")), 409, ErrorCodes.BRANCH_LIMIT_REACHED);
    }

    @Test
    void rejectsDuplicateNamesInTheSameTenant() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, admin.id());
        assertApiError(() -> branchService.create(request("centro")), 409,
                TenantAdminErrors.DUPLICATE_BRANCH_NAME);
    }

    // ------------------------------------------------------------------ baja y alta

    @Test
    void cannotDeactivateTheLastActiveBranch() {
        assertApiError(() -> branchService.deactivate(centro), 409, TenantAdminErrors.LAST_ACTIVE_BRANCH);
    }

    @Test
    void cannotDeactivateABranchWithStock() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, admin.id());
        branchService.create(request("Norte"));
        long product = data.product(tenant, data.barcode(), "Leche entera La Pradera 1 L", "900", "1500");
        data.lot(tenant, centro, product, "L2409A", "L2409A", LocalDate.now().plusDays(20), 12, "ACTIVE", 2);

        assertApiError(() -> branchService.deactivate(centro), 409, ErrorCodes.BRANCH_HAS_STOCK);
    }

    @Test
    void deactivateAndActivateRespectingTheLimit() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, admin.id());
        BranchDto norte = branchService.create(request("Norte"));

        assertThat(branchService.deactivate(norte.id()).active()).isFalse();
        assertThat(branchService.limits().activeBranches()).isEqualTo(1);
        // Idempotente.
        assertThat(branchService.deactivate(norte.id()).active()).isFalse();

        // Sin Multi-sucursal el máximo efectivo vuelve a 1 y no se puede reactivar.
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, false, admin.id());
        assertApiError(() -> branchService.activate(norte.id()), 409, ErrorCodes.BRANCH_LIMIT_REACHED);

        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, admin.id());
        assertThat(branchService.activate(norte.id()).active()).isTrue();
    }

    // ------------------------------------------------------------------ alcance del listado

    @Test
    void listRespectsRoleScopeAndInactiveBranches() {
        moduleService.setEnabled(tenant, TenantModule.MULTI_BRANCH, true, admin.id());
        BranchDto norte = branchService.create(request("Norte"));
        branchService.deactivate(norte.id());

        assertThat(branchService.list(false)).extracting(BranchDto::id).containsExactly(centro);
        assertThat(branchService.list(true)).extracting(BranchDto::id).containsExactly(centro, norte.id());

        AuthUser cashier = data.user(tenant, Role.TENANT_CASHIER, true, centro);
        authenticate(cashier);
        // El cajero solo ve su sucursal y nunca las desactivadas, aunque pida includeInactive.
        assertThat(branchService.list(true)).extracting(BranchDto::id).containsExactly(centro);
    }

    @Test
    void employeeCountsOnlyActiveEmployeesAndCashiers() {
        data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);
        data.user(tenant, Role.TENANT_CASHIER, true, centro);
        data.user(tenant, Role.TENANT_EMPLOYEE, false, centro);

        BranchDto branch = branchService.list(false).get(0);
        assertThat(branch.employeeCount()).isEqualTo(2);
        assertThat(branch.hasStock()).isFalse();
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void neverTouchesBranchesOfAnotherTenant() {
        long otherTenant = data.tenant("Otro comercio");
        long otherBranch = data.branch(otherTenant, "Ajena", true);

        assertApiError(() -> branchService.update(otherBranch, request("Robada")), 404, ErrorCodes.NOT_FOUND);
        assertApiError(() -> branchService.deactivate(otherBranch), 404, ErrorCodes.NOT_FOUND);
        assertApiError(() -> branchService.activate(otherBranch), 404, ErrorCodes.NOT_FOUND);
        assertThat(branchService.list(true)).extracting(BranchDto::id).doesNotContain(otherBranch);
    }

    // ------------------------------------------------------------------ helpers

    private static BranchRequest request(String name) {
        return new BranchRequest(name, name.substring(0, 3), "Av. Siempreviva 742", "Rosario", "Santa Fe",
                "341 555-0100");
    }

    private static void assertApiError(ThrowingCallable callable, int status, String code) {
        assertThatThrownBy(callable).isInstanceOfSatisfying(ApiException.class, error -> {
            assertThat(error.getStatus().value()).isEqualTo(status);
            assertThat(error.getCode()).isEqualTo(code);
        });
    }

    private static void authenticate(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
    }
}
