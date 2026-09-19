package com.gondolia.announcements.dto;

import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.common.Severity;
import java.time.Instant;

/**
 * Aviso tal como lo ve un comercio. {@code affectsMe} es {@code true} cuando el recall alcanzó a algún lote de una
 * sucursal accesible por el usuario.
 */
public record TenantAnnouncementDto(
        Long id,
        AnnouncementKind kind,
        Severity severity,
        String title,
        String body,
        Instant publishedAt,
        boolean read,
        boolean affectsMe,
        int myMatchesCount,
        int myOpenMatchesCount,
        RecallDetailDto recall) {
}
