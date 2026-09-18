package com.gondolia.catalog;

import com.gondolia.catalog.dto.SupplierDto;
import com.gondolia.catalog.dto.SupplierRequest;
import com.gondolia.security.Roles;
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
 * Proveedores del comercio (SPEC §6.3). Listar: administrador y empleado (la carga de mercadería los necesita);
 * crear, editar y eliminar: administrador.
 */
@Tag(name = "Catálogo · proveedores")
@RestController
@RequestMapping("/api/tenant/suppliers")
@RequiredArgsConstructor
public class SupplierController {

    private final SupplierService supplierService;

    /** {@code includeInactive=false} deja afuera los proveedores dados de baja. */
    @GetMapping
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public List<SupplierDto> list(@RequestParam(defaultValue = "true") boolean includeInactive) {
        return supplierService.list(includeInactive);
    }

    @PostMapping
    @PreAuthorize(Roles.TENANT_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierDto create(@Valid @RequestBody SupplierRequest request) {
        return supplierService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public SupplierDto update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    /**
     * Elimina el proveedor; si ya está usado en productos o lotes, lo <b>desactiva</b> (la respuesta lo informa en
     * {@code deactivated} para que la pantalla muestre el mensaje correcto).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public Map<String, Boolean> delete(@PathVariable Long id) {
        return Map.of("deactivated", supplierService.delete(id).deactivated());
    }
}
