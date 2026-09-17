package com.gondolia.common.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalización de números de lote para comparar lotes cargados con los de un recall.
 */
public final class LotNumbers {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^A-Z0-9]");

    private LotNumbers() {
    }

    /**
     * {@code null} si está vacío; en mayúsculas y sin ningún carácter fuera de {@code [A-Z0-9]}.
     * Ejemplo: {@code "l-2409/a "} → {@code "L2409A"}.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = NON_ALPHANUMERIC.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Valor a mostrar/guardar en {@code lot_number}: recortado, o {@code null} si queda vacío.
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
