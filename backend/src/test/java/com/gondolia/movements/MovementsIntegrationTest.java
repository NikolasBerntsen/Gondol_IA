package com.gondolia.movements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.movements.dto.ExpirationDtos.BulkDiscardResultDto;
import com.gondolia.movements.dto.ExpirationDtos.DiscardRequest;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationBucket;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationRowDto;
import com.gondolia.movements.dto.ExpirationDtos.ExpirationSummaryDto;
import com.gondolia.movements.dto.MovementDtos.AdjustmentRequest;
import com.gondolia.movements.dto.MovementDtos.MovementDto;
import com.gondolia.movements.dto.SaleDtos.SaleDto;
import com.gondolia.movements.dto.SaleDtos.SaleItemRequest;
import com.gondolia.movements.dto.SaleDtos.SaleRequest;
import com.gondolia.movements.dto.SaleDtos.SaleSummaryDto;
import com.gondolia.movements.dto.TransferDtos.TransferDto;
import com.gondolia.movements.dto.TransferDtos.TransferItemRequest;
import com.gondolia.movements.dto.TransferDtos.TransferRequest;
import com.gondolia.movements.dto.TransferDtos.TransferableLotDto;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.VoidSaleCommand;
import java.math.BigDecimal;
import java.time.Clock;
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
 * Servicios del módulo A2 contra PostgreSQL: venta manual con rotación, historial neteando
 * {@code SALE_VOID}, ajustes por rol, vencimientos y transferencias, y sobre todo el **aislamiento por
 * comercio y por sucursal** (SPEC §3.4, §3.5). Cada prueba se revierte.
 */
