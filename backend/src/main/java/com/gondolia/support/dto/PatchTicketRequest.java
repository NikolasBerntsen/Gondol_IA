package com.gondolia.support.dto;

import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketPriority;

/** Reclasificación de un ticket: prioridad y/o categoría (los campos nulos no se tocan). */
public record PatchTicketRequest(TicketPriority priority, TicketCategory category) {

    public boolean isEmpty() {
        return priority == null && category == null;
    }
}
