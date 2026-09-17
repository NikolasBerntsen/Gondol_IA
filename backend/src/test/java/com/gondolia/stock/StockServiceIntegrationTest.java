package com.gondolia.stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.events.LotReceivedEvent;
import com.gondolia.common.events.StockChangedEvent;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.stock.StockService.AdjustCommand;
import com.gondolia.stock.StockService.ReceiveLotCommand;
import com.gondolia.stock.StockService.ReceiveLotResult;
import com.gondolia.stock.StockService.SaleCommand;
import com.gondolia.stock.StockService.SaleResult;
import com.gondolia.stock.StockService.TransferCommand;
import com.gondolia.stock.StockService.TransferItem;
import com.gondolia.stock.StockService.TransferResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link StockService} contra PostgreSQL: rotación FIFO/FEFO, un lote por ingreso, descuentos, faltantes, estados de
 * lote, transferencias y aislamiento por sucursal. Cada prueba se revierte.
 */
@Transactional
@RecordApplicationEvents
class StockServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private StockService stockService;
    @Autowired
    private LotRepository lotRepository;
    @Autowired
    private StockMovementRepository movementRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private Clock clock;
    @Autowired
    private ApplicationEvents events;

    private TestData data;
    private long tenant;
    private long centro;
    private long norte;
    private long product;
    private AuthUser admin;
    private LocalDate today;
    private Instant now;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Stock");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Norte", true);
        product = data.product(tenant, data.barcode(), "Yogur frutilla", "100", "200");
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        today = LocalDate.now(clock);
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    // ------------------------------------------------------------------ rotación

    @Test
    void fifoSellsWhatCameInFirstEvenIfANewerLotExpiresSooner() {
        ReceiveLotResult older = receive(centro, product, "L-OLD", today.plusDays(30), 5, daysAgo(10));
        ReceiveLotResult newer = receive(centro, product, "L-NEW", today.plusDays(5), 5, daysAgo(2));

        assertThat(older.rotationWarning()).isNull();
        assertThat(newer.rotationWarning()).isEqualTo(StockService.ROTATION_WARNING);
        assertThat(stockService.lotsInRotationOrder(tenant, centro, product)).extracting(Lot::getId)
                .containsExactly(older.lot().getId(), newer.lot().getId());

        SaleResult sale = sell(centro, product, 7, null);

        assertThat(sale.movements()).extracting(StockMovement::getLotId, StockMovement::getQuantity)
                .containsExactly(tuple(older.lot().getId(), 5), tuple(newer.lot().getId(), 2));
        assertThat(sale.shortageQuantity()).isZero();
        assertThat(lot(older.lot().getId())).extracting(Lot::getQuantity, Lot::getStatus)
                .containsExactly(0, LotStatus.DEPLETED);
        assertThat(lot(newer.lot().getId())).extracting(Lot::getQuantity, Lot::getStatus)
                .containsExactly(3, LotStatus.ACTIVE);
    }

    @Test
    void fefoSellsWhatExpiresFirstWithLotsWithoutExpiryLast() {
        data.rotation(tenant, "FEFO");
        assertThat(stockService.rotationFor(tenant)).isEqualTo(StockRotation.FEFO);
        ReceiveLotResult noExpiry = receive(centro, product, "L-NULL", null, 4, daysAgo(20));
        ReceiveLotResult older = receive(centro, product, "L-OLD", today.plusDays(30), 5, daysAgo(10));
        ReceiveLotResult newer = receive(centro, product, "L-NEW", today.plusDays(5), 5, daysAgo(2));

        assertThat(newer.rotationWarning()).as("el aviso es solo para FIFO").isNull();
        assertThat(stockService.lotsInRotationOrder(tenant, centro, product)).extracting(Lot::getId)
                .containsExactly(newer.lot().getId(), older.lot().getId(), noExpiry.lot().getId());

        SaleResult sale = sell(centro, product, 6, null);

        assertThat(sale.movements()).extracting(StockMovement::getLotId, StockMovement::getQuantity)
                .containsExactly(tuple(newer.lot().getId(), 5), tuple(older.lot().getId(), 1));
    }

    @Test
    void rotationWarningOnlyWhenOlderSellableStockExpiresLater() {
        receive(centro, product, "A", today.plusDays(10), 5, daysAgo(5));
        assertThat(receive(centro, product, "B", today.plusDays(20), 5, daysAgo(1)).rotationWarning())
                .as("vence después que el lote más viejo").isNull();
        assertThat(receive(norte, product, "C", today.plusDays(2), 5, daysAgo(0)).rotationWarning())
                .as("otra sucursal sin stock previo").isNull();
        assertThat(receive(centro, product, "D", today.plusDays(8), 5, daysAgo(0)).rotationWarning())
                .as("vence antes que A y B, que ingresaron antes").isEqualTo(StockService.ROTATION_WARNING);
    }

    @Test
    void expiredLotsAreNeverSold() {
        ReceiveLotResult expired = receive(centro, product, "VENCIDO", today.minusDays(1), 10, daysAgo(40));
        ReceiveLotResult valid = receive(centro, product, "OK", today.plusDays(10), 2, daysAgo(1));

        assertThat(stockService.sellableStock(tenant, centro, product)).isEqualTo(2);
        assertThat(stockService.lotsInRotationOrder(tenant, centro, product)).extracting(Lot::getId)
                .containsExactly(valid.lot().getId());

        SaleResult sale = sell(centro, product, 5, null);

        assertThat(sale.shortageQuantity()).isEqualTo(3);
        assertThat(lot(expired.lot().getId())).extracting(Lot::getQuantity, Lot::getStatus)
                .containsExactly(10, LotStatus.ACTIVE);
        assertThat(lot(valid.lot().getId()).getStatus()).isEqualTo(LotStatus.DEPLETED);
    }

    @Test
    void historicalSaleUsesExpiryAtTheSaleDate() {
        ReceiveLotResult lot = receive(centro, product, "HIST", today.minusDays(3), 10, daysAgo(30));

        SaleResult sale = stockService.registerSale(new SaleCommand(tenant, centro, product, 4, null,
                now.minus(Duration.ofDays(10)), MovementSource.CSV, admin.id(), null));

        assertThat(sale.shortageQuantity()).isZero();
        assertThat(sale.movements()).singleElement().extracting(StockMovement::getLotId)
                .isEqualTo(lot.lot().getId());
    }

    // ------------------------------------------------------------------ ingresos

    @Test
    void eachIntakeCreatesItsOwnLotEvenWithTheSameLotNumberAndExpiry() {
        long supplier = data.supplier(tenant, "Proveedor");
        ReceiveLotResult first = stockService.receiveLot(new ReceiveLotCommand(tenant, centro, product, " l-2409/a ",
                today.plusDays(15), 6, new BigDecimal("95.5"), supplier, null, MovementSource.SCAN, admin.id(), null));
        ReceiveLotResult second = stockService.receiveLot(new ReceiveLotCommand(tenant, centro, product, "L2409A",
                today.plusDays(15), 4, null, null, null, null, admin.id(), "Reposición"));

        assertThat(first.lot().getId()).isNotEqualTo(second.lot().getId());
        assertThat(List.of(lot(first.lot().getId()), lot(second.lot().getId())))
                .extracting(Lot::getLotNumber, Lot::getLotNumberNormalized, Lot::getInitialQuantity, Lot::getSource)
                .containsExactly(tuple("l-2409/a", "L2409A", 6, MovementSource.SCAN),
                        tuple("L2409A", "L2409A", 4, MovementSource.MANUAL));
        assertThat(first.movement()).extracting(StockMovement::getType, StockMovement::getQuantity,
                        StockMovement::getUnitPrice, StockMovement::getTotalAmount)
                .containsExactly(MovementType.ENTRY, 6, new BigDecimal("95.50"), new BigDecimal("573.00"));
        assertThat(second.movement().getUnitPrice()).as("sin costo propio usa el del producto")
                .isEqualByComparingTo("100");
        assertThat(stockService.sellableStock(tenant, centro, product)).isEqualTo(10);
        assertThat(events.stream(LotReceivedEvent.class)).contains(
                new LotReceivedEvent(tenant, centro, product, first.lot().getId()));
        assertThat(events.stream(StockChangedEvent.class)).contains(new StockChangedEvent(tenant, centro, product));
    }

    @Test
    void writesValidateTenantOwnershipAndInput() {
        long otherTenant = data.tenant("Ajeno");
        long otherBranch = data.branch(otherTenant, "Principal", true);
        long otherProduct = data.product(otherTenant, data.barcode(), "Ajeno", "1", "2");
        long inactive = data.branch(tenant, "Cerrada", false);
        ReceiveLotResult ownLot = receive(centro, product, "X", today.plusDays(5), 3, daysAgo(1));

        assertApiError(() -> receive(centro, otherProduct, "X", null, 1, null), 404, "NOT_FOUND");
        assertApiError(() -> receive(otherBranch, product, "X", null, 1, null), 404, "NOT_FOUND");
        assertApiError(() -> receive(inactive, product, "X", null, 1, null), 403, "BRANCH_FORBIDDEN");
        assertApiError(() -> receive(centro, product, "X", null, 0, null), 400, "VALIDATION_ERROR");
        assertApiError(() -> receive(centro, product, "L".repeat(Lot.MAX_LOT_NUMBER_LENGTH + 1), null, 1, null), 400,
                "VALIDATION_ERROR");
        assertThat(receive(centro, product, "  " + "L".repeat(Lot.MAX_LOT_NUMBER_LENGTH) + " ", null, 1, null).lot()
                .getLotNumber()).hasSize(Lot.MAX_LOT_NUMBER_LENGTH);
        assertApiError(() -> stockService.adjust(new AdjustCommand(otherTenant, ownLot.lot().getId(),
                MovementType.ADJUSTMENT_OUT, 1, null, null, null)), 404, "NOT_FOUND");
        jdbc.update("update products set active = false where id = ?", product);
        entityManager.clear();
        assertApiError(() -> receive(centro, product, "X", null, 1, null), 409, "CONFLICT");
    }

    @Test
    void rejectedWritesDoNotMarkTheCallerTransactionRollbackOnly() {
        ReceiveLotResult lot = receive(centro, product, "RB", today.plusDays(5), 2, daysAgo(1));

        assertApiError(() -> receive(centro, product, "X", null, 0, null), 400, "VALIDATION_ERROR");
        assertApiError(() -> adjust(lot.lot().getId(), MovementType.ADJUSTMENT_OUT, 3), 409, "INSUFFICIENT_STOCK");
        assertApiError(() -> transfer(centro, centro, lot.lot().getId(), 1), 400, "VALIDATION_ERROR");

        EntityManagerHolder holder =
                (EntityManagerHolder) TransactionSynchronizationManager.getResource(entityManagerFactory);
        assertThat(holder.getEntityManager().getTransaction().getRollbackOnly())
                .as("un proceso por lotes puede capturar el rechazo y seguir").isFalse();
        assertThat(sell(centro, product, 1, null).shortageQuantity()).isZero();
    }

    // ------------------------------------------------------------------ ventas

    @Test
    void lotDiscountAppliesToTheUnitsSoldFromThatLot() {
        ReceiveLotResult discounted = receive(centro, product, "DESC", today.plusDays(4), 2, daysAgo(5));
        ReceiveLotResult regular = receive(centro, product, "REG", today.plusDays(20), 5, daysAgo(1));
        jdbc.update("update lots set discount_pct = 25 where id = ?", discounted.lot().getId());
        entityManager.clear();

        SaleResult sale = sell(centro, product, 3, null);

        assertThat(sale.movements())
                .extracting(StockMovement::getLotId, StockMovement::getQuantity, StockMovement::getUnitPrice,
                        StockMovement::getDiscountPct, StockMovement::getTotalAmount)
                .containsExactly(
                        tuple(discounted.lot().getId(), 2, new BigDecimal("150.00"), new BigDecimal("25.00"),
                                new BigDecimal("300.00")),
                        tuple(regular.lot().getId(), 1, new BigDecimal("200.00"), null, new BigDecimal("200.00")));
        assertThat(sale.totalAmount()).isEqualByComparingTo("500");
        assertThat(sale.movements()).extracting(StockMovement::getBatchRef).allMatch(ref -> ref.startsWith("S-"))
                .containsOnly(sale.movements().getFirst().getBatchRef());

        SaleResult explicitPrice = sell(centro, product, 1, new BigDecimal("180"));
        assertThat(explicitPrice.totalAmount()).isEqualByComparingTo("180");
    }

    @Test
    void shortageIsRecordedWithoutLotAndOpensASingleAlertPerBranchAndProduct() {
        receive(centro, product, "POCO", today.plusDays(10), 1, daysAgo(1));

        SaleResult first = stockService.registerSale(new SaleCommand(tenant, centro, product, 4, null, null,
                MovementSource.POS, null, "TICKET-1"));
        SaleResult second = sell(centro, product, 2, null);

        assertThat(first.shortageQuantity()).isEqualTo(3);
        assertThat(first.movements()).extracting(StockMovement::getLotId, StockMovement::getQuantity,
                        StockMovement::getBatchRef)
                .containsExactly(tuple(first.movements().getFirst().getLotId(), 1, "TICKET-1"),
                        tuple(null, 3, "TICKET-1"));
        assertThat(first.totalAmount()).isEqualByComparingTo("800");
        assertThat(second.shortageQuantity()).isEqualTo(2);
        List<Map<String, Object>> alerts = jdbc.queryForList(
                "select branch_id, severity, status, product_id from alerts where tenant_id = ? and type = ?",
                tenant, "SALE_WITHOUT_STOCK");
        assertThat(alerts).singleElement().satisfies(alert -> {
            assertThat(alert.get("branch_id")).isEqualTo(centro);
            assertThat(alert.get("status")).isEqualTo("OPEN");
            assertThat(alert.get("product_id")).isEqualTo(product);
        });
    }

    // ------------------------------------------------------------------ ajustes y estados

    @Test
    void lotStatusTransitions() {
        long wasteLot = receive(centro, product, "W", today.plusDays(3), 4, daysAgo(3)).lot().getId();
        long outLot = receive(centro, product, "O", today.plusDays(9), 2, daysAgo(2)).lot().getId();
        long recalledLot = data.lot(tenant, centro, product, "R", "R", today.plusDays(9), 3, "RECALLED", 1);

        StockMovement waste = adjust(wasteLot, MovementType.WASTE_EXPIRED, 4);
        adjust(outLot, MovementType.ADJUSTMENT_OUT, 2);
        adjust(recalledLot, MovementType.RECALL_REMOVAL, 3);

        assertThat(lot(wasteLot).getStatus()).isEqualTo(LotStatus.EXPIRED_DISCARDED);
        assertThat(lot(outLot).getStatus()).isEqualTo(LotStatus.DEPLETED);
        assertThat(lot(recalledLot)).extracting(Lot::getQuantity, Lot::getStatus).containsExactly(0, LotStatus.RECALLED);
        assertThat(waste.getBatchRef()).startsWith("A-");
        assertThat(waste.getTotalAmount()).isEqualByComparingTo("400");

        adjust(outLot, MovementType.ADJUSTMENT_IN, 5);
        assertThat(lot(outLot)).extracting(Lot::getQuantity, Lot::getStatus).containsExactly(5, LotStatus.ACTIVE);
        assertThat(stockService.sellableStock(tenant, centro, product)).isEqualTo(5);
    }

    @Test
    void adjustmentsNeverLeaveNegativeStock() {
        long lotId = receive(centro, product, "N", today.plusDays(3), 2, daysAgo(1)).lot().getId();

        assertApiError(() -> adjust(lotId, MovementType.WASTE_DAMAGED, 3), 409, "INSUFFICIENT_STOCK");
        assertApiError(() -> adjust(lotId, MovementType.SALE, 1), 400, "VALIDATION_ERROR");
        assertApiError(() -> adjust(lotId, MovementType.ADJUSTMENT_OUT, 0), 400, "VALIDATION_ERROR");
        assertThat(lot(lotId).getQuantity()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ transferencias

    @Test
    void transferKeepsLotDataAndOriginalReceivedAt() {
        long supplier = data.supplier(tenant, "Lácteos del Sur");
        ReceiveLotResult origin = stockService.receiveLot(new ReceiveLotCommand(tenant, centro, product, "LT-77",
                today.plusDays(12), 10, new BigDecimal("120"), supplier, daysAgo(8), MovementSource.MANUAL,
                admin.id(), null));
        ReceiveLotResult newerInNorte = receive(norte, product, "N-1", today.plusDays(40), 3, daysAgo(1));

        TransferResult result = stockService.transfer(new TransferCommand(tenant, centro, norte,
                List.of(new TransferItem(origin.lot().getId(), 4)), "Reparto semanal", admin.id()));

        assertThat(result.batchRef()).startsWith("T-");
        assertThat(result.recallMatches()).isEmpty();
        assertThat(lot(origin.lot().getId()).getQuantity()).isEqualTo(6);
        Lot destination = lot(result.destinationLots().getFirst().getId());
        assertThat(destination)
                .extracting(Lot::getBranchId, Lot::getLotNumber, Lot::getLotNumberNormalized, Lot::getExpiryDate,
                        Lot::getCostPrice, Lot::getSupplierId, Lot::getOriginLotId, Lot::getInitialQuantity,
                        Lot::getQuantity, Lot::getStatus)
                .containsExactly(norte, "LT-77", "LT77", today.plusDays(12), new BigDecimal("120.00"), supplier,
                        origin.lot().getId(), 4, 4, LotStatus.ACTIVE);
        assertThat(destination.getReceivedAt()).isEqualTo(origin.lot().getReceivedAt());

        List<StockMovement> movements = movementRepository.findByTenantIdAndBatchRefOrderByIdAsc(tenant,
                result.batchRef());
        assertThat(movements).extracting(StockMovement::getType, StockMovement::getBranchId, StockMovement::getLotId,
                        StockMovement::getQuantity, StockMovement::getReason)
                .containsExactly(
                        tuple(MovementType.TRANSFER_OUT, centro, origin.lot().getId(), 4, "Reparto semanal"),
                        tuple(MovementType.TRANSFER_IN, norte, destination.getId(), 4, "Reparto semanal"));
        assertThat(stockService.lotsInRotationOrder(tenant, norte, product)).extracting(Lot::getId)
                .as("conserva la antigüedad para FIFO").containsExactly(destination.getId(), newerInNorte.lot().getId());
        assertThat(events.stream(StockChangedEvent.class)).contains(new StockChangedEvent(tenant, centro, product),
                new StockChangedEvent(tenant, norte, product));
    }

    @Test
    void transferRejectsRecalledExpiredAndInvalidLots() {
        long recalled = data.lot(tenant, centro, product, "R1", "R1", today.plusDays(5), 5, "RECALLED", 3);
        long expired = data.lot(tenant, centro, product, "V1", "V1", today.minusDays(1), 5, "ACTIVE", 30);
        long inNorte = data.lot(tenant, norte, product, "N1", "N1", today.plusDays(5), 5, "ACTIVE", 3);
        long valid = data.lot(tenant, centro, product, "OK", "OK", today.plusDays(5), 5, "ACTIVE", 3);
        long closed = data.branch(tenant, "Cerrada", false);

        assertApiError(() -> transfer(centro, norte, recalled, 1), 409, "LOT_NOT_TRANSFERABLE");
        assertApiError(() -> transfer(centro, norte, expired, 1), 409, "LOT_NOT_TRANSFERABLE");
        assertApiError(() -> transfer(centro, norte, inNorte, 1), 400, "VALIDATION_ERROR");
        assertApiError(() -> transfer(centro, norte, valid, 6), 409, "INSUFFICIENT_STOCK");
        assertApiError(() -> transfer(centro, centro, valid, 1), 400, "VALIDATION_ERROR");
        assertApiError(() -> transfer(centro, closed, valid, 1), 403, "BRANCH_FORBIDDEN");
        assertApiError(() -> stockService.transfer(new TransferCommand(tenant, centro, norte,
                List.of(new TransferItem(valid, 1), new TransferItem(valid, 2)), null, null)), 400, "VALIDATION_ERROR");
        assertThat(lot(valid).getQuantity()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ stock por sucursal

    @Test
    void stockIsIsolatedPerBranch() {
        long otherProduct = data.product(tenant, data.barcode(), "Galletitas", "50", "90");
        receive(centro, product, "C1", today.plusDays(10), 10, daysAgo(2));
        receive(norte, product, "N1", today.plusDays(10), 4, daysAgo(2));
        receive(centro, otherProduct, "C2", null, 3, daysAgo(2));
        data.lot(tenant, norte, otherProduct, "Q", "Q", today.plusDays(10), 7, "RECALLED", 1);

        SaleResult sale = sell(norte, product, 4, null);

        assertThat(sale.shortageQuantity()).isZero();
        assertThat(stockService.sellableStock(tenant, centro, product)).isEqualTo(10);
        assertThat(stockService.sellableStock(tenant, norte, product)).isZero();
        assertThat(stockService.sellableStockByBranchAndProduct(tenant, List.of(centro, norte)))
                .isEqualTo(Map.of(centro, Map.of(product, 10, otherProduct, 3)));
        assertThat(stockService.sellableStockByProduct(tenant, List.of(centro, norte)))
                .isEqualTo(Map.of(product, 10, otherProduct, 3));
        assertThat(stockService.sellableStockByProduct(tenant, List.of(norte))).isEmpty();
        assertThat(stockService.sellableStockByProduct(tenant, List.of())).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private ReceiveLotResult receive(long branchId, long productId, String lotNumber, LocalDate expiry, int quantity,
                                     Instant receivedAt) {
        return stockService.receiveLot(new ReceiveLotCommand(tenant, branchId, productId, lotNumber, expiry, quantity,
                null, null, receivedAt, MovementSource.MANUAL, admin.id(), null));
    }

    private SaleResult sell(long branchId, long productId, int quantity, BigDecimal unitPrice) {
        return stockService.registerSale(new SaleCommand(tenant, branchId, productId, quantity, unitPrice, null,
                MovementSource.MANUAL, admin.id(), null));
    }

    private StockMovement adjust(long lotId, MovementType type, int quantity) {
        return stockService.adjust(new AdjustCommand(tenant, lotId, type, quantity, "Prueba", MovementSource.MANUAL,
                admin.id()));
    }

    private TransferResult transfer(long from, long to, long lotId, int quantity) {
        return stockService.transfer(new TransferCommand(tenant, from, to, List.of(new TransferItem(lotId, quantity)),
                null, admin.id()));
    }

    private Lot lot(long id) {
        entityManager.flush();
        entityManager.clear();
        return lotRepository.findById(id).orElseThrow();
    }

    private Instant daysAgo(int days) {
        return now.minus(Duration.ofDays(days));
    }

    private static void assertApiError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }
}
