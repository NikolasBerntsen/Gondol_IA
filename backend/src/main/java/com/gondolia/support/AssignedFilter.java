package com.gondolia.support;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import java.util.Locale;

/** Filtro de asignación de la bandeja del agente: {@code all}, {@code me} o {@code unassigned} (SPEC §6.8). */
public enum AssignedFilter {
    ALL,
    ME,
    UNASSIGNED;

    /** Acepta el valor tal como lo documenta la API (minúsculas) y también en mayúsculas; vacío = {@code ALL}. */
    public static AssignedFilter from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El filtro de asignación tiene que ser all, me o unassigned.");
        }
    }
}
