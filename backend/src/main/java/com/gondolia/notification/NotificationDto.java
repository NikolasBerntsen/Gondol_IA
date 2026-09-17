package com.gondolia.notification;

import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.Notification;
import com.gondolia.domain.notification.NotificationType;
import java.time.Instant;

/**
 * Notificación para la API y el push {@code /user/queue/notifications}.
 */
public record NotificationDto(Long id, NotificationType type, Severity severity, String title, String body, String link,
                              String referenceType, Long referenceId, boolean read, Instant createdAt) {

    public static NotificationDto of(Notification notification) {
        return new NotificationDto(notification.getId(), notification.getType(), notification.getSeverity(),
                notification.getTitle(), notification.getBody(), notification.getLink(),
                notification.getReferenceType(), notification.getReferenceId(), notification.isRead(),
                notification.getCreatedAt());
    }
}
