package com.gondolia.catalog;

import com.gondolia.security.BranchAccessService.BranchRef;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Alcance de sucursales de una lectura del catálogo (SPEC §3.5): las sucursales accesibles que entran en el
 * {@code X-Branch-Id} del request, ordenadas por nombre.
 *
 * @param branches sucursales del alcance (puede estar vacío si el usuario no tiene ninguna asignada)
 * @param single   {@code true} si el alcance es una sola sucursal (el frontend no muestra la columna "Sucursal")
 */
public record CatalogScope(List<BranchRef> branches, boolean single) {

    public CatalogScope {
        branches = branches == null ? List.of() : List.copyOf(branches);
    }

    public List<Long> branchIds() {
        return branches.stream().map(BranchRef::id).toList();
    }

    public Map<Long, String> names() {
        return branches.stream().collect(Collectors.toMap(BranchRef::id, BranchRef::name, (a, b) -> a));
    }

    public boolean isEmpty() {
        return branches.isEmpty();
    }

    /** Alcance a partir de las sucursales accesibles y de los ids que resolvió {@code scopeBranchIds()}. */
    public static CatalogScope of(List<BranchRef> accessible, List<Long> scopeBranchIds) {
        Map<Long, BranchRef> byId = accessible == null
                ? Map.of()
                : accessible.stream().collect(Collectors.toMap(BranchRef::id, Function.identity(), (a, b) -> a));
        List<Long> ids = scopeBranchIds == null ? List.of() : scopeBranchIds;
        List<BranchRef> inScope = ids.stream().map(byId::get).filter(java.util.Objects::nonNull)
                .sorted(java.util.Comparator.comparing(BranchRef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new CatalogScope(inScope, inScope.size() == 1);
    }
}
