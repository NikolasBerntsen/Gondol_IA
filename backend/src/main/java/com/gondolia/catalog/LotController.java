package com.gondolia.catalog;

import com.gondolia.catalog.dto.LotDto;
import com.gondolia.catalog.dto.LotRequest;
import com.gondolia.catalog.dto.LotUpdateRequest;
import com.gondolia.catalog.dto.ReceiveLotResponse;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Carga de mercadería (SPEC §6.3). Administrador y empleado, siempre sobre una sucursal a la que tengan acceso:
 * {@code branchId} del cuerpo si viene, si no la del encabezado {@code X-Branch-Id}; con alcance "todas" y más de una
 * sucursal accesible responde 400 {@code BRANCH_REQUIRED}.
 */
@Tag(name = "Catálogo · lotes")
@RestController
@RequestMapping("/api/tenant/lots")
@RequiredArgsConstructor
public class LotController {

    private final LotService lotService;

    /** Lotes de un producto en el alcance, por sucursal y en orden de rotación. */
    @GetMapping
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public List<LotDto> list(@RequestParam Long productId,
                             @RequestParam(defaultValue = "false") boolean includeEmpty) {
        return lotService.list(productId, includeEmpty);
    }

    /** Registra un ingreso: crea un lote nuevo, chequea recalls y avisa si rompe el orden FIFO. */
    @PostMapping
    @PreAuthorize(Roles.TENANT_INVENTORY)
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiveLotResponse receive(@Valid @RequestBody LotRequest request) {
        return lotService.receive(request);
    }

    /** Corrige el número de lote y el vencimiento de un lote ya cargado. */
    @PutMapping("/{id}")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public LotDto update(@PathVariable Long id, @Valid @RequestBody LotUpdateRequest request) {
        return lotService.update(id, request);
    }
}
