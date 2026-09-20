package com.gondolia.seed;

import static com.gondolia.domain.tenant.TenantModule.MULTI_BRANCH;
import static com.gondolia.domain.tenant.TenantModule.POS_GONDOLIA;
import static com.gondolia.domain.tenant.TenantModule.POS_INTEGRATION;

import com.gondolia.domain.tenant.TenantModule;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * La matriz de módulos del mundo demo, escrita a mano tal como la documentan SPEC §11 y
 * {@code docs/datos-demo.md} §2 ("Módulos por comercio"). Es el contrato: si alguien cambia los módulos de un
 * comercio en {@link DemoWorld} sin actualizar la documentación (o al revés), las pruebas que la usan fallan.
 * <p>
 * No se deriva de {@code DemoWorld} a propósito: comparar el mundo contra sí mismo no verificaría nada.
 */
final class DemoModuleMatrix {

    /** Comercio → módulos habilitados al sembrar, en el orden en que se dan de alta (el ID del comercio). */
    static final Map<String, Set<TenantModule>> DOCUMENTED = documented();

    private DemoModuleMatrix() {
    }

    private static Map<String, Set<TenantModule>> documented() {
        Map<String, Set<TenantModule>> matrix = new LinkedHashMap<>();
        matrix.put("Almacén Don Pepe", EnumSet.of(POS_GONDOLIA));
        matrix.put("Dietética Vida Sana", EnumSet.of(POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Minimercado El Sol", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Kiosco La Esquina", EnumSet.of(POS_GONDOLIA));
        matrix.put("Farmacia San Martín", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Almacén La Abuela", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Kiosco 24 Horas Centro", EnumSet.of(POS_GONDOLIA));
        matrix.put("Dietética Natural Sur", EnumSet.of(POS_INTEGRATION));
        matrix.put("Minimercado Los Andes", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Almacén Doña Rosa", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION));
        matrix.put("Farmacia del Pueblo", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Despensa El Trébol", EnumSet.of(POS_GONDOLIA));
        matrix.put("Maxikiosco Estación", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION));
        matrix.put("Dietética Grano de Oro", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        matrix.put("Almacén Los Hermanos", EnumSet.of(POS_GONDOLIA));
        matrix.put("Kiosco El Paso", EnumSet.of(POS_GONDOLIA));
        matrix.put("Minimercado Punto Fresco", EnumSet.of(POS_GONDOLIA, POS_INTEGRATION, MULTI_BRANCH));
        return Collections.unmodifiableMap(matrix);
    }
}
