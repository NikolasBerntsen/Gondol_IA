package com.gondolia.imports.parse;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Parseo de celdas de una planilla argentina (SPEC §16.2): números es-AR o en ("$ 1.234,50", "1234.5"),
 * booleanos ("si", "sí", "no", "true", "false", "1", "0", "x") y fechas DMY por defecto (dd/mm/aaaa, dd-mm-aa,
 * aaaa-mm-dd y seriales de Excel).
 */
public final class ImportValues {

    /** Orden de los componentes de una fecha escrita con separadores. */
    public enum DateOrder {
        DMY, MDY, YMD;

        public static DateOrder fromJson(String raw) {
            if (raw == null) {
                return DMY;
            }
            return switch (raw.trim().toUpperCase(Locale.ROOT)) {
                case "MDY" -> MDY;
                case "YMD" -> YMD;
                default -> DMY;
            };
        }
    }

    /** Fecha base de los seriales de Excel (sistema 1900, con el bug del 29/02/1900 ya contemplado). */
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);
    private static final int EXCEL_SERIAL_MIN = 20_000;   // 1954 aprox.: por debajo casi seguro no es una fecha
    private static final int EXCEL_SERIAL_MAX = 80_000;   // 2119 aprox.

    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern NUMBER_CHARS = Pattern.compile("[^0-9,.\\-+]");
    private static final Pattern INTEGER = Pattern.compile("^[-+]?\\d+$");
    private static final Pattern DATE_PARTS = Pattern.compile("^(\\d{1,4})[/\\-.](\\d{1,2})[/\\-.](\\d{1,4})$");

    private ImportValues() {
    }

    // -----------------------------------------------------------------------
    // Texto
    // -----------------------------------------------------------------------

    /** Recorta espacios (incluido el NBSP de Excel) y colapsa los internos; {@code null} si queda vacío. */
    public static String text(String raw) {
        if (raw == null) {
            return null;
        }
        String value = SPACES.matcher(raw.replace(' ', ' ').trim()).replaceAll(" ").trim();
        return value.isEmpty() ? null : value;
    }

    /** Clave de comparación: sin acentos, en minúsculas y sin caracteres que no sean letras o números. */
    public static String slug(String raw) {
        if (raw == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(raw.replace(' ', ' '), Normalizer.Form.NFD);
        String withoutAccents = ACCENTS.matcher(decomposed).replaceAll("");
        return NON_ALNUM.matcher(withoutAccents.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    // -----------------------------------------------------------------------
    // Números
    // -----------------------------------------------------------------------

    /**
     * Número es-AR o en. "$ 1.234,50" → 1234.50; "1.234" → 1234; "1234.5" → 1234.5; "12,5%" → 12.5.
     * Devuelve vacío si el texto no es un número.
     */
    public static Optional<BigDecimal> number(String raw) {
        String value = text(raw);
        if (value == null) {
            return Optional.empty();
        }
        boolean negative = value.startsWith("(") && value.endsWith(")");
        String cleaned = NUMBER_CHARS.matcher(negative ? value.substring(1, value.length() - 1) : value)
                .replaceAll("");
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals("+")) {
            return Optional.empty();
        }
        String sign = "";
        if (cleaned.startsWith("-") || cleaned.startsWith("+")) {
            sign = cleaned.startsWith("-") ? "-" : "";
            cleaned = cleaned.substring(1);
        }
        if (cleaned.contains("-") || cleaned.contains("+")) {
            return Optional.empty();
        }
        int lastComma = cleaned.lastIndexOf(',');
        int lastDot = cleaned.lastIndexOf('.');
        String digits;
        if (lastComma >= 0 && lastDot >= 0) {
            // El separador decimal es el último que aparece; el otro es de miles.
            char decimal = lastComma > lastDot ? ',' : '.';
            char thousands = decimal == ',' ? '.' : ',';
            digits = cleaned.replace(String.valueOf(thousands), "").replace(decimal, '.');
        } else if (lastComma >= 0) {
            digits = decimalOrThousands(cleaned, ',');
        } else if (lastDot >= 0) {
            digits = decimalOrThousands(cleaned, '.');
        } else {
            digits = cleaned;
        }
        if (digits.isEmpty() || digits.chars().noneMatch(Character::isDigit) || digits.indexOf('.') != digits.lastIndexOf('.')) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(sign + (digits.startsWith(".") ? "0" + digits : digits)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /**
     * Un único separador: es de miles si agrupa exactamente de a 3 dígitos y aparece más de una vez o la parte
     * derecha tiene 3 dígitos ("1.234" = 1234); si no, es decimal ("1234,5" = 1234.5).
     */
    private static String decimalOrThousands(String cleaned, char separator) {
        String[] parts = cleaned.split(Pattern.quote(String.valueOf(separator)), -1);
        if (parts.length > 2) {
            // Varios separadores iguales: siempre miles ("1.234.567").
            return String.join("", parts);
        }
        String right = parts[parts.length - 1];
        if (right.length() == 3 && parts[0].length() <= 3 && !parts[0].isEmpty()) {
            return String.join("", parts);
        }
        return String.join(".", parts);
    }

    /** {@code true} si el texto representa un entero exacto (sin parte decimal). */
    public static boolean isInteger(String raw) {
        String value = text(raw);
        if (value == null) {
            return false;
        }
        if (INTEGER.matcher(value).matches()) {
            return true;
        }
        return number(value).map(n -> n.stripTrailingZeros().scale() <= 0).orElse(false);
    }

    // -----------------------------------------------------------------------
    // Booleanos
    // -----------------------------------------------------------------------

    public static Optional<Boolean> bool(String raw) {
        String value = slug(raw);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return switch (value) {
            case "si", "s", "true", "t", "1", "x", "verdadero", "yes", "y", "ok" -> Optional.of(true);
            case "no", "n", "false", "f", "0", "falso" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    // -----------------------------------------------------------------------
    // Fechas
    // -----------------------------------------------------------------------

    /**
     * Fecha de una celda de texto: seriales de Excel, ISO (aaaa-mm-dd), y fechas con separadores según el orden
     * elegido, con corrección automática cuando el orden pedido es imposible (p. ej. "25/12/2026" con MDY).
     */
    public static Optional<LocalDate> date(String raw, DateOrder order) {
        String value = text(raw);
        if (value == null) {
            return Optional.empty();
        }
        // Instantes exportados por otras herramientas: "2026-09-17 00:00:00" o "2026-09-17T00:00".
        int separatorIndex = value.indexOf('T') >= 0 ? value.indexOf('T') : value.indexOf(' ');
        if (separatorIndex > 0 && value.length() > separatorIndex + 1 && Character.isDigit(value.charAt(0))
                && value.substring(separatorIndex + 1).matches("\\d{1,2}[:.].*")) {
            value = value.substring(0, separatorIndex);
        }
        if (INTEGER.matcher(value).matches()) {
            return excelSerial(Integer.parseInt(value));
        }
        var matcher = DATE_PARTS.matcher(value);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        int third = Integer.parseInt(matcher.group(3));
        if (matcher.group(1).length() == 4) {
            return build(first, second, third);   // aaaa-mm-dd, sin importar el orden elegido
        }
        Optional<LocalDate> preferred = switch (order) {
            case DMY -> build(year(third), second, first);
            case MDY -> build(year(third), first, second);
            case YMD -> build(year(first), second, third);
        };
        if (preferred.isPresent()) {
            return preferred;
        }
        // El orden elegido no da una fecha válida: probamos el complementario (típico "25/12" leído como MDY).
        return switch (order) {
            case DMY -> build(year(third), first, second);
            case MDY -> build(year(third), second, first);
            case YMD -> build(year(third), second, first);
        };
    }

    public static Optional<LocalDate> date(String raw) {
        return date(raw, DateOrder.DMY);
    }

    /** Serial de fecha de Excel (sistema 1900). */
    public static Optional<LocalDate> excelSerial(int serial) {
        if (serial < EXCEL_SERIAL_MIN || serial > EXCEL_SERIAL_MAX) {
            return Optional.empty();
        }
        return Optional.of(EXCEL_EPOCH.plusDays(serial));
    }

    /** Año de dos dígitos: 00–79 → 2000–2079; 80–99 → 1980–1999. */
    private static int year(int value) {
        if (value >= 100) {
            return value;
        }
        return value < 80 ? 2000 + value : 1900 + value;
    }

    private static Optional<LocalDate> build(int year, int month, int day) {
        if (year < 1900 || year > 2200 || month < 1 || month > 12 || day < 1 || day > 31) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException e) {
            return Optional.empty();
        }
    }
}
