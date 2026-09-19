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
        candidates.sort(java.util.Comparator.comparingInt(Candidate::score).reversed()
                .thenComparing(c -> c.field().required() ? 0 : 1)
                .thenComparingInt(c -> c.field().ordinal()));
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
     * Puntaje de coincidencia entre un encabezado normalizado y un campo:
     * <ul>
     *   <li>1001..1010: sinónimo exacto (los primeros de la lista valen más),</li>
     *   <li>1005: clave o etiqueta exacta,</li>
     *   <li>700 / 650 / 600: el encabezado empieza con, contiene o está contenido en un sinónimo,</li>
     *   <li>0: sin coincidencia.</li>
     * </ul>
     * Que un sinónimo pese como la etiqueta es a propósito: SPEC §16.1 manda «descripción» al campo
     * <em>Nombre</em>, aunque «Descripción» también sea la etiqueta de otro campo. Si el archivo trae las dos
     * columnas, «Nombre» gana por ser el primer sinónimo del campo y «Descripción» queda para el otro.
     */
    static int score(ImportField field, String headerSlug) {
        int best = 0;
        List<String> synonyms = field.synonyms();
        for (int i = 0; i < synonyms.size(); i++) {
            String slug = ImportValues.slug(synonyms.get(i));
            if (slug.isEmpty()) {
                continue;
            }
            if (headerSlug.equals(slug)) {
                best = Math.max(best, 1000 + Math.max(10 - i, 1));
            } else if (slug.length() >= 4 && headerSlug.startsWith(slug)) {
                best = Math.max(best, 700);
            } else if (slug.length() >= 5 && headerSlug.contains(slug)) {
                best = Math.max(best, 650);
            } else if (headerSlug.length() >= 5 && slug.contains(headerSlug)) {
                best = Math.max(best, 600);
            }
        }
        String key = ImportValues.slug(field.key());
        String label = ImportValues.slug(field.label());
        if (headerSlug.equals(key) || headerSlug.equals(label)) {
            best = Math.max(best, 1005);
        } else if (label.length() >= 5 && headerSlug.contains(label)) {
            best = Math.max(best, 600);
        }
        return best;
    }
}
