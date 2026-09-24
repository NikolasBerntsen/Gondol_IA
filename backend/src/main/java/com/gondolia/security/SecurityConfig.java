package com.gondolia.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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

    /**
     * Lecturas de la consola que también usa soporte (SPEC §3.3): listado y detalle de los clientes, sus módulos y la
     * matriz de módulos. El resto de {@code /api/platform/**} (métricas, equipo, avisos) sigue siendo del dueño.
     */
    static final String[] SUPPORT_PLATFORM_READS = {
            "/api/platform/tenants",
            "/api/platform/tenants/*",
            "/api/platform/tenants/*/modules",
            "/api/platform/tenant-modules"
    };

    /** Escrituras de la consola que también usa soporte: los datos de un cliente y el alta o baja de un módulo. */
    static final String[] SUPPORT_PLATFORM_EDITS = {
            "/api/platform/tenants/*",
            "/api/platform/tenants/*/modules/*"
    };

    /** Soporte también restablece la contraseña del administrador de un cliente (el "no puedo entrar"). */
    static final String SUPPORT_PLATFORM_ADMIN_PASSWORD = "/api/platform/tenants/*/reset-admin-password";

    /** Orden de {@link PreAuthorizeBeforeBindingInterceptor} entre los interceptores de Spring MVC. */
    public static final int PRE_AUTHORIZE_INTERCEPTOR_ORDER = 10;

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
                .headers(SecurityConfig::securityHeaders)
                .anonymous(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(securityErrorHandler)
                        .accessDeniedHandler(securityErrorHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // Soporte entra solo a estas rutas de la consola, con estos métodos (sin alta, bloqueo, baja
                        // ni eliminación). Qué acción es solo del dueño también lo dice el @PreAuthorize de cada
                        // controlador: son dos barreras.
                        .requestMatchers(HttpMethod.GET, SUPPORT_PLATFORM_READS)
                        .hasAnyRole("PLATFORM_OWNER", "SUPPORT_AGENT")
                        .requestMatchers(HttpMethod.PUT, SUPPORT_PLATFORM_EDITS)
                        .hasAnyRole("PLATFORM_OWNER", "SUPPORT_AGENT")
                        .requestMatchers(HttpMethod.POST, SUPPORT_PLATFORM_ADMIN_PASSWORD)
                        .hasAnyRole("PLATFORM_OWNER", "SUPPORT_AGENT")
                        .requestMatchers("/api/platform/**").hasRole("PLATFORM_OWNER")
                        .requestMatchers("/api/support/**").hasRole("SUPPORT_AGENT")
                        .requestMatchers("/api/tenant/**")
                        .hasAnyRole("TENANT_BOSS", "TENANT_ADMIN", "TENANT_EMPLOYEE", "TENANT_CASHIER")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Headers de seguridad propios del backend: {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY}
     * y {@code Cache-Control: no-store} (los de Spring Security). Coinciden con los que pone nginx en el borde
     * ({@code frontend/nginx/snippets/gondolia-security-headers.conf}), que oculta estos del backend para que cada header
     * salga una sola vez.
     * <p>
     * Sin HSTS: el TLS lo termina nginx con una CA local y en puertos no estándar, y en {@code localhost} también se usa
     * HTTP. Con {@code forward-headers-strategy: native} cada request por HTTPS llegaría "segura" y Spring mandaría
     * {@code Strict-Transport-Security; includeSubDomains}: el navegador fijaría HTTPS para {@code localhost} o el nombre
     * de la red y dejaría de abrir {@code http://localhost:8080} (y un certificado no confiable ya no se podría aceptar).
     */
    static void securityHeaders(HeadersConfigurer<HttpSecurity> headers) {
        headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(HeadersConfigurer.FrameOptionsConfig::deny)
                .cacheControl(Customizer.withDefaults())
                .httpStrictTransportSecurity(HeadersConfigurer.HstsConfig::disable);
    }

    /**
     * Aplica los {@code @PreAuthorize} de los controladores antes de leer y validar el cuerpo (ver
     * {@link PreAuthorizeBeforeBindingInterceptor}): un rol sin permiso recibe 403 sea cual sea el payload. Se
     * registra acá, junto a {@code @EnableMethodSecurity}, para que existan siempre juntos. Va después de
     * {@code RequiresModuleInterceptor} (orden 0), que conserva su precedencia.
     */
    @Bean
    public WebMvcConfigurer preAuthorizeBeforeBindingConfigurer(ApplicationContext context) {
        PreAuthorizeBeforeBindingInterceptor interceptor = PreAuthorizeBeforeBindingInterceptor.create(context);
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).order(PRE_AUTHORIZE_INTERCEPTOR_ORDER);
            }
        };
    }
}
