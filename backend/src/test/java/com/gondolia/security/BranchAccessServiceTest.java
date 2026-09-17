package com.gondolia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.domain.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BranchAccessServiceTest {

    private static final long TENANT = 1L;
    private static final long OTHER_TENANT = 2L;

    private static final AuthUser ADMIN = new AuthUser(10L, "admin@prueba.com", "Admin", Role.TENANT_ADMIN, TENANT);
    private static final AuthUser BOSS = new AuthUser(12L, "jefe@prueba.com", "Jefe", Role.TENANT_BOSS, TENANT);
    private static final AuthUser EMPLOYEE =
            new AuthUser(11L, "empleado@prueba.com", "Empleado", Role.TENANT_EMPLOYEE, TENANT);
    private static final AuthUser LONELY_EMPLOYEE =
            new AuthUser(13L, "sin@prueba.com", "Sin sucursal", Role.TENANT_EMPLOYEE, TENANT);
    private static final AuthUser OWNER = new AuthUser(1L, "dueno@gondolia.app", "Dueño", Role.PLATFORM_OWNER, null);

    @Mock
    private BranchRepository branchRepository;
    @Mock
    private UserBranchRepository userBranchRepository;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private BranchAccessService service;

    private final Branch centro = branch(1L, TENANT, "Sucursal Centro", "CEN", true);
    private final Branch norte = branch(2L, TENANT, "Sucursal Norte", "NOR", true);
    private final Branch cerrada = branch(3L, TENANT, "Sucursal Cerrada", "CER", false);
    private final Branch ajena = branch(9L, OTHER_TENANT, "Sucursal de Otro", "OTR", true);

    @BeforeEach
    void setUp() {
        for (Branch branch : List.of(centro, norte, cerrada, ajena)) {
            when(branchRepository.findById(branch.getId())).thenReturn(Optional.of(branch));
            when(branchRepository.findByIdAndTenantId(branch.getId(), branch.getTenantId()))
                    .thenReturn(Optional.of(branch));
        }
        when(branchRepository.findByIdAndTenantId(9L, TENANT)).thenReturn(Optional.empty());
        when(branchRepository.findByTenantIdAndActiveTrueOrderByNameAsc(TENANT)).thenReturn(List.of(centro, norte));
        when(branchRepository.findByTenantIdOrderByNameAsc(TENANT)).thenReturn(List.of(cerrada, centro, norte));
        when(branchRepository.findActiveAssignedToUser(TENANT, EMPLOYEE.id())).thenReturn(List.of(centro));
        when(branchRepository.findActiveAssignedToUser(TENANT, LONELY_EMPLOYEE.id())).thenReturn(List.of());
        when(userBranchRepository.existsByUserIdAndBranchId(EMPLOYEE.id(), 1L)).thenReturn(true);
        when(userBranchRepository.existsByUserIdAndBranchId(EMPLOYEE.id(), 3L)).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ accessibleBranches

    @Test
    void adminAndBossAccessAllActiveBranchesOrderedByName() {
        authenticate(ADMIN);
        assertThat(service.accessibleBranches()).containsExactly(
                new BranchAccessService.BranchRef(1L, "Sucursal Centro", "CEN"),
                new BranchAccessService.BranchRef(2L, "Sucursal Norte", "NOR"));

        authenticate(BOSS);
        assertThat(service.accessibleBranches()).extracting(BranchAccessService.BranchRef::id).containsExactly(1L, 2L);
    }

    @Test
    void employeeAccessesOnlyAssignedBranches() {
        authenticate(EMPLOYEE);

        assertThat(service.accessibleBranches()).extracting(BranchAccessService.BranchRef::id).containsExactly(1L);
    }

    @Test
    void platformUsersHaveNoBranches() {
        authenticate(OWNER);

        assertThat(service.accessibleBranches()).isEmpty();
        assertThat(service.scopeBranchIds()).isEmpty();
    }

    // ------------------------------------------------------------------ scopeBranchIds

    @Test
    void scopeWithoutHeaderIsAllAccessibleBranches() {
        authenticate(ADMIN);
        request(null);

        assertThat(service.scopeBranchIds()).containsExactly(1L, 2L);
    }

    @Test
    void scopeWithAllHeaderIsAllAccessibleBranches() {
        authenticate(ADMIN);
        request("all");
        assertThat(service.scopeBranchIds()).containsExactly(1L, 2L);

        request(" ALL ");
        assertThat(service.scopeBranchIds()).containsExactly(1L, 2L);

        authenticate(EMPLOYEE);
        request("all");
        assertThat(service.scopeBranchIds()).containsExactly(1L);
    }

    @Test
    void scopeWithAccessibleBranchHeaderIsThatBranch() {
        authenticate(ADMIN);
        request("2");
        assertThat(service.scopeBranchIds()).containsExactly(2L);

        authenticate(EMPLOYEE);
        request("1");
        assertThat(service.scopeBranchIds()).containsExactly(1L);
    }

    @Test
    void employeeRequestingUnassignedBranchIsForbidden() {
        authenticate(EMPLOYEE);
        request("2");

        assertApiError(() -> service.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void branchOfAnotherTenantInTheHeaderIsForbidden() {
        authenticate(ADMIN);
        request("9");

        assertApiError(() -> service.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void inactiveBranchIsForbidden() {
        authenticate(ADMIN);
        request("3");
        assertApiError(() -> service.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");

        authenticate(EMPLOYEE);
        assertApiError(() -> service.scopeBranchIds(), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void nonNumericHeaderIsValidationError() {
        authenticate(ADMIN);
        request("centro");

        assertApiError(() -> service.scopeBranchIds(), 400, "VALIDATION_ERROR");
    }

    @Test
    void outsideRequestsTheScopeIsEmptyAndNothingFails() {
        assertThat(service.accessibleBranches()).isEmpty();
        assertThat(service.scopeBranchIds()).isEmpty();
        assertThat(service.requestedBranchId()).isEmpty();

        authenticate(ADMIN);
        assertThat(service.scopeBranchIds()).containsExactly(1L, 2L);
    }

    // ------------------------------------------------------------------ requireSingleBranch

    @Test
    void requireSingleBranchWithSeveralBranchesAndNoHeaderIsBranchRequired() {
        authenticate(ADMIN);
        request(null);
        assertApiError(() -> service.requireSingleBranch(null), 400, "BRANCH_REQUIRED");

        request("all");
        assertApiError(() -> service.requireSingleBranch(null), 400, "BRANCH_REQUIRED");
    }

    @Test
    void requireSingleBranchUsesTheOnlyAccessibleBranch() {
        authenticate(EMPLOYEE);
        request("all");

        assertThat(service.requireSingleBranch(null)).isEqualTo(1L);
    }

    @Test
    void requireSingleBranchPrefersExplicitOverHeader() {
        authenticate(ADMIN);
        request("2");

        assertThat(service.requireSingleBranch(1L)).isEqualTo(1L);
        assertThat(service.requireSingleBranch(null)).isEqualTo(2L);
    }

    @Test
    void requireSingleBranchValidatesAccess() {
        authenticate(EMPLOYEE);
        request(null);
        assertApiError(() -> service.requireSingleBranch(2L), 403, "BRANCH_FORBIDDEN");

        authenticate(ADMIN);
        assertApiError(() -> service.requireSingleBranch(9L), 404, "NOT_FOUND");

        request("9");
        assertApiError(() -> service.requireSingleBranch(null), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void requireSingleBranchWithoutAssignedBranchesIsForbidden() {
        authenticate(LONELY_EMPLOYEE);
        request(null);

        assertApiError(() -> service.requireSingleBranch(null), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void requireSingleBranchNeedsTenantUser() {
        assertApiError(() -> service.requireSingleBranch(1L), 401, "UNAUTHORIZED");

        authenticate(OWNER);
        assertApiError(() -> service.requireSingleBranch(1L), 403, "NO_TENANT");
    }

    // ------------------------------------------------------------------ assertAccess

    @Test
    void assertAccessRules() {
        authenticate(ADMIN);
        service.assertAccess(2L);
        assertApiError(() -> service.assertAccess(9L), 404, "NOT_FOUND");
        assertApiError(() -> service.assertAccess(null), 400, "BRANCH_REQUIRED");

        authenticate(EMPLOYEE);
        service.assertAccess(1L);
        assertApiError(() -> service.assertAccess(2L), 403, "BRANCH_FORBIDDEN");
        assertApiError(() -> service.assertAccess(9L), 404, "NOT_FOUND");
    }

    // ------------------------------------------------------------------ user-independent methods

    @Test
    void canAccessChecksRoleAssignmentTenantAndActiveFlags() {
        stubUser(ADMIN, true);
        stubUser(EMPLOYEE, true);
        stubUser(OWNER, true);
        User inactiveAdmin = stubUser(new AuthUser(20L, "x@prueba.com", "X", Role.TENANT_ADMIN, TENANT), false);

        assertThat(service.canAccess(ADMIN.id(), 2L)).isTrue();
        assertThat(service.canAccess(ADMIN.id(), 9L)).isFalse();
        assertThat(service.canAccess(ADMIN.id(), 3L)).isFalse();
        assertThat(service.canAccess(EMPLOYEE.id(), 1L)).isTrue();
        assertThat(service.canAccess(EMPLOYEE.id(), 2L)).isFalse();
        assertThat(service.canAccess(OWNER.id(), 1L)).isFalse();
        assertThat(service.canAccess(inactiveAdmin.getId(), 1L)).isFalse();
        assertThat(service.canAccess(999L, 1L)).isFalse();
        assertThat(service.canAccess(null, 1L)).isFalse();
    }

    @Test
    void userIdsWithAccessRequiresActiveBranchOfTheTenant() {
        when(userRepository.findActiveUserIdsWithBranchAccess(TENANT, 1L)).thenReturn(List.of(10L, 11L, 12L));

        assertThat(service.userIdsWithAccess(TENANT, 1L)).containsExactly(10L, 11L, 12L);
        assertThat(service.userIdsWithAccess(TENANT, 9L)).isEmpty();
        assertThat(service.userIdsWithAccess(TENANT, 3L)).isEmpty();
        assertThat(service.userIdsWithAccess(null, 1L)).isEmpty();
        verify(userRepository, never()).findActiveUserIdsWithBranchAccess(TENANT, 9L);
    }

    @Test
    void branchNamesIncludeInactiveBranchesInNameOrder() {
        assertThat(service.branchNames(TENANT)).containsExactly(
                entry(3L, "Sucursal Cerrada"),
                entry(1L, "Sucursal Centro"),
                entry(2L, "Sucursal Norte"));
        assertThat(service.branchNames(null)).isEmpty();
        verify(branchRepository, never()).findByTenantIdOrderByNameAsc(null);
    }

    // ------------------------------------------------------------------ helpers

    private static Branch branch(Long id, Long tenantId, String name, String code, boolean active) {
        Branch branch = new Branch();
        branch.setId(id);
        branch.setTenantId(tenantId);
        branch.setName(name);
        branch.setCode(code);
        branch.setActive(active);
        return branch;
    }

    private User stubUser(AuthUser authUser, boolean active) {
        User user = new User();
        user.setId(authUser.id());
        user.setEmail(authUser.email());
        user.setFullName(authUser.fullName());
        user.setRole(authUser.role());
        user.setTenantId(authUser.tenantId());
        user.setActive(active);
        when(userRepository.findById(authUser.id())).thenReturn(Optional.of(user));
        return user;
    }

    private static void authenticate(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
    }

    private static void request(String branchHeader) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/ping");
        if (branchHeader != null) {
            request.addHeader(BranchAccessService.HEADER, branchHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void assertApiError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(status);
                    assertThat(ex.getCode()).isEqualTo(code);
                    assertThat(ex.getMessage()).isNotBlank();
                });
    }
}
