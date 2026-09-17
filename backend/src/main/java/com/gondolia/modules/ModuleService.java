package com.gondolia.modules;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantEvent;
import com.gondolia.domain.tenant.TenantEventRepository;
import com.gondolia.domain.tenant.TenantEventType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantModuleConfig;
import com.gondolia.domain.tenant.TenantModuleConfigRepository;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.realtime.Destinations;
import com.gondolia.realtime.RealtimePublisher;
import com.gondolia.security.CurrentUser;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Módulos habilitados por comercio (SPEC §14.2). Los dueños de GondolIA los activan y desactivan desde su consola;
 * los módulos de producto los <b>consultan</b> con {@link #isEnabled} o declaran {@link RequiresModule} en sus
 * controladores.
 * <ul>
 *   <li>Sin fila en {@code tenant_modules} (o con {@code enabled = false}) el módulo está deshabilitado.</li>
 *   <li>Deshabilitar {@code MULTI_BRANCH} con más de una sucursal activa: 409 {@code MODULE_IN_USE}.</li>
 *   <li>Cada cambio real registra un evento {@code MODULE_ENABLED}/{@code MODULE_DISABLED} con
 *       {@code from_value} = nombre del módulo y, al confirmar la transacción, hace push
 *       {@code {"type":"MODULES_CHANGED"}} a {@code /user/queue/session} de todos los usuarios activos del comercio.</li>
 *   <li>Máximo efectivo de sucursales = {@code MULTI_BRANCH} ? límite del plan : 1.</li>
 * </ul>
 * Los rechazos ({@link com.gondolia.common.error.ApiException}) se lanzan antes de escribir y no marcan como
 * rollback-only la transacción del que llama, igual que en {@code StockService} y {@code BranchAccessService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class ModuleService {

    static final String MSG_TENANT_NOT_FOUND = "El comercio no existe";

    private final TenantModuleConfigRepository moduleRepository;
    private final TenantRepository tenantRepository;
    private final BranchRepository branchRepository;
    private final TenantEventRepository tenantEventRepository;
    private final UserRepository userRepository;
    private final RealtimePublisher realtimePublisher;

    // ------------------------------------------------------------------ lecturas

    /** Módulos habilitados del comercio (vacío si no tiene ninguno). */
    public Set<TenantModule> enabledModules(Long tenantId) {
        Set<TenantModule> modules = EnumSet.noneOf(TenantModule.class);
        if (tenantId == null) {
            return modules;
        }
        moduleRepository.findByTenantIdAndEnabledTrue(tenantId).stream()
                .map(TenantModuleConfig::getModule)
                .filter(Objects::nonNull)
                .forEach(modules::add);
        return modules;
    }

    /** {@code true} si el comercio tiene el módulo habilitado. Lo usan el interceptor y el webhook del POS externo. */
    public boolean isEnabled(Long tenantId, TenantModule module) {
        return tenantId != null && module != null
                && moduleRepository.existsByTenantIdAndModuleAndEnabledTrue(tenantId, module);
    }

    /** Exige el módulo para el comercio del usuario actual; si no lo tiene, 403 {@code MODULE_DISABLED}. */
    public void require(TenantModule module) {
        require(CurrentUser.tenantId(), module);
    }

    /**
     * Exige el módulo para un comercio dado (útil donde no hay usuario autenticado, como el webhook del POS externo,
     * que resuelve el comercio con la API key); si no lo tiene, 403 {@code MODULE_DISABLED}.
     */
    public void require(Long tenantId, TenantModule module) {
        if (!isEnabled(tenantId, module)) {
            throw new ForbiddenException(ErrorCodes.MODULE_DISABLED, ModuleCatalog.MSG_MODULE_DISABLED);
        }
    }

    /** Máximo de sucursales activas: el límite del plan si {@code MULTI_BRANCH} está habilitado; si no, 1. */
    public int effectiveMaxBranches(Long tenantId) {
        if (tenantId == null) {
            return 1;
        }
        TenantPlan plan = tenantRepository.findById(tenantId).map(Tenant::getPlan).orElse(null);
        return effectiveMaxBranches(plan, isEnabled(tenantId, TenantModule.MULTI_BRANCH));
    }

    /** Variante sin consultas para quien ya tiene el plan y los módulos (p. ej. la consola de dueños). */
    public static int effectiveMaxBranches(TenantPlan plan, boolean multiBranchEnabled) {
        int planLimit = plan != null ? plan.maxBranches() : 1;
        return multiBranchEnabled ? planLimit : 1;
    }

    /** Estado de los tres módulos para el comercio, en el orden del catálogo. */
    public List<TenantModuleStatus> statuses(Long tenantId) {
        List<TenantModuleConfig> configs = tenantId == null ? List.of()
                : moduleRepository.findByTenantIdOrderByModuleAsc(tenantId);
        return ModuleCatalog.all().stream()
                .map(info -> configs.stream()
                        .filter(config -> config.getModule() == info.module())
                        .findFirst()
                        .map(this::toStatus)
                        .orElseGet(() -> TenantModuleStatus.disabled(info.module())))
                .toList();
    }

    // ------------------------------------------------------------------ escrituras

    /**
     * Habilita o deshabilita un módulo. Si ya estaba en ese estado no registra evento ni hace push (idempotente).
     *
     * @throws NotFoundException si el comercio no existe
     * @throws ConflictException 409 {@code MODULE_IN_USE} al deshabilitar {@code MULTI_BRANCH} con más de una sucursal
     *                           activa
     */
    @Transactional(noRollbackFor = ApiException.class)
    public TenantModuleStatus setEnabled(Long tenantId, TenantModule module, boolean enabled, Long actorUserId) {
        Tenant tenant = requireTenant(tenantId);
        Objects.requireNonNull(module, "module");
        if (!enabled) {
            checkNotInUse(tenant.getId(), module);
        }
        TenantModuleConfig config = apply(tenant.getId(), module, enabled, actorUserId);
        return toStatus(config);
    }

    /**
     * Aplica los módulos que corresponden al plan (SPEC §14.1: FREEMIUM {POS_GONDOLIA}; BASICO y PROFESIONAL, los
     * tres). Se usa al crear un comercio cuando no se indican módulos. Si el preset deshabilitaría
     * {@code MULTI_BRANCH} en un comercio que ya tiene más de una sucursal activa, ese módulo se deja como está (hay
     * que desactivar las sucursales extra primero).
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void applyPlanPreset(Long tenantId, TenantPlan plan) {
        Tenant tenant = requireTenant(tenantId);
        Set<TenantModule> preset = ModuleCatalog.preset(plan != null ? plan : tenant.getPlan());
        for (TenantModule module : TenantModule.values()) {
            boolean enabled = preset.contains(module);
            if (!enabled && isInUse(tenant.getId(), module)) {
                log.warn("El preset del plan {} no deshabilitó {} en el comercio {}: está en uso", plan, module,
                        tenant.getId());
                continue;
            }
            apply(tenant.getId(), module, enabled, null);
        }
    }

    // ------------------------------------------------------------------ internos

    /** Escribe el estado del módulo y, si cambió, registra el evento y programa el push. */
    private TenantModuleConfig apply(Long tenantId, TenantModule module, boolean enabled, Long actorUserId) {
        TenantModuleConfig config = moduleRepository.findByTenantIdAndModule(tenantId, module)
                .orElseGet(() -> new TenantModuleConfig(tenantId, module, false, null));
        boolean changed = config.getId() == null ? enabled : config.isEnabled() != enabled;
        if (!changed && config.getId() != null) {
            return config;
        }
        config.setEnabled(enabled);
        config.setUpdatedBy(actorUserId);
        moduleRepository.saveAndFlush(config);
        if (changed) {
            recordEvent(tenantId, module, enabled, actorUserId);
            notifyTenant(tenantId);
            log.info("Módulo {} {} para el comercio {}", module, enabled ? "habilitado" : "deshabilitado", tenantId);
        }
        return config;
    }

    private void recordEvent(Long tenantId, TenantModule module, boolean enabled, Long actorUserId) {
        TenantEvent event = new TenantEvent();
        event.setTenantId(tenantId);
        event.setType(enabled ? TenantEventType.MODULE_ENABLED : TenantEventType.MODULE_DISABLED);
        event.setFromValue(module.name());
        event.setToValue(String.valueOf(enabled));
        event.setActorUserId(actorUserId);
        tenantEventRepository.save(event);
    }

    /** Push {@code MODULES_CHANGED} a todos los usuarios activos del comercio (al confirmar la transacción). */
    private void notifyTenant(Long tenantId) {
        List<Long> userIds = userRepository.findByTenantIdAndActiveTrue(tenantId).stream().map(User::getId).toList();
        if (!userIds.isEmpty()) {
            realtimePublisher.toUsers(userIds, Destinations.QUEUE_SESSION, new ModulesChangedMessage());
        }
    }

    /** 409 {@code MODULE_IN_USE} si el comercio está usando el módulo que se quiere deshabilitar. */
    private void checkNotInUse(Long tenantId, TenantModule module) {
        if (module == TenantModule.MULTI_BRANCH) {
            long activeBranches = branchRepository.countByTenantIdAndActiveTrue(tenantId);
            if (activeBranches > 1) {
                throw new ConflictException(ErrorCodes.MODULE_IN_USE, "El cliente tiene " + activeBranches
                        + " sucursales activas: debe desactivar las sucursales extra antes");
            }
        }
    }

    private boolean isInUse(Long tenantId, TenantModule module) {
        return module == TenantModule.MULTI_BRANCH && branchRepository.countByTenantIdAndActiveTrue(tenantId) > 1;
    }

    private Tenant requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new NotFoundException(MSG_TENANT_NOT_FOUND);
        }
        return tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException(MSG_TENANT_NOT_FOUND));
    }

    private TenantModuleStatus toStatus(TenantModuleConfig config) {
        ModuleCatalog.ModuleInfo info = ModuleCatalog.of(config.getModule());
        String updatedByName = config.getUpdatedBy() == null ? null
                : userRepository.findById(config.getUpdatedBy()).map(User::getFullName).orElse(null);
        return new TenantModuleStatus(info.module(), info.name(), info.description(), info.monthlyPricePerBranch(),
                config.isEnabled(), config.getUpdatedAt(), updatedByName);
    }
}
