package com.gondolia.domain.imports;

/**
 * Estado de una importación masiva (SPEC §16): archivo subido, filas validadas, aplicación en curso, aplicada,
 * fallida o cancelada.
 */
public enum ImportStatus {
    UPLOADED,
    VALIDATED,
    APPLYING,
    APPLIED,
    FAILED,
    CANCELLED;

    /** {@code true} si la importación ya terminó (no admite más cambios). */
    public boolean isFinal() {
        return this == APPLIED || this == FAILED || this == CANCELLED;
    }
}
