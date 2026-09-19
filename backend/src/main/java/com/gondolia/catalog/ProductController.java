package com.gondolia.catalog;

import com.gondolia.catalog.dto.ProductDetail;
import com.gondolia.catalog.dto.ProductListItem;
import com.gondolia.catalog.dto.ProductMovementDto;
import com.gondolia.catalog.dto.ProductRequest;
import com.gondolia.common.PageResponse;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Productos del catálogo (SPEC §6.3). El catálogo es del comercio; el stock de cada fila corresponde al alcance de
 * sucursales del encabezado {@code X-Branch-Id} (SPEC §3.5).
 * <p>
 * Roles: listar y ver (incluidos los movimientos del producto) → jefe, administrador y empleado; crear y editar →
 * administrador y empleado; eliminar → solo administrador.
 */
@Tag(name = "Catálogo · productos")
@RestController
@RequestMapping("/api/tenant/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductMovementService productMovementService;

    @GetMapping
    @PreAuthorize(Roles.TENANT_INVENTORY_READ)
    public PageResponse<ProductListItem> list(
            @Parameter(description = "Busca en nombre, marca y código de barras") @RequestParam(required = false) String q,
            @RequestParam(required = false) Long categoryId,
            @Parameter(description = "ALL, OK, LOW, OUT o EXPIRING")
            @RequestParam(required = false) String stockStatus,
            @Parameter(description = "true solo activos, false solo dados de baja, vacío todos")
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "name,asc") String sort) {
        return productService.list(new ProductQuery(q, categoryId, stockStatus, active, page, size, sort));
    }

    @GetMapping("/{id}")
    @PreAuthorize(Roles.TENANT_INVENTORY_READ)
    public ProductDetail get(@PathVariable Long id) {
        return productService.get(id);
    }

    @GetMapping("/by-barcode/{barcode}")
    @PreAuthorize(Roles.TENANT_INVENTORY_READ)
    public ProductDetail getByBarcode(@PathVariable String barcode) {
        return productService.getByBarcode(barcode);
    }

    /**
     * Últimos movimientos del producto en el alcance (ficha del producto). El empleado los ve sin los importes ni la
     * referencia de la venta (el historial de ventas es del jefe y del administrador, SPEC §3.3).
     */
    @GetMapping("/{id}/movements")
    @PreAuthorize(Roles.TENANT_INVENTORY_READ)
    public List<ProductMovementDto> movements(@PathVariable Long id,
                                              @RequestParam(defaultValue = "12") int limit) {
        return productMovementService.recent(id, limit);
    }

    @PostMapping
    @PreAuthorize(Roles.TENANT_INVENTORY)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDetail create(@Valid @RequestBody ProductRequest request) {
        return productService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public ProductDetail update(@PathVariable Long id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    /**
     * Baja del producto. Si ya tiene movimientos o lotes queda desactivado ({@code deactivated: true}); si nunca se
     * usó, se elimina.
     */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public Map<String, Boolean> delete(@PathVariable Long id) {
        return Map.of("deactivated", productService.delete(id));
    }
}
