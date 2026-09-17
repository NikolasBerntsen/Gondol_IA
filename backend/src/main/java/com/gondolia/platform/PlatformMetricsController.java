package com.gondolia.platform;

import com.gondolia.platform.dto.PlatformMetrics;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Métricas de la consola de dueños (SPEC §6.6). Solo agregados: ningún dato de negocio de un comercio.
 */
@Tag(name = "Consola de dueños · Métricas")
@RestController
@RequestMapping("/api/platform/metrics")
@PreAuthorize(Roles.OWNER)
@RequiredArgsConstructor
public class PlatformMetricsController {

    private final PlatformMetricsService metricsService;

    @Operation(summary = "Métricas de la plataforma",
            description = "Comercios, sucursales, planes, rubros, uso, usuarios, MRR estimado, crecimiento de 12 "
                    + "meses, soporte, recalls y adopción de módulos. Nunca incluye productos, stock, ventas, "
                    + "alertas ni chats de un comercio.")
    @GetMapping
    public PlatformMetrics metrics() {
        return metricsService.metrics();
    }
}
