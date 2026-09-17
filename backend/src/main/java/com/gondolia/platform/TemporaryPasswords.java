package com.gondolia.platform;

import java.security.SecureRandom;

/**
 * Contraseñas temporales que los dueños le dictan al cliente por teléfono: sin caracteres que se confundan
 * (0/O, 1/l/I) y con guiones para leerlas en voz alta. Formato {@code Gnd-XXXX-9999} (13 caracteres).
 */
final class TemporaryPasswords {

    private static final String LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String DIGITS = "23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    /** Nueva contraseña temporal (cumple el mínimo de 8 caracteres del cambio de contraseña). */
    static String generate() {
        StringBuilder password = new StringBuilder("Gnd-");
        for (int i = 0; i < 4; i++) {
            password.append(LETTERS.charAt(RANDOM.nextInt(LETTERS.length())));
        }
        password.append('-');
        for (int i = 0; i < 4; i++) {
            password.append(DIGITS.charAt(RANDOM.nextInt(DIGITS.length())));
        }
        return password.toString();
    }
}
