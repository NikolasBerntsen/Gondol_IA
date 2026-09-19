package com.gondolia.announcements.dto;

import com.gondolia.domain.announcement.RecallResolution;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Resolución de una coincidencia de recall: qué se hizo con la mercadería en cuarentena. */
public record ResolveRecallRequest(
        @NotNull(message = "es obligatoria")
        RecallResolution resolution,

        @Size(max = 300, message = "no puede superar 300 caracteres")
        String note) {
}
