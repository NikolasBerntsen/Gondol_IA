package com.gondolia.notification;

import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.NotificationType;
import java.util.Objects;

/**
 * Contenido de una notificación a enviar. {@code severity} null = {@code INFO}; {@code link} es una ruta del frontend
 * (p. ej. {@code /app/recalls}); {@code referenceType}/{@code referenceId} identifican la entidad relacionada.
 * El título se recorta a 200 caracteres; {@code link} admite hasta 300 y {@code referenceType} hasta 30.
 */
public record NotificationDraft(NotificationType type, Severity severity, String title, String body, String link,
                                String referenceType, Long referenceId) {

    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_LINK_LENGTH = 300;
    static final int MAX_REFERENCE_TYPE_LENGTH = 30;

    public NotificationDraft {
        Objects.requireNonNull(type, "type");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("El título de la notificación es obligatorio");
        }
        title = title.strip();
        if (title.length() > MAX_TITLE_LENGTH) {
            title = title.substring(0, MAX_TITLE_LENGTH - 1) + "…";
        }
        severity = severity == null ? Severity.INFO : severity;
        link = link == null || link.isBlank() ? null : link.strip();
        if (link != null && link.length() > MAX_LINK_LENGTH) {
            throw new IllegalArgumentException("El link de la notificación supera " + MAX_LINK_LENGTH + " caracteres");
        }
        if (referenceType != null && referenceType.length() > MAX_REFERENCE_TYPE_LENGTH) {
            throw new IllegalArgumentException(
                    "referenceType supera " + MAX_REFERENCE_TYPE_LENGTH + " caracteres");
        }
    }

    public static NotificationDraft of(NotificationType type, Severity severity, String title, String body,
                                       String link) {
        return new NotificationDraft(type, severity, title, body, link, null, null);
    }
}
