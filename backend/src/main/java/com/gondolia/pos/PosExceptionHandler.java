package com.gondolia.pos;

import com.gondolia.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Solo para los controladores del POS: agrega el campo {@code details} al formato de error estándar cuando el cobro
 * se rechaza por falta de stock (SPEC §15.2). El resto de las excepciones las sigue traduciendo
 * {@code GlobalExceptionHandler}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackages = "com.gondolia.pos")
@RequiredArgsConstructor
public class PosExceptionHandler {

    /** Error estándar (SPEC §5.2) con el detalle por producto del faltante. */
    public record PosErrorResponse(Instant timestamp, int status, String error, String code, String message,
                                   String path, List<ErrorResponse.FieldErrorItem> fieldErrors,
                                   List<PosInsufficientStockException.Detail> details) {
    }

    private final Clock clock;

    @ExceptionHandler(PosInsufficientStockException.class)
    public ResponseEntity<PosErrorResponse> handleInsufficientStock(PosInsufficientStockException ex,
                                                                    HttpServletRequest request) {
        ErrorResponse base = ErrorResponse.of(Instant.now(clock), ex.getStatus(), ex.getCode(), ex.getMessage(),
                request.getRequestURI());
        return ResponseEntity.status(ex.getStatus()).body(new PosErrorResponse(base.timestamp(), base.status(),
                base.error(), base.code(), base.message(), base.path(), base.fieldErrors(), ex.getDetails()));
    }
}
