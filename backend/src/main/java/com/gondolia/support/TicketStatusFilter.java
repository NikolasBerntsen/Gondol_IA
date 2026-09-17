package com.gondolia.support;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.support.TicketStatus;
import java.util.List;
import java.util.Locale;

/**
 * Filtro de estado de la bandeja. Además de cada {@code TicketStatus} acepta {@code ALL} (todos) y {@code ACTIVE}
 * (los que siguen necesitando atención: abierto, en curso y esperando respuesta). No distingue mayúsculas, así que
 * {@code ?status=active} también funciona.
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

    /** Valor del parámetro {@code status}; vacío = {@code ALL}. */
    public static TicketStatusFilter from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El estado tiene que ser ALL, ACTIVE, OPEN, IN_PROGRESS, WAITING_CUSTOMER, RESOLVED o CLOSED.");
        }
    }
}
