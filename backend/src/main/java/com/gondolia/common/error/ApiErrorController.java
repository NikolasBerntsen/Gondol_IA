package com.gondolia.common.error;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reemplaza el {@code /error} de Spring Boot para que los errores ocurridos fuera de los controladores
 * (filtros, contenedor) también respondan con {@link ErrorResponse}.
 */
@Slf4j
@Hidden
@RestController
@RequiredArgsConstructor
public class ApiErrorController implements ErrorController {

    private final Clock clock;

    @RequestMapping("${server.error.path:/error}")
    public ResponseEntity<ErrorResponse> error(HttpServletRequest request) {
        Object statusAttribute = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = statusAttribute instanceof Integer code && HttpStatus.resolve(code) != null
                ? HttpStatus.valueOf(code)
                : HttpStatus.INTERNAL_SERVER_ERROR;
        Object pathAttribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = pathAttribute instanceof String value ? value : request.getRequestURI();

        if (status.is5xxServerError()) {
            Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
            log.error("Error {} fuera de los controladores en {}", status.value(), path,
                    exception instanceof Throwable throwable ? throwable : null);
        }
        ErrorResponse body = ErrorResponse.of(clock.instant(), status, ErrorCodes.forStatus(status),
                ErrorCodes.defaultMessage(status), path);
        return ResponseEntity.status(status).body(body);
    }
}
