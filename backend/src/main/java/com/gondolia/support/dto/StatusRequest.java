package com.gondolia.support.dto;

import com.gondolia.domain.support.TicketStatus;
import jakarta.validation.constraints.NotNull;

/** Cambio de estado de un ticket desde la consola de soporte. */
public record StatusRequest(@NotNull(message = "es obligatorio") TicketStatus status) {
}
