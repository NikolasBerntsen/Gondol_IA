package com.gondolia.seed;

import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.pos.CashMovementType;
import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.pos.PosSessionStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Registros en memoria que produce la simulación antes de escribirse en la base. Las referencias entre ellos son
 * objetos (no ids): los ids reales se asignan al escribir, en orden de creación, con valores tomados de las
 * secuencias de PostgreSQL.
 */
final class SimModel {

    private SimModel() {
    }

    /** Producto de un comercio con su plantilla y sus ids reales. */
    static final class Product {
        final long id;
        final long tenantId;
        final DemoCatalog.Template template;
        final long supplierId;
        final int leadTimeDays;

        Product(long id, long tenantId, DemoCatalog.Template template, long supplierId, int leadTimeDays) {
            this.id = id;
            this.tenantId = tenantId;
            this.template = template;
            this.supplierId = supplierId;
            this.leadTimeDays = leadTimeDays;
        }
    }

    static final class Lot {
        /** Orden de creación global: define el id real (y el desempate FIFO por id). */
        final int seq;
        long id;
        final long tenantId;
        final long branchId;
        final Product product;
        final Lot origin;
        final Long supplierId;
        final String lotNumber;
        final LocalDate expiryDate;
        final int initialQuantity;
        int quantity;
        final BigDecimal costPrice;
        final Instant receivedAt;
        /** Momento desde el que el lote está en la sucursal (una transferencia llega después de su received_at). */
        final Instant availableAt;
        final MovementSource source;
        final Long createdBy;
        BigDecimal discountPct;
        Instant discountStartedAt;
        boolean recalled;
        boolean discarded;
        /** Día en que el personal descarta el lote si vence con stock ({@code null} = nunca, queda pendiente). */
        LocalDate discardOn;
        MovementType lastMovementType = MovementType.ENTRY;
        Instant updatedAt;

        Lot(int seq, long tenantId, long branchId, Product product, Lot origin, Long supplierId, String lotNumber,
            LocalDate expiryDate, int quantity, BigDecimal costPrice, Instant receivedAt, Instant availableAt,
            MovementSource source, Long createdBy) {
            this.seq = seq;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.product = product;
            this.origin = origin;
            this.supplierId = supplierId;
            this.lotNumber = lotNumber;
            this.expiryDate = expiryDate;
            this.initialQuantity = quantity;
            this.quantity = quantity;
            this.costPrice = costPrice;
            this.receivedAt = receivedAt;
            this.availableAt = availableAt;
            this.source = source;
            this.createdBy = createdBy;
            this.updatedAt = availableAt;
        }

        boolean discountActiveAt(Instant moment) {
            return discountPct != null && discountPct.signum() > 0 && discountStartedAt != null
                    && !discountStartedAt.isAfter(moment);
        }

        LotStatus finalStatus() {
            if (recalled) {
                return LotStatus.RECALLED;
            }
            if (quantity == 0) {
                return lastMovementType == MovementType.WASTE_EXPIRED ? LotStatus.EXPIRED_DISCARDED
                        : LotStatus.DEPLETED;
            }
            return LotStatus.ACTIVE;
        }
    }

    static final class Movement {
        final long tenantId;
        final long branchId;
        final Product product;
        final Lot lot;
        final MovementType type;
        final int quantity;
        final BigDecimal unitPrice;
        final BigDecimal discountPct;
        final BigDecimal totalAmount;
        final MovementSource source;
        final String batchRef;
        final String reason;
        final Long userId;
        final Instant occurredAt;

        Movement(long tenantId, long branchId, Product product, Lot lot, MovementType type, int quantity,
                 BigDecimal unitPrice, BigDecimal discountPct, BigDecimal totalAmount, MovementSource source,
                 String batchRef, String reason, Long userId, Instant occurredAt) {
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.product = product;
            this.lot = lot;
            this.type = type;
            this.quantity = quantity;
            this.unitPrice = unitPrice;
            this.discountPct = discountPct;
            this.totalAmount = totalAmount;
            this.source = source;
            this.batchRef = batchRef;
            this.reason = reason;
            this.userId = userId;
            this.occurredAt = occurredAt;
        }
    }

