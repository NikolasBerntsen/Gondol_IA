package com.gondolia.domain.common;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instantes con la precisión de {@code TIMESTAMPTZ} (microsegundos), para que el valor en memoria coincida con el
 * que se lee de la base.
 */
public final class Timestamps {

    private Timestamps() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
