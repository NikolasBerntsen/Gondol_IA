package com.gondolia.announcements.dto;

import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.tenant.BusinessType;
import java.time.Instant;
import java.util.List;

/**
 * Fila del listado de avisos de la consola de dueños. Solo agregados: nunca dice qué comercios coinciden (SPEC §3.4).
 */
public record AnnouncementListItem(
        Long id,
        AnnouncementKind kind,
        Severity severity,
        AnnouncementStatus status,
        String title,
        String body,
        List<BusinessType> targetBusinessTypes,
        Instant publishedAt,
        Instant createdAt,
        String createdByName,
        int recipientsCount,
        int affectedTenantsCount,
        RecallDetailDto recall) {
}
