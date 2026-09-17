package com.gondolia.movements.dto;

import com.gondolia.recall.RecallMatchingService.RecallInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs de transferencias de stock entre sucursales (SPEC §4.2, §6.4). Requieren el módulo
 * {@code MULTI_BRANCH}.
 */
public final class TransferDtos {

    private TransferDtos() {
    }

    public record TransferItemRequest(
            @NotNull(message = "es obligatorio") Long lotId,
            @NotNull(message = "es obligatoria") @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 1_000_000, message = "no puede superar 1.000.000") Integer quantity) {
    }

    public record TransferRequest(
            @NotNull(message = "elegí la sucursal de origen") Long fromBranchId,
            @NotNull(message = "elegí la sucursal de destino") Long toBranchId,
            @NotEmpty(message = "elegí al menos un lote")
            @Size(max = 100, message = "no puede tener más de 100 lotes")
            @Valid List<TransferItemRequest> items,
            @Size(max = 300, message = "no puede superar los 300 caracteres") String note) {
    }

    /** Lote transferido y el lote nuevo creado en la sucursal de destino. */
    public record TransferItemDto(Long lotId, Long destinationLotId, Long productId, String productName,
                                  String barcode, String lotNumber, LocalDate expiryDate, int quantity,
                                  BigDecimal costPrice, boolean quarantined) {
    }

    /** Respuesta de {@code POST /api/tenant/transfers} y de su detalle. */
    public record TransferDto(String batchRef, Long fromBranchId, String fromBranchName, Long toBranchId,
                              String toBranchName, Instant occurredAt, List<TransferItemDto> items,
                              int units, BigDecimal costValue, String note, String userName,
                              List<RecallInfo> recalls) {
    }

    /** Fila del historial de transferencias. */
    public record TransferSummaryDto(String batchRef, Long fromBranchId, String fromBranchName, Long toBranchId,
                                     String toBranchName, Instant occurredAt, int itemsCount, int units,
                                     BigDecimal costValue, String userName, String note) {
    }

    /** Lote disponible para transferir desde una sucursal (vendible, con remanente). */
    public record TransferableLotDto(Long lotId, Long branchId, String branchName, Long productId,
                                     String productName, String barcode, String lotNumber, LocalDate expiryDate,
                                     Long daysLeft, int quantity, Instant receivedAt, int rotationRank,
                                     BigDecimal costPrice, BigDecimal discountPct) {
    }
}
