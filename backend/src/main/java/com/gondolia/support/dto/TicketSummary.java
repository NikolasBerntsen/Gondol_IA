package com.gondolia.support.dto;

import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketChannel;
import com.gondolia.domain.support.TicketPriority;
import com.gondolia.domain.support.TicketStatus;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import java.time.Instant;

/**
 * Fila de la bandeja de soporte (SPEC §6.8).
 * <p>
 * {@code unreadCount} depende de quién mira: para el comercio son los mensajes del agente posteriores a su última
 * lectura; para el agente, los del cliente. En los eventos de {@code /topic/tickets/{id}} viaja en 0 (el destino es
 * compartido por las dos partes) y cada cliente conserva el suyo.
 * <p>
 * {@code tenantPlan} y {@code tenantBusinessType} son datos administrativos del comercio (nunca de negocio): le dan
 * contexto al agente en el panel derecho de la consola.
 */
public record TicketSummary(Long id, Long tenantId, String tenantName, TenantPlan tenantPlan,
                            BusinessType tenantBusinessType, String subject, TicketCategory category,
                            TicketPriority priority, TicketStatus status, TicketChannel channel, Long createdById,
                            String createdByName, Role createdByRole, Long assignedToId, String assignedToName,
                            Instant lastMessageAt, String lastMessagePreview, int unreadCount, Instant createdAt,
                            Instant resolvedAt, Integer rating) {

    /** Copia con el contador de no leídos en cero (eventos de un destino compartido). */
    public TicketSummary withoutUnread() {
        return unreadCount == 0 ? this : new TicketSummary(id, tenantId, tenantName, tenantPlan, tenantBusinessType,
                subject, category, priority, status, channel, createdById, createdByName, createdByRole, assignedToId,
                assignedToName, lastMessageAt, lastMessagePreview, 0, createdAt, resolvedAt, rating);
    }
}
