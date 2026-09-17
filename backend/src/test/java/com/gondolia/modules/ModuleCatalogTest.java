package com.gondolia.modules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Catálogo y presets de módulos (SPEC §14.1): nombres, adicionales por sucursal, preset por plan y cuota estimada.
 */
class ModuleCatalogTest {

    @Test
    void catalogKeepsTheNamesAndPricesOfTheSpec() {
        assertThat(ModuleCatalog.all()).extracting(ModuleCatalog.ModuleInfo::module, ModuleCatalog.ModuleInfo::name)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TenantModule.POS_GONDOLIA, "Punto de venta GondolIA"),
                        org.assertj.core.groups.Tuple.tuple(TenantModule.POS_INTEGRATION,
                                "Integración con POS propio"),
                        org.assertj.core.groups.Tuple.tuple(TenantModule.MULTI_BRANCH, "Multi-sucursal"));
        assertThat(ModuleCatalog.monthlyPricePerBranch(TenantModule.POS_GONDOLIA)).isEqualByComparingTo("12000");
        assertThat(ModuleCatalog.monthlyPricePerBranch(TenantModule.POS_INTEGRATION)).isEqualByComparingTo("8000");
        assertThat(ModuleCatalog.monthlyPricePerBranch(TenantModule.MULTI_BRANCH)).isEqualByComparingTo("0");
        assertThat(ModuleCatalog.description(TenantModule.MULTI_BRANCH)).contains("transferencias");
    }

    @Test
    void presetsByPlan() {
        assertThat(ModuleCatalog.preset(TenantPlan.FREEMIUM)).containsExactly(TenantModule.POS_GONDOLIA);
        assertThat(ModuleCatalog.preset(TenantPlan.BASICO)).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(TenantModule.class));
        assertThat(ModuleCatalog.preset(TenantPlan.PROFESIONAL)).containsExactlyInAnyOrderElementsOf(
                EnumSet.allOf(TenantModule.class));
        assertThat(ModuleCatalog.preset(null)).isEmpty();
    }

    @Test
    void monthlyFeeAddsTheModulesPerActiveBranch() {
        Set<TenantModule> modules = EnumSet.of(TenantModule.POS_GONDOLIA, TenantModule.MULTI_BRANCH);

        // (55.000 del plan + 12.000 del POS) × 3 sucursales
        assertThat(ModuleCatalog.monthlyFee(TenantPlan.PROFESIONAL, modules, 3)).isEqualByComparingTo("201000");
        assertThat(ModuleCatalog.monthlyFee(TenantPlan.FREEMIUM, modules, 1)).isEqualByComparingTo("12000");
        assertThat(ModuleCatalog.monthlyFee(TenantPlan.BASICO, Set.of(), 2)).isEqualByComparingTo("50000");
        assertThat(ModuleCatalog.monthlyFee(TenantPlan.BASICO, modules, 0)).isEqualByComparingTo("0");
    }

    @Test
    void effectiveMaxBranchesDependsOnMultiBranch() {
        assertThat(ModuleService.effectiveMaxBranches(TenantPlan.PROFESIONAL, true)).isEqualTo(10);
        assertThat(ModuleService.effectiveMaxBranches(TenantPlan.PROFESIONAL, false)).isEqualTo(1);
        assertThat(ModuleService.effectiveMaxBranches(TenantPlan.FREEMIUM, true)).isEqualTo(1);
        assertThat(ModuleService.effectiveMaxBranches(null, true)).isEqualTo(1);
    }

    @Test
    void unknownModuleIsRejected() {
        assertThatThrownBy(() -> ModuleCatalog.of(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
