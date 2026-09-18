package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Estadísticas del POS del alcance de sucursales (SPEC §15.2): por medio de pago, por hora, por cajero, ticket
 * promedio y anulaciones. Solo cuenta ventas vigentes salvo en {@code voidedCount}/{@code voidedTotal}.
 */
public record PosStatsDto(int days, LocalDate from, LocalDate to, int salesCount, BigDecimal salesTotal, int units,
                          BigDecimal averageTicket, int voidedCount, BigDecimal voidedTotal,
                          List<MethodTotal> byMethod, List<HourBucket> byHour, List<CashierTotal> byCashier,
                          List<PosTopProductDto> topProducts) {

    public record MethodTotal(PaymentMethod method, String label, BigDecimal total, int sales, BigDecimal sharePct) {
    }

    /** Hora del día en zona de negocio (0..23). */
    public record HourBucket(int hour, int sales, BigDecimal total) {
    }

    public record CashierTotal(Long userId, String name, int sales, BigDecimal total, BigDecimal averageTicket,
                               int voided) {
    }
}
