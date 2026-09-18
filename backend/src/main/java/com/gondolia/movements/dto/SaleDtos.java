package com.gondolia.movements.dto;

import com.gondolia.domain.inventory.MovementSource;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs de ventas manuales e historial de ventas (SPEC §6.4).
 */
public final class SaleDtos {

    private SaleDtos() {
    }

    /** Línea de una venta manual. */
    public record SaleItemRequest(
            @NotNull(message = "es obligatorio") Long productId,
            @NotNull(message = "es obligatoria") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100_000, message = "no puede superar 100.000") Integer quantity,
            @PositiveOrZero(message = "no puede ser negativo") BigDecimal unitPrice) {
    }

    /** Venta manual: {@code branchId} null toma la sucursal del header {@code X-Branch-Id}. */
    public record SaleRequest(
            Long branchId,
            @NotEmpty(message = "cargá al menos un producto")
            @Size(max = 100, message = "no puede tener más de 100 líneas")
            @Valid List<SaleItemRequest> items,
            @Schema(description = "Momento de la venta; null = ahora") Instant occurredAt) {
    }

    /** Lote del que salieron unidades de una línea. */
    public record SaleLotDto(Long lotId, String lotNumber, LocalDate expiryDate, int quantity,
                             BigDecimal unitPrice, BigDecimal discountPct) {
    }

    /** Línea de la venta con los lotes consumidos según la rotación del comercio. */
    public record SaleLineDto(Long productId, String productName, String barcode, int quantity,
                              BigDecimal unitPrice, BigDecimal discountPct, BigDecimal total, int shortage,
                              List<SaleLotDto> lots) {
    }

    /** Respuesta de {@code POST /api/tenant/sales}. */
    public record SaleDto(String batchRef, Long branchId, String branchName, Instant occurredAt,
                          List<SaleLineDto> lines, BigDecimal total, int units, int shortageUnits) {
    }

    /**
     * Fila del historial de ventas. Los importes y unidades ya descuentan las anulaciones
     * ({@code SALE_VOID}); {@code ticketCode} y {@code posSaleId} solo vienen para el POS GondolIA.
     */
    public record SaleSummaryDto(String batchRef, Long branchId, String branchName, Instant occurredAt,
                                 int itemsCount, int units, BigDecimal total, MovementSource source,
                                 String sourceLabel, String userName, boolean voided, int voidedUnits,
                                 String ticketCode, Long posSaleId) {
    }

    /** Detalle de una venta del historial. */
    public record SaleDetailDto(SaleSummaryDto sale, List<SaleLineDto> lines, BigDecimal grossTotal,
                               String reason) {
    }

    /** Error de una línea del CSV de ventas. */
    public record SalesImportErrorDto(int line, String message) {
    }

    /** Resultado de {@code POST /api/tenant/sales/import}. */
    public record SalesImportResultDto(int imported, int skipped, int units, BigDecimal total,
                                       List<String> batchRefs, List<SalesImportErrorDto> errors) {
    }
}
