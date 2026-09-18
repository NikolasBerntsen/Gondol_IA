package com.gondolia.analytics.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Estadísticas del comercio (SPEC §6.5). Estructura documentada en {@code docs/api-b.md}.
 * Todas las ventas son <strong>netas</strong>: los movimientos {@code SALE_VOID} restan.
 */
public record StatisticsOverview(
        String scope,
        int branchCount,
        int days,
        LocalDate from,
        LocalDate to,
        Sales sales,
        Products products,
        Losses losses,
        Ai ai) {

    /** Un día de ventas netas. */
    public record DayPoint(LocalDate date, long units, BigDecimal amount) {
    }

    /** Una semana de ventas netas ({@code label} = "lun 15/09"). */
    public record WeekPoint(LocalDate weekStart, String label, long units, BigDecimal amount) {
    }

    /** Ventas netas de una categoría. */
    public record CategoryPoint(Long categoryId, String categoryName, long units, BigDecimal amount,
                                BigDecimal sharePct) {
    }

    /** Ventas netas de una sucursal. */
    public record BranchPoint(Long branchId, String branchName, long units, BigDecimal amount, BigDecimal sharePct) {
    }

    /** Ventas netas por origen ({@code MovementSource}). */
    public record SourcePoint(String source, long units, BigDecimal amount) {
    }

    public record Sales(
            long units,
            BigDecimal amount,
            BigDecimal cost,
            BigDecimal margin,
            BigDecimal marginPct,
            BigDecimal avgDailyUnits,
            BigDecimal avgDailyAmount,
            BigDecimal bestDayAmount,
            LocalDate bestDay,
            List<DayPoint> byDay,
            List<WeekPoint> byWeek,
            List<CategoryPoint> byCategory,
            List<BranchPoint> byBranch,
            List<SourcePoint> bySource) {
    }

    /** Un producto en el ranking de ventas. */
    public record ProductPoint(
            Long productId,
            String productName,
            String brand,
            String categoryName,
            long units,
            BigDecimal amount,
            BigDecimal margin,
            String abcClass) {
    }

    /** Clase ABC por facturación (80/15/5 acumulado). */
    public record AbcBucket(String abcClass, long products, long units, BigDecimal amount, BigDecimal sharePct) {
    }

    /** Rotación de un producto en el alcance. */
    public record RotationRow(
            Long productId,
            String productName,
            long units,
            BigDecimal avgDailySales,
            int sellableStock,
            BigDecimal daysOfCover,
            BigDecimal turnoverRatio,
            String pattern) {
    }

    public record Products(
            List<ProductPoint> top,
            List<ProductPoint> bottom,
            List<AbcBucket> abc,
            List<RotationRow> rotation,
            long withoutSales) {
    }

    /** Merma de un mes ({@code month} = "2026-09"). */
    public record MonthPoint(String month, long units, BigDecimal value) {
    }

    /** Merma por motivo ({@code WASTE_EXPIRED} / {@code WASTE_DAMAGED}). */
    public record WasteReason(String type, long units, BigDecimal value) {
    }

    /** Ventas perdidas por faltante de stock (movimientos {@code SALE} sin lote). */
    public record LostSales(long units, BigDecimal estimatedAmount, long events) {
    }

    public record Losses(
            long wasteUnits,
            BigDecimal wasteValue,
            List<MonthPoint> wasteByMonth,
            List<WasteReason> wasteByReason,
            LostSales lostSales,
            long expiredLots,
            long expiredUnits,
            BigDecimal expiredValue,
            BigDecimal expiringRiskValue) {
    }

    /** Ventas recuperadas con descuentos por vencimiento (lotes en liquidación). */
    public record RecoveredSales(long units, BigDecimal amount, BigDecimal costValue, BigDecimal avgDiscountPct,
                                 long lots) {
    }

    public record Recommendations(
            long total,
            long pending,
            long accepted,
            long discarded,
            long expired,
            BigDecimal acceptanceRatePct,
            BigDecimal expectedImpactAccepted) {
    }

    public record Ai(Recommendations recommendations, RecoveredSales recoveredSales, Instant lastRunAt,
                     long productsWithInsights) {
    }
}
