package com.gondolia.tenantadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.tenantadmin.dto.CreateUserRequest;
import com.gondolia.tenantadmin.dto.ResetPasswordRequest;
import com.gondolia.tenantadmin.dto.ResetPasswordResponse;
import com.gondolia.tenantadmin.dto.TenantUserDto;
import com.gondolia.tenantadmin.dto.UpdateUserRequest;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantUserService} contra PostgreSQL: sucursales obligatorias para empleados y cajeros, aislamiento entre
 * comercios, protecciones del propio administrador, último administrador activo y reseteo de contraseña. Cada prueba
 * se revierte.
 */
@Transactional
class TenantUserServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TenantUserService userService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserBranchRepository userBranchRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long norte;
    private AuthUser admin;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Comercio F");
        otherTenant = data.tenant("Comercio ajeno");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Norte", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        authenticate(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------ alta

    @Test
    void createsCashierWithAssignedBranches() {
        TenantUserDto created = userService.create(new CreateUserRequest("Carla Cajera",
                "Carla." + data.suffix() + "@Prueba.com", "Demo2026!", Role.TENANT_CASHIER, List.of(centro)));

        assertThat(created.email()).isEqualTo("carla." + data.suffix() + "@prueba.com");
        assertThat(created.role()).isEqualTo(Role.TENANT_CASHIER);
        assertThat(created.active()).isTrue();
        assertThat(created.mustChangePassword()).isTrue();
        assertThat(created.branches()).extracting(TenantUserDto.AssignedBranchDto::id).containsExactly(centro);

        User stored = userRepository.findById(created.id()).orElseThrow();
        assertThat(stored.getTenantId()).isEqualTo(tenant);
        assertThat(passwordEncoder.matches("Demo2026!", stored.getPasswordHash())).isTrue();
    }

    @Test
    void employeeAndCashierNeedAtLeastOneBranch() {
        assertApiError(() -> userService.create(create("Sin sucursal", Role.TENANT_EMPLOYEE, List.of())),
                400, "VALIDATION_ERROR");
        assertApiError(() -> userService.create(create("Sin sucursal", Role.TENANT_CASHIER, null)),
                400, "VALIDATION_ERROR");
    }

    @Test
    void bossAndAdminNeverGetBranchAssignments() {
        TenantUserDto boss = userService.create(create("Jefa", Role.TENANT_BOSS, List.of(centro, norte)));
        assertThat(boss.branches()).isEmpty();
        assertThat(userBranchRepository.findByUserId(boss.id())).isEmpty();
    }

    @Test
    void rejectsBranchesOfAnotherTenantAndInactiveBranches() {
        long otherBranch = data.branch(otherTenant, "Ajena", true);
        long closed = data.branch(tenant, "Cerrada", false);

        assertApiError(() -> userService.create(create("Empleado", Role.TENANT_EMPLOYEE, List.of(otherBranch))),
                400, "VALIDATION_ERROR");
        assertApiError(() -> userService.create(create("Empleado", Role.TENANT_EMPLOYEE, List.of(closed))),
                400, "VALIDATION_ERROR");
    }

    @Test
    void rejectsDuplicateEmailAndPlatformRoles() {
        userService.create(create("Primero", Role.TENANT_BOSS, List.of()));
        String email = userRepository.findByTenantIdOrderByFullNameAsc(tenant).stream()
                .filter(user -> "Primero".equals(user.getFullName())).findFirst().orElseThrow().getEmail();

        assertApiError(() -> userService.create(new CreateUserRequest("Repetido", email, "Demo2026!",
                Role.TENANT_BOSS, List.of())), 409, TenantAdminErrors.DUPLICATE_EMAIL);
        assertApiError(() -> userService.create(create("Dueño", Role.PLATFORM_OWNER, List.of())),
                400, "VALIDATION_ERROR");
    }

    // ------------------------------------------------------------------ edición

    @Test
    void adminCannotDemoteOrDeactivateItself() {
        assertApiError(() -> userService.update(admin.id(),
                        new UpdateUserRequest("Admin", Role.TENANT_EMPLOYEE, true, List.of(centro))),
                409, TenantAdminErrors.SELF_UPDATE_FORBIDDEN);
        assertApiError(() -> userService.update(admin.id(),
                        new UpdateUserRequest("Admin", Role.TENANT_ADMIN, false, List.of())),
                409, TenantAdminErrors.SELF_UPDATE_FORBIDDEN);
        assertApiError(() -> userService.resetPassword(admin.id(), new ResetPasswordRequest(null)),
                409, TenantAdminErrors.SELF_UPDATE_FORBIDDEN);
    }

    /**
     * El comercio nunca queda sin administrador activo. En la API el caso habitual lo corta antes la regla de
     * "no podés desactivarte a vos mismo"; este chequeo es la última línea de defensa del servicio.
     */
    @Test
    void keepsAtLeastOneActiveAdmin() {
        authenticate(data.user(tenant, Role.TENANT_ADMIN, false));

        assertApiError(() -> userService.update(admin.id(),
                        new UpdateUserRequest("Admin", Role.TENANT_ADMIN, false, List.of())),
                409, TenantAdminErrors.LAST_ACTIVE_ADMIN);
        assertApiError(() -> userService.update(admin.id(),
                        new UpdateUserRequest("Admin", Role.TENANT_BOSS, true, List.of())),
                409, TenantAdminErrors.LAST_ACTIVE_ADMIN);

        // Con dos administradores activos sí se puede degradar a uno.
        data.user(tenant, Role.TENANT_ADMIN, true);
        assertThat(userService.update(admin.id(), new UpdateUserRequest("Admin", Role.TENANT_BOSS, true, List.of()))
                .role()).isEqualTo(Role.TENANT_BOSS);
    }

    @Test
    void roleChangeAndDeactivationCloseOpenSessions() {
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);
        int before = userRepository.findById(employee.id()).orElseThrow().getTokenVersion();

        userService.update(employee.id(), new UpdateUserRequest("Empleado", Role.TENANT_CASHIER, true,
                List.of(centro, norte)));

        User stored = userRepository.findById(employee.id()).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(Role.TENANT_CASHIER);
        assertThat(stored.getTokenVersion()).isEqualTo(before + 1);
        assertThat(userBranchRepository.findBranchIdsByUserId(employee.id()))
                .containsExactlyInAnyOrder(centro, norte);
        verify(realtimePublisher).afterCommit(any());
    }

    @Test
    void deactivatingAUserKeepsTheBranchAssignmentsAndClosesSessions() {
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);

        TenantUserDto updated = userService.update(employee.id(),
                new UpdateUserRequest("Empleado", Role.TENANT_EMPLOYEE, false, List.of(centro)));

        assertThat(updated.active()).isFalse();
        assertThat(updated.branches()).hasSize(1);
        verify(realtimePublisher).afterCommit(any());
    }

    // ------------------------------------------------------------------ contraseñas

    @Test
    void resetPasswordGeneratesATemporaryOneAndForcesLogout() {
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);
        int before = userRepository.findById(employee.id()).orElseThrow().getTokenVersion();

        ResetPasswordResponse response = userService.resetPassword(employee.id(), new ResetPasswordRequest(null));

        assertThat(response.generated()).isTrue();
        assertThat(response.temporaryPassword()).hasSizeGreaterThanOrEqualTo(8);
        User stored = userRepository.findById(employee.id()).orElseThrow();
        assertThat(passwordEncoder.matches(response.temporaryPassword(), stored.getPasswordHash())).isTrue();
        assertThat(stored.isMustChangePassword()).isTrue();
        assertThat(stored.getTokenVersion()).isEqualTo(before + 1);
        verify(realtimePublisher).afterCommit(any());
    }

    @Test
    void resetPasswordAcceptsAnExplicitPassword() {
        AuthUser employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);

        ResetPasswordResponse response = userService.resetPassword(employee.id(),
                new ResetPasswordRequest("NuevaClave2026!"));

        assertThat(response.generated()).isFalse();
        assertThat(response.temporaryPassword()).isEqualTo("NuevaClave2026!");
        assertThat(passwordEncoder.matches("NuevaClave2026!",
                userRepository.findById(employee.id()).orElseThrow().getPasswordHash())).isTrue();
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void neverTouchesUsersOfAnotherTenant() {
        long otherBranch = data.branch(otherTenant, "Ajena", true);
        AuthUser foreign = data.user(otherTenant, Role.TENANT_EMPLOYEE, true, otherBranch);

        assertApiError(() -> userService.update(foreign.id(),
                        new UpdateUserRequest("Ajeno", Role.TENANT_EMPLOYEE, false, List.of(centro))),
                404, "NOT_FOUND");
        assertApiError(() -> userService.resetPassword(foreign.id(), new ResetPasswordRequest(null)),
                404, "NOT_FOUND");
        assertThat(userService.list()).extracting(TenantUserDto::id).doesNotContain(foreign.id());
        assertThat(userRepository.findById(foreign.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void listOnlyReturnsUsersOfTheCurrentTenant() {
        data.user(otherTenant, Role.TENANT_ADMIN, true);
        AuthUser mine = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);

        List<TenantUserDto> users = userService.list();

        assertThat(users).extracting(TenantUserDto::id).contains(admin.id(), mine.id());
        assertThat(users).allSatisfy(user ->
                assertThat(userRepository.findById(user.id()).orElseThrow().getTenantId()).isEqualTo(tenant));
    }

    // ------------------------------------------------------------------ helpers

    private CreateUserRequest create(String fullName, Role role, List<Long> branchIds) {
        return new CreateUserRequest(fullName, fullName.toLowerCase().replace(' ', '.') + "." + data.suffix()
                + "@prueba.com", "Demo2026!", role, branchIds);
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
