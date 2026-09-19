package com.gondolia.announcements;

import com.gondolia.announcements.dto.RecallMatchDto;
import com.gondolia.announcements.dto.ResolveRecallRequest;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.alert.Alert;
import com.gondolia.domain.alert.AlertRepository;
import com.gondolia.domain.alert.AlertStatus;
import com.gondolia.domain.announcement.Announcement;
import com.gondolia.domain.announcement.AnnouncementRepository;
import com.gondolia.domain.announcement.RecallMatch;
import com.gondolia.domain.announcement.RecallMatchRepository;
import com.gondolia.domain.announcement.RecallMatchStatus;
import com.gondolia.domain.announcement.RecallResolution;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.stock.StockService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seguridad alimentaria dentro del comercio (SPEC §6.7): las coincidencias de recall de las sucursales accesibles,
 * la confirmación de lectura ("Entendido") y la resolución, que retira del stock lo que quedó en cuarentena.
 * <p>
 * La cuarentena (lote {@code RECALLED}) la aplica el núcleo al detectar la coincidencia
 * ({@link RecallMatchingService}); acá solo se resuelve qué se hizo con esa mercadería.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecallMatchService {

    /** Filtros del listado: {@code ACTIVE} son las que todavía requieren acción (pendientes + confirmadas). */
    public enum MatchFilter {
        ACTIVE, OPEN, ACKNOWLEDGED, RESOLVED, ALL
    }

    private static final Set<RecallMatchStatus> ACTIVE_STATUSES =
            EnumSet.of(RecallMatchStatus.OPEN, RecallMatchStatus.ACKNOWLEDGED);

    private final RecallMatchRepository recallMatchRepository;
    private final AnnouncementRepository announcementRepository;
    private final LotRepository lotRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final AlertRepository alertRepository;
    private final BranchAccessService branchAccessService;
    private final StockService stockService;

    /** Coincidencias del alcance de sucursales elegido, más recientes primero. */
    @Transactional(readOnly = true)
    public List<RecallMatchDto> list(MatchFilter filter) {
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = branchAccessService.scopeBranchIds();
        if (branchIds.isEmpty()) {
            return List.of();
        }
        MatchFilter effective = filter == null ? MatchFilter.ALL : filter;
        List<RecallMatch> matches = switch (effective) {
            case ALL -> recallMatchRepository.findByTenantIdAndBranchIdInOrderByMatchedAtDesc(tenantId, branchIds);
            case ACTIVE -> recallMatchRepository.findByTenantIdAndBranchIdInAndStatusInOrderByMatchedAtDesc(
                    tenantId, branchIds, ACTIVE_STATUSES);
            case OPEN, ACKNOWLEDGED, RESOLVED ->
                    recallMatchRepository.findByTenantIdAndBranchIdInAndStatusInOrderByMatchedAtDesc(
                            tenantId, branchIds, Set.of(RecallMatchStatus.valueOf(effective.name())));
        };
        return toDtos(tenantId, matches);
    }

    /** "Entendido": deja registrado quién vio la alerta. Idempotente. */
    @Transactional
    public RecallMatchDto acknowledge(Long id) {
        Long tenantId = CurrentUser.tenantId();
        RecallMatch match = require(tenantId, id);
        if (match.getStatus() == RecallMatchStatus.OPEN) {
            match.setStatus(RecallMatchStatus.ACKNOWLEDGED);
            match.setAcknowledgedAt(Timestamps.now());
            match.setAcknowledgedBy(CurrentUser.id());
            acknowledgeAlert(match);
            log.info("Coincidencia de recall {} confirmada por el usuario {}", id, CurrentUser.id());
        }
        return toDtos(tenantId, List.of(match)).getFirst();
    }

    /**
     * Resuelve la coincidencia y retira del stock el remanente en cuarentena: {@code RECALL_REMOVAL} si se descarta en
     * el local, {@code ADJUSTMENT_OUT} si se devuelve al proveedor o si no se encontró la mercadería (SPEC §6.7).
     */
    @Transactional
    public RecallMatchDto resolve(Long id, ResolveRecallRequest request) {
        Long tenantId = CurrentUser.tenantId();
        RecallMatch match = require(tenantId, id);
        if (match.getStatus() == RecallMatchStatus.RESOLVED) {
            throw new ConflictException("CONFLICT", "Esta alerta ya está resuelta");
        }
        Lot lot = lotRepository.findByIdAndTenantId(match.getLotId(), tenantId)
                .orElseThrow(() -> new NotFoundException("El lote del recall no existe"));

        if (lot.getQuantity() > 0) {
            stockService.adjust(new StockService.AdjustCommand(tenantId, lot.getId(), movementType(request.resolution()),
                    lot.getQuantity(), reason(match, request), MovementSource.MANUAL, CurrentUser.id()));
        }
        match.setStatus(RecallMatchStatus.RESOLVED);
        match.setResolution(request.resolution());
        match.setResolutionNote(blankToNull(request.note()));
        match.setResolvedAt(Timestamps.now());
        match.setResolvedBy(CurrentUser.id());
        if (match.getAcknowledgedAt() == null) {
            match.setAcknowledgedAt(match.getResolvedAt());
            match.setAcknowledgedBy(CurrentUser.id());
        }
        resolveAlert(match);
        log.info("Coincidencia de recall {} resuelta como {} por el usuario {}", id, request.resolution(),
                CurrentUser.id());
        return toDtos(tenantId, List.of(match)).getFirst();
    }

    // ------------------------------------------------------------------ helpers

    private RecallMatch require(Long tenantId, Long id) {
        RecallMatch match = recallMatchRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException("La alerta de recall no existe"));
        branchAccessService.assertAccess(match.getBranchId());
        return match;
    }

    private static MovementType movementType(RecallResolution resolution) {
        return resolution == RecallResolution.REMOVED_FROM_STOCK
                ? MovementType.RECALL_REMOVAL
                : MovementType.ADJUSTMENT_OUT;
    }

    private static String reason(RecallMatch match, ResolveRecallRequest request) {
        String base = switch (request.resolution()) {
            case REMOVED_FROM_STOCK -> "Retiro por recall #" + match.getAnnouncementId();
            case RETURNED_TO_SUPPLIER -> "Devolución al proveedor por recall #" + match.getAnnouncementId();
            case NOT_FOUND_IN_STORE -> "Recall #" + match.getAnnouncementId() + ": no se encontró en el local";
        };
        String note = blankToNull(request.note());
        return note == null ? base : base + " · " + note;
    }

    /** Pasa la alerta CRITICAL del recall a ACKNOWLEDGED (el tablero deja de pedirla como urgente). */
    private void acknowledgeAlert(RecallMatch match) {
        findOpenAlert(match).ifPresent(alert -> {
            alert.setStatus(AlertStatus.ACKNOWLEDGED);
            alert.setHandledBy(CurrentUser.id());
        });
    }

    private void resolveAlert(RecallMatch match) {
        findOpenAlert(match).ifPresent(alert -> {
            alert.setStatus(AlertStatus.RESOLVED);
            alert.setHandledBy(CurrentUser.id());
            alert.setResolvedAt(Timestamps.now());
        });
    }

    private java.util.Optional<Alert> findOpenAlert(RecallMatch match) {
        String dedupeKey = RecallMatchingService.ALERT_DEDUPE_PREFIX + match.getAnnouncementId() + ":"
                + match.getLotId();
        return alertRepository.findFirstByTenantIdAndDedupeKeyAndStatusIn(match.getTenantId(), dedupeKey,
                EnumSet.of(AlertStatus.OPEN, AlertStatus.ACKNOWLEDGED));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private List<RecallMatchDto> toDtos(Long tenantId, Collection<RecallMatch> matches) {
        if (matches.isEmpty()) {
            return List.of();
        }
        Map<Long, Announcement> announcements = byId(announcementRepository.findAllById(
                ids(matches, RecallMatch::getAnnouncementId)), Announcement::getId);
        Map<Long, Lot> lots = byId(lotRepository.findByTenantIdAndIdIn(tenantId,
                ids(matches, RecallMatch::getLotId)), Lot::getId);
        Map<Long, Product> products = byId(productRepository.findByTenantIdAndIdIn(tenantId,
                ids(matches, RecallMatch::getProductId)), Product::getId);
        Map<Long, String> branchNames = branchAccessService.branchNames(tenantId);

        Set<Long> userIds = new LinkedHashSet<>();
        matches.forEach(match -> {
            if (match.getAcknowledgedBy() != null) {
                userIds.add(match.getAcknowledgedBy());
            }
            if (match.getResolvedBy() != null) {
                userIds.add(match.getResolvedBy());
            }
        });
        Map<Long, String> userNames = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : userRepository.findAllById(userIds)) {
                userNames.put(user.getId(), user.getFullName());
            }
        }

        List<RecallMatchDto> result = new ArrayList<>(matches.size());
        for (RecallMatch match : matches) {
            Announcement announcement = announcements.get(match.getAnnouncementId());
            Lot lot = lots.get(match.getLotId());
            Product product = products.get(match.getProductId());
            result.add(new RecallMatchDto(
                    match.getId(),
                    match.getAnnouncementId(),
                    match.getBranchId(),
                    branchNames.getOrDefault(match.getBranchId(), "Sucursal"),
                    announcement != null ? announcement.getTitle() : "Recall",
                    announcement != null ? announcement.getSeverity() : Severity.CRITICAL,
                    announcement != null ? announcement.getRecallReason() : null,
                    announcement != null ? announcement.getRecallInstructions() : null,
                    match.getProductId(),
                    product != null ? product.getName()
                            : (announcement != null ? announcement.getRecallProductName() : "Producto"),
                    product != null ? product.getBarcode()
                            : (announcement != null ? announcement.getRecallBarcode() : null),
                    match.getLotId(),
                    lot != null ? lot.getLotNumber() : null,
                    lot != null ? lot.getExpiryDate() : null,
                    match.getQuantityAtMatch(),
                    lot != null ? lot.getQuantity() : 0,
                    match.getStatus(),
                    match.getMatchedAt(),
                    match.getAcknowledgedAt(),
                    userNames.get(match.getAcknowledgedBy()),
                    match.getResolvedAt(),
                    userNames.get(match.getResolvedBy()),
                    match.getResolution(),
                    match.getResolutionNote()));
        }
        return result;
    }

    private static List<Long> ids(Collection<RecallMatch> matches,
                                  java.util.function.Function<RecallMatch, Long> getter) {
        return matches.stream().map(getter).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<Long, T> byId(Collection<T> items, java.util.function.Function<T, Long> getter) {
        Map<Long, T> map = new HashMap<>();
        items.forEach(item -> map.put(getter.apply(item), item));
        return map;
    }
}
