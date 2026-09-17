package com.gondolia.common.error;

import org.springframework.http.HttpStatus;

/**
 * Error de negocio con estado HTTP, código estable (para el frontend) y mensaje en español para el usuario.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
