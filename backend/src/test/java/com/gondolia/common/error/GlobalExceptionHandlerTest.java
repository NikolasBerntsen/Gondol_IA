package com.gondolia.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneOffset.UTC));
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tenant/lots");

    @Test
    void valueThatDoesNotFitItsColumnIsAValidationError() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(wrap("22001"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.VALIDATION_ERROR);
        assertThat(response.getBody().path()).isEqualTo("/api/tenant/lots");
        assertThat(handler.handleDataIntegrity(wrap("22003"), request).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void constraintViolationIsAConflict() {
        ResponseEntity<ErrorResponse> response = handler.handleDataIntegrity(wrap("23505"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.CONFLICT);
        assertThat(handler.handleDataIntegrity(new DataIntegrityViolationException("sin causa SQL"), request)
                .getStatusCode().value()).isEqualTo(409);
    }

    private static DataIntegrityViolationException wrap(String sqlState) {
        SQLException cause = new SQLException("error de la base", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("hibernate", cause));
    }
}
