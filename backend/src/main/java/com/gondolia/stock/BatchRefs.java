package com.gondolia.stock;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Referencias que agrupan los movimientos de una misma operación: {@code S-} ventas, {@code A-} ajustes y mermas,
 * {@code T-} transferencias. Formato {@code <prefijo>-<yyyyMMddHHmmss en hora de negocio>-<6 caracteres>}, p. ej.
 * {@code S-20260917143012-K3F9QZ} (23 caracteres; la columna admite 40).
 */
public final class BatchRefs {

    public static final String SALE = "S";
    public static final String ADJUSTMENT = "A";
    public static final String TRANSFER = "T";

    static final int MAX_LENGTH = 40;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final char[] ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ".toCharArray();
    private static final int RANDOM_LENGTH = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BatchRefs() {
    }

    public static String sale(Clock clock) {
        return next(SALE, clock);
    }

    public static String adjustment(Clock clock) {
        return next(ADJUSTMENT, clock);
    }

    public static String transfer(Clock clock) {
        return next(TRANSFER, clock);
    }

    public static String next(String prefix, Clock clock) {
        StringBuilder ref = new StringBuilder(prefix).append('-').append(STAMP.format(ZonedDateTime.now(clock)))
                .append('-');
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            ref.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return ref.toString();
    }
}
