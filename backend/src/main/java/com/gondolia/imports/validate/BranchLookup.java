package com.gondolia.imports.validate;

import com.gondolia.imports.parse.ImportValues;
import com.gondolia.security.BranchAccessService.BranchRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Busca la sucursal que nombra una celda de la planilla entre las sucursales accesibles por quien importa
 * (SPEC §16.1: nombre o código). Compara sin acentos, mayúsculas ni signos y acepta el nombre sin el prefijo
 * «Sucursal» / «Suc.»: «Norte», «Suc. Norte», «sucursal norte» y «NOR» encuentran a «Sucursal Norte».
 */
public final class BranchLookup {

    private static final List<String> PREFIXES = List.of("sucursal", "suc");

    private final Map<String, BranchRef> exact = new LinkedHashMap<>();
    private final Map<String, BranchRef> aliases = new LinkedHashMap<>();
    private final List<BranchRef> branches;

    public BranchLookup(List<BranchRef> branches) {
        this.branches = List.copyOf(branches);
        for (BranchRef branch : branches) {
            register(branch, branch.name());
            if (branch.code() != null && !branch.code().isBlank()) {
                register(branch, branch.code());
            }
        }
    }

    private void register(BranchRef branch, String value) {
        String slug = ImportValues.slug(value);
        if (slug.isEmpty()) {
            return;
        }
        exact.putIfAbsent(slug, branch);
        String stripped = withoutPrefix(slug);
        if (stripped != null) {
            aliases.putIfAbsent(stripped, branch);
        }
    }

    /** Sucursal que corresponde al texto de la celda, o {@code null} si no es ninguna de las accesibles. */
    public BranchRef find(String raw) {
        String slug = ImportValues.slug(raw);
        if (slug.isEmpty()) {
            return null;
        }
        BranchRef branch = exact.get(slug);
        if (branch == null) {
            branch = aliases.get(slug);
        }
        String stripped = withoutPrefix(slug);
        if (branch == null && stripped != null) {
            branch = exact.get(stripped);
            if (branch == null) {
                branch = aliases.get(stripped);
            }
        }
        return branch;
    }

    /** Nombres de las sucursales válidas, para el mensaje de error. */
    public String names() {
        List<String> names = new ArrayList<>();
        branches.forEach(b -> names.add(b.name()));
        return names.isEmpty() ? "ninguna" : String.join(", ", names);
    }

    private static String withoutPrefix(String slug) {
        for (String prefix : PREFIXES) {
            if (slug.startsWith(prefix) && slug.length() > prefix.length()) {
                return slug.substring(prefix.length());
            }
        }
        return null;
    }
}
