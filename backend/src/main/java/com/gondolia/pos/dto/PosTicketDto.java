package com.gondolia.pos.dto;

import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSaleStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Datos para imprimir el comprobante de 80 mm (SPEC §15.2). Ticket **no fiscal**: siempre lleva {@code legend}.
 */
public record PosTicketDto(Long saleId, String store, String taxId, String branch, String address, String ticketCode,
                           Instant dateTime, String registerName, String cashierName, String customerName,
                           String customerDoc, List<TicketItem> items, List<TicketPayment> payments,
                           BigDecimal change, BigDecimal subtotal, BigDecimal discountTotal, BigDecimal total,
                           int units, PosSaleStatus status, Instant voidedAt, String voidReason, String legend) {

    public record TicketItem(String name, int quantity, BigDecimal unitPrice, BigDecimal listPrice, String lotNumber,
                             LocalDate expiryDate, BigDecimal discountPct, BigDecimal lineTotal) {
    }

    public record TicketPayment(PaymentMethod method, String label, BigDecimal amount, String reference) {
    }
}
