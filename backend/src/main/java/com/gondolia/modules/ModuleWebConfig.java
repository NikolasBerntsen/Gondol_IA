package com.gondolia.modules;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registra {@link RequiresModuleInterceptor} para todo {@code /api/**} (SPEC §14.2).
 * <p>
 * {@link ModuleService} se toma con {@link ObjectProvider} para que las pruebas de porción web ({@code @WebMvcTest})
 * que no lo cargan sigan levantando: sin ese bean no hay nada que chequear y no se registra el interceptor.
 */
@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class ModuleWebConfig implements WebMvcConfigurer {

    /** Prefijo protegido: todas las APIs del producto. */
    public static final String API_PATH_PATTERN = "/api/**";

    private final ObjectProvider<ModuleService> moduleService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        ModuleService service = moduleService.getIfAvailable();
        if (service != null) {
            registry.addInterceptor(new RequiresModuleInterceptor(service)).addPathPatterns(API_PATH_PATTERN);
        }
    }
}
