package com.gondolia.tenantadmin;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.tenantadmin.dto.BranchDto;
import com.gondolia.tenantadmin.dto.BranchLimitsDto;
import com.gondolia.tenantadmin.dto.BranchRequest;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sucursales del comercio (SPEC §6.9, §3.5, §14.1).
 * <ul>
 *   <li>El listado respeta el alcance: jefe y administrador ven todas las activas, empleados y cajeros solo las
 *       asignadas; solo el administrador puede pedir también las desactivadas.</li>
 *   <li>El límite de sucursales activas es el <b>máximo efectivo</b>
 *       ({@link ModuleService#effectiveMaxBranches(Long)}): el del plan si {@code MULTI_BRANCH} está habilitado y 1
 *       si no lo está. Pasarse devuelve 409 {@code BRANCH_LIMIT_REACHED}.</li>
 *   <li>No se puede desactivar una sucursal con stock físico (409 {@code BRANCH_HAS_STOCK}) ni la última activa
 *       (409 {@code LAST_ACTIVE_BRANCH}).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class TenantBranchService {

    static final String MSG_NOT_FOUND = "La sucursal no existe";

    private final BranchRepository branchRepository;
    private final UserBranchRepository userBranchRepository;
    private final LotRepository lotRepository;
    private final TenantRepository tenantRepository;
    private final ModuleService moduleService;
    private final BranchAccessService branchAccessService;

    /**
     * Sucursales visibles para el usuario actual. {@code includeInactive} solo lo respeta el administrador
     * (los demás roles nunca ven sucursales desactivadas).
     */
    public List<BranchDto> list(boolean includeInactive) {
        Long tenantId = CurrentUser.tenantId();
        boolean admin = CurrentUser.role() == Role.TENANT_ADMIN;
        List<Branch> branches;
        if (admin && includeInactive) {
            branches = branchRepository.findByTenantIdOrderByNameAsc(tenantId);
        } else {
            List<Long> accessible = branchAccessService.accessibleBranches().stream()
                    .map(BranchAccessService.BranchRef::id)
                    .toList();
            branches = accessible.isEmpty() ? List.of()
                    : branchRepository.findByTenantIdAndIdIn(tenantId, accessible).stream()
                            .sorted(Comparator.comparing(Branch::getName))
                            .toList();
        }
        return branches.stream().map(this::toDto).toList();
    }

    /** Plan, máximo efectivo de sucursales y cuántas hay activas (para el indicador "2 de 3 sucursales"). */
    public BranchLimitsDto limits() {
        return limits(CurrentUser.tenantId());
    }

    private BranchLimitsDto limits(Long tenantId) {
        TenantPlan plan = tenantRepository.findById(tenantId).map(Tenant::getPlan).orElse(TenantPlan.FREEMIUM);
        boolean multiBranch = moduleService.isEnabled(tenantId, TenantModule.MULTI_BRANCH);
        int maxBranches = ModuleService.effectiveMaxBranches(plan, multiBranch);
        return new BranchLimitsDto(plan, maxBranches, plan.maxBranches(), multiBranch,
                branchRepository.countByTenantIdAndActiveTrue(tenantId), branchRepository.countByTenantId(tenantId));
    }

    @Transactional
    public BranchDto create(BranchRequest request) {
        Long tenantId = CurrentUser.tenantId();
        String name = request.name().strip();
        BranchLimitsDto limits = limits(tenantId);
        if (!limits.canCreate()) {
            throw branchLimitReached(limits);
        }
        if (branchRepository.existsByTenantIdAndNameIgnoreCase(tenantId, name)) {
            throw duplicateName(name);
        }

        Branch branch = new Branch();
        branch.setTenantId(tenantId);
        branch.setActive(true);
        apply(branch, request, name);
        branchRepository.save(branch);
        log.info("Comercio {}: alta de la sucursal {} ({})", tenantId, branch.getId(), name);
        return toDto(branch);
    }

    @Transactional
    public BranchDto update(Long branchId, BranchRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Branch branch = load(branchId, tenantId);
        String name = request.name().strip();
        if (branchRepository.existsByTenantIdAndNameIgnoreCaseAndIdNot(tenantId, name, branchId)) {
            throw duplicateName(name);
        }
        apply(branch, request, name);
        branchRepository.save(branch);
        return toDto(branch);
    }

    /** Desactiva la sucursal. Bloqueada si tiene stock físico o si es la única activa. */
    @Transactional
    public BranchDto deactivate(Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        Branch branch = load(branchId, tenantId);
        if (!branch.isActive()) {
            return toDto(branch);
        }
        if (branchRepository.countByTenantIdAndActiveTrue(tenantId) <= 1) {
            throw new ConflictException(TenantAdminErrors.LAST_ACTIVE_BRANCH,
                    "No podés desactivar la única sucursal activa del comercio.");
        }
        if (lotRepository.hasPhysicalStock(branchId)) {
            throw new ConflictException(ErrorCodes.BRANCH_HAS_STOCK,
                    "La sucursal todavía tiene stock. Transferilo a otra sucursal o descartalo antes de desactivarla.");
        }
        branch.setActive(false);
        branchRepository.save(branch);
        log.info("Comercio {}: sucursal {} desactivada", tenantId, branchId);
        return toDto(branch);
    }

    /** Vuelve a activar la sucursal, respetando el máximo efectivo del plan y los módulos. */
    @Transactional
    public BranchDto activate(Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        Branch branch = load(branchId, tenantId);
        if (branch.isActive()) {
            return toDto(branch);
        }
        BranchLimitsDto limits = limits(tenantId);
        if (!limits.canCreate()) {
            throw branchLimitReached(limits);
        }
        branch.setActive(true);
        branchRepository.save(branch);
        log.info("Comercio {}: sucursal {} reactivada", tenantId, branchId);
        return toDto(branch);
    }

    // ------------------------------------------------------------------ interno

    private Branch load(Long branchId, Long tenantId) {
        return branchRepository.findByIdAndTenantId(branchId, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
    }

    private static void apply(Branch branch, BranchRequest request, String name) {
        branch.setName(name);
        branch.setCode(normalizeCode(request.code()));
        branch.setAddress(trimToNull(request.address()));
        branch.setCity(trimToNull(request.city()));
        branch.setProvince(trimToNull(request.province()));
        branch.setPhone(trimToNull(request.phone()));
    }

    private BranchDto toDto(Branch branch) {
        return new BranchDto(branch.getId(), branch.getName(), branch.getCode(), branch.getAddress(), branch.getCity(),
                branch.getProvince(), branch.getPhone(), branch.isActive(),
                userBranchRepository.countActiveEmployeesByBranchId(branch.getId()),
                lotRepository.hasPhysicalStock(branch.getId()), branch.getCreatedAt());
    }

    private static ConflictException branchLimitReached(BranchLimitsDto limits) {
        String message = limits.multiBranchEnabled()
                ? "Tu plan permite hasta " + limits.maxBranches() + " sucursales activas. Desactivá una o "
                        + "contactá a soporte para cambiar de plan."
                : "Tu comercio no tiene habilitado el módulo Multi-sucursal: solo podés tener una sucursal activa. "
                        + "Contactá a GondolIA para activarlo.";
        return new ConflictException(ErrorCodes.BRANCH_LIMIT_REACHED, message);
    }

    private static ConflictException duplicateName(String name) {
        return new ConflictException(TenantAdminErrors.DUPLICATE_BRANCH_NAME,
                "Ya tenés una sucursal con el nombre " + name + ".");
    }

    private static String normalizeCode(String raw) {
        String code = trimToNull(raw);
        return code == null ? null : code.toUpperCase(java.util.Locale.ROOT);
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
