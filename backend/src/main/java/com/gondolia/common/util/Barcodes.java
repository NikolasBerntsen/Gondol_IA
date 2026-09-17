package com.gondolia.common.util;

import java.util.regex.Pattern;

/**
 * Normalización y validación de códigos de barras (EAN-13, EAN-8, UPC-A, Code128 alfanumérico ≤ 32).
 */
public final class Barcodes {

    public static final int MAX_LENGTH = 32;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9]{1," + MAX_LENGTH + "}$");
    private static final Pattern GTIN = Pattern.compile("^(\\d{8}|\\d{12}|\\d{13})$");

    private Barcodes() {
    }

    /**
     * Recorta y elimina todo espacio interno; {@code null} si queda vacío.
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = WHITESPACE.matcher(raw).replaceAll("");
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * {@code true} si el código (ya normalizado) es alfanumérico y tiene hasta 32 caracteres.
     */
    public static boolean isValid(String barcode) {
        return barcode != null && VALID.matcher(barcode).matches();
    }

    /**
     * {@code true} si es un GTIN numérico (EAN-8, UPC-A o EAN-13) con dígito verificador correcto.
     */
    public static boolean isValidGtin(String barcode) {
        if (barcode == null || !GTIN.matcher(barcode).matches()) {
            return false;
        }
        int sum = 0;
        int length = barcode.length();
        for (int i = 0; i < length - 1; i++) {
            int digit = barcode.charAt(i) - '0';
            // Desde la derecha (sin el verificador), las posiciones impares pesan 3.
            boolean weightThree = (length - 1 - i) % 2 == 1;
            sum += weightThree ? digit * 3 : digit;
        }
        int check = (10 - (sum % 10)) % 10;
        return check == barcode.charAt(length - 1) - '0';
    }
}
