package com.gondolia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.config.AppProperties;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-0123456789-0123456789-0123456789-0123456789-abcdefghij";
    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    static AppProperties properties(String secret, int expirationHours) {
        return new AppProperties(
                new AppProperties.Jwt(secret, expirationHours),
                new AppProperties.Ai("http://localhost:8000"),
                new AppProperties.Storage("./data/uploads"),
                "America/Argentina/Buenos_Aires",
                false,
                false,
                false,
                new AppProperties.Bootstrap("dueno@gondolia.app", "Gondolia2026!"),
                new AppProperties.Cors(List.of("http://localhost:5173")));
    }

    private static JwtService service(String secret, Instant now) {
        return new JwtService(properties(secret, 12), Clock.fixed(now, ZoneOffset.UTC));
    }

    static User user(Long id, Role role, Long tenantId, int tokenVersion) {
        User user = new User();
        user.setId(id);
        user.setEmail("user" + id + "@gondolia.test");
        user.setFullName("Usuario " + id);
        user.setRole(role);
        user.setTenantId(tenantId);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    @Test
    void issuesAndParsesTenantUserToken() {
        JwtService jwt = service(SECRET, NOW);

        IssuedToken issued = jwt.issueToken(user(5L, Role.TENANT_ADMIN, 2L, 3));
        JwtClaims claims = jwt.parse(issued.token()).orElseThrow();

        assertThat(claims.userId()).isEqualTo(5L);
        assertThat(claims.email()).isEqualTo("user5@gondolia.test");
        assertThat(claims.role()).isEqualTo(Role.TENANT_ADMIN);
        assertThat(claims.tenantId()).isEqualTo(2L);
        assertThat(claims.tokenVersion()).isEqualTo(3);
        assertThat(claims.issuedAt()).isEqualTo(NOW);
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(12)));
        assertThat(claims.expiresAt()).isEqualTo(issued.expiresAt());
    }

    @Test
    void platformUserTokenHasNoTenant() {
        JwtService jwt = service(SECRET, NOW);

        JwtClaims claims = jwt.parse(jwt.issue(user(1L, Role.PLATFORM_OWNER, null, 0))).orElseThrow();

        assertThat(claims.role()).isEqualTo(Role.PLATFORM_OWNER);
        assertThat(claims.tenantId()).isNull();
    }

    @Test
    void usesContractClaimNames() throws Exception {
        String token = service(SECRET, NOW).issue(user(7L, Role.TENANT_EMPLOYEE, 4L, 2));

        JsonNode payload = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        JsonNode header = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));

        assertThat(header.get("alg").asText()).isEqualTo("HS256");
        assertThat(payload.get("sub").asText()).isEqualTo("7");
        assertThat(payload.get("email").asText()).isEqualTo("user7@gondolia.test");
        assertThat(payload.get("role").asText()).isEqualTo("TENANT_EMPLOYEE");
        assertThat(payload.get("tid").asLong()).isEqualTo(4L);
        assertThat(payload.get("tv").asInt()).isEqualTo(2);
    }

    @Test
    void rejectsExpiredToken() {
        String token = service(SECRET, NOW).issue(user(5L, Role.TENANT_ADMIN, 2L, 0));

        JwtService later = service(SECRET, NOW.plus(Duration.ofHours(13)));

        assertThat(later.parse(token)).isEmpty();
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String token = service(SECRET, NOW).issue(user(5L, Role.TENANT_ADMIN, 2L, 0));

        JwtService other = service(SECRET.replace('a', 'b'), NOW);

        assertThat(other.parse(token)).isEmpty();
    }

    @Test
    void rejectsTamperedToken() {
        JwtService jwt = service(SECRET, NOW);
        String token = jwt.issue(user(5L, Role.TENANT_EMPLOYEE, 2L, 0));
        String[] parts = token.split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"iss\":\"gondolia\",\"sub\":\"5\",\"role\":\"TENANT_ADMIN\",\"tid\":2,\"tv\":0,\"exp\":4102444800}"
                        .getBytes());

        assertThat(jwt.parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void rejectsGarbageAndBlankTokens() {
        JwtService jwt = service(SECRET, NOW);

        assertThat(jwt.parse(null)).isEmpty();
        assertThat(jwt.parse("  ")).isEmpty();
        assertThat(jwt.parse("not-a-jwt")).isEmpty();
        assertThat(jwt.parse("a.b.c")).isEmpty();
    }

    @Test
    void requiresSecretOfAtLeast32Bytes() {
        assertThatThrownBy(() -> service("short-secret", NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET");
    }
}
