package com.gondolia.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Seguridad HTTP stateless con JWT y reglas por prefijo (SPEC §5.2). El detalle fino se define con
 * {@code @PreAuthorize(Roles.X)} en cada controlador y el alcance por sucursal con {@link BranchAccessService}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * Rutas sin JWT: el WebSocket autentica en el CONNECT de STOMP y el webhook POS con API key. {@code /error} es
     * el despacho interno de errores del contenedor.
     */
    public static final String[] PUBLIC_PATHS = {
            "/api/auth/login",
            "/api/integrations/pos/**",
            "/actuator/health",
            "/actuator/health/**",
            "/api/docs",
            "/api/docs/**",
            "/api/swagger-ui.html",
            "/api/swagger-ui/**",
            "/ws",
            "/ws/**",
            "/error"
    };

    private final JwtService jwtService;
    private final UserAccessValidator userAccessValidator;
    private final SecurityErrorHandler securityErrorHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter =
                new JwtAuthenticationFilter(jwtService, userAccessValidator, securityErrorHandler, PUBLIC_PATHS);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/api/platform/**").hasRole("PLATFORM_OWNER")
                        .requestMatchers("/api/support/**").hasRole("SUPPORT_AGENT")
                        .requestMatchers("/api/tenant/**").hasAnyRole("TENANT_BOSS", "TENANT_ADMIN", "TENANT_EMPLOYEE")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
