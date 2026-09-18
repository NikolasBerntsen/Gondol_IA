package com.gondolia.catalog;

import com.gondolia.catalog.dto.BarcodeLookupResponse;
import com.gondolia.catalog.dto.RecallCheckResponse;
import com.gondolia.common.util.Barcodes;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ayudas de la carga de mercadería: autocompletar un producto nuevo por su código de barras y chequear, antes de
 * cargar, si ese código/lote/vencimiento está alcanzado por un recall (SPEC §6.3).
 */
@Tag(name = "Catálogo · consultas")
@RestController
@RequiredArgsConstructor
public class CatalogLookupController {

    private final OpenFoodFactsClient openFoodFactsClient;
    private final RecallMatchingService recallMatchingService;

    /**
     * Datos públicos del producto (Open Food Facts). Nunca falla: si está deshabilitado, no hay internet o el código
     * no existe, devuelve {@code found: false}.
     */
    @GetMapping("/api/tenant/catalog/lookup/{barcode}")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public BarcodeLookupResponse lookup(@PathVariable String barcode) {
        String normalized = Barcodes.normalize(barcode);
        if (normalized == null) {
            return BarcodeLookupResponse.notFound(barcode);
        }
        return openFoodFactsClient.lookup(normalized);
    }

    /** Chequeo previo de recall: no modifica nada, solo avisa. */
    @GetMapping("/api/tenant/recalls/check")
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public RecallCheckResponse check(
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) String lotNumber,
            @Parameter(description = "Vencimiento del lote a cargar (yyyy-MM-dd)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryDate) {
        if (Barcodes.normalize(barcode) == null) {
            return RecallCheckResponse.of(List.of());
        }
        return RecallCheckResponse.of(recallMatchingService.findActiveRecalls(barcode, lotNumber, expiryDate));
    }
}
