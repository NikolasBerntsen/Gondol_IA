package com.gondolia.pos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.pos.CashMovementType;
import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.pos.PosSession;
import com.gondolia.domain.pos.PosSessionRepository;
import com.gondolia.domain.pos.PosSessionStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.pos.dto.CashMovementRequest;
import com.gondolia.pos.dto.CloseSessionRequest;
import com.gondolia.pos.dto.OpenSessionRequest;
import com.gondolia.pos.dto.PosProductDto;
import com.gondolia.pos.dto.PosSaleDto;
import com.gondolia.pos.dto.PosSaleRequest;
import com.gondolia.pos.dto.PosSessionReportDto;
import com.gondolia.pos.dto.PosTicketDto;
import com.gondolia.pos.dto.VoidSaleRequest;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * POS GondolIA contra PostgreSQL (SPEC §15): cobro con rotación y descuentos por lote, faltantes, numeración por
 * sucursal, arqueo del turno, anulación y aislamiento por comercio y por sucursal. Cada prueba se revierte.
 */
@Transactional
class PosServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private PosSaleService saleService;
    @Autowired
    private PosSessionService sessionService;
    @Autowired
    private PosRegisterService registerService;
    @Autowired
    private PosCatalogService catalogService;
    @Autowired
    private PosSessionRepository sessionRepository;
    @Autowired
    private LotRepository lotRepository;
    @Autowired
    private StockMovementRepository movementRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private long tenantId;
    private long centro;
    private long norte;
    private long registerCentro;
    private long registerNorte;
    private long leche;
    private long yogur;
    private AuthUser admin;
    private AuthUser cashier;
    private AuthUser otherCashier;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenantId = data.tenant("Comercio POS");
        centro = data.branch(tenantId, "Sucursal Centro", true);
        norte = data.branch(tenantId, "Sucursal Norte", true);
        admin = data.user(tenantId, Role.TENANT_ADMIN, true);
        cashier = data.user(tenantId, Role.TENANT_CASHIER, true, centro);
        otherCashier = data.user(tenantId, Role.TENANT_CASHIER, true, centro);

        leche = data.product(tenantId, data.barcode(), "Leche entera La Pradera 1 L", "900", "1400");
        yogur = data.product(tenantId, data.barcode(), "Yogur bebible Vaquita 1 L", "1400", "2100");
        // Dos lotes de yogur: el más nuevo tiene descuento activo, así que sale primero (SPEC §4.2).
        data.lot(tenantId, centro, leche, "LP2409A", "LP2409A", LocalDate.now().plusDays(20), 10, "ACTIVE", 10);
        data.lot(tenantId, centro, yogur, "YV0930", "YV0930", LocalDate.now().plusDays(30), 6, "ACTIVE", 9);
        long descuento = data.lot(tenantId, centro, yogur, "YV0925", "YV0925", LocalDate.now().plusDays(8), 4,
                "ACTIVE", 2);
        jdbc.update("update lots set discount_pct = 20, discount_started_at = now() where id = ?", descuento);

        registerCentro = register(tenantId, centro, "Caja 1");
        registerNorte = register(tenantId, norte, "Caja Norte");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ cobro

    @Test
    void chargesConsumingDiscountedLotFirstAndNumbersTicketPerBranch() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, new BigDecimal("20000")));

        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(yogur, 6)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("15000"), null)),
                "Marta Suárez", null, false));

        // 4 u. con -20% (1680) + 2 u. a precio de lista (2100) = 10920
        assertThat(sale.total()).isEqualByComparingTo("10920.00");
        assertThat(sale.subtotal()).isEqualByComparingTo("12600.00");
        assertThat(sale.discountTotal()).isEqualByComparingTo("1680.00");
        assertThat(sale.changeAmount()).isEqualByComparingTo("4080.00");
        assertThat(sale.ticketCode()).isEqualTo(String.format("%04d-%08d", centro, 1L));
        assertThat(sale.batchRef()).startsWith("P-");
        assertThat(sale.status()).isEqualTo(PosSaleStatus.COMPLETED);
        assertThat(sale.items()).singleElement().satisfies(item -> {
            assertThat(item.quantity()).isEqualTo(6);
            assertThat(item.shortageQuantity()).isZero();
            assertThat(item.lots()).extracting(lot -> lot.lotNumber()).containsExactly("YV0925", "YV0930");
            assertThat(item.lots().getFirst().discountPct()).isEqualByComparingTo("20.00");
        });
        assertThat(sale.payments()).singleElement().satisfies(payment ->
                assertThat(payment.label()).isEqualTo("Efectivo"));

        List<StockMovement> movements =
                movementRepository.findByTenantIdAndBatchRefOrderByIdAsc(tenantId, sale.batchRef());
        assertThat(movements).hasSize(2)
                .allSatisfy(movement -> {
                    assertThat(movement.getType()).isEqualTo(MovementType.SALE);
                    assertThat(movement.getSource()).isEqualTo(MovementSource.POS_GONDOLIA);
                    assertThat(movement.getBranchId()).isEqualTo(centro);
                });

        PosSession stored = sessionRepository.findById(session.id()).orElseThrow();
        assertThat(stored.getSalesCount()).isEqualTo(1);
        assertThat(stored.getSalesTotal()).isEqualByComparingTo("10920.00");

        // La numeración es por sucursal: la primera venta de Norte vuelve a arrancar en 1.
        PosSaleDto next = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.DEBIT, new BigDecimal("1400"), "0001")),
                null, null, false));
        assertThat(next.ticketCode()).isEqualTo(String.format("%04d-%08d", centro, 2L));
    }

    @Test
    void rejectsSaleWithoutEnoughStockAndDetailsTheShortage() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));

        assertThatThrownBy(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 40)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("60000"), null)),
                null, null, false)))
                .isInstanceOfSatisfying(PosInsufficientStockException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(ex.getDetails()).singleElement().satisfies(detail -> {
                        assertThat(detail.productId()).isEqualTo(leche);
                        assertThat(detail.requested()).isEqualTo(40);
                        assertThat(detail.available()).isEqualTo(10);
                    });
                });

        PosSaleDto forced = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 12)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("20000"), null)),
                null, null, true));
        assertThat(forced.hasShortage()).isTrue();
        assertThat(forced.items()).singleElement().satisfies(item ->
                assertThat(item.shortageQuantity()).isEqualTo(2));
        assertThat(forced.total()).isEqualByComparingTo("16800.00");
    }

    @Test
    void blocksProductsQuarantinedByRecallAndValidatesPayments() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));

        assertApiError(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("100"), null)),
                null, null, false)), 400, "PAYMENT_INSUFFICIENT");
        // El mostrador muestra el mensaje tal cual: el importe va en formato de pesos, como el resto de la interfaz.
        assertThatThrownBy(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("100"), null)),
                null, null, false)))
                .hasMessage("Los pagos no cubren el total: faltan $ 1.300,00.");

        assertApiError(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.DEBIT, new BigDecimal("5000"), null)),
                null, null, false)), 400, "CHANGE_NOT_ALLOWED");

        jdbc.update("update lots set status = 'RECALLED' where product_id = ? and branch_id = ?", leche, centro);
        assertApiError(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1400"), null)),
                null, null, false)), 409, "PRODUCT_RECALLED");
    }

    @Test
    void cartPriceTiersMatchWhatTheCoreChargesAcrossLots() {
        as(cashier, centro);
        // Yogur: 4 u. del lote en liquidación (-20 %, $ 1.680) y 6 u. a precio de lista ($ 2.100), en ese orden.
        PosProductDto product = catalogService.search(tenantId, centro, "yogur", null, 20).getFirst();
        assertThat(product.priceTiers()).hasSize(2);
        assertThat(product.priceTiers().getFirst()).satisfies(tier -> {
            assertThat(tier.quantity()).isEqualTo(4);
            assertThat(tier.discountPct()).isEqualByComparingTo("20");
            assertThat(tier.unitPrice()).isEqualByComparingTo("1680.00");
        });
        assertThat(product.priceTiers().get(1)).satisfies(tier -> {
            assertThat(tier.quantity()).isEqualTo(6);
            assertThat(tier.discountPct()).isNull();
            assertThat(tier.unitPrice()).isEqualByComparingTo("2100.00");
        });

        // 5 u. cruzan de lote: 4 x 1680 + 1 x 2100 = 8820. Pagar exactamente eso con débito tiene que alcanzar.
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));
        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(yogur, 5)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.DEBIT, new BigDecimal("8820"), null)),
                null, null, false));
        assertThat(sale.total()).isEqualByComparingTo("8820.00");
        assertThat(sale.changeAmount()).isEqualByComparingTo("0.00");

        // El ticket muestra un renglón por precio cobrado, con el lote y el descuento solo donde corresponde.
        PosTicketDto ticket = saleService.ticket(cashier, sale.id());
        assertThat(ticket.items()).hasSize(2);
        assertThat(ticket.items().getFirst()).satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(4);
            assertThat(line.unitPrice()).isEqualByComparingTo("1680.00");
            assertThat(line.listPrice()).isEqualByComparingTo("2100.00");
            assertThat(line.lotNumber()).isEqualTo("YV0925");
            assertThat(line.discountPct()).isEqualByComparingTo("20");
            assertThat(line.lineTotal()).isEqualByComparingTo("6720.00");
        });
        assertThat(ticket.items().get(1)).satisfies(line -> {
            assertThat(line.quantity()).isEqualTo(1);
            assertThat(line.unitPrice()).isEqualByComparingTo("2100.00");
            assertThat(line.listPrice()).isNull();
            assertThat(line.discountPct()).isNull();
            assertThat(line.lineTotal()).isEqualByComparingTo("2100.00");
        });
        assertThat(ticket.total()).isEqualByComparingTo("8820.00");

        // Al abrir el cobro el mostrador vuelve a pedir el carrito: el lote en liquidación ya se agotó.
        PosProductDto fresh = catalogService.byIds(tenantId, centro, List.of(yogur)).getFirst();
        assertThat(fresh.sellableStock()).isEqualTo(5);
        assertThat(fresh.priceTiers()).singleElement().satisfies(tier -> {
            assertThat(tier.quantity()).isEqualTo(5);
            assertThat(tier.unitPrice()).isEqualByComparingTo("2100.00");
        });
    }

    @Test
    void productUnderAnActiveRecallCannotBeSoldWithoutAVerifiedLot() {
        String code = data.barcode();
        long sopa = data.product(tenantId, code, "Sopa de tomate La Huerta 340 g", "900", "1500");
        // El lote retirado ya se resolvió: quedó en 0 y RECALLED, pero el recall sigue publicado.
        data.lot(tenantId, centro, sopa, "L2409A", "L2409A", LocalDate.now().plusYears(1), 0, "RECALLED", 30);
        long recall = data.recall(code, false, null, null, "PUBLISHED", "L2409A");

        as(cashier, centro);
        PosProductDto product = catalogService.lookup(tenantId, centro, code);
        assertThat(product.hasRecalledStock()).isFalse();
        assertThat(product.outOfStock()).isTrue();
        assertThat(product.activeRecall()).isNotNull();
        assertThat(product.activeRecall().announcementId()).isEqualTo(recall);
        assertThat(product.activeRecall().allLots()).isFalse();
        assertThat(product.activeRecall().lotNumbers()).containsExactly("L2409A");

        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));
        // "Vender igual" no corre: una lata sin lote registrado puede ser del lote retirado.
        assertApiError(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(sopa, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1500"), null)),
                null, null, true)), 409, "PRODUCT_RECALLED");

        // Otro lote cargado (ya pasó el chequeo de recall) se vende, pero no por encima de lo cargado.
        data.lot(tenantId, centro, sopa, "L2501C", "L2501C", LocalDate.now().plusYears(1), 2, "ACTIVE", 1);
        assertApiError(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(sopa, 3)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("4500"), null)),
                null, null, true)), 409, "PRODUCT_RECALLED");
        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(sopa, 2)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("3000"), null)),
                null, null, false));
        assertThat(sale.total()).isEqualByComparingTo("3000.00");

        // Archivado el recall, el faltante vuelve a estar permitido.
        jdbc.update("update announcements set status = 'ARCHIVED' where id = ?", recall);
        assertThat(catalogService.lookup(tenantId, centro, code).activeRecall()).isNull();
        PosSaleDto shortage = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(sopa, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1500"), null)),
                null, null, true));
        assertThat(shortage.hasShortage()).isTrue();
    }

    // ------------------------------------------------------------------ anulación

    @Test
    void voidingASaleReturnsStockAndUpdatesCounters() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, new BigDecimal("1000")));
        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 4)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("6000"), null)),
                null, null, false));
        assertThat(sellable(leche)).isEqualTo(6);

        PosSaleDto voided = saleService.voidSale(cashier, sale.id(), new VoidSaleRequest("Error de cobro"));
        assertThat(voided.status()).isEqualTo(PosSaleStatus.VOIDED);
        assertThat(voided.voidReason()).isEqualTo("Error de cobro");
        assertThat(sellable(leche)).isEqualTo(10);
        assertThat(movementRepository.findByTenantIdAndBatchRefOrderByIdAsc(tenantId, sale.batchRef()))
                .anyMatch(movement -> movement.getType() == MovementType.SALE_VOID);

        assertApiError(() -> saleService.voidSale(cashier, sale.id(), new VoidSaleRequest("otra vez")),
                409, "ALREADY_VOIDED");

        PosSessionReportDto report = sessionService.get(cashier, session.id());
        assertThat(report.salesCount()).isZero();
        assertThat(report.voidedCount()).isEqualTo(1);
        assertThat(report.voidedTotal()).isEqualByComparingTo("5600.00");
        // El efectivo de la venta anulada sale del arqueo: 1000 de apertura + 6000 − 400 de vuelto − 5600 netos.
        assertThat(report.expectedCash()).isEqualByComparingTo("1000.00");
    }

    @Test
    void onlyTheAdminVoidsSalesOfAnotherCashier() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));
        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1400"), null)),
                null, null, false));

        as(otherCashier, centro);
        assertApiError(() -> saleService.voidSale(otherCashier, sale.id(), new VoidSaleRequest("no es mía")),
                403, "FORBIDDEN");
        assertApiError(() -> saleService.get(otherCashier, sale.id()), 403, "FORBIDDEN");

        as(admin, centro);
        assertThat(saleService.voidSale(admin, sale.id(), new VoidSaleRequest("Corrección del administrador"))
                .status()).isEqualTo(PosSaleStatus.VOIDED);
    }

    @Test
    void voidingASaleOfTheCashiersOwnClosedShiftExplainsThatTheShiftClosed() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));
        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1400"), null)),
                null, null, false));
        sessionService.close(cashier, session.id(), new CloseSessionRequest(new BigDecimal("1400"), null));

        assertThatThrownBy(() -> saleService.voidSale(cashier, sale.id(), new VoidSaleRequest("tarde")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus().value()).isEqualTo(403);
                    assertThat(ex.getMessage()).isEqualTo(PosAccess.ONLY_OPEN_SESSION_SALE);
                });

        as(otherCashier, centro);
        assertThatThrownBy(() -> saleService.voidSale(otherCashier, sale.id(), new VoidSaleRequest("ajena")))
                .hasMessage(PosAccess.ONLY_OWN_SALE);
    }

    // ------------------------------------------------------------------ turnos y arqueo

    @Test
    void arqueoFollowsTheFormulaOfTheSpec() {
        as(cashier, centro);
        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, new BigDecimal("10000")));
        saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 2)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("5000"), null)),
                null, null, false));
        saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.QR, new BigDecimal("1400"), "op-1")),
                null, null, false));
        sessionService.addCashMovement(cashier, session.id(),
                new CashMovementRequest(CashMovementType.CASH_IN, new BigDecimal("500"), "Cambio de otra caja"));
        sessionService.addCashMovement(cashier, session.id(),
                new CashMovementRequest(CashMovementType.CASH_OUT, new BigDecimal("2000"), "Pago a proveedor"));

        // 10000 + 5000 (efectivo) − 2200 (vuelto) + 500 − 2000 = 11300
        PosSessionReportDto report = sessionService.get(cashier, session.id());
        assertThat(report.expectedCash()).isEqualByComparingTo("11300.00");
        assertThat(report.changeGiven()).isEqualByComparingTo("2200.00");
        assertThat(report.totalsByMethod().get(PaymentMethod.CASH)).isEqualByComparingTo("5000.00");
        assertThat(report.totalsByMethod().get(PaymentMethod.QR)).isEqualByComparingTo("1400.00");
        assertThat(report.totalsByMethod().get(PaymentMethod.CREDIT)).isEqualByComparingTo("0.00");
        assertThat(report.cashMovements()).hasSize(2);
        assertThat(report.salesCount()).isEqualTo(2);
        assertThat(report.units()).isEqualTo(3);
        assertThat(report.difference()).isNull();

        PosSessionReportDto closed = sessionService.close(cashier, session.id(),
                new CloseSessionRequest(new BigDecimal("11000"), "Faltan monedas"));
        assertThat(closed.status()).isEqualTo(PosSessionStatus.CLOSED);
        assertThat(closed.expectedCash()).isEqualByComparingTo("11300.00");
        assertThat(closed.difference()).isEqualByComparingTo("-300.00");
        assertThat(closed.closingNote()).isEqualTo("Faltan monedas");

        assertApiError(() -> saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1400"), null)),
                null, null, false)), 409, "SESSION_NOT_OPEN");
    }

    @Test
    void refusesASecondSessionOnTheSameRegisterOrUser() {
        as(cashier, centro);
        sessionService.open(cashier, new OpenSessionRequest(registerCentro, BigDecimal.ZERO));
        assertApiError(() -> sessionService.open(cashier, new OpenSessionRequest(registerCentro, BigDecimal.ZERO)),
                409, "SESSION_ALREADY_OPEN");

        as(otherCashier, centro);
        assertApiError(() -> sessionService.open(otherCashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO)), 409, "REGISTER_BUSY");
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void doesNotLeakAcrossTenantsOrBranches() {
        // El cajero solo tiene asignada Sucursal Centro: no puede abrir la caja de Norte.
        as(cashier, centro);
        assertApiError(() -> sessionService.open(cashier, new OpenSessionRequest(registerNorte, BigDecimal.ZERO)),
                403, "BRANCH_FORBIDDEN");
        assertApiError(() -> registerService.get(tenantId, registerNorte), 403, "BRANCH_FORBIDDEN");

        PosSessionReportDto session = sessionService.open(cashier,
                new OpenSessionRequest(registerCentro, BigDecimal.ZERO));
        PosSaleDto sale = saleService.create(cashier, new PosSaleRequest(session.id(),
                List.of(new PosSaleRequest.Item(leche, 1)),
                List.of(new PosSaleRequest.Payment(PaymentMethod.CASH, new BigDecimal("1400"), null)),
                null, null, false));

        // Otro comercio: sus usuarios no ven ni la caja, ni el turno, ni la venta (404, sin revelar que existen).
        long otherTenant = data.tenant("Otro Comercio POS");
        long otherBranch = data.branch(otherTenant, "Sucursal Única", true);
        AuthUser foreignAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true, otherBranch);
        as(foreignAdmin, otherBranch);

        assertApiError(() -> registerService.get(otherTenant, registerCentro), 404, "NOT_FOUND");
        assertApiError(() -> sessionService.get(foreignAdmin, session.id()), 404, "NOT_FOUND");
        assertApiError(() -> saleService.get(foreignAdmin, sale.id()), 404, "NOT_FOUND");
        assertApiError(() -> saleService.ticket(foreignAdmin, sale.id()), 404, "NOT_FOUND");
        assertApiError(() -> saleService.voidSale(foreignAdmin, sale.id(), new VoidSaleRequest("ajena")),
                404, "NOT_FOUND");
        assertThat(saleService.list(foreignAdmin, null, null, null, null, null, false, 0, 20).content()).isEmpty();
        assertThat(sessionService.list(foreignAdmin, null, null, null, false, 0, 20).content()).isEmpty();
    }

    @Test
    void lookupOnlySeesTheStockOfItsOwnBranch() {
        as(admin, norte);
        assertThatThrownBy(() -> catalogService.lookup(tenantId, norte, "no-existe"))
                .isInstanceOf(ApiException.class);

        as(admin, centro);
        PosProductDto product = catalogService.search(tenantId, centro, "yogur", null, 20).getFirst();
        assertThat(product.sellableStock()).isEqualTo(10);
        assertThat(product.nextLot()).isNotNull();
        assertThat(product.nextLot().lotNumber()).isEqualTo("YV0925");
        assertThat(product.nextLot().unitPrice()).isEqualByComparingTo("1680.00");
        assertThat(product.branchId()).isEqualTo(centro);

        // En Norte no hay lotes de ese producto: el POS lo muestra sin stock.
        PosProductDto inNorte = catalogService.search(tenantId, norte, "yogur", null, 20).getFirst();
        assertThat(inNorte.sellableStock()).isZero();
        assertThat(inNorte.outOfStock()).isTrue();
        assertThat(inNorte.nextLot()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private long register(long tenant, long branch, String name) {
        return jdbc.queryForObject("""
                insert into pos_registers (tenant_id, branch_id, name) values (?, ?, ?) returning id
                """, Long.class, tenant, branch, name);
    }

    private int sellable(long productId) {
        return lotRepository.findSellableFifo(tenantId, centro, productId, LocalDate.now()).stream()
                .mapToInt(Lot::getQuantity).sum();
    }

    private static void as(AuthUser user, long branchId) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/pos");
        request.addHeader(BranchAccessService.HEADER, String.valueOf(branchId));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void assertApiError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }
}