@Transactional
class MovementsIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private SalesService salesService;
    @Autowired
    private MovementsService movementsService;
    @Autowired
    private ExpirationsService expirationsService;
    @Autowired
    private TransfersService transfersService;
    @Autowired
    private StockService stockService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    private TestData data;
    private long tenant;
    private long centro;
    private long norte;
    private long milk;
    private long yogurt;
    private AuthUser admin;
    private AuthUser employee;

    private long otherTenant;
    private long otherBranch;
    private long otherProduct;
    private AuthUser otherAdmin;

    private LocalDate today;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Movimientos");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Norte", true);
        milk = data.product(tenant, data.barcode(), "Leche entera 1 L", "800", "1200");
        yogurt = data.product(tenant, data.barcode(), "Yogur bebible 1 L", "900", "1500");
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        // El empleado solo tiene Centro asignada (como empleado@prueba.com del fixture).
        employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);

        otherTenant = data.tenant("Otro Comercio");
        otherBranch = data.branch(otherTenant, "Principal", true);
        otherProduct = data.product(otherTenant, data.barcode(), "Galletitas 200 g", "500", "900");
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);

        today = LocalDate.now(clock);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ venta manual

    @Test
    void aManualSaleConsumesTheLotsInRotationOrderAndReturnsThem() {
        as(admin, centro);
        long older = data.lot(tenant, centro, milk, "L-VIEJO", "LVIEJO", today.plusDays(40), 4, "ACTIVE", 10);
        long newer = data.lot(tenant, centro, milk, "L-NUEVO", "LNUEVO", today.plusDays(60), 10, "ACTIVE", 2);

        SaleDto sale = salesService.register(new SaleRequest(centro,
                List.of(new SaleItemRequest(milk, 6, null)), null));

        assertThat(sale.batchRef()).startsWith("S-");
        assertThat(sale.branchId()).isEqualTo(centro);
        assertThat(sale.units()).isEqualTo(6);
        assertThat(sale.shortageUnits()).isZero();
        // FIFO: primero se agota el lote que entró antes y el resto sale del nuevo.
        assertThat(sale.lines()).singleElement().satisfies(line -> {
            assertThat(line.lots()).extracting(lot -> lot.lotId() + ":" + lot.quantity())
                    .containsExactly(older + ":4", newer + ":2");
            assertThat(line.total()).isEqualByComparingTo("7200"); // 6 × 1200
        });
        assertThat(quantityOf(older)).isZero();
        assertThat(quantityOf(newer)).isEqualTo(8);
    }

    @Test
    void aSaleWithoutEnoughStockReportsTheShortage() {
        as(admin, centro);
        data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(30), 2, "ACTIVE", 1);

        SaleDto sale = salesService.register(new SaleRequest(centro,
                List.of(new SaleItemRequest(milk, 5, null)), null));

        assertThat(sale.shortageUnits()).isEqualTo(3);
        assertThat(sale.lines()).singleElement().satisfies(line -> assertThat(line.shortage()).isEqualTo(3));
    }

    @Test
    void theSameProductTwiceInOneSaleIsRejected() {
        as(admin, centro);
        assertApiError(() -> salesService.register(new SaleRequest(centro,
                        List.of(new SaleItemRequest(milk, 1, null), new SaleItemRequest(milk, 2, null)), null)),
                400, "VALIDATION_ERROR");
    }

    @Test
    void aSaleDatedInTheFutureIsRejected() {
        as(admin, centro);
        assertApiError(() -> salesService.register(new SaleRequest(centro,
                        List.of(new SaleItemRequest(milk, 1, null)), clock.instant().plusSeconds(7200))),
                400, "VALIDATION_ERROR");
    }

    @Test
    void aSaleOfAProductOfAnotherTenantIsNotFound() {
        as(admin, centro);
        assertApiError(() -> salesService.register(new SaleRequest(centro,
                List.of(new SaleItemRequest(otherProduct, 1, null)), null)), 404, "NOT_FOUND");
    }

    // ------------------------------------------------------------------ historial y anulaciones

    @Test
    void theHistoryNetsTheVoidedUnitsAndAmounts() {
        as(admin, centro);
        data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(30), 10, "ACTIVE", 1);
        SaleDto sale = salesService.register(new SaleRequest(centro,
                List.of(new SaleItemRequest(milk, 3, null)), null));

        PageResponse<SaleSummaryDto> before = salesService.list(null, null, null, null, null, 0, 20);
        assertThat(before.content()).singleElement().satisfies(row -> {
            assertThat(row.units()).isEqualTo(3);
            assertThat(row.total()).isEqualByComparingTo("3600");
            assertThat(row.voided()).isFalse();
            assertThat(row.source()).isEqualTo(MovementSource.MANUAL);
            assertThat(row.sourceLabel()).isEqualTo("Manual");
            assertThat(row.ticketCode()).isNull();
        });

        stockService.voidSale(new VoidSaleCommand(tenant, sale.batchRef(), admin.id(), "Error de cobro"));

        PageResponse<SaleSummaryDto> after = salesService.list(null, null, null, null, null, 0, 20);
        assertThat(after.content()).singleElement().satisfies(row -> {
            assertThat(row.units()).isZero();
            assertThat(row.total()).isEqualByComparingTo("0");
            assertThat(row.voided()).isTrue();
            assertThat(row.voidedUnits()).isEqualTo(3);
        });
        assertThat(salesService.detail(sale.batchRef()).sale().units()).isZero();
        assertThat(salesService.detail(sale.batchRef()).grossTotal()).isEqualByComparingTo("3600");
    }

    @Test
    void theHistoryOnlyShowsTheSalesOfTheCallersTenant() {
        as(admin, centro);
        data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(30), 10, "ACTIVE", 1);
        SaleDto mine = salesService.register(new SaleRequest(centro,
                List.of(new SaleItemRequest(milk, 1, null)), null));

        as(otherAdmin, otherBranch);
        data.lot(otherTenant, otherBranch, otherProduct, "X1", "X1", today.plusDays(30), 10, "ACTIVE", 1);
        SaleDto theirs = salesService.register(new SaleRequest(otherBranch,
                List.of(new SaleItemRequest(otherProduct, 1, null)), null));

        assertThat(salesService.list(null, null, null, null, null, 0, 20).content())
                .extracting(SaleSummaryDto::batchRef)
                .containsExactly(theirs.batchRef());

        as(admin, centro);
        assertThat(salesService.list(null, null, null, null, null, 0, 20).content())
                .extracting(SaleSummaryDto::batchRef)
                .containsExactly(mine.batchRef());
    }

    @Test
    void theDetailOfASaleOfAnotherTenantIsNotFound() {
        as(otherAdmin, otherBranch);
        data.lot(otherTenant, otherBranch, otherProduct, "X1", "X1", today.plusDays(30), 5, "ACTIVE", 1);
        SaleDto theirs = salesService.register(new SaleRequest(otherBranch,
                List.of(new SaleItemRequest(otherProduct, 1, null)), null));

        as(admin, centro);
        assertApiError(() -> salesService.detail(theirs.batchRef()), 404, "NOT_FOUND");
    }

    @Test
    void anEmployeeDoesNotSeeTheSalesOfABranchThatIsNotAssignedToThem() {
        as(admin, norte);
        data.lot(tenant, norte, milk, "N1", "N1", today.plusDays(30), 5, "ACTIVE", 1);
        SaleDto inNorte = salesService.register(new SaleRequest(norte,
                List.of(new SaleItemRequest(milk, 2, null)), null));

        // El empleado solo tiene Centro: con el alcance "todas" el historial no incluye Norte.
        as(employee, null);
        assertThat(salesService.list(null, null, null, null, null, 0, 20).content()).isEmpty();
        assertApiError(() -> salesService.detail(inNorte.batchRef()), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void anEmployeeCannotUseTheBranchHeaderOfABranchThatIsNotTheirs() {
        as(employee, norte);
        assertApiError(() -> movementsService.list(null, null, null, null, null, null, null, 0, 20),
                403, "BRANCH_FORBIDDEN");
    }

    // ------------------------------------------------------------------ movimientos y ajustes

    @Test
    void theAdminCanRegisterAnyAdjustmentType() {
        as(admin, centro);
        long lot = data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(30), 10, "ACTIVE", 1);

        MovementDto movement = movementsService.adjust(
                new AdjustmentRequest(lot, MovementType.ADJUSTMENT_OUT, 4, "Faltante de conteo"));

        assertThat(movement.type()).isEqualTo(MovementType.ADJUSTMENT_OUT);
        assertThat(movement.typeLabel()).isEqualTo("Ajuste negativo");
        assertThat(movement.signedQuantity()).isEqualTo(-4);
        assertThat(movement.branchName()).isEqualTo("Centro");
        assertThat(quantityOf(lot)).isEqualTo(6);
    }

    @Test
    void theEmployeeCanOnlyWriteOffExpiredOrDamagedStock() {
        as(employee, centro);
        long lot = data.lot(tenant, centro, milk, "L1", "L1", today.minusDays(2), 10, "ACTIVE", 5);

        MovementDto waste = movementsService.adjust(
                new AdjustmentRequest(lot, MovementType.WASTE_DAMAGED, 2, "Rotura"));
        assertThat(waste.type()).isEqualTo(MovementType.WASTE_DAMAGED);

        assertApiError(() -> movementsService.adjust(
                new AdjustmentRequest(lot, MovementType.ADJUSTMENT_IN, 1, "Corrección")), 403, "FORBIDDEN");
    }

    @Test
    void anAdjustmentTypeThatIsNotAnAdjustmentIsRejected() {
        as(admin, centro);
        long lot = data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(30), 10, "ACTIVE", 1);
        assertApiError(() -> movementsService.adjust(new AdjustmentRequest(lot, MovementType.SALE, 1, null)),
                400, "VALIDATION_ERROR");
    }

    @Test
    void anAdjustmentOnALotOfAnotherTenantIsNotFound() {
        long theirLot = data.lot(otherTenant, otherBranch, otherProduct, "X1", "X1", today.plusDays(30), 5,
                "ACTIVE", 1);
        as(admin, centro);
        assertApiError(() -> movementsService.adjust(
                new AdjustmentRequest(theirLot, MovementType.WASTE_DAMAGED, 1, null)), 404, "NOT_FOUND");
    }

    @Test
    void anAdjustmentOnALotOfAnotherBranchIsForbiddenForTheEmployee() {
        long lotInNorte = data.lot(tenant, norte, milk, "N1", "N1", today.plusDays(30), 5, "ACTIVE", 1);
        as(employee, centro);
        assertApiError(() -> movementsService.adjust(
                new AdjustmentRequest(lotInNorte, MovementType.WASTE_DAMAGED, 1, null)), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void theMovementsListIsScopedToTheAccessibleBranches() {
        as(admin, centro);
        long lotCentro = data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(30), 10, "ACTIVE", 1);
        movementsService.adjust(new AdjustmentRequest(lotCentro, MovementType.WASTE_DAMAGED, 1, "Rotura"));

        as(admin, norte);
        long lotNorte = data.lot(tenant, norte, yogurt, "N1", "N1", today.plusDays(30), 10, "ACTIVE", 1);
        movementsService.adjust(new AdjustmentRequest(lotNorte, MovementType.WASTE_DAMAGED, 2, "Rotura"));

        as(admin, centro);
        assertThat(movementsService.list(null, null, null, null, null, null, null, 0, 20).content())
                .extracting(MovementDto::branchId).containsOnly(centro);

        as(admin, null); // consolidado: las dos sucursales
        assertThat(movementsService.list(null, null, null, null, null, null, null, 0, 20).content())
                .extracting(MovementDto::branchId).contains(centro, norte);

        as(employee, null); // el empleado solo ve Centro
        assertThat(movementsService.list(null, null, null, null, null, null, null, 0, 20).content())
                .extracting(MovementDto::branchId).containsOnly(centro);
    }

    // ------------------------------------------------------------------ vencimientos

    @Test
    void theSummaryGroupsLotsIntoTheirBuckets() {
        as(admin, centro);
        data.lot(tenant, centro, milk, "VENCIDO", "VENCIDO", today.minusDays(3), 4, "ACTIVE", 20);
        data.lot(tenant, centro, milk, "CRITICO", "CRITICO", today.plusDays(1), 6, "ACTIVE", 10);
        data.lot(tenant, centro, yogurt, "PROXIMO", "PROXIMO", today.plusDays(25), 8, "ACTIVE", 5);

        ExpirationSummaryDto summary = expirationsService.summary(null);
        assertThat(summary.expired().lots()).isEqualTo(1);
        assertThat(summary.expired().units()).isEqualTo(4);
        assertThat(summary.critical().lots()).isEqualTo(1);
        assertThat(summary.upcoming().lots()).isEqualTo(1);
        assertThat(summary.asOf()).isEqualTo(today);

        PageResponse<ExpirationRowDto> expired =
                expirationsService.list(ExpirationBucket.EXPIRED, null, null, 0, 20);
        assertThat(expired.content()).singleElement().satisfies(row -> {
            assertThat(row.lotNumber()).isEqualTo("VENCIDO");
            assertThat(row.daysLeft()).isNegative();
            assertThat(row.branchName()).isEqualTo("Centro");
            assertThat(row.rotationRank()).isNull(); // vencido: no es vendible
        });
    }

    @Test
    void discardingALotWritesItOffAsExpiredWaste() {
        as(admin, centro);
        long lot = data.lot(tenant, centro, milk, "VENCIDO", "VENCIDO", today.minusDays(1), 9, "ACTIVE", 20);

        MovementDto movement = expirationsService.discard(lot, new DiscardRequest(4, "Vencido en góndola"));
        assertThat(movement.type()).isEqualTo(MovementType.WASTE_EXPIRED);
        assertThat(movement.quantity()).isEqualTo(4);
        assertThat(quantityOf(lot)).isEqualTo(5);

        expirationsService.discard(lot, null); // sin cantidad: todo el remanente
        assertThat(quantityOf(lot)).isZero();
        assertThat(statusOf(lot)).isEqualTo("EXPIRED_DISCARDED");
    }

    @Test
    void discardingALotOfAnotherTenantIsNotFound() {
        long theirLot = data.lot(otherTenant, otherBranch, otherProduct, "X1", "X1", today.minusDays(1), 5,
                "ACTIVE", 1);
        as(admin, centro);
        assertApiError(() -> expirationsService.discard(theirLot, null), 404, "NOT_FOUND");
    }

    @Test
    void theBulkDiscardOnlyTouchesTheExpiredLotsOfTheScope() {
        as(admin, null);
        long expiredCentro = data.lot(tenant, centro, milk, "C-VEN", "CVEN", today.minusDays(5), 3, "ACTIVE", 20);
        long expiredNorte = data.lot(tenant, norte, milk, "N-VEN", "NVEN", today.minusDays(2), 7, "ACTIVE", 20);
        long fresh = data.lot(tenant, centro, yogurt, "OK", "OK", today.plusDays(20), 5, "ACTIVE", 1);
        long expiredOther = data.lot(otherTenant, otherBranch, otherProduct, "X-VEN", "XVEN", today.minusDays(9), 4,
                "ACTIVE", 20);

        BulkDiscardResultDto result = expirationsService.discardAllExpired(null, null);

        assertThat(result.lots()).isEqualTo(2);
        assertThat(result.units()).isEqualTo(10);
        assertThat(result.failed()).isZero();
        assertThat(quantityOf(expiredCentro)).isZero();
        assertThat(quantityOf(expiredNorte)).isZero();
        assertThat(quantityOf(fresh)).isEqualTo(5);
        assertThat(quantityOf(expiredOther)).isEqualTo(4); // el otro comercio queda intacto
    }

    @Test
    void theEmployeeBulkDiscardOnlyReachesTheirOwnBranch() {
        long expiredCentro = data.lot(tenant, centro, milk, "C-VEN", "CVEN", today.minusDays(5), 3, "ACTIVE", 20);
        long expiredNorte = data.lot(tenant, norte, milk, "N-VEN", "NVEN", today.minusDays(2), 7, "ACTIVE", 20);

        as(employee, null);
        BulkDiscardResultDto result = expirationsService.discardAllExpired(null, null);

        assertThat(result.lots()).isEqualTo(1);
        assertThat(quantityOf(expiredCentro)).isZero();
        assertThat(quantityOf(expiredNorte)).isEqualTo(7);
    }

    // ------------------------------------------------------------------ transferencias

    @Test
    void aTransferMovesTheLotKeepingItsAgeAndExpiry() {
        as(admin, centro);
        long origin = data.lot(tenant, centro, milk, "L-1", "L1", today.plusDays(40), 10, "ACTIVE", 7);

        TransferDto transfer = transfersService.transfer(new TransferRequest(centro, norte,
                List.of(new TransferItemRequest(origin, 4)), "Reparto semanal"));

        assertThat(transfer.batchRef()).startsWith("T-");
        assertThat(transfer.fromBranchId()).isEqualTo(centro);
        assertThat(transfer.toBranchId()).isEqualTo(norte);
        assertThat(transfer.units()).isEqualTo(4);
        assertThat(quantityOf(origin)).isEqualTo(6);

        Long destination = transfer.items().getFirst().destinationLotId();
        assertThat(destination).isNotNull();
        assertThat(jdbc.queryForObject("select branch_id from lots where id = ?", Long.class, destination))
                .isEqualTo(norte);
        assertThat(jdbc.queryForObject("select lot_number from lots where id = ?", String.class, destination))
                .isEqualTo("L-1");
        assertThat(jdbc.queryForObject(
                "select received_at from lots where id = ?", java.sql.Timestamp.class, destination))
                .isEqualTo(jdbc.queryForObject(
                        "select received_at from lots where id = ?", java.sql.Timestamp.class, origin));
    }

    @Test
    void transferringToTheSameBranchIsRejected() {
        as(admin, centro);
        long lot = data.lot(tenant, centro, milk, "L1", "L1", today.plusDays(40), 5, "ACTIVE", 1);
        assertApiError(() -> transfersService.transfer(new TransferRequest(centro, centro,
                List.of(new TransferItemRequest(lot, 1)), null)), 400, "VALIDATION_ERROR");
    }

    @Test
    void anExpiredLotCannotBeTransferred() {
        as(admin, centro);
        long lot = data.lot(tenant, centro, milk, "VEN", "VEN", today.minusDays(1), 5, "ACTIVE", 10);
        assertApiError(() -> transfersService.transfer(new TransferRequest(centro, norte,
                List.of(new TransferItemRequest(lot, 1)), null)), 409, "LOT_NOT_TRANSFERABLE");
    }

    @Test
    void aTransferFromABranchOfAnotherTenantIsRejected() {
        as(admin, centro);
        long theirLot = data.lot(otherTenant, otherBranch, otherProduct, "X1", "X1", today.plusDays(30), 5,
                "ACTIVE", 1);
        // La sucursal de origen ni siquiera es del comercio: 404 antes de mirar los lotes.
        assertApiError(() -> transfersService.transfer(new TransferRequest(otherBranch, centro,
                List.of(new TransferItemRequest(theirLot, 1)), null)), 404, "NOT_FOUND");
        // Con sucursales propias, el lote ajeno tampoco se encuentra: 404, sin revelar que existe.
        assertApiError(() -> transfersService.transfer(new TransferRequest(centro, norte,
                List.of(new TransferItemRequest(theirLot, 1)), null)), 404, "NOT_FOUND");
    }

    @Test
    void theAvailableLotsAreTheSellableOnesOfTheOriginBranchInRotationOrder() {
        as(admin, centro);
        long older = data.lot(tenant, centro, milk, "L-VIEJO", "LVIEJO", today.plusDays(40), 5, "ACTIVE", 10);
        long newer = data.lot(tenant, centro, milk, "L-NUEVO", "LNUEVO", today.plusDays(50), 5, "ACTIVE", 2);
        data.lot(tenant, centro, milk, "VENCIDO", "VENCIDO", today.minusDays(1), 5, "ACTIVE", 30);
        data.lot(tenant, norte, milk, "OTRA", "OTRA", today.plusDays(40), 5, "ACTIVE", 1);

        List<TransferableLotDto> lots = transfersService.availableLots(centro, null, 0, 50).content();

        assertThat(lots).extracting(TransferableLotDto::lotId).containsExactly(older, newer);
        assertThat(lots).extracting(TransferableLotDto::rotationRank).containsExactly(1, 2);
    }

    @Test
    void theAvailableLotsOfABranchOfAnotherTenantAreNotFound() {
        as(admin, centro);
        assertApiError(() -> transfersService.availableLots(otherBranch, null, 0, 50), 404, "NOT_FOUND");
    }

    // ------------------------------------------------------------------ helpers

    /** Autentica al usuario y arma un request con el encabezado {@code X-Branch-Id} (null = "todas"). */
    private void as(AuthUser user, Long branchId) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/movements");
        request.addHeader(BranchAccessService.HEADER, branchId == null ? "all" : String.valueOf(branchId));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private int quantityOf(long lotId) {
        Integer quantity = jdbc.queryForObject("select quantity from lots where id = ?", Integer.class, lotId);
        return quantity == null ? 0 : quantity;
    }

    private String statusOf(long lotId) {
        return jdbc.queryForObject("select status from lots where id = ?", String.class, lotId);
    }

    private static void assertApiError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getMessage()).isNotBlank();
        });
    }

    @SuppressWarnings("unused")
    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
