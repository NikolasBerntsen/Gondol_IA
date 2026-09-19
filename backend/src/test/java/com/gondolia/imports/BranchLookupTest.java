package com.gondolia.imports;

import static org.assertj.core.api.Assertions.assertThat;

import com.gondolia.imports.validate.BranchLookup;
import com.gondolia.security.BranchAccessService.BranchRef;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Cómo se reconoce la sucursal escrita en la planilla (SPEC §16.1: nombre o código). */
class BranchLookupTest {

    private static final BranchRef CENTRO = new BranchRef(1L, "Sucursal Centro", "CEN");
    private static final BranchRef NORTE = new BranchRef(2L, "Sucursal Norte", "NOR");
    private static final BranchRef DEPOSITO = new BranchRef(3L, "Depósito", null);

    private final BranchLookup lookup = new BranchLookup(List.of(CENTRO, NORTE, DEPOSITO));

    @ParameterizedTest
    @ValueSource(strings = {"Sucursal Norte", "sucursal norte", "SUCURSAL NORTE ", "Norte", "Suc. Norte", "NOR", "nor"})
    void findsTheBranchByNameCodeOrShortName(String cell) {
        assertThat(lookup.find(cell)).isEqualTo(NORTE);
    }

    @Test
    void ignoresAccents() {
        assertThat(lookup.find("deposito")).isEqualTo(DEPOSITO);
        assertThat(lookup.find("Sucursal Depósito")).isEqualTo(DEPOSITO);
    }

    @Test
    void unknownBranchesAreNotFound() {
        assertThat(lookup.find("Sucursal Oeste")).isNull();
        assertThat(lookup.find("Oeste")).isNull();
        assertThat(lookup.find("Sucursal")).isNull();
        assertThat(lookup.find("")).isNull();
        assertThat(lookup.find(null)).isNull();
    }

    @Test
    void listsTheValidNamesForTheErrorMessage() {
        assertThat(lookup.names()).isEqualTo("Sucursal Centro, Sucursal Norte, Depósito");
        assertThat(new BranchLookup(List.of()).names()).isEqualTo("ninguna");
    }
}
