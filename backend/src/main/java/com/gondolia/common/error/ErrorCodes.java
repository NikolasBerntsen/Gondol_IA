package com.gondolia.common.error;

import org.springframework.http.HttpStatusCode;

/**
 * Códigos de error comunes de la API (SPEC §5.2). Los módulos pueden definir códigos propios.
 */
public final class ErrorCodes {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String UNAUTHORIZED = "UNAUTHORIZED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String TENANT_DISABLED = "TENANT_DISABLED";
    public static final String TENANT_CANCELLED = "TENANT_CANCELLED";
    public static final String USER_DISABLED = "USER_DISABLED";
    public static final String NO_TENANT = "NO_TENANT";
    public static final String CONFLICT = "CONFLICT";
    public static final String INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String BRANCH_REQUIRED = "BRANCH_REQUIRED";
    public static final String BRANCH_FORBIDDEN = "BRANCH_FORBIDDEN";
    public static final String BRANCH_LIMIT_REACHED = "BRANCH_LIMIT_REACHED";
    public static final String BRANCH_HAS_STOCK = "BRANCH_HAS_STOCK";
    public static final String LOT_NOT_TRANSFERABLE = "LOT_NOT_TRANSFERABLE";
    public static final String INVALID_FILE = "INVALID_FILE";
    public static final String INVALID_API_KEY = "INVALID_API_KEY";
    public static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    public static final String UNSUPPORTED_MEDIA_TYPE = "UNSUPPORTED_MEDIA_TYPE";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private ErrorCodes() {
    }

    /** Código genérico para un estado HTTP cuando no hay uno más específico. */
    public static String forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> VALIDATION_ERROR;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 409 -> CONFLICT;
            case 413 -> INVALID_FILE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? INTERNAL_ERROR : "ERROR";
        };
    }

    /** Mensaje genérico en español para un estado HTTP. */
    public static String defaultMessage(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "Revisá los datos ingresados";
            case 401 -> "Necesitás iniciar sesión para continuar";
            case 403 -> "No tenés permisos para realizar esta acción";
            case 404 -> "El recurso solicitado no existe";
            case 405 -> "Método no permitido para este recurso";
            case 409 -> "La operación entra en conflicto con datos existentes";
            case 413 -> "El archivo supera el tamaño máximo permitido";
            case 415 -> "Tipo de contenido no soportado";
            default -> status.is5xxServerError()
                    ? "Ocurrió un error inesperado. Intentá nuevamente en unos minutos"
                    : "No se pudo procesar el pedido";
        };
    }
}
