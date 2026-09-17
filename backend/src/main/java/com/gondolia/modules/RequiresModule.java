package com.gondolia.modules;

import com.gondolia.domain.tenant.TenantModule;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un controlador (o un método de controlador) que solo funciona si el comercio del usuario tiene habilitado el
 * módulo indicado (SPEC §14). La aplica {@link RequiresModuleInterceptor} sobre {@code /api/**}: si el módulo está
 * deshabilitado responde 403 {@code MODULE_DISABLED}.
 * <p>
 * La anotación en el método tiene prioridad sobre la de la clase. Para endpoints sin JWT (el webhook del POS externo,
 * que se autentica con API key) el interceptor no puede resolver el comercio: ahí el controlador tiene que chequear
 * {@code ModuleService.isEnabled(tenantId, module)} después de resolver la sucursal de la API key.
 *
 * <pre>{@code
 * @RestController
 * @RequestMapping("/api/tenant/pos")
 * @RequiresModule(TenantModule.POS_GONDOLIA)
 * class PosController { ... }
 * }</pre>
 */
@Documented
@Inherited
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresModule {

    /** Módulo que tiene que estar habilitado. */
    TenantModule value();
}
