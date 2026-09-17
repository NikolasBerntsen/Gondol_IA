package com.gondolia.security;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Autentica requests con {@code Authorization: Bearer <jwt>}.
 * <ul>
 *   <li>Sin header Bearer: sigue la cadena (las reglas de acceso deciden si corresponde 401).</li>
 *   <li>Token inválido o vencido: 401 {@code UNAUTHORIZED}.</li>
 *   <li>Usuario deshabilitado, token revocado o comercio bloqueado: el código de {@link UserAccessValidator}.</li>
 * </ul>
 * No se registra como bean para que el contenedor no lo agregue también a la cadena de filtros del servlet.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserAccessValidator userAccessValidator;
    private final SecurityErrorHandler errorHandler;
    private final List<PathPattern> skippedPaths;
    private final WebAuthenticationDetailsSource detailsSource = new WebAuthenticationDetailsSource();

    public JwtAuthenticationFilter(JwtService jwtService, UserAccessValidator userAccessValidator,
                                   SecurityErrorHandler errorHandler, String... skippedPathPatterns) {
        this.jwtService = jwtService;
        this.userAccessValidator = userAccessValidator;
        this.errorHandler = errorHandler;
        this.skippedPaths = Arrays.stream(skippedPathPatterns).map(PathPatternParser.defaultInstance::parse).toList();
    }

    /** Las rutas públicas ignoran el token (un token viejo no debe bloquear, por ejemplo, el login). */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        PathContainer container = PathContainer.parsePath(path);
        return skippedPaths.stream().anyMatch(pattern -> pattern.matches(container));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            chain.doFilter(request, response);
            return;
        }

        Optional<JwtClaims> claims = jwtService.parse(header.substring(BEARER_PREFIX.length()));
        if (claims.isEmpty()) {
            errorHandler.write(request, response, HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                    UserAccessValidator.MSG_SESSION_INVALID);
            return;
        }

        AuthUser user;
        try {
            user = userAccessValidator.validate(claims.get().userId(), claims.get().tokenVersion());
        } catch (ApiException ex) {
            errorHandler.write(request, response, ex.getStatus(), ex.getCode(), ex.getMessage());
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities());
        authentication.setDetails(detailsSource.buildDetails(request));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }
}
