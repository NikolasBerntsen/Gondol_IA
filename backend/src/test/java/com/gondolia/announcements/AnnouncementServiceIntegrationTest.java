package com.gondolia.announcements;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.announcements.dto.AnnouncementDetailDto;
import com.gondolia.announcements.dto.CreateAnnouncementRequest;
import com.gondolia.announcements.dto.RecallMatchDto;
import com.gondolia.announcements.dto.RecallPreviewRequest;
import com.gondolia.announcements.dto.RecallPreviewResponse;
import com.gondolia.announcements.dto.RecallRequest;
import com.gondolia.announcements.dto.ResolveRecallRequest;
import com.gondolia.announcements.dto.TenantAnnouncementDto;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.domain.announcement.RecallMatchStatus;
import com.gondolia.domain.announcement.RecallResolution;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
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
 * Módulo D contra PostgreSQL: publicación de avisos y recalls, vista previa, bandeja del comercio (lectura y
 * {@code affectsMe}) y resolución de coincidencias con retiro de stock. Cada prueba se revierte.
 */
@Transactional
class AnnouncementServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private AnnouncementService announcementService;
    @Autowired
    private TenantAnnouncementService tenantAnnouncementService;
    @Autowired
    private RecallMatchService recallMatchService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;
    @Autowired
    private EntityManager entityManager;

    private TestData data;
    private LocalDate today;
    private String barcode;
    private AuthUser owner;

    private long tenant;
    private long centro;
    private long norte;
    private long product;
    private AuthUser admin;
    private AuthUser employeeCentro;
    private AuthUser cashierCentro;
    private long lotCentro;
    private long lotNorte;

    private long otherTenant;
    private long otherBranch;
    private long otherProduct;
    private AuthUser otherAdmin;
    private long otherLot;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        today = LocalDate.now(clock);
        barcode = data.barcode();
        owner = data.user(null, Role.PLATFORM_OWNER, true);

        tenant = data.tenant("Avisos A", "ALMACEN", "PROFESIONAL", "ACTIVE", "FIFO");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Fisherton", true);
        product = data.product(tenant, barcode, "Sopa de tomate La Huerta 340 g", "800", "1200");
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        employeeCentro = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);
        cashierCentro = data.user(tenant, Role.TENANT_CASHIER, true, centro);
        lotCentro = data.lot(tenant, centro, product, "L-2409/a", "L2409A", today.plusDays(40), 24, "ACTIVE", 10);
        lotNorte = data.lot(tenant, norte, product, "L2409A", "L2409A", today.plusDays(40), 6, "ACTIVE", 8);

        otherTenant = data.tenant("Avisos B", "KIOSCO", "FREEMIUM", "ACTIVE", "FIFO");
        otherBranch = data.branch(otherTenant, "Principal", true);
        otherProduct = data.product(otherTenant, barcode, "Sopa de tomate", "800", "1200");
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);
        otherLot = data.lot(otherTenant, otherBranch, otherProduct, "L2409A", "L2409A", today.plusDays(40), 9,
                "ACTIVE", 5);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ dueños

    @Test
    void publishingARecallQuarantinesMatchingLotsAndCountsOnlyAggregates() {
        as(owner, null);
        AnnouncementDetailDto detail = announcementService.create(recallRequest("L-2409/a"));

        assertThat(detail.kind()).isEqualTo(AnnouncementKind.RECALL);
        assertThat(detail.status()).isEqualTo(AnnouncementStatus.PUBLISHED);
        assertThat(detail.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(detail.publishedAt()).isNotNull();
        assertThat(detail.recall().lotNumbers()).containsExactly("L-2409/a");
        assertThat(detail.recall().barcode()).isEqualTo(barcode);
        assertThat(detail.affectedTenantsCount()).as("dos comercios con lotes alcanzados").isEqualTo(2);
        assertThat(detail.matchesOpen()).isEqualTo(3);
        assertThat(detail.matchesResolved()).isZero();
        assertThat(detail.recipientsCount()).isGreaterThanOrEqualTo(4);

        assertThat(lotStatus(lotCentro)).isEqualTo(LotStatus.RECALLED.name());
        assertThat(lotStatus(lotNorte)).isEqualTo(LotStatus.RECALLED.name());
        assertThat(lotStatus(otherLot)).isEqualTo(LotStatus.RECALLED.name());
    }

    @Test
    void generalAnnouncementReachesOnlyTheChosenBusinessTypes() {
        as(owner, null);
        announcementService.create(new CreateAnnouncementRequest(AnnouncementKind.GENERAL, Severity.INFO,
                "Nueva versión de GondolIA", "Ya podés importar tu planilla de Excel.",
                List.of(BusinessType.ALMACEN), null));

        as(admin, null);
        assertThat(tenantAnnouncementService.list(0, 20).content())
                .extracting(TenantAnnouncementDto::title).containsExactly("Nueva versión de GondolIA");

        as(otherAdmin, null);
        assertThat(tenantAnnouncementService.list(0, 20).content())
                .as("el kiosco no es del rubro elegido").isEmpty();
        assertThat(tenantAnnouncementService.unreadCount()).isZero();
    }

    @Test
    void previewCountsWithoutTouchingAnything() {
        as(owner, null);
        RecallPreviewResponse preview = announcementService.preview(
                new RecallPreviewRequest(barcode, List.of("l 2409 a"), false, null, null));

        assertThat(preview.affectedTenantsCount()).isEqualTo(2);
        assertThat(preview.affectedLotsCount()).isEqualTo(3);
        assertThat(preview.affectedUnits()).isEqualTo(24 + 6 + 9);
        assertThat(lotStatus(lotCentro)).as("la vista previa no pone nada en cuarentena").isEqualTo("ACTIVE");
        assertThat(countMatches()).isZero();

        assertThat(announcementService.preview(
                new RecallPreviewRequest(barcode, List.of("OTRO"), false, null, null)).affectedLotsCount()).isZero();
        assertThat(announcementService.preview(
                new RecallPreviewRequest(barcode, List.of(), true, today.plusDays(100), today.plusDays(200))
        ).affectedLotsCount()).as("fuera del rango de vencimiento").isZero();
        assertThat(announcementService.preview(new RecallPreviewRequest(data.barcode(), List.of(), true, null, null))
                .affectedTenantsCount()).isZero();
    }

    @Test
    void recallValidationRejectsBadBarcodeInvertedRangeAndMissingLots() {
        as(owner, null);
        assertApiError(() -> announcementService.create(recallWith(
                new RecallRequest("Sopa", "La Huerta", "7791234500018", List.of("L2409A"), false, null, null,
                        "Contaminación", "Retirala"))), 400, "VALIDATION_ERROR");
        assertApiError(() -> announcementService.create(recallWith(
                new RecallRequest("Sopa", "La Huerta", barcode, List.of("L2409A"), false, today.plusDays(20),
                        today.plusDays(10), "Contaminación", "Retirala"))), 400, "VALIDATION_ERROR");
        assertApiError(() -> announcementService.create(recallWith(
                new RecallRequest("Sopa", "La Huerta", barcode, List.of("  "), false, null, null, "Contaminación",
                        "Retirala"))), 400, "VALIDATION_ERROR");
        assertApiError(() -> announcementService.create(new CreateAnnouncementRequest(AnnouncementKind.RECALL,
                null, "Recall sin datos", "cuerpo", null, null)), 400, "VALIDATION_ERROR");
        assertThat(countMatches()).isZero();
    }

    @Test
    void archivingHidesTheAnnouncementFromTenants() {
        as(owner, null);
        AnnouncementDetailDto created = announcementService.create(new CreateAnnouncementRequest(
                AnnouncementKind.GENERAL, Severity.WARNING, "Mantenimiento programado", "El domingo de 2 a 4.",
                null, null));

        as(admin, null);
        assertThat(tenantAnnouncementService.list(0, 20).content()).hasSize(1);

        as(owner, null);
        assertThat(announcementService.archive(created.id()).status()).isEqualTo(AnnouncementStatus.ARCHIVED);
        assertApiError(() -> announcementService.archive(created.id()), 409, "CONFLICT");

        as(admin, null);
        assertThat(tenantAnnouncementService.list(0, 20).content()).isEmpty();
    }

    // ------------------------------------------------------------------ comercio

    @Test
    void tenantInboxTracksReadStateAndWhetherTheRecallAffectsTheUserBranches() {
        as(owner, null);
        long recallId = announcementService.create(recallRequest("L2409A")).id();

        as(admin, null);
        PageResponse<TenantAnnouncementDto> page = tenantAnnouncementService.list(0, 20);
        assertThat(page.totalElements()).isEqualTo(1);
        TenantAnnouncementDto aviso = page.content().getFirst();
        assertThat(aviso.id()).isEqualTo(recallId);
        assertThat(aviso.read()).isFalse();
        assertThat(aviso.affectsMe()).isTrue();
        assertThat(aviso.myMatchesCount()).as("Centro y Fisherton").isEqualTo(2);
        assertThat(aviso.myOpenMatchesCount()).isEqualTo(2);
        assertThat(tenantAnnouncementService.unreadCount()).isEqualTo(1);

        tenantAnnouncementService.markRead(recallId);
        tenantAnnouncementService.markRead(recallId);
        assertThat(tenantAnnouncementService.list(0, 20).content().getFirst().read()).isTrue();
        assertThat(tenantAnnouncementService.unreadCount()).isZero();

        as(cashierCentro, null);
        TenantAnnouncementDto forCashier = tenantAnnouncementService.list(0, 20).content().getFirst();
        assertThat(forCashier.read()).as("la lectura es por usuario").isFalse();
        assertThat(forCashier.myMatchesCount()).as("el cajero solo ve su sucursal").isEqualTo(1);
    }

    @Test
    void markingAnAnnouncementOfAnotherBusinessTypeAsReadIs404() {
        as(owner, null);
        long onlyDietetica = announcementService.create(new CreateAnnouncementRequest(AnnouncementKind.GENERAL,
                Severity.INFO, "Novedades para dietéticas", "Cuerpo", List.of(BusinessType.DIETETICA), null)).id();

        as(admin, null);
        assertApiError(() -> tenantAnnouncementService.markRead(onlyDietetica), 404, "NOT_FOUND");
        assertApiError(() -> tenantAnnouncementService.markRead(-1L), 404, "NOT_FOUND");
    }

    // ------------------------------------------------------------------ coincidencias

    @Test
    void employeeOnlySeesMatchesOfTheirBranchesAndCannotTouchAnotherBranch() {
        as(owner, null);
        announcementService.create(recallRequest("L2409A"));

        as(admin, null);
        List<RecallMatchDto> all = recallMatchService.list(RecallMatchService.MatchFilter.ACTIVE);
        assertThat(all).hasSize(2).extracting(RecallMatchDto::branchName)
                .containsExactlyInAnyOrder("Centro", "Fisherton");
        assertThat(all).allSatisfy(match -> {
            assertThat(match.status()).isEqualTo(RecallMatchStatus.OPEN);
            assertThat(match.barcode()).isEqualTo(barcode);
            assertThat(match.reason()).isEqualTo("Posible presencia de metal");
        });
        long matchNorte = all.stream().filter(match -> match.branchId() == norte).findFirst().orElseThrow().id();

        as(employeeCentro, null);
        List<RecallMatchDto> mine = recallMatchService.list(RecallMatchService.MatchFilter.ALL);
        assertThat(mine).singleElement().satisfies(match -> {
            assertThat(match.branchId()).isEqualTo(centro);
            assertThat(match.quantityAtMatch()).isEqualTo(24);
            assertThat(match.currentQuantity()).isEqualTo(24);
        });
        assertApiError(() -> recallMatchService.acknowledge(matchNorte), 403, "BRANCH_FORBIDDEN");
        assertApiError(() -> recallMatchService.resolve(matchNorte,
                new ResolveRecallRequest(RecallResolution.REMOVED_FROM_STOCK, null)), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void matchesOfAnotherTenantAreNotVisibleNorReachable() {
        as(owner, null);
        announcementService.create(recallRequest("L2409A"));

        as(admin, null);
        long myMatch = recallMatchService.list(RecallMatchService.MatchFilter.ALL).getFirst().id();

        as(otherAdmin, null);
        assertThat(recallMatchService.list(RecallMatchService.MatchFilter.ALL))
                .singleElement()
                .satisfies(match -> assertThat(match.branchId()).isEqualTo(otherBranch));
        assertApiError(() -> recallMatchService.acknowledge(myMatch), 404, "NOT_FOUND");
        assertApiError(() -> recallMatchService.resolve(myMatch,
                new ResolveRecallRequest(RecallResolution.REMOVED_FROM_STOCK, null)), 404, "NOT_FOUND");
    }

    @Test
    void acknowledgeThenResolveRemovesTheQuarantinedStock() {
        as(owner, null);
        announcementService.create(recallRequest("L2409A"));

        as(employeeCentro, null);
        long matchId = recallMatchService.list(RecallMatchService.MatchFilter.OPEN).getFirst().id();

        RecallMatchDto acknowledged = recallMatchService.acknowledge(matchId);
        assertThat(acknowledged.status()).isEqualTo(RecallMatchStatus.ACKNOWLEDGED);
        assertThat(acknowledged.acknowledgedByName()).isEqualTo(employeeCentro.fullName());
        assertThat(recallMatchService.acknowledge(matchId).acknowledgedAt())
                .as("es idempotente").isEqualTo(acknowledged.acknowledgedAt());

        RecallMatchDto resolved = recallMatchService.resolve(matchId,
                new ResolveRecallRequest(RecallResolution.REMOVED_FROM_STOCK, "Se descartó en el local"));
        assertThat(resolved.status()).isEqualTo(RecallMatchStatus.RESOLVED);
        assertThat(resolved.resolution()).isEqualTo(RecallResolution.REMOVED_FROM_STOCK);
        assertThat(resolved.resolutionNote()).isEqualTo("Se descartó en el local");
        assertThat(resolved.resolvedByName()).isEqualTo(employeeCentro.fullName());
        assertThat(resolved.currentQuantity()).isZero();

        entityManager.flush();
        assertThat(jdbc.queryForObject("select quantity from lots where id = ?", Integer.class, lotCentro)).isZero();
        assertThat(lotStatus(lotCentro)).as("el lote sigue en cuarentena con 0").isEqualTo(LotStatus.RECALLED.name());
        assertThat(jdbc.queryForObject("""
                select type from stock_movements where lot_id = ? order by id desc limit 1
                """, String.class, lotCentro)).isEqualTo("RECALL_REMOVAL");
        assertThat(jdbc.queryForObject("""
                select status from alerts where tenant_id = ? and type = 'RECALL_MATCH' and lot_id = ?
                """, String.class, tenant, lotCentro)).isEqualTo("RESOLVED");

        assertApiError(() -> recallMatchService.resolve(matchId,
                new ResolveRecallRequest(RecallResolution.REMOVED_FROM_STOCK, null)), 409, "CONFLICT");
    }

    @Test
    void returningToSupplierUsesAnAdjustmentOut() {
        as(owner, null);
        announcementService.create(recallRequest("L2409A"));

        as(admin, null);
        long matchId = recallMatchService.list(RecallMatchService.MatchFilter.ACTIVE).stream()
                .filter(match -> match.branchId() == norte).findFirst().orElseThrow().id();
        RecallMatchDto resolved = recallMatchService.resolve(matchId,
                new ResolveRecallRequest(RecallResolution.RETURNED_TO_SUPPLIER, null));

        assertThat(resolved.acknowledgedAt()).as("resolver también confirma").isNotNull();
        assertThat(jdbc.queryForObject("""
                select type from stock_movements where lot_id = ? order by id desc limit 1
                """, String.class, lotNorte)).isEqualTo("ADJUSTMENT_OUT");
        assertThat(recallMatchService.list(RecallMatchService.MatchFilter.RESOLVED)).hasSize(1);
        assertThat(recallMatchService.list(RecallMatchService.MatchFilter.ACTIVE)).hasSize(1);
    }

    @Test
    void branchHeaderNarrowsTheScopeOfTheMatchList() {
        as(owner, null);
        announcementService.create(recallRequest("L2409A"));

        as(admin, String.valueOf(centro));
        assertThat(recallMatchService.list(RecallMatchService.MatchFilter.ALL)).singleElement()
                .satisfies(match -> assertThat(match.branchId()).isEqualTo(centro));

        as(admin, "all");
        assertThat(recallMatchService.list(RecallMatchService.MatchFilter.ALL)).hasSize(2);

        as(employeeCentro, String.valueOf(norte));
        assertApiError(() -> recallMatchService.list(RecallMatchService.MatchFilter.ALL), 403, "BRANCH_FORBIDDEN");
    }

    // ------------------------------------------------------------------ helpers

    private CreateAnnouncementRequest recallRequest(String lotNumber) {
        return recallWith(new RecallRequest("Sopa de tomate en lata La Huerta 340 g", "La Huerta", barcode,
                List.of(lotNumber), false, null, null, "Posible presencia de metal",
                "Retirá el producto de la góndola y no lo vendas."));
    }

    private static CreateAnnouncementRequest recallWith(RecallRequest recall) {
        return new CreateAnnouncementRequest(AnnouncementKind.RECALL, null,
                "Retiro: Sopa de tomate La Huerta 340 g", "Retiro voluntario del lote indicado.", null, recall);
    }

    private String lotStatus(long lotId) {
        return jdbc.queryForObject("select status from lots where id = ?", String.class, lotId);
    }

    private int countMatches() {
        return jdbc.queryForObject("select count(*) from recall_matches where tenant_id in (?, ?)", Integer.class,
                tenant, otherTenant);
    }

    private static void as(AuthUser user, String branchHeader) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/recall-matches");
        if (branchHeader != null) {
            request.addHeader(BranchAccessService.HEADER, branchHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void assertApiError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status,
                                       String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
        });
    }
}
