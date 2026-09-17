package com.gondolia.common.error;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Formato único de error de la API (también para 401/403 del filtro de seguridad).
 */
public record ErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldErrorItem> fieldErrors) {

    public record FieldErrorItem(String field, String message) {
    }

    public ErrorResponse {
        timestamp = timestamp == null ? null : timestamp.truncatedTo(ChronoUnit.MILLIS);
        fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public static ErrorResponse of(Instant timestamp, HttpStatusCode status, String code, String message, String path) {
        return of(timestamp, status, code, message, path, List.of());
    }

    public static ErrorResponse of(Instant timestamp, HttpStatusCode status, String code, String message, String path,
                                   List<FieldErrorItem> fieldErrors) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        String reason = resolved != null ? resolved.getReasonPhrase() : String.valueOf(status.value());
        return new ErrorResponse(timestamp, status.value(), reason, code, message, path, fieldErrors);
    }
}
