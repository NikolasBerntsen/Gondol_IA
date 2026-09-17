package com.gondolia.support.dto;

import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketChannel;
import com.gondolia.domain.support.TicketPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Alta de un ticket o de un chat (SPEC §6.8). {@code category} por defecto {@code USO}, {@code priority}
 * {@code MEDIA} y {@code channel} {@code TICKET}.
 */
public record CreateTicketRequest(
        @NotBlank(message = "es obligatorio")
        @Size(max = 200, message = "no puede superar los 200 caracteres")
        String subject,
        TicketCategory category,
        TicketPriority priority,
        TicketChannel channel,
        @NotBlank(message = "es obligatorio")
        @Size(max = 4000, message = "no puede superar los 4000 caracteres")
        String message) {

    public TicketCategory categoryOrDefault() {
        return category == null ? TicketCategory.USO : category;
    }

    public TicketPriority priorityOrDefault() {
        return priority == null ? TicketPriority.MEDIA : priority;
    }

    public TicketChannel channelOrDefault() {
        return channel == null ? TicketChannel.TICKET : channel;
    }
}
