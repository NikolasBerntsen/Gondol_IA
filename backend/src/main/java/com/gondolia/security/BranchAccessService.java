package com.gondolia.security;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.domain.user.UserRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Alcance por sucursal (SPEC §3.5). Jefe y administrador acceden a todas las sucursales activas del tenant; el
 * empleado solo a las asignadas en {@code user_branches}. El frontend elige la sucursal con el encabezado
 * {@code X-Branch-Id} (id numérico, o {@code all}/ausente para todas las accesibles).
 * <p>
 * Fuera de un request HTTP autenticado (tareas programadas, listeners asincrónicos, STOMP) no hay encabezado ni
 * usuario: {@link #accessibleBranches()} y {@link #scopeBranchIds()} devuelven listas vacías, y
 * {@link #canAccess}, {@link #userIdsWithAccess} y {@link #branchNames} funcionan normalmente porque no dependen del
 * usuario actual.
 * <p>
 * Solo lee: sus rechazos ({@link ApiException}) no marcan como rollback-only la transacción del que llama, que puede
 * capturarlos y seguir.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class BranchAccessService {

    public static final String HEADER = "X-Branch-Id";
    public static final String ALL = "all";

    static final String MSG_BRANCH_REQUIRED = "Elegí una sucursal para esta operación";
    static final String MSG_BRANCH_FORBIDDEN = "No tenés acceso a esta sucursal";
    static final String MSG_BRANCH_INACTIVE = "La sucursal está desactivada";
    static final String MSG_BRANCH_NOT_FOUND = "La sucursal no existe";
    static final String MSG_NO_BRANCHES =
            "No tenés sucursales asignadas. Pedile a un administrador del comercio que te asigne una";
    static final String MSG_INVALID_HEADER =
            "El encabezado X-Branch-Id tiene un valor inválido: usá el id de la sucursal o \"all\"";
    static final String MSG_NO_TENANT = "Esta operación solo está disponible para usuarios de un comercio";

    /** Sucursal resumida para respuestas ({@code {id,name,code}}). */
    public record BranchRef(Long id, String name, String code) {

        public static BranchRef of(Branch branch) {
            return new BranchRef(branch.getId(), branch.getName(), branch.getCode());
        }
    }

    private final BranchRepository branchRepository;
    private final UserBranchRepository userBranchRepository;
    private final UserRepository userRepository;

    /** Sucursales activas accesibles por el usuario actual, por nombre. Vacía sin usuario de tenant. */
    public List<BranchRef> accessibleBranches() {
        return CurrentUser.optional().map(this::accessibleBranches).orElse(List.of());
    }

    /** Sucursales activas accesibles por un usuario (p. ej. al armar la respuesta del login), por nombre. */
    public List<BranchRef> accessibleBranches(AuthUser user) {
        if (user == null || !user.isTenantUser() || user.tenantId() == null) {
            return List.of();
        }
        List<Branch> branches = user.role().accessesAllBranches()
                ? branchRepository.findByTenantIdAndActiveTrueOrderByNameAsc(user.tenantId())
                : branchRepository.findActiveAssignedToUser(user.tenantId(), user.id());
        return branches.stream().map(BranchRef::of).toList();
    }

    /**
     * Sucursales sobre las que opera una lectura: {@code [id]} si el encabezado trae un id accesible (403
     * {@code BRANCH_FORBIDDEN} si no lo es: de otro comercio, inexistente, desactivada o no asignada), o todas las
     * accesibles si falta o es {@code all}. Vacía sin usuario de tenant.
     */
    public List<Long> scopeBranchIds() {
        Optional<AuthUser> user = CurrentUser.optional().filter(AuthUser::isTenantUser);
        if (user.isEmpty()) {
            return List.of();
        }
        Optional<Long> requested = requestedBranchId();
        if (requested.isPresent()) {
            checkAccess(user.get(), requested.get(), false);
            return List.of(requested.get());
        }
        return accessibleBranches(user.get()).stream().map(BranchRef::id).toList();
    }

    /**
     * Sucursal única para una escritura: explícita (p. ej. {@code branchId} del body) &gt; encabezado &gt; la única
     * accesible. Si el alcance es "todas" y hay más de una: 400 {@code BRANCH_REQUIRED}. Valida el acceso: un id
     * explícito sigue las reglas de {@link #assertAccess}; uno del encabezado, las de {@link #scopeBranchIds}.
     */
    public Long requireSingleBranch(Long explicitBranchId) {
        AuthUser user = CurrentUser.get();
        requireTenantUser(user);
        if (explicitBranchId != null) {
            checkAccess(user, explicitBranchId, true);
            return explicitBranchId;
        }
        Optional<Long> requested = requestedBranchId();
        if (requested.isPresent()) {
            checkAccess(user, requested.get(), false);
            return requested.get();
        }
        List<BranchRef> accessible = accessibleBranches(user);
        if (accessible.size() == 1) {
            return accessible.getFirst().id();
        }
        if (accessible.isEmpty()) {
            throw new ForbiddenException(ErrorCodes.BRANCH_FORBIDDEN, MSG_NO_BRANCHES);
        }
        throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED, MSG_BRANCH_REQUIRED);
    }

    /**
     * Verifica que el usuario actual pueda operar en la sucursal: 404 {@code NOT_FOUND} si no es de su tenant,
     * 403 {@code BRANCH_FORBIDDEN} si está desactivada o no la tiene asignada.
     */
    public void assertAccess(Long branchId) {
        checkAccess(CurrentUser.get(), branchId, true);
    }

    /** {@code true} si el usuario (activo, de tenant) tiene acceso a la sucursal activa de su tenant. */
    public boolean canAccess(Long userId, Long branchId) {
        if (userId == null || branchId == null) {
            return false;
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isActive() || user.getRole() == null || !user.getRole().isTenantRole()) {
            return false;
        }
        Branch branch = branchRepository.findById(branchId).orElse(null);
        if (branch == null || !branch.isActive() || !Objects.equals(branch.getTenantId(), user.getTenantId())) {
            return false;
        }
        return user.getRole().accessesAllBranches() || userBranchRepository.existsByUserIdAndBranchId(userId, branchId);
    }

    /**
     * Usuarios activos con acceso a la sucursal: jefes y administradores del tenant más los empleados asignados.
     * Vacía si la sucursal no es del tenant o está desactivada.
     */
    public List<Long> userIdsWithAccess(Long tenantId, Long branchId) {
        if (tenantId == null || branchId == null) {
            return List.of();
        }
        boolean activeBranchOfTenant = branchRepository.findByIdAndTenantId(branchId, tenantId)
                .map(Branch::isActive)
                .orElse(false);
        return activeBranchOfTenant ? userRepository.findActiveUserIdsWithBranchAccess(tenantId, branchId) : List.of();
    }

    /** Nombre de cada sucursal del tenant (incluidas las desactivadas, para datos históricos), por nombre. */
    public Map<Long, String> branchNames(Long tenantId) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (tenantId != null) {
            branchRepository.findByTenantIdOrderByNameAsc(tenantId)
                    .forEach(branch -> names.put(branch.getId(), branch.getName()));
        }
        return names;
    }

    /**
     * Id pedido en el encabezado {@code X-Branch-Id} del request actual, sin validar acceso. Vacío si no hay request,
     * falta el encabezado o vale {@code all}; 400 {@code VALIDATION_ERROR} si no es numérico.
     */
    public Optional<Long> requestedBranchId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return Optional.empty();
        }
        String raw = servletAttributes.getRequest().getHeader(HEADER);
        if (raw == null || raw.isBlank() || ALL.equalsIgnoreCase(raw.strip())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.valueOf(raw.strip()));
        } catch (NumberFormatException ex) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_INVALID_HEADER);
        }
    }

    /**
     * @param foreignAsNotFound {@code true}: una sucursal inexistente o de otro comercio da 404 {@code NOT_FOUND};
     *                          {@code false}: da 403 {@code BRANCH_FORBIDDEN} (alcance de lecturas)
     */
    private void checkAccess(AuthUser user, Long branchId, boolean foreignAsNotFound) {
        requireTenantUser(user);
        if (branchId == null) {
            throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED, MSG_BRANCH_REQUIRED);
        }
        Branch branch = branchRepository.findByIdAndTenantId(branchId, user.tenantId())
                .orElseThrow(() -> foreignAsNotFound
                        ? new NotFoundException(MSG_BRANCH_NOT_FOUND)
                        : new ForbiddenException(ErrorCodes.BRANCH_FORBIDDEN, MSG_BRANCH_FORBIDDEN));
        if (!branch.isActive()) {
            throw new ForbiddenException(ErrorCodes.BRANCH_FORBIDDEN, MSG_BRANCH_INACTIVE);
        }
        if (!user.role().accessesAllBranches() && !userBranchRepository.existsByUserIdAndBranchId(user.id(), branchId)) {
            throw new ForbiddenException(ErrorCodes.BRANCH_FORBIDDEN, MSG_BRANCH_FORBIDDEN);
        }
    }

    private static void requireTenantUser(AuthUser user) {
        if (!user.isTenantUser() || user.tenantId() == null) {
            throw new ForbiddenException(ErrorCodes.NO_TENANT, MSG_NO_TENANT);
        }
    }
}
