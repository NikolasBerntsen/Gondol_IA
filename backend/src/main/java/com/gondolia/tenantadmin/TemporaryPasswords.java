package com.gondolia.tenantadmin;

import java.security.SecureRandom;

/**
 * Contraseñas temporales para el reseteo desde la pantalla de usuarios: se muestran una sola vez y el usuario
 * tiene que cambiarlas al entrar ({@code must_change_password}).
 */
final class TemporaryPasswords {

    /** Sin caracteres ambiguos (0/O, 1/l/I) para poder dictarla por teléfono. */
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
    private static final String PREFIX = "Gondolia-";
    private static final int RANDOM_LENGTH = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private TemporaryPasswords() {
    }

    /** Contraseña legible de 17 caracteres, por ejemplo {@code Gondolia-4Kp9xW2m}. */
    static String generate() {
        StringBuilder password = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            password.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return password.toString();
    }
}
