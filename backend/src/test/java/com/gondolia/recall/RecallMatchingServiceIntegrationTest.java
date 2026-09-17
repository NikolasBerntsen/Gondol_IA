package com.gondolia.recall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.gondolia.common.events.RecallMatchedEvent;
import com.gondolia.common.events.StockChangedEvent;
import com.gondolia.common.events.TenantStatusChangedEvent;
import com.gondolia.domain.announcement.RecallMatch;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.realtime.Destinations;
import com.gondolia.recall.RecallMatchingService.RecallInfo;
import com.gondolia.security.AuthUser;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.ReceiveLotCommand;
import com.gondolia.stock.StockService.ReceiveLotResult;
import com.gondolia.stock.StockService.SaleCommand;
import com.gondolia.stock.StockService.TransferCommand;
import com.gondolia.stock.StockService.TransferItem;
import com.gondolia.stock.StockService.TransferResult;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RecallMatchingService} contra PostgreSQL: reglas de coincidencia, cuarentena, alertas, destinatarios por
 * sucursal, idempotencia y barrido de tenants activos. Cada prueba se revierte.
 */
@Transactional
@RecordApplicationEvents
class RecallMatchingServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private RecallMatchingService recallMatchingService;
    @Autowired
    private TenantReactivationRecallListener tenantReactivationRecallListener;
    @Autowired
    private StockService stockService;
    @Autowired
    private LotRepository lotRepository;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private Clock clock;
    @Autowired
    private ApplicationEvents events;

    private TestData data;
    private LocalDate today;
    private String barcode;
    private long tenant;
    private long centro;
    private long norte;
    private long product;
    private AuthUser admin;
    private AuthUser boss;
    private AuthUser employeeCentro;
    private AuthUser employeeNorte;
    private AuthUser inactiveAdmin;
    private long otherTenant;
    private long otherBranch;
    private long otherProduct;
    private AuthUser otherAdmin;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        today = LocalDate.now(clock);
        barcode = data.barcode();
        tenant = data.tenant("Recall A");
        centro = data.branch(tenant, "Centro", true);
        norte = data.branch(tenant, "Fisherton", true);
        product = data.product(tenant, barcode, "Sopa de tomate La Huerta 340 g", "800", "1200");
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        boss = data.user(tenant, Role.TENANT_BOSS, true);
        employeeCentro = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);
        employeeNorte = data.user(tenant, Role.TENANT_EMPLOYEE, true, norte);
        inactiveAdmin = data.user(tenant, Role.TENANT_ADMIN, false);
        otherTenant = data.tenant("Recall B");
        otherBranch = data.branch(otherTenant, "Principal", true);
        otherProduct = data.product(otherTenant, barcode, "Sopa de tomate", "800", "1200");
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true);
    }

    @Test
    void findActiveRecallsAppliesLotAndExpiryRules() {
        long byLot = data.recall(barcode, false, null, null, "PUBLISHED", "L-2409/a", "L2410B");
        long allLots = data.recall(barcode, true, today.plusDays(10), today.plusDays(20), "PUBLISHED");
        data.recall(barcode, true, null, null, "ARCHIVED");

        assertThat(recallMatchingService.findActiveRecalls(" " + barcode, "l 2409 a", null))
                .extracting(RecallInfo::announcementId).containsExactly(byLot);
        assertThat(recallMatchingService.findActiveRecalls(barcode, "L2409B", today.plusDays(15)))
                .extracting(RecallInfo::announcementId).containsExactly(allLots);
        assertThat(recallMatchingService.findActiveRecalls(barcode, "L2410B", today.plusDays(10)))
                .extracting(RecallInfo::announcementId).containsExactlyInAnyOrder(byLot, allLots);
        assertThat(recallMatchingService.findActiveRecalls(barcode, "OTRO", today.plusDays(21))).isEmpty();
        assertThat(recallMatchingService.findActiveRecalls(barcode, "OTRO", null))
                .as("sin vencimiento no coincide con un recall con rango").isEmpty();
        assertThat(recallMatchingService.findActiveRecalls(data.barcode(), "L2409A", null)).isEmpty();
        assertThat(recallMatchingService.findActiveRecalls("  ", "L2409A", null)).isEmpty();
        assertThat(recallMatchingService.findActiveRecalls(barcode, "L2409A", null)).singleElement()
                .satisfies(info -> {
                    assertThat(info.reason()).isEqualTo("Posible contaminación");
                    assertThat(info.instructions()).isEqualTo("Retirá el producto de la góndola");
                    assertThat(info.allLots()).isFalse();
                });
    }

    @Test
    void receivingARecalledLotQuarantinesItAndAlertsOnlyUsersOfThatBranch() {
        long recall = data.recall(barcode, false, null, null, "PUBLISHED", "L2409A");

        ReceiveLotResult result = stockService.receiveLot(new ReceiveLotCommand(tenant, centro, product, "l-2409/a",
                today.plusDays(30), 12, null, null, null, MovementSource.SCAN, employeeCentro.id(), null));

        assertThat(result.rotationWarning()).isNull();
        assertThat(result.recallMatches()).singleElement().satisfies(match -> {
            assertThat(match.getAnnouncementId()).isEqualTo(recall);
            assertThat(match.getBranchId()).isEqualTo(centro);
            assertThat(match.getTenantId()).isEqualTo(tenant);
            assertThat(match.getLotId()).isEqualTo(result.lot().getId());
            assertThat(match.getQuantityAtMatch()).isEqualTo(12);
        });
        assertThat(result.lot().getStatus()).isEqualTo(LotStatus.RECALLED);
        assertThat(recallMatchingService.toRecallInfos(result.recallMatches())).extracting(RecallInfo::announcementId)
                .containsExactly(recall);
        assertThat(stockService.sellableStock(tenant, centro, product)).isZero();

        long matchId = result.recallMatches().getFirst().getId();
        assertThat(jdbc.queryForList("""
                select branch_id, severity, status, lot_id, announcement_id from alerts
                where tenant_id = ? and type = 'RECALL_MATCH' and dedupe_key = ?
                """, tenant, "RECALL:" + recall + ":" + result.lot().getId()))
                .singleElement().satisfies(alert -> {
                    assertThat(alert.get("branch_id")).isEqualTo(centro);
                    assertThat(alert.get("severity")).isEqualTo("CRITICAL");
                    assertThat(alert.get("status")).isEqualTo("OPEN");
                });
        assertThat(notifiedUsers(matchId)).containsExactlyInAnyOrder(admin.id(), boss.id(), employeeCentro.id());
        assertThat(jdbc.queryForMap("""
                select type, severity, link, reference_type from notifications where reference_id = ? limit 1
                """, matchId)).containsAllEntriesOf(Map.of("type", "RECALL_ALERT", "severity", "CRITICAL",
                "link", "/app/recalls", "reference_type", "RECALL_MATCH"));
        assertThat(affectedTenants(recall)).isEqualTo(1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> recipients = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(realtimePublisher).toUsers(recipients.capture(), eq(Destinations.QUEUE_SECURITY_ALERTS),
                payload.capture());
        assertThat(recipients.getValue()).containsExactlyInAnyOrder(admin.id(), boss.id(), employeeCentro.id());
        assertThat(payload.getValue()).isInstanceOfSatisfying(RecallAlertMessage.class, message -> {
            assertThat(message.matchId()).isEqualTo(matchId);
            assertThat(message.branchId()).isEqualTo(centro);
            assertThat(message.branchName()).isEqualTo("Centro");
            assertThat(message.barcode()).isEqualTo(barcode);
            assertThat(message.lotNumber()).isEqualTo("l-2409/a");
            assertThat(message.quantity()).isEqualTo(12);
            assertThat(message.productName()).isEqualTo("Sopa de tomate La Huerta 340 g");
        });
        assertThat(events.stream(RecallMatchedEvent.class))
                .containsExactly(new RecallMatchedEvent(tenant, recall, List.of(matchId)));
        assertThat(events.stream(StockChangedEvent.class)).contains(new StockChangedEvent(tenant, centro, product));

        assertThat(stockService.registerSale(new SaleCommand(tenant, centro, product, 2, null, null,
                MovementSource.MANUAL, admin.id(), null)).shortageQuantity())
                .as("un lote en cuarentena no se vende").isEqualTo(2);
    }

    @Test
    void checkLotIsIdempotent() {
        data.recall(barcode, true, null, null, "PUBLISHED");
        ReceiveLotResult result = stockService.receiveLot(new ReceiveLotCommand(tenant, norte, product, null, null, 3,
                null, null, null, null, admin.id(), null));
        long matchId = result.recallMatches().getFirst().getId();

        List<RecallMatch> again = recallMatchingService.checkLot(tenant, result.lot().getId());

        assertThat(again).extracting(RecallMatch::getId).containsExactly(matchId);
        assertThat(notifiedUsers(matchId)).containsExactlyInAnyOrder(admin.id(), boss.id(), employeeNorte.id());
        assertThat(jdbc.queryForObject("select count(*) from alerts where tenant_id = ? and type = 'RECALL_MATCH'",
                Long.class, tenant)).isEqualTo(1);
        assertThat(events.stream(RecallMatchedEvent.class)).hasSize(1);
    }

    @Test
    void matchAnnouncementSweepsStockedLotsOfActiveTenantsOnly() {
        long disabledTenant = data.tenant("Recall C", "KIOSCO", "FREEMIUM", "DISABLED", "FIFO");
        long disabledBranch = data.branch(disabledTenant, "Principal", true);
        long disabledProduct = data.product(disabledTenant, barcode, "Sopa", "1", "2");

        long norteLot = data.lot(tenant, norte, product, "L2409A", "L2409A", today.plusDays(20), 5, "ACTIVE", 3);
        data.lot(tenant, centro, product, "L2409A", "L2409A", today.plusDays(20), 0, "DEPLETED", 9);
        data.lot(tenant, centro, product, "L9999Z", "L9999Z", today.plusDays(20), 5, "ACTIVE", 3);
        long otherLot = data.lot(otherTenant, otherBranch, otherProduct, "L 2409 A", null, today.plusDays(20), 8,
                "ACTIVE", 2);
        data.lot(disabledTenant, disabledBranch, disabledProduct, "L2409A", "L2409A", today.plusDays(20), 4, "ACTIVE", 2);
        long recall = data.recall(barcode, false, today, today.plusDays(30), "PUBLISHED", "L2409A");

        List<RecallMatch> matches = recallMatchingService.matchAnnouncement(recall);

        assertThat(matches).extracting(RecallMatch::getLotId, RecallMatch::getBranchId)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(norteLot, norte),
                        org.assertj.core.groups.Tuple.tuple(otherLot, otherBranch));
        assertThat(affectedTenants(recall)).isEqualTo(2);
        assertThat(lotStatus(norteLot)).isEqualTo(LotStatus.RECALLED);
        assertThat(lotStatus(otherLot)).isEqualTo(LotStatus.RECALLED);
        long norteMatch = matches.stream().filter(m -> m.getLotId() == norteLot).findFirst().orElseThrow().getId();
        long otherMatch = matches.stream().filter(m -> m.getLotId() == otherLot).findFirst().orElseThrow().getId();
        assertThat(notifiedUsers(norteMatch)).containsExactlyInAnyOrder(admin.id(), boss.id(), employeeNorte.id())
                .doesNotContain(employeeCentro.id(), inactiveAdmin.id(), otherAdmin.id());
        assertThat(notifiedUsers(otherMatch)).containsExactly(otherAdmin.id());
        assertThat(events.stream(RecallMatchedEvent.class)).containsExactlyInAnyOrder(
                new RecallMatchedEvent(tenant, recall, List.of(norteMatch)),
                new RecallMatchedEvent(otherTenant, recall, List.of(otherMatch)));

        long notificationsBefore = jdbc.queryForObject("select count(*) from notifications where reference_id in (?, ?)",
                Long.class, norteMatch, otherMatch);
        List<RecallMatch> again = recallMatchingService.matchAnnouncement(recall);

        assertThat(again).extracting(RecallMatch::getId).containsExactlyInAnyOrder(norteMatch, otherMatch);
        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_id in (?, ?)", Long.class,
                norteMatch, otherMatch)).isEqualTo(notificationsBefore);
        assertThat(affectedTenants(recall)).isEqualTo(2);
        assertThat(events.stream(RecallMatchedEvent.class)).hasSize(2);
    }

    @Test
    void notPublishedAnnouncementsDoNotMatch() {
        data.lot(tenant, norte, product, "L2409A", "L2409A", today.plusDays(20), 5, "ACTIVE", 3);
        long archived = data.recall(barcode, true, null, null, "ARCHIVED");

        assertThat(recallMatchingService.matchAnnouncement(archived)).isEmpty();
        verify(realtimePublisher, never()).toUsers(anyCollection(), any(), any());
    }

    @Test
    void transferDestinationLotGoesThroughTheRecallCheck() {
        long origin = data.lot(tenant, centro, product, "L2409A", "L2409A", today.plusDays(15), 10, "ACTIVE", 6);
        long recall = data.recall(barcode, false, today.plusDays(10), today.plusDays(20), "PUBLISHED", "L2409A");

        TransferResult result = stockService.transfer(new TransferCommand(tenant, centro, norte,
                List.of(new TransferItem(origin, 4)), null, admin.id()));

        Lot destination = result.destinationLots().getFirst();
        assertThat(result.recallMatches()).singleElement().satisfies(match -> {
            assertThat(match.getAnnouncementId()).isEqualTo(recall);
            assertThat(match.getBranchId()).isEqualTo(norte);
            assertThat(match.getLotId()).isEqualTo(destination.getId());
        });
        assertThat(lotStatus(destination.getId())).isEqualTo(LotStatus.RECALLED);
        assertThat(notifiedUsers(result.recallMatches().getFirst().getId()))
                .containsExactlyInAnyOrder(admin.id(), boss.id(), employeeNorte.id());
    }

    @Test
    void checkTenantQuarantinesWhatWasMissedWhileTheTenantWasBlocked() {
        long blockedTenant = data.tenant("Recall D", "KIOSCO", "FREEMIUM", "DISABLED", "FIFO");
        long blockedBranch = data.branch(blockedTenant, "Principal", true);
        long blockedProduct = data.product(blockedTenant, barcode, "Sopa de tomate", "800", "1200");
        AuthUser blockedAdmin = data.user(blockedTenant, Role.TENANT_ADMIN, true);
        long matching = data.lot(blockedTenant, blockedBranch, blockedProduct, "L2409A", "L2409A", today.plusDays(20),
                6, "ACTIVE", 3);
        long other = data.lot(blockedTenant, blockedBranch, blockedProduct, "L9999Z", "L9999Z", today.plusDays(20), 4,
                "ACTIVE", 3);
        long recall = data.recall(barcode, false, today, today.plusDays(30), "PUBLISHED", "L2409A");

        assertThat(recallMatchingService.matchAnnouncement(recall))
                .as("mientras el comercio esta bloqueado el barrido no lo alcanza").isEmpty();

        jdbc.update("update tenants set status = 'ACTIVE' where id = ?", blockedTenant);
        List<RecallMatch> matches = recallMatchingService.checkTenant(blockedTenant);

        assertThat(matches).extracting(RecallMatch::getLotId, RecallMatch::getBranchId)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(matching, blockedBranch));
        assertThat(lotStatus(matching)).isEqualTo(LotStatus.RECALLED);
        assertThat(lotStatus(other)).isEqualTo(LotStatus.ACTIVE);
        long matchId = matches.getFirst().getId();
        assertThat(notifiedUsers(matchId)).containsExactly(blockedAdmin.id());
        assertThat(affectedTenants(recall)).isEqualTo(1);
        assertThat(events.stream(RecallMatchedEvent.class))
                .containsExactly(new RecallMatchedEvent(blockedTenant, recall, List.of(matchId)));
        assertThat(jdbc.queryForObject("select count(*) from alerts where tenant_id = ? and type = 'RECALL_MATCH'",
                Long.class, blockedTenant)).isEqualTo(1);

        List<RecallMatch> again = recallMatchingService.checkTenant(blockedTenant);

        assertThat(again).extracting(RecallMatch::getId).as("idempotente").containsExactly(matchId);
        assertThat(notifiedUsers(matchId)).hasSize(1);
        assertThat(events.stream(RecallMatchedEvent.class)).hasSize(1);
        assertThat(recallMatchingService.checkTenant(null)).isEmpty();
    }

    @Test
    void theListenerOnlyRunsWhenTheTenantGoesBackToActive() {
        long blockedTenant = data.tenant("Recall E", "KIOSCO", "FREEMIUM", "DISABLED", "FIFO");
        long blockedBranch = data.branch(blockedTenant, "Principal", true);
        long blockedProduct = data.product(blockedTenant, barcode, "Sopa de tomate", "800", "1200");
        data.user(blockedTenant, Role.TENANT_ADMIN, true);
        long matching = data.lot(blockedTenant, blockedBranch, blockedProduct, "L2409A", "L2409A", today.plusDays(20),
                6, "ACTIVE", 3);
        data.recall(barcode, false, today, today.plusDays(30), "PUBLISHED", "L2409A");

        tenantReactivationRecallListener.onTenantStatusChanged(
                new TenantStatusChangedEvent(blockedTenant, TenantStatus.ACTIVE, TenantStatus.DISABLED));
        assertThat(lotStatus(matching)).as("al bloquear no se revisa nada").isEqualTo(LotStatus.ACTIVE);

        jdbc.update("update tenants set status = 'ACTIVE' where id = ?", blockedTenant);
        tenantReactivationRecallListener.onTenantStatusChanged(
                new TenantStatusChangedEvent(blockedTenant, TenantStatus.DISABLED, TenantStatus.ACTIVE));

        assertThat(lotStatus(matching)).isEqualTo(LotStatus.RECALLED);
    }

    private List<Long> notifiedUsers(long matchId) {
        return jdbc.queryForList("select user_id from notifications where reference_type = 'RECALL_MATCH' "
                + "and reference_id = ?", Long.class, matchId);
    }

    private int affectedTenants(long announcementId) {
        entityManager.flush();
        return jdbc.queryForObject("select affected_tenants_count from announcements where id = ?", Integer.class,
                announcementId);
    }

    private LotStatus lotStatus(long lotId) {
        entityManager.flush();
        return LotStatus.valueOf(jdbc.queryForObject("select status from lots where id = ?", String.class, lotId));
    }
}
