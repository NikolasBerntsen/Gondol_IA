package com.gondolia.announcements.dto;

import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.announcement.AnnouncementStatus;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.tenant.BusinessType;
import java.time.Instant;
import java.util.List;

/**
 * Detalle de un aviso para los dueños, con estadísticas <b>agregadas</b> (SPEC §6.7): cuántos usuarios lo recibieron,
 * cuántos lo leyeron, cuántos comercios quedaron alcanzados y en qué estado están las coincidencias del recall.
 */
public record AnnouncementDetailDto(
        Long id,
        AnnouncementKind kind,
        Severity severity,
        AnnouncementStatus status,
        String title,
        String body,
        List<BusinessType> targetBusinessTypes,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        String createdByName,
        RecallDetailDto recall,
        int recipientsCount,
        long readCount,
        int affectedTenantsCount,
        long matchesOpen,
        long matchesAcknowledged,
        long matchesResolved) {
}
