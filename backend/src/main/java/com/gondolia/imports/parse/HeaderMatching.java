package com.gondolia.imports.parse;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Auto-mapeo de los encabezados del archivo a los campos de GondolIA por sinónimos, sin acentos ni mayúsculas
 * (SPEC §16.1). Cada encabezado se usa una sola vez y gana la coincidencia más específica.
 */
public final class HeaderMatching {

    private HeaderMatching() {
    }

    /**
     * Sugerencia de mapeo {@code {fieldKey: header}} para los encabezados detectados.
     */
    public static Map<ImportField, String> suggest(List<String> headers) {
        record Candidate(ImportField field, String header, int score) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (String header : headers) {
            String slug = ImportValues.slug(header);
            if (slug.isEmpty()) {
                continue;
            }
            for (ImportField field : ImportField.values()) {
                int score = score(field, slug);
                if (score > 0) {
                    candidates.add(new Candidate(field, header, score));
                }
            }
        }
        candidates.sort((a, b) -> b.score() - a.score());
        Map<ImportField, String> mapping = new EnumMap<>(ImportField.class);
        Set<String> usedHeaders = new HashSet<>();
        for (Candidate candidate : candidates) {
            if (mapping.containsKey(candidate.field()) || usedHeaders.contains(candidate.header())) {
                continue;
            }
            mapping.put(candidate.field(), candidate.header());
            usedHeaders.add(candidate.header());
        }
        return mapping;
    }

    /**
     * Puntaje de coincidencia entre un encabezado normalizado y un campo: 100 clave o etiqueta exacta,
     * 90 sinónimo exacto, 60..70 el encabezado contiene o está contenido en un sinónimo, 0 sin coincidencia.
     */
    static int score(ImportField field, String headerSlug) {
        String key = ImportValues.slug(field.key());
        String label = ImportValues.slug(field.label());
        if (headerSlug.equals(key) || headerSlug.equals(label)) {
            return 100;
        }
        int best = 0;
        for (String synonym : field.synonyms()) {
            String slug = ImportValues.slug(synonym);
            if (slug.isEmpty()) {
                continue;
            }
            if (headerSlug.equals(slug)) {
                return 90;
            }
            if (slug.length() >= 4 && headerSlug.startsWith(slug)) {
                best = Math.max(best, 70);
            } else if (slug.length() >= 5 && headerSlug.contains(slug)) {
                best = Math.max(best, 65);
            } else if (headerSlug.length() >= 5 && slug.contains(headerSlug)) {
                best = Math.max(best, 60);
            }
        }
        if (best == 0 && label.length() >= 5 && headerSlug.contains(label)) {
            best = 60;
        }
        return best;
    }
}
