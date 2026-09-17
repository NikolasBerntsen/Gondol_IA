package com.gondolia.security;

import com.gondolia.config.AppProperties;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Emisión y validación de JWT HS256. La validez del usuario (activo, versión de token, estado del tenant) la
 * verifica {@link UserAccessValidator} en cada request.
 */
@Slf4j
@Service
public class JwtService {

    static final String ISSUER = "gondolia";
    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_TENANT_ID = "tid";
    static final String CLAIM_TOKEN_VERSION = "tv";

    private static final int MIN_SECRET_BYTES = 32;
    private static final String DEV_SECRET_PREFIX = "gondolia-dev-only-";

    private final SecretKey key;
    private final Duration expiration;
    private final Clock clock;
    private final JwtParser parser;

    public JwtService(AppProperties properties, Clock clock) {
        String secret = properties.jwt().secret();
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret (JWT_SECRET) debe tener al menos " + MIN_SECRET_BYTES + " bytes para HS256");
        }
        if (secret.startsWith(DEV_SECRET_PREFIX)) {
            log.warn("Se está usando el secreto JWT de desarrollo. Definí JWT_SECRET fuera del entorno local.");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expiration = Duration.ofHours(properties.jwt().expirationHours());
        this.clock = clock;
        this.parser = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .clock(() -> Date.from(clock.instant()))
                .build();
    }

    /** Token firmado para el usuario. */
    public String issue(User user) {
        return issueToken(user).token();
    }

    /** Token firmado junto con su vencimiento (para las respuestas de login y cambio de contraseña). */
    public IssuedToken issueToken(User user) {
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(expiration);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_TENANT_ID, user.getTenantId())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt);
    }

    /** Claims si el token tiene firma válida, no venció y su contenido es coherente; si no, vacío. */
    public Optional<JwtClaims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(token.strip()).getPayload();
            Long userId = parseLong(claims.getSubject());
            String roleName = claims.get(CLAIM_ROLE, String.class);
            Long tokenVersion = asLong(claims.get(CLAIM_TOKEN_VERSION));
            if (userId == null || roleName == null || tokenVersion == null || claims.getExpiration() == null) {
                return Optional.empty();
            }
            Role role = Role.valueOf(roleName);
            Long tenantId = asLong(claims.get(CLAIM_TENANT_ID));
            if (role.isTenantRole() == (tenantId == null)) {
                return Optional.empty();
            }
            return Optional.of(new JwtClaims(userId, claims.get(CLAIM_EMAIL, String.class), role, tenantId,
                    Math.toIntExact(tokenVersion), toInstant(claims.getIssuedAt()),
                    claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException | ArithmeticException ex) {
            log.debug("JWT rechazado: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private static Long parseLong(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Instant toInstant(Date date) {
        return date != null ? date.toInstant() : null;
    }
}
