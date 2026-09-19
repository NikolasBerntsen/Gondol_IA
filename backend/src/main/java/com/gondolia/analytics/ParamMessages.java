package com.gondolia.analytics;

/**
 * Mensajes en castellano para las validaciones de parámetros del módulo B ({@code @Min}, {@code @Max}, {@code @Size}).
 * Sin mensaje explícito, Hibernate Validator responde con su texto en inglés ("must be greater than or equal to 7").
 * Las llaves ({@code {value}}, {@code {max}}) las completa el validador con el límite de la anotación.
 */
public final class ParamMessages {

    public static final String MIN = "tiene que ser al menos {value}";
    public static final String MAX = "no puede ser mayor a {value}";
    public static final String SIZE_MAX = "admite hasta {max} caracteres";

    private ParamMessages() {
    }
}
