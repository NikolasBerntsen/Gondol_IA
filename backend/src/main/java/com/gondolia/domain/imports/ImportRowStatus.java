package com.gondolia.domain.imports;

/**
 * Estado de una fila de la importación (SPEC §16.2): validación y resultado de la aplicación.
 */
public enum ImportRowStatus {
    PENDING,
    VALID,
    WARNING,
    ERROR,
    SKIPPED,
    IMPORTED,
    FAILED;

    /** {@code true} si la fila se puede aplicar (válida o solo con advertencias). */
    public boolean isApplicable() {
        return this == VALID || this == WARNING;
    }
}
