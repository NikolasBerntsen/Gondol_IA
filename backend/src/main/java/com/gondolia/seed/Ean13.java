package com.gondolia.seed;

/**
 * Códigos EAN-13 argentinos ficticios para los datos demo: prefijo GS1 {@code 779}, 4 dígitos de "empresa"
 * (la marca ficticia), 5 dígitos de artículo y el dígito verificador.
 */
final class Ean13 {

    static final String ARGENTINA_PREFIX = "779";

    private Ean13() {
    }

    /** {@code 779} + empresa (4 dígitos) + artículo (5 dígitos) + dígito verificador. */
    static String of(String companyCode, int item) {
        if (companyCode == null || !companyCode.matches("\\d{4}")) {
            throw new IllegalArgumentException("El código de empresa debe tener 4 dígitos: " + companyCode);
        }
        if (item < 0 || item > 99_999) {
            throw new IllegalArgumentException("El artículo debe tener hasta 5 dígitos: " + item);
        }
        String body = ARGENTINA_PREFIX + companyCode + String.format("%05d", item);
        return body + checkDigit(body);
    }

    /** Dígito verificador de los primeros 12 dígitos (pesos 1 y 3 alternados). */
    static int checkDigit(String first12) {
        if (first12 == null || !first12.matches("\\d{12}")) {
            throw new IllegalArgumentException("Se necesitan 12 dígitos: " + first12);
        }
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int digit = first12.charAt(i) - '0';
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return (10 - sum % 10) % 10;
    }

    static boolean isValid(String ean) {
        return ean != null && ean.matches("\\d{13}") && checkDigit(ean.substring(0, 12)) == ean.charAt(12) - '0';
    }
}
