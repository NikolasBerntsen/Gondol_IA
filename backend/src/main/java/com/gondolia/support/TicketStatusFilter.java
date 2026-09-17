package com.gondolia.support;

import com.gondolia.domain.support.TicketStatus;
import java.util.List;

/**
 * Filtro de estado de la bandeja. Además de cada {@code TicketStatus} acepta {@code ALL} (todos) y {@code ACTIVE}
 * (los que siguen necesitando atención: abierto, en curso y esperando respuesta). Spring convierte el parámetro sin
 * distinguir mayúsculas, así que {@code ?status=active} también funciona.
 */
public enum TicketStatusFilter {
    ALL(null),
    ACTIVE(SupportQueryRepository.ACTIVE_STATUSES),
    OPEN(List.of(TicketStatus.OPEN)),
    IN_PROGRESS(List.of(TicketStatus.IN_PROGRESS)),
    WAITING_CUSTOMER(List.of(TicketStatus.WAITING_CUSTOMER)),
    RESOLVED(List.of(TicketStatus.RESOLVED)),
    CLOSED(List.of(TicketStatus.CLOSED));

    private final List<TicketStatus> statuses;

    TicketStatusFilter(List<TicketStatus> statuses) {
        this.statuses = statuses;
    }

    /** Estados incluidos, o {@code null} si no hay que filtrar. */
    public List<TicketStatus> statuses() {
        return statuses;
    }
}
