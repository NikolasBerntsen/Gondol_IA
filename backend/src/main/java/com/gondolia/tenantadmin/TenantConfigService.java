package com.gondolia.tenantadmin;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.modules.ModuleCatalog;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.CurrentUser;
import com.gondolia.tenantadmin.dto.TenantAccountDto;
import com.gondolia.tenantadmin.dto.TenantSettingsDto;
import com.gondolia.tenantadmin.dto.TenantSettingsRequest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Configuración y datos del comercio (SPEC §6.9).
 * <ul>
 *   <li>{@code stockRotation} decide el orden de salida del stock en ventas y bajas sin lote indicado
 *       (SPEC §4.2): {@code FIFO} por fecha de ingreso, {@code FEFO} por fecha de vencimiento.</li>
 *   <li>Los umbrales de vencimiento tienen que ser coherentes: el crítico siempre antes que el de aviso.</li>
 *   <li>Los datos del comercio (razón social, plan, contacto) son de solo lectura: los cambia GondolIA.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, noRollbackFor = ApiException.class)
public class TenantConfigService {

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;
    private final BranchRepository branchRepository;
    private final ModuleService moduleService;

    public TenantSettingsDto settings() {
        return toDto(loadSettings(CurrentUser.tenantId()));
    }

    @Transactional
    public TenantSettingsDto updateSettings(TenantSettingsRequest request) {
        Long tenantId = CurrentUser.tenantId();
        if (request.expiryCriticalDays() >= request.expiryWarningDays()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Los días de aviso tienen que ser más que los días críticos: primero avisás y después es crítico.");
        }
        TenantSettings settings = loadSettings(tenantId);
        if (request.currency() != null && !request.currency().isBlank()) {
            settings.setCurrency(request.currency().strip().toUpperCase(Locale.ROOT));
        }
        boolean rotationChanged = settings.getStockRotation() != request.stockRotation();
        settings.setStockRotation(request.stockRotation());
        settings.setExpiryWarningDays(request.expiryWarningDays());
        settings.setExpiryCriticalDays(request.expiryCriticalDays());
        settings.setDefaultLeadTimeDays(request.defaultLeadTimeDays());
        settings.setTargetCoverageDays(request.targetCoverageDays());
        settings.setServiceLevel(request.serviceLevel());
        settings.setMaxDiscountPct(request.maxDiscountPct());
        settingsRepository.saveAndFlush(settings);

        if (rotationChanged) {
            log.info("Comercio {}: rotación de stock cambiada a {}", tenantId, request.stockRotation());
        }
        return toDto(settings);
    }

    /** Datos administrativos del comercio (SPEC §6.9): solo lectura para el cliente. */
    public TenantAccountDto account() {
        Long tenantId = CurrentUser.tenantId();
        Tenant tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new NotFoundException("El comercio no existe"));
        Set<TenantModule> modules = moduleService.enabledModules(tenantId);
        long activeBranches = branchRepository.countByTenantIdAndActiveTrue(tenantId);
        BigDecimal fee = ModuleCatalog.monthlyFee(tenant.getPlan(), modules, activeBranches);
        int maxBranches = ModuleService.effectiveMaxBranches(tenant.getPlan(),
                modules.contains(TenantModule.MULTI_BRANCH));
        return new TenantAccountDto(tenant.getId(), tenant.getName(), tenant.getLegalName(), tenant.getTaxId(),
                tenant.getBusinessType(), tenant.getPlan(), tenant.getStatus(), tenant.getContactName(),
                tenant.getContactEmail(), tenant.getContactPhone(), tenant.getAddress(), tenant.getCity(),
                tenant.getProvince(), tenant.getCreatedAt(), List.copyOf(modules), activeBranches, maxBranches, fee);
    }

    /** Settings del comercio; si todavía no existen se crean con los valores por defecto del esquema. */
    private TenantSettings loadSettings(Long tenantId) {
        return settingsRepository.findById(tenantId)
                .orElseGet(() -> settingsRepository.save(TenantSettings.defaultsFor(tenantId)));
    }

    private static TenantSettingsDto toDto(TenantSettings settings) {
        return new TenantSettingsDto(settings.getCurrency(), settings.getStockRotation(),
                settings.getExpiryWarningDays(), settings.getExpiryCriticalDays(), settings.getDefaultLeadTimeDays(),
                settings.getTargetCoverageDays(), settings.getServiceLevel(), settings.getMaxDiscountPct(),
                settings.getUpdatedAt());
    }
}
