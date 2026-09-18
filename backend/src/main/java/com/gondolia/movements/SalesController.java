package com.gondolia.movements;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.modules.RequiresModule;
import com.gondolia.movements.dto.SaleDtos.SaleDetailDto;
import com.gondolia.movements.dto.SaleDtos.SaleDto;
import com.gondolia.movements.dto.SaleDtos.SaleRequest;
import com.gondolia.movements.dto.SaleDtos.SaleSummaryDto;
import com.gondolia.movements.dto.SaleDtos.SalesImportResultDto;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ventas manuales, historial de ventas de todas las fuentes e importación CSV de ventas del POS propio
 * (SPEC §6.4). Rol: administrador del comercio.
 */
@Tag(name = "Ventas")
@RestController
@RequestMapping("/api/tenant/sales")
@PreAuthorize(Roles.TENANT_ADMIN)
@RequiredArgsConstructor
public class SalesController {

    private final SalesService salesService;
    private final SalesCsvService salesCsvService;

    @Operation(summary = "Registrar una venta manual",
            description = "Descuenta stock con la rotación del comercio (FIFO/FEFO, liquidaciones primero) y "
                    + "devuelve de qué lotes salió cada unidad.")
    @PostMapping
    public SaleDto register(@Valid @RequestBody SaleRequest request) {
        return salesService.register(request);
    }

    @Operation(summary = "Historial de ventas",
            description = "Agrupado por venta y neteando las anulaciones (SALE_VOID). Incluye el origen y, en el "
                    + "POS GondolIA, el código de ticket.")
    @GetMapping
    public PageResponse<SaleSummaryDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) MovementSource source,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "no puede ser negativa") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100, message = "no puede superar 100") int size) {
        return salesService.list(from, to, source, q, branchId, page, size);
    }

    @Operation(summary = "Plantilla CSV de ventas")
    @RequiresModule(TenantModule.POS_INTEGRATION)
    @GetMapping("/import/template")
    public ResponseEntity<byte[]> template() {
        byte[] body = SalesCsvService.TEMPLATE.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ventas-plantilla.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "Importar ventas desde un CSV",
            description = "Columnas fecha, codigo_barras, cantidad, precio_unitario. Requiere el módulo "
                    + "Integración con POS propio.")
    @RequiresModule(TenantModule.POS_INTEGRATION)
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SalesImportResultDto importSales(@RequestPart("file") MultipartFile file,
                                            @RequestParam(required = false) Long branchId) {
        return salesCsvService.importSales(file, branchId);
    }

    @Operation(summary = "Detalle de una venta", description = "Líneas con los lotes de los que salió cada unidad.")
    @GetMapping("/{batchRef}")
    public SaleDetailDto detail(@PathVariable String batchRef) {
        return salesService.detail(batchRef);
    }
}
