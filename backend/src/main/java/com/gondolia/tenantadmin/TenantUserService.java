package com.gondolia.tenantadmin;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.util.Emails;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserBranch;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.realtime.SessionTerminationService;
import com.gondolia.security.CurrentUser;
import com.gondolia.tenantadmin.dto.CreateUserRequest;
import com.gondolia.tenantadmin.dto.ResetPasswordRequest;
import com.gondolia.tenantadmin.dto.ResetPasswordResponse;
import com.gondolia.tenantadmin.dto.TenantUserDto;
import com.gondolia.tenantadmin.dto.UpdateUserRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Usuarios del comercio (SPEC §6.9). Reglas que garantiza el servicio:
 * <ul>
 *   <li>El {@code tenantId} sale siempre del usuario autenticado; un id de otro comercio devuelve 404.</li>
 *   <li>{@code TENANT_EMPLOYEE} y {@code TENANT_CASHIER} necesitan al menos una sucursal activa asignada
 *       ({@code Role.worksInAssignedBranches()}); jefe y administrador acceden a todas y no llevan asignaciones.</li>
 *   <li>Un administrador no puede cambiarse el rol, desactivarse ni resetear su propia contraseña.</li>
 *   <li>Siempre queda al menos un administrador activo.</li>
 *   <li>Desactivar, cambiar de rol o resetear la contraseña incrementa {@code token_version} y cierra las sesiones
 *       abiertas de ese usuario.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class TenantUserService {

    static final String MSG_NOT_FOUND = "El usuario no existe";

    private final UserRepository userRepository;
    private final UserBranchRepository userBranchRepository;
    private final BranchRepository branchRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionTerminationService sessionTerminationService;

    /** Todos los usuarios del comercio (activos e inactivos), por nombre. */
    public List<TenantUserDto> list() {
        Long tenantId = CurrentUser.tenantId();
        List<User> users = userRepository.findByTenantIdOrderByFullNameAsc(tenantId);
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, Branch> branches = branchRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(Branch::getId, Function.identity()));
        Map<Long, List<Long>> assignments = userBranchRepository
                .findByUserIdIn(users.stream().map(User::getId).toList()).stream()
                .collect(Collectors.groupingBy(UserBranch::getUserId,
                        Collectors.mapping(UserBranch::getBranchId, Collectors.toList())));
        return users.stream().map(user -> toDto(user, assignments.getOrDefault(user.getId(), List.of()), branches))
                .toList();
    }

    @Transactional
    public TenantUserDto create(CreateUserRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Role role = requireTenantRole(request.role());
        String email = Emails.normalize(request.email());
        if (email == null) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "Escribí un email válido");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(TenantAdminErrors.DUPLICATE_EMAIL, "Ya hay una cuenta con el email " + email);
        }
        List<Branch> assigned = resolveBranches(tenantId, role, request.branchIds());

        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(email);
        user.setFullName(request.fullName().strip());
        user.setRole(role);
        user.setActive(true);
        user.setMustChangePassword(true);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);
        saveAssignments(user.getId(), assigned);

        log.info("Comercio {}: alta del usuario {} con rol {}", tenantId, user.getId(), role);
        return toDto(user, assigned.stream().map(Branch::getId).toList(), byId(assigned));
    }

    @Transactional
    public TenantUserDto update(Long userId, UpdateUserRequest request) {
        Long tenantId = CurrentUser.tenantId();
        User user = load(userId, tenantId);
        Role newRole = requireTenantRole(request.role());
        boolean newActive = Boolean.TRUE.equals(request.active());
        boolean roleChanged = user.getRole() != newRole;
        boolean deactivating = user.isActive() && !newActive;

        if (Objects.equals(userId, CurrentUser.id()) && (roleChanged || !newActive)) {
            throw new ConflictException(TenantAdminErrors.SELF_UPDATE_FORBIDDEN,
                    "No podés cambiar tu propio rol ni desactivar tu usuario. Pedíselo a otro administrador.");
        }
        if (wouldLeaveTenantWithoutAdmin(user, newRole, newActive, tenantId)) {
            throw new ConflictException(TenantAdminErrors.LAST_ACTIVE_ADMIN,
                    "Tiene que quedar al menos un administrador activo en el comercio.");
        }

        List<Branch> assigned = resolveBranches(tenantId, newRole, request.branchIds());
        user.setFullName(request.fullName().strip());
        user.setRole(newRole);
        user.setActive(newActive);
        userBranchRepository.deleteByUserId(userId);
        saveAssignments(userId, assigned);

        if (roleChanged || deactivating) {
            user.incrementTokenVersion();
            userRepository.saveAndFlush(user);
            String code = deactivating ? ErrorCodes.USER_DISABLED : "ROLE_CHANGED";
            String message = deactivating
                    ? "Un administrador desactivó tu usuario."
                    : "Un administrador cambió tu rol. Volvé a iniciar sesión.";
            sessionTerminationService.forceLogoutUser(userId, code, message);
            log.info("Comercio {}: usuario {} actualizado (rol {}, activo {}); sesiones cerradas",
                    tenantId, userId, newRole, newActive);
        }
        return toDto(user, assigned.stream().map(Branch::getId).toList(), byId(assigned));
    }

    /**
     * Resetea la contraseña: si el pedido no trae una, genera una temporal. Marca {@code must_change_password},
     * incrementa {@code token_version} y cierra las sesiones abiertas del usuario.
     */
    @Transactional
    public ResetPasswordResponse resetPassword(Long userId, ResetPasswordRequest request) {
        Long tenantId = CurrentUser.tenantId();
        User user = load(userId, tenantId);
        if (Objects.equals(userId, CurrentUser.id())) {
            throw new ConflictException(TenantAdminErrors.SELF_UPDATE_FORBIDDEN,
                    "Para cambiar tu propia contraseña entrá a tu perfil.");
        }
        boolean generated = request == null || request.newPassword() == null || request.newPassword().isBlank();
        String password = generated ? TemporaryPasswords.generate() : request.newPassword();

        user.setPasswordHash(passwordEncoder.encode(password));
        user.setMustChangePassword(true);
        user.incrementTokenVersion();
        userRepository.saveAndFlush(user);
        sessionTerminationService.forceLogoutUser(userId, "PASSWORD_RESET",
                "Un administrador cambió tu contraseña. Volvé a iniciar sesión.");

        log.info("Comercio {}: reseteo de contraseña del usuario {}", tenantId, userId);
        return new ResetPasswordResponse(user.getId(), user.getEmail(), user.getFullName(), password, generated);
    }

    // ------------------------------------------------------------------ interno

    private User load(Long userId, Long tenantId) {
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        if (!user.getRole().isTenantRole()) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return user;
    }

    private static Role requireTenantRole(Role role) {
        if (role == null || !role.isTenantRole()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Elegí un rol del comercio: jefe, administrador, empleado o cajero.");
        }
        return role;
    }

    /**
     * Sucursales a asignar: vacío para jefe y administrador (acceden a todas); para empleado y cajero, al menos una
     * sucursal activa del comercio.
     */
    private List<Branch> resolveBranches(Long tenantId, Role role, List<Long> requestedIds) {
        if (!role.worksInAssignedBranches()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>(requestedIds == null ? List.of() : requestedIds);
        ids.remove(null);
        if (ids.isEmpty()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Elegí al menos una sucursal para el empleado o cajero.");
        }
        List<Branch> branches = branchRepository.findByTenantIdAndIdIn(tenantId, ids);
        if (branches.size() != ids.size()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Alguna de las sucursales elegidas no existe en tu comercio.");
        }
        List<String> inactive = branches.stream().filter(branch -> !branch.isActive()).map(Branch::getName).toList();
        if (!inactive.isEmpty()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "No podés asignar sucursales desactivadas: " + String.join(", ", inactive) + ".");
        }
        return branches.stream().sorted(Comparator.comparing(Branch::getName)).toList();
    }

    private void saveAssignments(Long userId, List<Branch> branches) {
        if (branches.isEmpty()) {
            return;
        }
        List<UserBranch> rows = new ArrayList<>(branches.size());
        for (Branch branch : branches) {
            rows.add(new UserBranch(userId, branch.getId()));
        }
        userBranchRepository.saveAll(rows);
    }

    /** {@code true} si el usuario es el único administrador activo y el cambio lo dejaría de serlo. */
    private boolean wouldLeaveTenantWithoutAdmin(User user, Role newRole, boolean newActive, Long tenantId) {
        boolean wasActiveAdmin = user.getRole() == Role.TENANT_ADMIN && user.isActive();
        boolean staysActiveAdmin = newRole == Role.TENANT_ADMIN && newActive;
        if (!wasActiveAdmin || staysActiveAdmin) {
            return false;
        }
        return userRepository.countByTenantIdAndRoleAndActiveTrue(tenantId, Role.TENANT_ADMIN) <= 1;
    }

    private static Map<Long, Branch> byId(List<Branch> branches) {
        return branches.stream().collect(Collectors.toMap(Branch::getId, Function.identity()));
    }

    private static TenantUserDto toDto(User user, List<Long> branchIds, Map<Long, Branch> branches) {
        List<TenantUserDto.AssignedBranchDto> assigned = branchIds.stream()
                .map(branches::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Branch::getName))
                .map(branch -> new TenantUserDto.AssignedBranchDto(branch.getId(), branch.getName(), branch.getCode(),
                        branch.isActive()))
                .toList();
        return new TenantUserDto(user.getId(), user.getFullName(), user.getEmail(), user.getRole(), user.isActive(),
                user.isMustChangePassword(), user.getLastLoginAt(), user.getCreatedAt(), assigned);
    }
}
