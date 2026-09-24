package com.gondolia.recall;

import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.events.RecallMatchedEvent;
import com.gondolia.common.events.StockChangedEvent;
import com.gondolia.common.util.Barcodes;
import com.gondolia.common.util.LotNumbers;
import com.gondolia.domain.alert.Alert;
import com.gondolia.domain.alert.AlertType;
import com.gondolia.domain.alert.OpenAlertWriter;
import com.gondolia.domain.announcement.Announcement;
import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.announcement.AnnouncementRecallLot;
import com.gondolia.domain.announcement.AnnouncementRecallLotRepository;
import com.gondolia.domain.announcement.AnnouncementRepository;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.domain.announcement.RecallMatch;
import com.gondolia.domain.announcement.RecallMatchRepository;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.notification.NotificationType;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.notification.NotificationDraft;
import com.gondolia.notification.NotificationService;
import com.gondolia.realtime.Destinations;
import com.gondolia.realtime.RealtimePublisher;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coincidencia de recalls publicados con lotes de las sucursales (SPEC §5.3).
 * <p>
 * Un lote coincide con un recall {@code PUBLISHED} si {@code products.barcode = recall_barcode} y
 * ({@code recall_all_lots} o su número de lote normalizado está entre los del recall) y, si el recall tiene rango de
 * vencimiento, su {@code expiry_date} cae dentro (con solo "desde" o solo "hasta" se usa ese límite; un lote sin
 * vencimiento no coincide con un recall con rango). Solo se consideran lotes {@code ACTIVE} o {@code RECALLED} con
 * remanente.
 * <p>
 * Por cada coincidencia <b>nueva</b>: crea {@code recall_matches} (idempotente por {@code (announcement_id, lot_id)}),
 * pone el lote en {@code RECALLED}, abre la alerta {@code RECALL_MATCH} CRITICAL de la sucursal, notifica
 * ({@code RECALL_ALERT}, con el deep link {@code /app/recalls?match=<id>}) y hace push de {@link RecallAlertMessage}
 * a los usuarios con acceso a esa sucursal, actualiza {@code announcements.affected_tenants_count} y publica
 * {@link RecallMatchedEvent} por tenant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecallMatchingService {

    /** Recall que alcanza a un producto/lote, para mostrar al cargar mercadería. */
    public record RecallInfo(Long announcementId, String title, String reason, String instructions, boolean allLots) {
    }

    public static final String ALERT_DEDUPE_PREFIX = "RECALL:";
    public static final String RECALLS_LINK = "/app/recalls";
    public static final String REFERENCE_TYPE = "RECALL_MATCH";

    /**
     * Link de la notificación {@code RECALL_ALERT}: el deep link de la coincidencia, igual que el del diálogo de
     * seguridad. Sin {@code ?match=…} la campana cae en la sucursal elegida en el topbar, que puede no ser la del
     * lote alcanzado (SPEC §3.4.6, api-d §5).
     */
    public static String recallMatchLink(Long matchId) {
        return matchId == null ? RECALLS_LINK : RECALLS_LINK + "?match=" + matchId;
    }

    /** Título de la alerta {@code RECALL_MATCH} de un lote alcanzado. */
    public static String alertTitle(String productName, String lotNumber) {
        return "Recall: " + productName + " (lote " + lotLabel(lotNumber) + ")";
    }

    /** Título de la notificación {@code RECALL_ALERT}. */
    public static String notificationTitle(String productName) {
        return "Alerta de recall: " + productName;
    }

    /**
     * Texto de la alerta y de la notificación de una coincidencia: sucursal, lote, vencimiento, recall, motivo y qué
     * hacer. Público para que la siembra de datos demo escriba exactamente lo mismo que el barrido en vivo.
     */
    public static String alertMessage(String branchName, String productName, String lotNumber, LocalDate expiryDate,
                                      String recallTitle, String reason, String instructions) {
        StringBuilder text = new StringBuilder()
                .append(branchName).append(": el lote ").append(lotLabel(lotNumber)).append(" de ").append(productName);
        if (expiryDate != null) {
            text.append(" (vence ").append(DATE_FORMAT.format(expiryDate)).append(')');
        }
        text.append(" está alcanzado por el recall \"").append(recallTitle)
                .append("\". Quedó en cuarentena y no se puede vender.");
        appendSentence(text, "Motivo", reason);
        appendSentence(text, "Qué hacer", instructions);
        return text.toString();
    }

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final Set<LotStatus> CANDIDATE_STATUSES = EnumSet.of(LotStatus.ACTIVE, LotStatus.RECALLED);
    private static final String INSERT_MATCH_SQL = """
            insert into recall_matches (announcement_id, tenant_id, branch_id, product_id, lot_id, quantity_at_match,
                                        status, matched_at)
            values (?, ?, ?, ?, ?, ?, 'OPEN', ?)
            on conflict (announcement_id, lot_id) do nothing
            returning id
            """;
    private static final int[] INSERT_MATCH_TYPES = {
            Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.BIGINT, Types.INTEGER,
            Types.TIMESTAMP_WITH_TIMEZONE
    };

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementRecallLotRepository recallLotRepository;
    private final RecallMatchRepository recallMatchRepository;
    private final LotRepository lotRepository;
    private final ProductRepository productRepository;
    private final BranchRepository branchRepository;
    private final OpenAlertWriter openAlertWriter;
    private final NotificationService notificationService;
    private final RealtimePublisher realtimePublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    // ------------------------------------------------------------------ API

    /** Recalls publicados que alcanzan a ese código, lote y vencimiento. Sin efectos. */
    @Transactional(readOnly = true)
    public List<RecallInfo> findActiveRecalls(String barcode, String lotNumber, LocalDate expiryDate) {
        String code = Barcodes.normalize(barcode);
        if (code == null) {
            return List.of();
        }
        String normalizedLot = LotNumbers.normalize(lotNumber);
        return publishedRecalls(code).stream()
                .filter(recall -> recall.matches(normalizedLot, expiryDate))
                .map(recall -> info(recall.announcement()))
                .toList();
    }

    /**
     * Barre los lotes con remanente ({@code ACTIVE}/{@code RECALLED}) de tenants {@code ACTIVE} contra un recall
     * publicado. Devuelve todas las coincidencias vigentes del recall con esos lotes (nuevas y previas); los efectos
     * se aplican solo a las nuevas. Si el aviso no es un recall publicado, devuelve una lista vacía.
     */
    @Transactional
    public List<RecallMatch> matchAnnouncement(Long announcementId) {
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new NotFoundException("El aviso no existe"));
        String barcode = Barcodes.normalize(announcement.getRecallBarcode());
        if (announcement.getKind() != AnnouncementKind.RECALL || announcement.getStatus() != AnnouncementStatus.PUBLISHED
                || barcode == null) {
            return List.of();
        }
        RecallCriteria recall = new RecallCriteria(announcement,
                lotNumbersByAnnouncement(List.of(announcementId)).getOrDefault(announcementId, Set.of()));
        List<Lot> candidates = lotRepository.findStockedLotsByBarcodeInActiveTenants(barcode).stream()
                .filter(recall::matches)
                .toList();
        Map<Long, Product> products = productRepository.findAllById(
                        candidates.stream().map(Lot::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        MatchRun run = new MatchRun();
        for (Lot lot : candidates) {
            matchLot(recall, lot, products.get(lot.getProductId()), run);
        }
        complete(run, List.of(announcement));
        log.info("Recall {}: {} coincidencias ({} nuevas), {} comercios afectados", announcementId,
                run.matches.size(), run.createdCount, announcement.getAffectedTenantsCount());
        return List.copyOf(run.matches);
    }

    /**
     * Chequea un lote contra los recalls publicados (al cargar mercadería, al recibir una transferencia o al corregir
     * número/vencimiento). Devuelve las coincidencias vigentes del lote (nuevas y previas); si hay nuevas, el lote queda
     * {@code RECALLED}. Lotes sin remanente o ya descartados no se chequean.
     */
    @Transactional
    public List<RecallMatch> checkLot(Long tenantId, Long lotId) {
        Lot lot = lotRepository.findByIdAndTenantId(lotId, tenantId)
                .orElseThrow(() -> new NotFoundException("El lote no existe"));
        if (!isCandidate(lot)) {
            return List.of();
        }
        Product product = productRepository.findByIdAndTenantId(lot.getProductId(), tenantId).orElse(null);
        String barcode = product == null ? null : Barcodes.normalize(product.getBarcode());
        if (barcode == null) {
            return List.of();
        }
        List<RecallCriteria> recalls = publishedRecalls(barcode).stream().filter(recall -> recall.matches(lot)).toList();
        if (recalls.isEmpty()) {
            return List.of();
        }
        MatchRun run = new MatchRun();
        for (RecallCriteria recall : recalls) {
            matchLot(recall, lot, product, run);
        }
        complete(run, recalls.stream().map(RecallCriteria::announcement).toList());
        return List.copyOf(run.matches);
    }

    /**
     * Chequea <b>todos</b> los lotes con remanente de un comercio contra los recalls {@code PUBLISHED}. Se usa cuando
     * un comercio vuelve a estar {@code ACTIVE} (mientras estuvo bloqueado, {@code matchAnnouncement} lo salteaba) y
     * lo dispara el listener de {@code TenantStatusChangedEvent}. Mismos efectos e idempotencia que
     * {@link #checkLot}: solo actúa sobre coincidencias nuevas.
     *
     * @return las coincidencias vigentes de ese comercio con recalls publicados (nuevas y previas)
     */
    @Transactional
    public List<RecallMatch> checkTenant(Long tenantId) {
        if (tenantId == null) {
            return List.of();
        }
        List<Lot> candidates = lotRepository.findStockedLotsWithBarcodeByTenant(tenantId);
        if (candidates.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> products = productRepository.findAllById(
                        candidates.stream().map(Lot::getProductId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        Map<String, List<RecallCriteria>> recallsByBarcode = new HashMap<>();
        Map<Long, Announcement> announcements = new LinkedHashMap<>();
        MatchRun run = new MatchRun();
        for (Lot lot : candidates) {
            Product product = products.get(lot.getProductId());
            String barcode = product == null ? null : Barcodes.normalize(product.getBarcode());
            if (barcode == null) {
                continue;
            }
            for (RecallCriteria recall : recallsByBarcode.computeIfAbsent(barcode, this::publishedRecalls)) {
                if (!recall.matches(lot)) {
                    continue;
                }
                announcements.putIfAbsent(recall.announcement().getId(), recall.announcement());
                matchLot(recall, lot, product, run);
            }
        }
        complete(run, announcements.values());
        if (!run.matches.isEmpty()) {
            log.info("Comercio {}: {} coincidencias de recall ({} nuevas) al revisar {} lotes", tenantId,
                    run.matches.size(), run.createdCount, candidates.size());
        }
        return List.copyOf(run.matches);
    }

    /** Recalls (sin repetir, en el orden de las coincidencias) de un conjunto de coincidencias. */
    @Transactional(readOnly = true)
    public List<RecallInfo> toRecallInfos(Collection<RecallMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        List<Long> ids = matches.stream().map(RecallMatch::getAnnouncementId).distinct().toList();
        Map<Long, Announcement> announcements = announcementRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Announcement::getId, Function.identity()));
        return ids.stream().map(announcements::get).filter(Objects::nonNull).map(RecallMatchingService::info).toList();
    }

    // ------------------------------------------------------------------ coincidencias

    private void matchLot(RecallCriteria recall, Lot candidate, Product product, MatchRun run) {
        Long announcementId = recall.announcement().getId();
        Optional<RecallMatch> existing = recallMatchRepository.findByAnnouncementIdAndLotId(announcementId,
                candidate.getId());
        if (existing.isPresent()) {
            run.matches.add(existing.get());
            return;
        }

        Lot lot = lockFresh(candidate);
        if (!isCandidate(lot) || !recall.matches(lot)) {
            return;
        }
        Long matchId = insertMatchIfAbsent(announcementId, lot);
        if (matchId == null) {
            // Otra transacción registró la misma coincidencia en paralelo: ya aplicó los efectos.
            recallMatchRepository.findByAnnouncementIdAndLotId(announcementId, lot.getId()).ifPresent(run.matches::add);
            return;
        }
        RecallMatch match = recallMatchRepository.findById(matchId)
                .orElseThrow(() -> new IllegalStateException("No se encontró la coincidencia " + matchId));
        boolean quarantined = lot.getStatus() == LotStatus.ACTIVE;
        if (quarantined) {
            lot.setStatus(LotStatus.RECALLED);
        }
        run.matches.add(match);
        run.createdCount++;
        onNewMatch(recall.announcement(), match, lot, product, quarantined, run);
    }

    private void onNewMatch(Announcement announcement, RecallMatch match, Lot lot, Product product,
                            boolean quarantined, MatchRun run) {
        String branchName = run.branchNames.computeIfAbsent(lot.getBranchId(),
                id -> branchRepository.findById(id).map(Branch::getName).orElse("Sucursal"));
        String productName = product != null ? product.getName() : "Producto";
        String message = alertMessage(branchName, productName, lot.getLotNumber(), lot.getExpiryDate(),
                announcement.getTitle(), announcement.getRecallReason(), announcement.getRecallInstructions());

        Alert alert = new Alert();
        alert.setTenantId(lot.getTenantId());
        alert.setBranchId(lot.getBranchId());
        alert.setType(AlertType.RECALL_MATCH);
        alert.setSeverity(Severity.CRITICAL);
        alert.setProductId(lot.getProductId());
        alert.setLotId(lot.getId());
        alert.setAnnouncementId(announcement.getId());
        alert.setTitle(alertTitle(productName, lot.getLotNumber()));
        alert.setMessage(message);
        alert.setDedupeKey(ALERT_DEDUPE_PREFIX + announcement.getId() + ":" + lot.getId());
        openAlertWriter.openIfAbsent(alert);

        List<Long> recipients = run.recipients.computeIfAbsent(lot.getBranchId(),
                branchId -> notificationService.branchRecipients(lot.getTenantId(), branchId, null));
        notificationService.notifyUsers(recipients, new NotificationDraft(NotificationType.RECALL_ALERT,
                Severity.CRITICAL, notificationTitle(productName), message, recallMatchLink(match.getId()),
                REFERENCE_TYPE, match.getId()));
        realtimePublisher.toUsers(recipients, Destinations.QUEUE_SECURITY_ALERTS, new RecallAlertMessage(
                match.getId(), announcement.getId(), lot.getBranchId(), branchName, announcement.getTitle(),
                announcement.getSeverity(), announcement.getRecallReason(), announcement.getRecallInstructions(),
                lot.getProductId(), productName, product != null ? product.getBarcode() : null, lot.getId(),
                lot.getLotNumber(), lot.getExpiryDate(), match.getQuantityAtMatch(), match.getMatchedAt()));

        if (quarantined) {
            eventPublisher.publishEvent(new StockChangedEvent(lot.getTenantId(), lot.getBranchId(), lot.getProductId()));
        }
        run.newMatchIds.computeIfAbsent(new TenantAnnouncement(lot.getTenantId(), announcement.getId()),
                key -> new ArrayList<>()).add(match.getId());
        log.info("Recall {} coincide con el lote {} (tenant {}, sucursal {}): {} destinatarios", announcement.getId(),
                lot.getId(), lot.getTenantId(), lot.getBranchId(), recipients.size());
    }

    private void complete(MatchRun run, Collection<Announcement> announcements) {
        lotRepository.flush();
        for (Announcement announcement : announcements) {
            long affected = recallMatchRepository.countDistinctTenantsByAnnouncementId(announcement.getId());
            announcement.setAffectedTenantsCount(Math.toIntExact(affected));
        }
        run.newMatchIds.forEach((key, ids) ->
                eventPublisher.publishEvent(new RecallMatchedEvent(key.tenantId(), key.announcementId(), ids)));
    }

    /** Bloquea la fila del lote y recarga su estado (evita pisar un remanente modificado por otra transacción). */
    private Lot lockFresh(Lot lot) {
        entityManager.flush();
        entityManager.refresh(lot, LockModeType.PESSIMISTIC_WRITE);
        return lot;
    }

    private Long insertMatchIfAbsent(Long announcementId, Lot lot) {
        Object[] args = {
                announcementId, lot.getTenantId(), lot.getBranchId(), lot.getProductId(), lot.getId(), lot.getQuantity(),
                OffsetDateTime.ofInstant(Timestamps.now(), ZoneOffset.UTC)
        };
        List<Long> ids = jdbcTemplate.query(INSERT_MATCH_SQL, args, INSERT_MATCH_TYPES, (rs, rowNum) -> rs.getLong(1));
        return ids.isEmpty() ? null : ids.getFirst();
    }

    // ------------------------------------------------------------------ recalls publicados

    private List<RecallCriteria> publishedRecalls(String barcode) {
        List<Announcement> announcements = announcementRepository.findByKindAndStatusAndRecallBarcode(
                AnnouncementKind.RECALL, AnnouncementStatus.PUBLISHED, barcode);
        if (announcements.isEmpty()) {
            return List.of();
        }
        Map<Long, Set<String>> lotNumbers =
                lotNumbersByAnnouncement(announcements.stream().map(Announcement::getId).toList());
        return announcements.stream()
                .sorted(Comparator.comparing(Announcement::getPublishedAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Announcement::getId))
                .map(announcement -> new RecallCriteria(announcement,
                        lotNumbers.getOrDefault(announcement.getId(), Set.of())))
                .toList();
    }

    private Map<Long, Set<String>> lotNumbersByAnnouncement(Collection<Long> announcementIds) {
        Map<Long, Set<String>> result = new HashMap<>();
        for (AnnouncementRecallLot recallLot : recallLotRepository.findByAnnouncementIdIn(announcementIds)) {
            String normalized = recallLot.getLotNumberNormalized() != null
                    ? LotNumbers.normalize(recallLot.getLotNumberNormalized())
                    : LotNumbers.normalize(recallLot.getLotNumber());
            if (normalized != null) {
                result.computeIfAbsent(recallLot.getAnnouncementId(), id -> new LinkedHashSet<>()).add(normalized);
            }
        }
        return result;
    }

    private record RecallCriteria(Announcement announcement, Set<String> lotNumbers) {

        boolean matches(Lot lot) {
            String normalized = lot.getLotNumberNormalized() != null
                    ? lot.getLotNumberNormalized()
                    : LotNumbers.normalize(lot.getLotNumber());
            return matches(normalized, lot.getExpiryDate());
        }

        boolean matches(String normalizedLotNumber, LocalDate expiryDate) {
            boolean lotMatches = announcement.isRecallAllLots()
                    || (normalizedLotNumber != null && lotNumbers.contains(normalizedLotNumber));
            if (!lotMatches) {
                return false;
            }
            LocalDate from = announcement.getRecallExpiryFrom();
            LocalDate to = announcement.getRecallExpiryTo();
            if (from == null && to == null) {
                return true;
            }
            if (expiryDate == null) {
                return false;
            }
            return (from == null || !expiryDate.isBefore(from)) && (to == null || !expiryDate.isAfter(to));
        }
    }

    private record TenantAnnouncement(Long tenantId, Long announcementId) {
    }

    /** Estado de una ejecución de matching (caches y coincidencias nuevas). */
    private static final class MatchRun {
        private final List<RecallMatch> matches = new ArrayList<>();
        private final Map<Long, String> branchNames = new HashMap<>();
        private final Map<Long, List<Long>> recipients = new HashMap<>();
        private final Map<TenantAnnouncement, List<Long>> newMatchIds = new LinkedHashMap<>();
        private int createdCount;
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isCandidate(Lot lot) {
        return lot.getQuantity() > 0 && CANDIDATE_STATUSES.contains(lot.getStatus());
    }

    private static RecallInfo info(Announcement announcement) {
        return new RecallInfo(announcement.getId(), announcement.getTitle(), announcement.getRecallReason(),
                announcement.getRecallInstructions(), announcement.isRecallAllLots());
    }

    private static String lotLabel(String lotNumber) {
        return lotNumber != null ? lotNumber : "sin número";
    }

    private static void appendSentence(StringBuilder text, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String sentence = value.strip();
        text.append(' ').append(label).append(": ").append(sentence);
        if (!sentence.endsWith(".") && !sentence.endsWith("!") && !sentence.endsWith("?")) {
            text.append('.');
        }
    }
}
