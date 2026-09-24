package com.gondolia.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.announcements.PlatformAnnouncementController;
import com.gondolia.security.Roles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Matriz de la consola de GondolIA (SPEC §3.3) leída de los {@code @PreAuthorize} de sus controladores: qué endpoints
 * comparte soporte con el dueño y cuáles siguen siendo solo del dueño. Es la segunda barrera (la primera es la regla
 * por URL y método de {@code SecurityConfig}); si un endpoint nuevo se abre a soporte, esta prueba obliga a decidirlo
 * a propósito. Sin base de datos.
 */
class PlatformConsoleAccessMatrixTest {

    @Test
    void supportSharesOnlyTheClientAndModuleEndpoints() {
        assertThat(expressions(TenantAdminController.class)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "list", Roles.PLATFORM_ANY,
                "detail", Roles.PLATFORM_ANY,
                "update", Roles.PLATFORM_ANY,
                "resetAdminPassword", Roles.PLATFORM_ANY,
                "create", Roles.OWNER,
                "disable", Roles.OWNER,
                "enable", Roles.OWNER,
                "cancel", Roles.OWNER,
                "reactivate", Roles.OWNER,
                "delete", Roles.OWNER));
        assertThat(expressions(PlatformModulesController.class)).containsExactlyInAnyOrderEntriesOf(Map.of(
                "catalog", Roles.OWNER,
                "matrix", Roles.PLATFORM_ANY,
                "statuses", Roles.PLATFORM_ANY,
                "setEnabled", Roles.PLATFORM_ANY));
    }

    @Test
    void metricsTeamAndAnnouncementsStayWithTheOwner() {
        for (Class<?> controller : List.of(PlatformMetricsController.class, PlatformTeamController.class,
                PlatformAnnouncementController.class)) {
            assertThat(expressions(controller).values()).as(controller.getSimpleName())
                    .isNotEmpty()
                    .containsOnly(Roles.OWNER);
        }
    }

    /** Expresión efectiva de cada endpoint: la del método o, si no tiene, la de la clase. */
    private static Map<String, String> expressions(Class<?> controller) {
        PreAuthorize classLevel = controller.getAnnotation(PreAuthorize.class);
        Map<String, String> result = new HashMap<>();
        for (Method method : controller.getDeclaredMethods()) {
            if (!AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class)) {
                continue;
            }
            PreAuthorize own = method.getAnnotation(PreAuthorize.class);
            result.put(method.getName(), own != null ? own.value() : classLevel.value());
        }
        return result;
    }
}
