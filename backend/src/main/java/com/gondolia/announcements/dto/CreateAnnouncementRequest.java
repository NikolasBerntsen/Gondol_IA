package com.gondolia.announcements.dto;

import com.gondolia.domain.announcement.AnnouncementKind;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.tenant.BusinessType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Alta de un aviso: se publica en el acto (SPEC §6.7). Para {@code RECALL} el bloque {@code recall} es obligatorio y
 * los rubros destinatarios se ignoran (un retiro de seguridad alimentaria alcanza a todos los comercios).
 */
public record CreateAnnouncementRequest(
        @NotNull(message = "es obligatorio")
        AnnouncementKind kind,

        Severity severity,

        @NotBlank(message = "es obligatorio")
        @Size(max = 200, message = "no puede superar 200 caracteres")
        String title,

        @NotBlank(message = "es obligatorio")
        @Size(max = 4000, message = "no puede superar 4000 caracteres")
        String body,

        List<BusinessType> targetBusinessTypes,

        @Valid
        RecallRequest recall) {
}
