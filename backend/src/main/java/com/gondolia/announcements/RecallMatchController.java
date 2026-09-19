package com.gondolia.announcements;

import com.gondolia.announcements.dto.RecallMatchDto;
import com.gondolia.announcements.dto.ResolveRecallRequest;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seguridad alimentaria del comercio (SPEC §6.7): coincidencias de recall de las sucursales del alcance elegido.
 * Confirmar la alerta la puede cualquier rol de comercio; retirar la mercadería, solo administrador y empleado
 * (matriz de permisos §3.3).
 */
@Tag(name = "Seguridad alimentaria (comercio)")
@RestController
@RequestMapping("/api/tenant/recall-matches")
@RequiredArgsConstructor
public class RecallMatchController {

    private final RecallMatchService recallMatchService;

    @Operation(summary = "Coincidencias de recall",
            description = "Del alcance de sucursales elegido (X-Branch-Id). ACTIVE = pendientes y confirmadas.")
    @GetMapping
    @PreAuthorize(Roles.TENANT_ANY)
    public List<RecallMatchDto> list(
            @RequestParam(defaultValue = "ACTIVE") RecallMatchService.MatchFilter status) {
        return recallMatchService.list(status);
    }

    @Operation(summary = "Entendido", description = "Deja registrado quién vio la alerta.")
    @PostMapping("/{id}/acknowledge")
    @PreAuthorize(Roles.TENANT_ANY)
    public RecallMatchDto acknowledge(@PathVariable Long id) {
        return recallMatchService.acknowledge(id);
    }

    @Operation(summary = "Resolver el recall",
            description = "Retira del stock el remanente en cuarentena con RECALL_REMOVAL o ADJUSTMENT_OUT.")
    @PostMapping("/{id}/resolve")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public RecallMatchDto resolve(@PathVariable Long id, @Valid @RequestBody ResolveRecallRequest request) {
        return recallMatchService.resolve(id, request);
    }
}
