package com.gondolia.movements;

import com.gondolia.modules.ModuleService;
import com.gondolia.security.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra {@link PosApiKeyInterceptor} para el webhook del POS ({@link #WEBHOOK_PATH_PATTERN}, ruta pública en
 * {@code SecurityConfig}: se autentica con API key y no con JWT).
 * <p>
 * Los servicios se toman con {@link ObjectProvider} para que las pruebas de porción web ({@code @WebMvcTest}) que no
 * los cargan sigan levantando.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class PosWebhookWebConfig implements WebMvcConfigurer {

    public static final String WEBHOOK_PATH_PATTERN = "/api/integrations/pos/**";

    private final ObjectProvider<ApiKeyService> apiKeyService;
    private final ObjectProvider<ModuleService> moduleService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ApiKeyService keys = apiKeyService.getIfAvailable();
        ModuleService modules = moduleService.getIfAvailable();
        if (keys != null && modules != null) {
            registry.addInterceptor(new PosApiKeyInterceptor(keys, modules)).addPathPatterns(WEBHOOK_PATH_PATTERN);
        }
    }
}
