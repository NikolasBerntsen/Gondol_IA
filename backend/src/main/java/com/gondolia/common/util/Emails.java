package com.gondolia.common.util;

import java.util.Locale;

/**
 * Normalización de emails: los usuarios se guardan y buscan siempre en minúsculas.
 */
public final class Emails {

    private Emails() {
    }

    /** Recortado y en minúsculas; {@code null} si es {@code null} o queda vacío. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
