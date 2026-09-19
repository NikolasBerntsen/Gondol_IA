package com.gondolia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.auth.AuthService;
import com.gondolia.auth.dto.MeDto;
import com.gondolia.common.error.ApiException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.security.ApiKeyService;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.GeneratedApiKey;
import com.gondolia.security.PosBranch;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Pruebas del núcleo contra PostgreSQL real (Flyway + validación de Hibernate, consultas de sucursales y de rotación de
 * lotes). Corren con las demás pruebas de integración ({@code mvn test -Dgondolia.it=true}, misma base que
 * {@link PostgresIntegrationTest}). Cada prueba corre en una transacción que se revierte.
 */
@SpringBootTest
@Transactional
@PostgresIntegrationTest.EnabledWhenRequested
class FoundationDatabaseTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.registerDatasource(registry);
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private BranchAccessService branchAccessService;
    @Autowired
    private ApiKeyService apiKeyService;
    @Autowired
    private LotRepository lotRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AuthService authService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private long tenantA;
    private long tenantB;
    private long centro;
    private long norte;
    private long vieja;
    private long ajena;
    private AuthUser admin;
    private AuthUser boss;
    private AuthUser employee;
    private AuthUser cashier;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant("Comercio A " + suffix, "PROFESIONAL");
        tenantB = insertTenant("Comercio B " + suffix, "FREEMIUM");
        jdbc.update("insert into tenant_settings (tenant_id, stock_rotation) values (?, 'FEFO')", tenantA);
        norte = insertBranch(tenantA, "Sucursal Norte", "NOR", true);
        centro = insertBranch(tenantA, "Sucursal Centro", "CEN", true);
        vieja = insertBranch(tenantA, "Sucursal Antigua", "ANT", false);
        ajena = insertBranch(tenantB, "Sucursal Principal", "PRI", true);
        admin = insertUser(tenantA, "admin", Role.TENANT_ADMIN);
        boss = insertUser(tenantA, "jefe", Role.TENANT_BOSS);
        employee = insertUser(tenantA, "empleado", Role.TENANT_EMPLOYEE);
        cashier = insertUser(tenantA, "cajero", Role.TENANT_CASHIER);
        insertUser(tenantB, "admin", Role.TENANT_ADMIN);
        jdbc.update("insert into user_branches (user_id, branch_id) values (?, ?)", employee.id(), centro);
        jdbc.update("insert into user_branches (user_id, branch_id) values (?, ?)", employee.id(), vieja);
        jdbc.update("insert into user_branches (user_id, branch_id) values (?, ?)", cashier.id(), centro);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void accessibleBranchesByRole() {
        assertThat(branchAccessService.accessibleBranches(admin)).extracting(BranchAccessService.BranchRef::id)
                .containsExactly(centro, norte);
        assertThat(branchAccessService.accessibleBranches(boss)).extracting(BranchAccessService.BranchRef::name)
                .containsExactly("Sucursal Centro", "Sucursal Norte");
        assertThat(branchAccessService.accessibleBranches(employee)).containsExactly(
                new BranchAccessService.BranchRef(centro, "Sucursal Centro", "CEN"));
        assertThat(branchAccessService.accessibleBranches(cashier))
                .as("el cajero ve solo sus sucursales asignadas, igual que el empleado")
                .containsExactly(new BranchAccessService.BranchRef(centro, "Sucursal Centro", "CEN"));
    }

    @Test
    void cashierScopeIsLimitedToAssignedBranches() {
        as(cashier, "all");
        assertThat(branchAccessService.scopeBranchIds()).containsExactly(centro);
        assertThat(branchAccessService.requireSingleBranch(null)).isEqualTo(centro);

        as(cashier, String.valueOf(norte));
        assertApiError(() -> branchAccessService.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");
        assertApiError(() -> branchAccessService.assertAccess(norte), 403, "BRANCH_FORBIDDEN");

        assertThat(branchAccessService.canAccess(cashier.id(), centro)).isTrue();
        assertThat(branchAccessService.canAccess(cashier.id(), norte)).isFalse();
    }

    @Test
    void scopeAndSingleBranchRules() {
        as(employee, String.valueOf(norte));
        assertApiError(() -> branchAccessService.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");

        as(employee, "all");
        assertThat(branchAccessService.scopeBranchIds()).containsExactly(centro);
        assertThat(branchAccessService.requireSingleBranch(null)).isEqualTo(centro);

        as(admin, String.valueOf(ajena));
        assertApiError(() -> branchAccessService.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");
        assertApiError(() -> branchAccessService.assertAccess(ajena), 404, "NOT_FOUND");

        as(admin, null);
        assertThat(branchAccessService.scopeBranchIds()).containsExactly(centro, norte);
        assertApiError(() -> branchAccessService.requireSingleBranch(null), 400, "BRANCH_REQUIRED");
        assertThat(branchAccessService.requireSingleBranch(norte)).isEqualTo(norte);
        assertApiError(() -> branchAccessService.assertAccess(vieja), 403, "BRANCH_FORBIDDEN");

        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
        assertThat(holder.getEntityManager().getTransaction().getRollbackOnly())
                .as("los rechazos no invalidan la transacción del que llama").isFalse();
    }

    @Test
    void userIndependentQueries() {
        assertThat(branchAccessService.userIdsWithAccess(tenantA, centro))
                .as("el cajero asignado también recibe las notificaciones de su sucursal")
                .containsExactlyInAnyOrder(admin.id(), boss.id(), employee.id(), cashier.id());
        assertThat(branchAccessService.userIdsWithAccess(tenantA, norte)).containsExactlyInAnyOrder(admin.id(), boss.id());
        assertThat(branchAccessService.userIdsWithAccess(tenantA, ajena)).isEmpty();
        assertThat(branchAccessService.canAccess(employee.id(), centro)).isTrue();
        assertThat(branchAccessService.canAccess(employee.id(), norte)).isFalse();
        assertThat(branchAccessService.canAccess(admin.id(), ajena)).isFalse();
        assertThat(branchAccessService.branchNames(tenantA).keySet()).containsExactly(vieja, centro, norte);
    }

    @Test
    void meIncludesTenantBlockModulesAndAccessibleBranches() {
        MeDto me = authService.toMe(userRepository.findById(employee.id()).orElseThrow());

        assertThat(me.tenant().plan()).isEqualTo(TenantPlan.PROFESIONAL);
        assertThat(me.tenant().stockRotation()).isEqualTo(StockRotation.FEFO);
        assertThat(me.tenant().currency()).isEqualTo("ARS");
        assertThat(me.tenant().modules()).as("sin módulos habilitados").isEmpty();
        assertThat(me.tenant().maxBranches()).as("sin MULTI_BRANCH el máximo efectivo es 1").isEqualTo(1);
        assertThat(me.branches()).extracting(BranchAccessService.BranchRef::id).containsExactly(centro);

        enableModule(tenantA, TenantModule.POS_GONDOLIA);
        enableModule(tenantA, TenantModule.MULTI_BRANCH);
        MeDto withModules = authService.toMe(userRepository.findById(cashier.id()).orElseThrow());

        assertThat(withModules.role()).isEqualTo(Role.TENANT_CASHIER);
        assertThat(withModules.tenant().modules())
                .containsExactly(TenantModule.POS_GONDOLIA, TenantModule.MULTI_BRANCH);
        assertThat(withModules.tenant().maxBranches()).as("con MULTI_BRANCH manda el plan").isEqualTo(10);
        assertThat(withModules.branches()).extracting(BranchAccessService.BranchRef::id).containsExactly(centro);
    }

    @Test
    void posApiKeyResolvesToItsBranch() {
        GeneratedApiKey key = apiKeyService.generate();
        jdbc.update("update branches set pos_api_key_hash = ?, pos_api_key_prefix = ?, pos_api_key_created_at = now() "
                + "where id = ?", key.hash(), key.prefix(), norte);

        assertThat(apiKeyService.resolveBranch(key.rawKey())).contains(new PosBranch(tenantA, norte));
        assertThat(apiKeyService.authenticate(key.rawKey())).isEqualTo(new PosBranch(tenantA, norte));

        // SPEC §3.2: con el comercio bloqueado la key sigue siendo válida; el webhook responde el bloqueo.
        jdbc.update("update tenants set status = 'DISABLED' where id = ?", tenantA);
        assertThat(apiKeyService.resolveBranch(key.rawKey())).isEmpty();
        assertApiError(() -> apiKeyService.authenticate(key.rawKey()), 403, "TENANT_DISABLED");

        jdbc.update("update tenants set status = 'CANCELLED' where id = ?", tenantA);
        assertApiError(() -> apiKeyService.authenticate(key.rawKey()), 403, "TENANT_CANCELLED");

        jdbc.update("update tenants set status = 'ACTIVE' where id = ?", tenantA);
        GeneratedApiKey inactiveBranchKey = apiKeyService.generate();
        jdbc.update("update branches set pos_api_key_hash = ?, pos_api_key_prefix = ?, pos_api_key_created_at = now() "
                + "where id = ?", inactiveBranchKey.hash(), inactiveBranchKey.prefix(), vieja);
        assertApiError(() -> apiKeyService.authenticate(inactiveBranchKey.rawKey()), 401, "INVALID_API_KEY");
        assertApiError(() -> apiKeyService.authenticate(apiKeyService.generate().rawKey()), 401, "INVALID_API_KEY");
    }

    @Test
    void sellableLotsInRotationOrder() {
        LocalDate today = LocalDate.of(2026, 9, 17);
        long product = jdbc.queryForObject("insert into products (tenant_id, barcode, name) values (?, ?, ?) returning id",
                Long.class, tenantA, "779" + suffix.hashCode(), "Yogur " + suffix);
        Instant base = Instant.parse("2026-09-01T12:00:00Z");
        long oldLate = insertLot(tenantA, centro, product, base, today.plusDays(30), 5, "ACTIVE");
        long newEarly = insertLot(tenantA, centro, product, base.plusSeconds(86_400), today.plusDays(3), 7, "ACTIVE");
        long noExpiry = insertLot(tenantA, centro, product, base.plusSeconds(2 * 86_400), null, 2, "ACTIVE");
        insertLot(tenantA, centro, product, base.minusSeconds(86_400), today.minusDays(1), 4, "ACTIVE");
        insertLot(tenantA, centro, product, base, today.plusDays(10), 6, "RECALLED");
        insertLot(tenantA, norte, product, base, today.plusDays(10), 9, "ACTIVE");

        assertThat(lotRepository.findSellableFifo(tenantA, centro, product, today)).extracting(Lot::getId)
                .containsExactly(oldLate, newEarly, noExpiry);
        assertThat(lotRepository.findSellableFefo(tenantA, centro, product, today)).extracting(Lot::getId)
                .containsExactly(newEarly, oldLate, noExpiry);
        assertThat(lotRepository.findSellableForUpdate(tenantA, centro, product, today)).extracting(Lot::getId)
                .as("se bloquean en orden de id").containsExactly(oldLate, newEarly, noExpiry);
        assertThat(lotRepository.sumSellableQuantity(tenantA, centro, product, today)).isEqualTo(14);
        assertThat(lotRepository.sumSellableQuantityByBranchAndProduct(tenantA, List.of(centro, norte), today))
                .extracting(LotRepository.BranchProductQuantity::getBranchId,
                        LotRepository.BranchProductQuantity::getQuantity)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(centro, 14L),
                        org.assertj.core.groups.Tuple.tuple(norte, 9L));
        assertThat(lotRepository.hasPhysicalStock(centro)).isTrue();
        assertThat(lotRepository.hasPhysicalStock(vieja)).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private long insertTenant(String name, String plan) {
        return jdbc.queryForObject(
                "insert into tenants (name, business_type, plan) values (?, 'ALMACEN', ?) returning id",
                Long.class, name, plan);
    }

    private void enableModule(long tenantId, TenantModule module) {
        jdbc.update("insert into tenant_modules (tenant_id, module, enabled) values (?, ?, true)", tenantId,
                module.name());
    }

    private long insertBranch(long tenantId, String name, String code, boolean active) {
        return jdbc.queryForObject(
                "insert into branches (tenant_id, name, code, active) values (?, ?, ?, ?) returning id",
                Long.class, tenantId, name, code, active);
    }

    private AuthUser insertUser(long tenantId, String prefix, Role role) {
        String email = prefix + "." + suffix + "." + tenantId + "@test.gondolia";
        long id = jdbc.queryForObject(
                "insert into users (tenant_id, email, password_hash, full_name, role) values (?, ?, 'x', ?, ?) "
                        + "returning id",
                Long.class, tenantId, email, prefix, role.name());
        return new AuthUser(id, email, prefix, role, tenantId);
    }

    private long insertLot(long tenantId, long branchId, long productId, Instant receivedAt, LocalDate expiry,
                           int quantity, String status) {
        return jdbc.queryForObject("""
                        insert into lots (tenant_id, branch_id, product_id, expiry_date, initial_quantity, quantity,
                                          received_at, status)
                        values (?, ?, ?, ?, ?, ?, ?, ?) returning id
                        """, Long.class, tenantId, branchId, productId, expiry == null ? null : Date.valueOf(expiry),
                quantity, quantity, Timestamp.from(receivedAt), status);
    }

    private static void as(AuthUser user, String branchHeader) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/test");
        if (branchHeader != null) {
            request.addHeader(BranchAccessService.HEADER, branchHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status,
                                       String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }
}