    /** Porción de una línea de venta tomada de un lote (o faltante si {@code lot == null}). */
    record Take(Lot lot, int quantity, BigDecimal unitPrice, BigDecimal discountPct) {
    }

    static final class Register {
        final int seq;
        long id;
        final long tenantId;
        final long branchId;
        final String name;
        final Instant createdAt;

        Register(int seq, long tenantId, long branchId, String name, Instant createdAt) {
            this.seq = seq;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.name = name;
            this.createdAt = createdAt;
        }
    }

    static final class Session {
        final int seq;
        long id;
        final long tenantId;
        final long branchId;
        final Register register;
        final long openedBy;
        final Instant openedAt;
        final Instant scheduledClose;
        final BigDecimal openingCash;
        PosSessionStatus status = PosSessionStatus.OPEN;
        Long closedBy;
        Instant closedAt;
        BigDecimal expectedCash;
        BigDecimal countedCash;
        BigDecimal cashDifference;
        String closingNote;
        int salesCount;
        BigDecimal salesTotal = BigDecimal.ZERO;
        int voidedCount;
        BigDecimal voidedTotal = BigDecimal.ZERO;
        final List<Sale> sales = new ArrayList<>();
        final List<CashMovement> cashMovements = new ArrayList<>();

        Session(int seq, long tenantId, long branchId, Register register, long openedBy, Instant openedAt,
                Instant scheduledClose, BigDecimal openingCash) {
            this.seq = seq;
            this.tenantId = tenantId;
            this.branchId = branchId;
            this.register = register;
            this.openedBy = openedBy;
            this.openedAt = openedAt;
            this.scheduledClose = scheduledClose;
            this.openingCash = openingCash;
        }
    }

    static final class Sale {
        final int seq;
        long id;
        final Session session;
        final long number;
        final String batchRef;
        final long cashierId;
        final Instant createdAt;
        PosSaleStatus status = PosSaleStatus.COMPLETED;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int units;
        BigDecimal paidTotal = BigDecimal.ZERO;
        BigDecimal changeAmount = BigDecimal.ZERO;
        Instant voidedAt;
        Long voidedBy;
        String voidReason;
        final List<SaleItem> items = new ArrayList<>();
        final List<Payment> payments = new ArrayList<>();

        Sale(int seq, Session session, long number, String batchRef, long cashierId, Instant createdAt) {
            this.seq = seq;
            this.session = session;
            this.number = number;
            this.batchRef = batchRef;
            this.cashierId = cashierId;
            this.createdAt = createdAt;
        }

        BigDecimal cashReceived() {
            return payments.stream().filter(payment -> payment.method == PaymentMethod.CASH)
                    .map(payment -> payment.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    static final class SaleItem {
        final Product product;
        final int quantity;
        final BigDecimal listUnitPrice;
        final BigDecimal lineTotal;
        final List<Take> takes;

        SaleItem(Product product, int quantity, BigDecimal listUnitPrice, BigDecimal lineTotal, List<Take> takes) {
            this.product = product;
            this.quantity = quantity;
            this.listUnitPrice = listUnitPrice;
            this.lineTotal = lineTotal;
            this.takes = takes;
        }

        BigDecimal discountAmount() {
            return listUnitPrice.multiply(BigDecimal.valueOf(quantity)).subtract(lineTotal);
        }
    }

    static final class Payment {
        final PaymentMethod method;
        final BigDecimal amount;
        final String reference;

        Payment(PaymentMethod method, BigDecimal amount, String reference) {
            this.method = method;
            this.amount = amount;
            this.reference = reference;
        }
    }

    static final class CashMovement {
        final CashMovementType type;
        final BigDecimal amount;
        final String reason;
        final long userId;
        final Instant createdAt;

        CashMovement(CashMovementType type, BigDecimal amount, String reason, long userId, Instant createdAt) {
            this.type = type;
            this.amount = amount;
            this.reason = reason;
            this.userId = userId;
            this.createdAt = createdAt;
        }
    }
}
