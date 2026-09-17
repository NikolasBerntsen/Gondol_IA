package com.gondolia.config;

import com.gondolia.security.BranchAccessService;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI: {@code /api/docs} y Swagger UI en {@code /api/swagger-ui.html}.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI gondoliaOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("GondolIA API")
                        .version("1.0.0")
                        .description("API del prototipo GondolIA: gestión inteligente de inventario para comercios. "
                                + "Iniciá sesión en POST /api/auth/login y usá el token como Bearer. En /api/tenant/** "
                                + "el encabezado X-Branch-Id elige la sucursal (id numérico o \"all\")."))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }

    /** Agrega el encabezado opcional {@code X-Branch-Id} a todas las operaciones de {@code /api/tenant/**}. */
    @Bean
    public OpenApiCustomizer branchHeaderCustomizer() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/tenant/")) {
                    return;
                }
                item.readOperations().forEach(operation -> {
                    boolean present = operation.getParameters() != null && operation.getParameters().stream()
                            .anyMatch(p -> BranchAccessService.HEADER.equalsIgnoreCase(p.getName()));
                    if (!present) {
                        operation.addParametersItem(new HeaderParameter()
                                .name(BranchAccessService.HEADER)
                                .required(false)
                                .description("Sucursal: id numérico, o \"all\" (u omitido) para todas las accesibles")
                                .schema(new StringSchema().example("all")));
                    }
                });
            });
        };
    }
}
