package com.gondolia.support.dto;

import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketChannel;
import com.gondolia.domain.support.TicketPriority;
import com.gondolia.domain.support.TicketStatus;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import java.time.Instant;
import java.util.List;

/** {@code TicketSummary} + conversación completa, comentario de la calificación y primera respuesta (SPEC §6.8). */
public record TicketDetail(Long id, Long tenantId, String tenantName, TenantPlan tenantPlan,
                           BusinessType tenantBusinessType, String subject, TicketCategory category,
                           TicketPriority priority, TicketStatus status, TicketChannel channel, Long createdById,
                           String createdByName, Role createdByRole, Long assignedToId, String assignedToName,
                           Instant lastMessageAt, String lastMessagePreview, int unreadCount, Instant createdAt,
                           Instant resolvedAt, Integer rating, List<MessageDto> messages, String ratingComment,
                           Instant firstResponseAt) {

    public static TicketDetail of(TicketSummary summary, List<MessageDto> messages, String ratingComment,
                                  Instant firstResponseAt) {
        return new TicketDetail(summary.id(), summary.tenantId(), summary.tenantName(), summary.tenantPlan(),
                summary.tenantBusinessType(), summary.subject(), summary.category(), summary.priority(),
                summary.status(), summary.channel(), summary.createdById(), summary.createdByName(),
                summary.createdByRole(), summary.assignedToId(), summary.assignedToName(), summary.lastMessageAt(),
                summary.lastMessagePreview(), summary.unreadCount(), summary.createdAt(), summary.resolvedAt(),
                summary.rating(), messages == null ? List.of() : List.copyOf(messages), ratingComment,
                firstResponseAt);
    }
}
