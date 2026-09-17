package com.gondolia.common.error;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Traduce toda excepción de los controladores al formato {@link ErrorResponse}.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String VALIDATION_MESSAGE = "Revisá los datos ingresados";

    private final Clock clock;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        if (ex.getStatus().is5xxServerError()) {
            log.warn("{} {} -> {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getStatus().value(),
                    ex.getCode(), ex.getMessage());
        }
        return build(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    // ---------------------------------------------------------------- validación

    @ExceptionHandler(BindException.class) // incluye MethodArgumentNotValidException
    public ResponseEntity<ErrorResponse> handleBind(BindException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldErrorItem> fields = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new ErrorResponse.FieldErrorItem(
                        error instanceof FieldError fe ? fe.getField() : error.getObjectName(),
                        messageOf(error)))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, VALIDATION_MESSAGE, request, fields);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleMethodValidation(HandlerMethodValidationException ex,
                                                                HttpServletRequest request) {
        List<ErrorResponse.FieldErrorItem> fields = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldErrorItem(
                                error instanceof FieldError fe ? fe.getField() : parameterName(result.getMethodParameter()
                                        .getParameterName()),
                                messageOf(error))))
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, VALIDATION_MESSAGE, request, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<ErrorResponse.FieldErrorItem> fields = ex.getConstraintViolations().stream()
                .map(violation -> {
                    String path = violation.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return new ErrorResponse.FieldErrorItem(field, violation.getMessage());
                })
                .toList();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, VALIDATION_MESSAGE, request, fields);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String name = ex.getName();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "El parámetro '" + name + "' tiene un valor inválido", request,
                List.of(new ErrorResponse.FieldErrorItem(name, "tiene un valor inválido")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        String name = ex.getParameterName();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "Falta el parámetro obligatorio '" + name + "'", request,
                List.of(new ErrorResponse.FieldErrorItem(name, "es obligatorio")));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException ex,
                                                           HttpServletRequest request) {
        String name = ex.getRequestPartName();
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "Falta la parte obligatoria '" + name + "'", request,
                List.of(new ErrorResponse.FieldErrorItem(name, "es obligatorio")));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "Falta el encabezado obligatorio '" + ex.getHeaderName() + "'", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        if (ex.getCause() instanceof MismatchedInputException mismatch && !mismatch.getPath().isEmpty()) {
            String field = mismatch.getPath().stream()
                    .map(ref -> ref.getFieldName() != null ? ref.getFieldName() : "[" + ref.getIndex() + "]")
                    .collect(Collectors.joining(".")).replace(".[", "[");
            String detail = mismatch instanceof InvalidFormatException invalid && invalid.getTargetType().isEnum()
                    ? "valor inválido; opciones: " + enumValues(invalid.getTargetType())
                    : "tiene un formato inválido";
            return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR, VALIDATION_MESSAGE, request,
                    List.of(new ErrorResponse.FieldErrorItem(field, detail)));
        }
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "El cuerpo del pedido está vacío o tiene un formato inválido", request);
    }

    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ErrorResponse> handlePropertyReference(PropertyReferenceException ex,
                                                                 HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                "No se puede ordenar ni filtrar por '" + ex.getPropertyName() + "'", request,
                List.of(new ErrorResponse.FieldErrorItem("sort", "campo inválido: " + ex.getPropertyName())));
    }

    @ExceptionHandler(InvalidDataAccessApiUsageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidDataAccessUsage(InvalidDataAccessApiUsageException ex,
                                                                      HttpServletRequest request) {
        if (ex.getCause() instanceof PropertyReferenceException propertyReference) {
            return handlePropertyReference(propertyReference, request);
        }
        return handleUnexpected(ex, request);
    }

    // ---------------------------------------------------------------- archivos

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUpload(MaxUploadSizeExceededException ex,
                                                         HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_FILE,
                "El archivo supera el tamaño máximo permitido (15 MB)", request);
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ErrorResponse> handleMultipart(MultipartException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ErrorCodes.INVALID_FILE,
                "No se pudo leer el archivo enviado. Probá nuevamente", request);
    }

    // ---------------------------------------------------------------- seguridad

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN, ErrorCodes.defaultMessage(HttpStatus.FORBIDDEN),
                request);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        return build(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                ErrorCodes.defaultMessage(HttpStatus.UNAUTHORIZED), request);
    }

    // ---------------------------------------------------------------- HTTP / MVC

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ErrorCodes.NOT_FOUND, ErrorCodes.defaultMessage(HttpStatus.NOT_FOUND),
                request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                  HttpServletRequest request) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED,
                "El método " + ex.getMethod() + " no está permitido para este recurso", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex,
                                                                     HttpServletRequest request) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                ErrorCodes.defaultMessage(HttpStatus.UNSUPPORTED_MEDIA_TYPE), request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable() {
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleErrorResponse(ErrorResponseException ex, HttpServletRequest request) {
        HttpStatusCode status = ex.getStatusCode();
        if (status.is5xxServerError()) {
            log.error("Error {} en {} {}", status.value(), request.getMethod(), request.getRequestURI(), ex);
        }
        return build(status, ErrorCodes.forStatus(status), ErrorCodes.defaultMessage(status), request);
    }

    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientGone() {
        // El cliente cerró la conexión: no hay a quién responder.
    }

    // ---------------------------------------------------------------- datos

    /**
     * Violación de restricción (clave única, FK, check) → 409 {@code CONFLICT}. Un dato que no entra en su columna
     * (SQLState clase {@code 22}: texto demasiado largo, número fuera de rango) → 400 {@code VALIDATION_ERROR}.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex,
                                                             HttpServletRequest request) {
        log.warn("Violación de integridad en {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        if (isDataException(ex)) {
            return build(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_ERROR,
                    "Algún dato supera el largo o el valor máximo permitido. Revisá los datos ingresados", request);
        }
        return build(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                "La operación entra en conflicto con datos existentes", request);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleLocking(RuntimeException ex, HttpServletRequest request) {
        log.warn("Conflicto de concurrencia en {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMessage());
        return build(HttpStatus.CONFLICT, ErrorCodes.CONFLICT,
                "Los datos fueron modificados por otra operación. Intentá nuevamente", request);
    }

    // ---------------------------------------------------------------- genérico

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Error inesperado en {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                ErrorCodes.defaultMessage(HttpStatus.INTERNAL_SERVER_ERROR), request);
    }

    // ---------------------------------------------------------------- helpers

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String code, String message,
                                                HttpServletRequest request) {
        return build(status, code, message, request, List.of());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatusCode status, String code, String message,
                                                HttpServletRequest request,
                                                List<ErrorResponse.FieldErrorItem> fieldErrors) {
        ErrorResponse body = ErrorResponse.of(clock.instant(), status, code, message, request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.status(status).body(body);
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "es inválido";
    }

    /** {@code true} si la causa es un error de dato de SQL (SQLState clase {@code 22}). */
    static boolean isDataException(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null
                    && sql.getSQLState().startsWith("22")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    private static String parameterName(String name) {
        return name != null ? name : "parámetro";
    }

    private static String enumValues(Class<?> enumType) {
        Object[] constants = enumType.getEnumConstants();
        return Arrays.stream(constants).map(String::valueOf).collect(Collectors.joining(", "));
    }
}
