package com.gondolia.pos;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.pos.dto.PosCategoryDto;
import com.gondolia.pos.dto.PosProductDto;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Productos para vender en el POS (SPEC §15.2). Siempre sobre la sucursal del turno: se toma de {@code branchId},
 * del encabezado {@code X-Branch-Id} o de la única sucursal accesible (400 {@code BRANCH_REQUIRED} si no alcanza).
 */
@Tag(name = "POS · Productos")
@RestController
@RequestMapping("/api/tenant/pos/products")
@RequiresModule(TenantModule.POS_GONDOLIA)
@PreAuthorize(Roles.TENANT_POS)
@RequiredArgsConstructor
public class PosCatalogController {

    private final PosCatalogService catalogService;

    @Operation(summary = "Buscar por código de barras exacto",
            description = "Lector USB o cámara. 404 si no existe o el producto está dado de baja.")
    @GetMapping("/lookup")
    public PosProductDto lookup(@RequestParam String code, @RequestParam(required = false) Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        return catalogService.lookup(tenantId, catalogService.resolveBranch(branchId), code);
    }

    @Operation(summary = "Buscar productos del mostrador",
            description = "Hasta 20 resultados por nombre, marca o código. Sin texto devuelve los primeros por nombre.")
    @GetMapping("/search")
    public List<PosProductDto> search(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) Long categoryId,
                                      @RequestParam(required = false) Long branchId,
                                      @RequestParam(defaultValue = "20")
                                      @Min(value = 1, message = "tiene que ser al menos 1")
                                      @Max(value = 20, message = "no puede superar 20") int limit) {
        Long tenantId = CurrentUser.tenantId();
        return catalogService.search(tenantId, catalogService.resolveBranch(branchId), q, categoryId, limit);
    }

    @Operation(summary = "Categorías con stock en la sucursal", description = "Filtros rápidos del mostrador.")
    @GetMapping("/categories")
    public List<PosCategoryDto> categories(@RequestParam(required = false) Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        return catalogService.categories(tenantId, catalogService.resolveBranch(branchId));
    }
}
