package com.gondolia.pos;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.pos.dto.PosStatsDto;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Estadísticas del POS GondolIA (SPEC §15.2). Solo el administrador, sobre su alcance de sucursales.
 */
@Tag(name = "POS · Estadísticas")
@RestController
@RequestMapping("/api/tenant/pos/stats")
@RequiresModule(TenantModule.POS_GONDOLIA)
@PreAuthorize(Roles.TENANT_ADMIN)
@RequiredArgsConstructor
public class PosStatsController {

    private final PosStatsService statsService;

    @Operation(summary = "Ventas por medio de pago, hora, cajero y producto")
    @GetMapping
    public PosStatsDto stats(@RequestParam(defaultValue = "30")
                             @Min(value = 1, message = "tiene que ser al menos 1")
                             @Max(value = 365, message = "no puede superar 365") int days) {
        return statsService.stats(CurrentUser.tenantId(), days);
    }
}
