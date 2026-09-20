package com.gondolia.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.domain.tenant.TenantEventType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.seed.DemoWorld.EventSpec;
import com.gondolia.seed.DemoWorld.TenantSpec;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * El reparto de módulos del mundo demo (SPEC §11, {@code docs/datos-demo.md} §2). Vale la pena fijarlo: la consola de
 * dueños calcula la adopción por módulo y el MRR estimado sobre esta matriz, y el menú de cada comercio depende de
 * ella (Don Pepe no ve Transferencias ni Integración POS; Vida Sana no ve Punto de venta ni Cajas y turnos).
 */
class DemoWorldModulesTest {

    @Test
    void everyTenantGetsTheDocumentedModules() {
        Map<String, Set<TenantModule>> seeded = new LinkedHashMap<>();
        for (TenantSpec spec : DemoWorld.tenants()) {
            seeded.put(spec.name(), EnumSet.copyOf(spec.modules()));
        }
        // En orden: los comercios se dan de alta en ese orden, así que es el ID que documenta la tabla.
        assertThat(seeded).containsExactlyEntriesOf(DemoModuleMatrix.DOCUMENTED);
    }

    /**
     * {@code DemoWorldBuilder} da de alta los módulos a partir de los eventos {@code MODULE_ENABLED}, no del conjunto
     * {@code modules} del comercio: si los dos se separan, la base queda distinta del mundo que simula el seeder.
     */
    @Test
    void moduleEventsExplainExactlyTheModulesOfEachTenant() {
        for (TenantSpec spec : DemoWorld.tenants()) {
            Set<String> enabledByEvents = new TreeSet<>();
            for (EventSpec event : spec.events()) {
                assertThat(event.type())
                        .as("%s: el seeder no sabe dar de baja módulos sembrados", spec.name())
                        .isNotEqualTo(TenantEventType.MODULE_DISABLED);
                if (event.type() == TenantEventType.MODULE_ENABLED) {
                    assertThat(enabledByEvents.add(event.fromValue()))
                            .as("%s: módulo %s habilitado dos veces", spec.name(), event.fromValue())
                            .isTrue();
                }
            }
            Set<String> modules = new TreeSet<>();
            spec.modules().forEach(module -> modules.add(module.name()));
            assertThat(enabledByEvents).as("módulos de %s", spec.name()).isEqualTo(modules);
        }
    }

    /** Ningún módulo puede quedar al 100 % ni en 0 % de adopción: las métricas del dueño quedarían planas. */
    @Test
    void moduleAdoptionStaysVaried() {
        for (TenantModule module : TenantModule.values()) {
            long active = DemoWorld.tenants().stream().filter(spec -> spec.status() == TenantStatus.ACTIVE).count();
            long withModule = DemoWorld.tenants().stream()
                    .filter(spec -> spec.status() == TenantStatus.ACTIVE && spec.modules().contains(module))
                    .count();
            assertThat(withModule).as("comercios activos con %s", module).isPositive().isLessThan(active);
        }
    }
}
