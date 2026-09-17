package com.gondolia.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Propiedades {@code app.*} tipadas (ver application.yml y SPEC §2.2).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid @NotNull Jwt jwt,
        @Valid @NotNull Ai ai,
        @Valid @NotNull Storage storage,
        @NotBlank String timezone,
        boolean seedDemo,
        boolean devFixture,
        boolean openfoodfactsEnabled,
        @Valid @NotNull Bootstrap bootstrap,
        @Valid @NotNull Cors cors) {

    public record Jwt(@NotBlank String secret, @Min(1) int expirationHours) {
    }

    public record Ai(@NotBlank String baseUrl) {
    }

    public record Storage(@NotBlank String dir) {
    }

    public record Bootstrap(String ownerEmail, String ownerPassword) {
    }

    public record Cors(List<String> allowedOrigins) {

        public Cors {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        }
    }
}
