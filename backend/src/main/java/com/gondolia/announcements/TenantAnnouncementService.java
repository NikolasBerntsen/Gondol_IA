package com.gondolia.announcements;

import com.gondolia.announcements.dto.TenantAnnouncementDto;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.announcement.Announcement;
import com.gondolia.domain.announcement.AnnouncementRead;
import com.gondolia.domain.announcement.AnnouncementReadRepository;
import com.gondolia.domain.announcement.AnnouncementRepository;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.domain.announcement.RecallMatch;
import com.gondolia.domain.announcement.RecallMatchRepository;
import com.gondolia.domain.announcement.RecallMatchStatus;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bandeja de avisos de un comercio (SPEC §6.7): avisos publicados que le corresponden por rubro, con estado de lectura
 * y la marca {@code affectsMe} cuando el recall alcanzó a un lote de una sucursal accesible por el usuario.
 * <p>
 * Los avisos archivados no se muestran. Los recalls llegan a todos los rubros; los avisos generales, solo a los
 * rubros elegidos por los dueños (sin rubros = todos).
 */
@Service
@RequiredArgsConstructor
public class TenantAnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementReadRepository readRepository;
    private final RecallMatchRepository recallMatchRepository;
    private final TenantRepository tenantRepository;
    private final BranchAccessService branchAccessService;
    private final AnnouncementMapper mapper;

    /** Avisos visibles para el comercio, más nuevos primero. */
    @Transactional(readOnly = true)
    public PageResponse<TenantAnnouncementDto> list(int page, int size) {
        Long tenantId = CurrentUser.tenantId();
        Long userId = CurrentUser.id();
        List<Announcement> visible = visibleAnnouncements(tenantId);
        int from = Math.min(page * size, visible.size());
        int to = Math.min(from + size, visible.size());
        List<Announcement> pageContent = visible.subList(from, to);

        List<Long> ids = pageContent.stream().map(Announcement::getId).toList();
        Set<Long> read = ids.isEmpty() ? Set.of() : new HashSet<>(readRepository.findReadAnnouncementIds(userId, ids));
        Map<Long, List<String>> lots = mapper.lotNumbersByAnnouncement(ids);
        Map<Long, MatchCount> matches = myMatchCounts(tenantId);

        List<TenantAnnouncementDto> content = pageContent.stream().map(announcement -> {
            MatchCount count = matches.getOrDefault(announcement.getId(), MatchCount.NONE);
            return new TenantAnnouncementDto(
                    announcement.getId(),
                    announcement.getKind(),
                    announcement.getSeverity(),
                    announcement.getTitle(),
                    announcement.getBody(),
                    announcement.getPublishedAt(),
                    read.contains(announcement.getId()),
                    count.total() > 0,
                    count.total(),
                    count.open(),
                    mapper.recall(announcement, lots.getOrDefault(announcement.getId(), List.of())));
        }).toList();
        return PageResponse.of(content, page, size, visible.size());
    }

    /** Marca el aviso como leído (idempotente). 404 si el aviso no le corresponde al comercio. */
    @Transactional
    public void markRead(Long announcementId) {
        Long tenantId = CurrentUser.tenantId();
        Long userId = CurrentUser.id();
        Announcement announcement = announcementRepository.findById(announcementId)
                .filter(found -> isVisible(found, businessTypeOf(tenantId)))
                .orElseThrow(() -> new NotFoundException("El aviso no existe"));
        if (readRepository.existsByAnnouncementIdAndUserId(announcement.getId(), userId)) {
            return;
        }
        try {
            readRepository.saveAndFlush(new AnnouncementRead(announcement.getId(), userId));
        } catch (DataIntegrityViolationException alreadyRead) {
            // Dos pestañas marcaron el mismo aviso a la vez: con una fila alcanza.
        }
    }

    /** Avisos visibles que el usuario todavía no leyó. */
    @Transactional(readOnly = true)
    public long unreadCount() {
        Long tenantId = CurrentUser.tenantId();
        List<Long> ids = visibleAnnouncements(tenantId).stream().map(Announcement::getId).toList();
        if (ids.isEmpty()) {
            return 0;
        }
        return ids.size() - readRepository.findReadAnnouncementIds(CurrentUser.id(), ids).size();
    }

    // ------------------------------------------------------------------ helpers

    private List<Announcement> visibleAnnouncements(Long tenantId) {
        BusinessType businessType = businessTypeOf(tenantId);
        return announcementRepository.findByStatusOrderByPublishedAtDesc(AnnouncementStatus.PUBLISHED).stream()
                .filter(announcement -> isVisible(announcement, businessType))
                .toList();
    }

    private static boolean isVisible(Announcement announcement, BusinessType businessType) {
        if (announcement.getStatus() != AnnouncementStatus.PUBLISHED) {
            return false;
        }
        if (announcement.isRecall()) {
            return true;
        }
        Set<BusinessType> targets = announcement.targetBusinessTypeSet();
        return targets.isEmpty() || (businessType != null && targets.contains(businessType));
    }

    private BusinessType businessTypeOf(Long tenantId) {
        return tenantRepository.findById(tenantId).map(Tenant::getBusinessType).orElse(null);
    }

    /**
     * Coincidencias del comercio por aviso, limitadas a las sucursales accesibles por el usuario: un empleado nunca ve
     * recalls de sucursales que no tiene asignadas (SPEC §3.4.6).
     */
    private Map<Long, MatchCount> myMatchCounts(Long tenantId) {
        List<Long> branchIds = branchAccessService.accessibleBranches().stream()
                .map(BranchAccessService.BranchRef::id)
                .toList();
        if (branchIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, MatchCount> counts = new HashMap<>();
        for (RecallMatch match : recallMatchRepository.findByTenantIdAndBranchIdInOrderByMatchedAtDesc(tenantId,
                branchIds)) {
            counts.merge(match.getAnnouncementId(),
                    new MatchCount(1, match.getStatus() == RecallMatchStatus.RESOLVED ? 0 : 1), MatchCount::plus);
        }
        return counts;
    }

    /** Coincidencias del comercio con un aviso: totales y pendientes (sin resolver). */
    private record MatchCount(int total, int open) {

        private static final MatchCount NONE = new MatchCount(0, 0);

        MatchCount plus(MatchCount other) {
            return new MatchCount(total + other.total, open + other.open);
        }
    }
}
