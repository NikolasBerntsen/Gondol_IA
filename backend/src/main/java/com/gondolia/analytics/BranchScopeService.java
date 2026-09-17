package com.gondolia.analytics;

import com.gondolia.security.BranchAccessService;
import com.gondolia.security.BranchAccessService.BranchRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Alcance de sucursales de una lectura del módulo B (SPEC §3.5): las sucursales accesibles del header
 * {@code X-Branch-Id} más sus nombres, para informar {@code branchId}/{@code branchName} en cada fila.
 * <p>
 * Lo usan los tres paquetes del módulo ({@code analytics}, {@code alerts} e {@code insights}).
 */
@Service
@RequiredArgsConstructor
public class BranchScopeService {

    private final BranchAccessService branchAccess;

    /**
     * Alcance actual: ids accesibles según el header, sus nombres y si es la vista consolidada.
     *
     * @param branchIds sucursales del alcance (nunca {@code null}; vacío = el usuario no tiene ninguna)
     * @param names     id → nombre de todas las sucursales del comercio
     * @param all       {@code true} si el header no pidió una sucursal puntual
     */
    public record Scope(List<Long> branchIds, Map<Long, String> names, boolean all) {

        public Scope {
            branchIds = branchIds == null ? List.of() : List.copyOf(branchIds);
            names = names == null ? Map.of() : Map.copyOf(names);
        }

        public boolean isEmpty() {
            return branchIds.isEmpty();
        }

        public String nameOf(Long branchId) {
            return branchId == null ? null : names.get(branchId);
        }

        /** {@code "ALL"} o {@code "BRANCH"} para las respuestas del dashboard (SPEC §6.5). */
        public String label() {
            return all ? "ALL" : "BRANCH";
        }

        /** La única sucursal del alcance, o {@code null} si son varias (o ninguna). */
        public Long singleBranchId() {
            return branchIds.size() == 1 ? branchIds.getFirst() : null;
        }
    }

    /** Alcance del request actual. */
    public Scope current(Long tenantId) {
        List<Long> ids = branchAccess.scopeBranchIds();
        boolean all = branchAccess.requestedBranchId().isEmpty();
        return new Scope(ids, branchAccess.branchNames(tenantId), all);
    }

    /** Alcance de una sola sucursal ya validada (escrituras y detalle por sucursal). */
    public Scope of(Long tenantId, Long branchId) {
        branchAccess.assertAccess(branchId);
        return new Scope(List.of(branchId), branchAccess.branchNames(tenantId), false);
    }

    /** Sucursales accesibles con nombre, en orden alfabético (para selectores de la UI). */
    public List<BranchRef> accessibleBranches() {
        return branchAccess.accessibleBranches();
    }

    /** Nombres de todas las sucursales del comercio (incluidas las desactivadas). */
    public Map<Long, String> branchNames(Long tenantId) {
        return new LinkedHashMap<>(branchAccess.branchNames(tenantId));
    }
}
