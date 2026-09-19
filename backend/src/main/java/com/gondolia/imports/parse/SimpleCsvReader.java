package com.gondolia.imports.parse;

import java.util.ArrayList;
import java.util.List;

/**
 * Lector CSV mínimo (RFC 4180: comillas dobles, {@code ""} como comilla escapada, saltos de línea dentro de un
 * campo entrecomillado) que se usa cuando Apache Commons CSV no puede arrancar.
 * <p>
 * <strong>Por qué existe:</strong> {@code commons-csv 1.12.0} necesita
 * {@code org.apache.commons.io.input.UnsynchronizedBufferedReader}, que recién aparece en {@code commons-io 2.17};
 * el {@code pom.xml} de la fundación trae {@code commons-io 2.16.1} (la que fija Apache POI 5.3.0), así que
 * {@code CSVParser} lanza {@link NoClassDefFoundError}. Mientras la fundación no suba {@code commons-io}, la
 * importación usa este lector; {@link SpreadsheetParser} vuelve solo a Commons CSV en cuanto la clase aparece.
 */
final class SimpleCsvReader {

    private SimpleCsvReader() {
    }

    /** Filas del texto con el separador indicado; los valores vienen recortados, como en Commons CSV. */
    static List<List<String>> read(String text, char delimiter, int maxRows, int maxColumns) {
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        int length = text.length();
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < length && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }
            if (c == '"' && field.isEmpty()) {
                quoted = true;
                any = true;
            } else if (c == delimiter) {
                current.add(field.toString().trim());
                field.setLength(0);
                any = true;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < length && text.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString().trim());
                field.setLength(0);
                if (addRow(rows, current, any, maxColumns) && rows.size() > maxRows) {
                    return rows;
                }
                current = new ArrayList<>();
                any = false;
            } else {
                field.append(c);
                any = true;
            }
        }
        if (any || !field.isEmpty()) {
            current.add(field.toString().trim());
            addRow(rows, current, true, maxColumns);
        }
        return rows;
    }

    /** Agrega la fila salvo que esté completamente vacía (Commons CSV también ignora las líneas en blanco). */
    private static boolean addRow(List<List<String>> rows, List<String> current, boolean any, int maxColumns) {
        if (!any && current.size() <= 1 && (current.isEmpty() || current.getFirst().isEmpty())) {
            return false;
        }
        rows.add(current.size() > maxColumns ? new ArrayList<>(current.subList(0, maxColumns)) : current);
        return true;
    }
}
