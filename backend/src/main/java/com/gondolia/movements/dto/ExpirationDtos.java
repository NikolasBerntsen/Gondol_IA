package com.gondolia.movements.dto;

import com.gondolia.domain.inventory.LotStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * DTOs de vencimientos: listado por bucket, resumen y descarte (SPEC §4.2, §6.4).
 */
public final class ExpirationDtos {

    private ExpirationDtos() {
    }

    /** Buckets de vencimiento de SPEC §4.2 más {@code ALL} para el filtro. */
    public enum ExpirationBucket {
        ALL, EXPIRED, CRITICAL, WARNING, UPCOMING
    }

    /** Fila del listado de vencimientos. */
    public record ExpirationRowDto(Long lotId, Long branchId, String branchName, Long productId, String productName,
                                   String barcode, String categoryName, String lotNumber, LocalDate expiryDate,
                                   long daysLeft, int quantity, Instant receivedAt, Integer rotationRank,
                                   BigDecimal costValue, BigDecimal saleValue, ExpirationBucket bucket,
                                   BigDecimal discountPct, LotStatus status) {
    }

    /** Totales de un bucket. */
    public record ExpirationBucketTotals(long lots, long units, BigDecimal costValue, BigDecimal saleValue) {

        public static ExpirationBucketTotals empty() {
            return new ExpirationBucketTotals(0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    /** Resumen por bucket ({@code GET /api/tenant/expirations/summary}). */
    public record ExpirationSummaryDto(ExpirationBucketTotals expired, ExpirationBucketTotals critical,
                                       ExpirationBucketTotals warning, ExpirationBucketTotals upcoming,
                                       int criticalDays, int warningDays, int upcomingDays, LocalDate asOf) {
    }

    /** Descarte de un lote vencido o dañado; {@code quantity} null = todo el remanente. */
    public record DiscardRequest(
            @Min(value = 1, message = "tiene que ser al menos 1")
            @Max(value = 1_000_000, message = "no puede superar 1.000.000") Integer quantity,
            @Size(max = 300, message = "no puede superar los 300 caracteres") String reason) {
    }

    /** Resultado del descarte masivo de vencidos. */
    public record BulkDiscardResultDto(int lots, int units, BigDecimal costValue, int failed) {
    }

    /** Sucursal opcional para el descarte masivo (null = todas las accesibles del alcance). */
    public record BulkDiscardRequest(Long branchId,
                                     @Size(max = 300, message = "no puede superar los 300 caracteres")
                                     String reason) {
    }
}
