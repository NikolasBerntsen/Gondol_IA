package com.gondolia.movements.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * DTOs de la integración con el POS propio del cliente: API keys por sucursal, simulador y webhook
 * público de ventas (SPEC §6.4). Requieren el módulo {@code POS_INTEGRATION}.
 */
public final class PosIntegrationDtos {

    private PosIntegrationDtos() {
    }

    /** Estado de la integración en una sucursal. */
    public record PosIntegrationDto(Long branchId, String branchName, String branchCode, boolean configured,
                                    String prefix, Instant createdAt, Instant lastSaleAt, long salesLast24h,
                                    long unitsLast24h) {
    }

    /** La API key en claro se devuelve una sola vez, al generarla. */
    public record PosApiKeyDto(Long branchId, String branchName, String apiKey, String prefix, Instant createdAt) {
    }

    /** Simulador: cantidad de ventas a generar. */
    public record SimulateRequest(
            @NotNull(message = "es obligatoria") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 50, message = "no puede superar 50") Integer sales) {
    }

    /** Resultado del simulador. */
    public record SimulateResultDto(Long branchId, String branchName, int sales, int lines, int units,
                                    BigDecimal total, int shortages, List<String> batchRefs) {
    }

    // ------------------------------------------------------------------ webhook público

    public record PosSaleItemRequest(
            @NotBlank(message = "es obligatorio") @Size(max = 32, message = "no puede superar los 32 caracteres")
            String barcode,
            @NotNull(message = "es obligatoria") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 100_000, message = "no puede superar 100.000") Integer quantity,
            @PositiveOrZero(message = "no puede ser negativo") BigDecimal unitPrice) {
    }

    /** Cuerpo del webhook {@code POST /api/integrations/pos/sales}. */
    public record PosWebhookRequest(
            @Size(max = 60, message = "no puede superar los 60 caracteres") String externalId,
            @Schema(description = "Momento de la venta en el POS del cliente; null = ahora") Instant occurredAt,
            @NotEmpty(message = "cargá al menos un ítem")
            @Size(max = 200, message = "no puede tener más de 200 ítems")
            @Valid List<PosSaleItemRequest> items) {
    }

    /** Faltante de stock informado al POS del cliente. */
    public record ShortageDto(String barcode, String productName, int quantity) {
    }

    /** Respuesta del webhook. */
    public record PosWebhookResponse(String batchRef, int processed, int units, BigDecimal total,
                                     List<String> unknownBarcodes, List<ShortageDto> shortages) {
    }
}
