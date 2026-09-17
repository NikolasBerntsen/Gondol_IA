package com.gondolia.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.domain.common.Severity;
import com.gondolia.domain.notification.NotificationType;
import org.junit.jupiter.api.Test;

class NotificationDraftTest {

    @Test
    void appliesDefaultsAndNormalizesText() {
        NotificationDraft draft = new NotificationDraft(NotificationType.SYSTEM, null, "  Hola  ", "Cuerpo", "  ", null,
                null);

        assertThat(draft.severity()).isEqualTo(Severity.INFO);
        assertThat(draft.title()).isEqualTo("Hola");
        assertThat(draft.link()).isNull();

        NotificationDraft longTitle = NotificationDraft.of(NotificationType.ALERT, Severity.CRITICAL, "x".repeat(250),
                null, "/app/alerts");
        assertThat(longTitle.title()).hasSize(200).endsWith("…");
    }

    @Test
    void rejectsInvalidDrafts() {
        assertThatThrownBy(() -> NotificationDraft.of(null, Severity.INFO, "t", null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> NotificationDraft.of(NotificationType.ALERT, Severity.INFO, " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationDraft.of(NotificationType.ALERT, Severity.INFO, "t", null,
                "/" + "x".repeat(300))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NotificationDraft(NotificationType.ALERT, Severity.INFO, "t", null, null,
                "x".repeat(31), 1L)).isInstanceOf(IllegalArgumentException.class);
    }
}
