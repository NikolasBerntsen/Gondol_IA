package com.gondolia.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.security.ApiKeyService;
import com.gondolia.seed.DemoWorld.TenantSpec;
import com.gondolia.seed.DemoWorld.UserSpec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Consistencia del catálogo, del mundo demo (SPEC §11) y de los escenarios armados, sin base de datos. */
class DemoCatalogAndWorldTest {

    private final List<TenantSpec> tenants = DemoWorld.tenants();

    private TenantSpec tenant(String key) {
        return tenants.stream().filter(spec -> spec.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void ean13CheckDigits() {
        assertThat(Ean13.of("1234", 50001)).isEqualTo("7791234500017");
        assertThat(Ean13.isValid("7791234500017")).isTrue();
        assertThat(Ean13.isValid("7791234500018")).isFalse();
        assertThat(Ean13.isValid("779123450001")).isFalse();
        assertThat(Ean13.checkDigit("779100000001")).isBetween(0, 9);
        assertThatThrownBy(() -> Ean13.of("12", 1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void catalogBarcodesAreValidUniqueArgentineEans() {
        Set<String> barcodes = new HashSet<>();
        for (DemoCatalog.Template template : DemoCatalog.ALL) {
            assertThat(template.barcode()).startsWith("779");
            assertThat(Ean13.isValid(template.barcode())).as(template.name()).isTrue();
            assertThat(barcodes.add(template.barcode())).as("EAN repetido: " + template.name()).isTrue();
            assertThat(template.price()).isGreaterThan(template.cost());
            assertThat(template.packSize()).isPositive();
            DemoCatalog.supplier(template.supplierKey());
        }
        assertThat(DemoCatalog.byKey(DemoScenarios.LIVE_RECALL_PRODUCT).barcode()).isEqualTo("7791234500017");
        assertThat(DemoCatalog.byKey(DemoScenarios.LIVE_RECALL_PRODUCT).name())
                .isEqualTo("Sopa de tomate en lata La Huerta 340 g");
    }

    @Test
    void richTenantsCatalogsHaveAboutSixtyProductsWithEveryPattern() {
        for (String key : List.of(DemoWorld.DON_PEPE, DemoWorld.VIDA_SANA, DemoWorld.EL_SOL)) {
            List<DemoCatalog.Template> templates = DemoWorldBuilder.templatesFor(tenant(key));
            assertThat(templates).hasSizeBetween(58, 70);
            Set<DemoCatalog.Pattern> patterns = templates.stream().map(DemoCatalog.Template::pattern)
                    .collect(Collectors.toSet());
            assertThat(patterns).contains(DemoCatalog.Pattern.STABLE, DemoCatalog.Pattern.WEEKEND,
                    DemoCatalog.Pattern.INTERMITTENT, DemoCatalog.Pattern.GROWING, DemoCatalog.Pattern.DECLINING,
                    DemoCatalog.Pattern.NONE);
        }
        // El producto del recall en vivo está en Don Pepe y El Sol, no en Vida Sana ni en los livianos.
        assertThat(DemoWorldBuilder.templatesFor(tenant(DemoWorld.DON_PEPE)))
                .anyMatch(t -> t.key().equals(DemoScenarios.LIVE_RECALL_PRODUCT));
        assertThat(DemoWorldBuilder.templatesFor(tenant(DemoWorld.EL_SOL)))
                .anyMatch(t -> t.key().equals(DemoScenarios.LIVE_RECALL_PRODUCT));
        for (TenantSpec spec : tenants) {
            if (!spec.key().equals(DemoWorld.DON_PEPE) && !spec.key().equals(DemoWorld.EL_SOL)) {
                assertThat(DemoWorldBuilder.templatesFor(spec))
                        .noneMatch(t -> t.key().equals(DemoScenarios.LIVE_RECALL_PRODUCT)
                                || t.key().equals(DemoScenarios.OLD_RECALL_PRODUCT));
            }
        }
    }

    @Test
    void worldMatchesSpecSection11() {
        TenantSpec donPepe = tenant(DemoWorld.DON_PEPE);
        assertThat(donPepe.name()).isEqualTo("Almacén Don Pepe");
        assertThat(donPepe.plan()).isEqualTo(TenantPlan.BASICO);
        assertThat(donPepe.branches()).extracting(DemoWorld.BranchSpec::name).containsExactly("Sucursal Principal");
        assertThat(donPepe.modules()).containsExactly(TenantModule.POS_GONDOLIA);

        TenantSpec vidaSana = tenant(DemoWorld.VIDA_SANA);
        assertThat(vidaSana.rotation()).isEqualTo(StockRotation.FEFO);
        assertThat(vidaSana.branches()).hasSize(2);
        assertThat(vidaSana.modules()).containsExactlyInAnyOrder(TenantModule.POS_INTEGRATION,
                TenantModule.MULTI_BRANCH);

        TenantSpec elSol = tenant(DemoWorld.EL_SOL);
        assertThat(elSol.rotation()).isEqualTo(StockRotation.FIFO);
        assertThat(elSol.branches()).extracting(DemoWorld.BranchSpec::name)
                .containsExactly("Sucursal Centro", "Sucursal Fisherton", "Sucursal Echesortu");
        assertThat(elSol.modules()).isEqualTo(EnumSet.allOf(TenantModule.class));
        assertThat(elSol.users()).filteredOn(user -> user.email().equals("empleado@elsol.com"))
                .extracting(UserSpec::branchKeys).containsExactly(List.of("CEN", "FIS"));
        assertThat(elSol.users()).filteredOn(user -> user.email().equals("empleado.echesortu@elsol.com"))
                .extracting(UserSpec::branchKeys).containsExactly(List.of("ECH"));

        TenantSpec laEsquina = tenant(DemoWorld.LA_ESQUINA);
        assertThat(laEsquina.plan()).isEqualTo(TenantPlan.FREEMIUM);
        assertThat(laEsquina.status()).isEqualTo(TenantStatus.DISABLED);

        assertThat(tenants).filteredOn(spec -> spec.status() == TenantStatus.CANCELLED).hasSize(2);
        assertThat(tenants).filteredOn(spec -> !spec.rich() && !spec.key().equals(DemoWorld.LA_ESQUINA))
                .hasSizeBetween(12, 14);
        // Altas repartidas en los últimos 12 meses (métricas de crecimiento).
        LocalDate today = LocalDate.of(2026, 9, 19);
        Set<String> months = tenants.stream()
                .map(spec -> today.minusDays(spec.createdDaysAgo()).toString().substring(0, 7))
                .collect(Collectors.toSet());
        assertThat(months).hasSizeGreaterThanOrEqualTo(10);
        assertThat(tenants).allMatch(spec -> spec.createdDaysAgo() <= 365);
        // El equipo de la plataforma (el dueño da de alta los comercios) es anterior a todos.
        assertThat(tenants).allMatch(spec -> spec.createdDaysAgo() < DemoWorldBuilder.PLATFORM_TEAM_DAYS_AGO);
    }

    @Test
    void usersAreUniqueAndEveryTenantHasBossAdminAndEmployee() {
        Set<String> emails = new HashSet<>();
        DemoWorld.PLATFORM_USERS.forEach(user -> assertThat(emails.add(user.email())).isTrue());
        for (TenantSpec spec : tenants) {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            for (UserSpec user : spec.users()) {
                assertThat(emails.add(user.email())).as("email repetido " + user.email()).isTrue();
                roles.add(user.role());
                if (user.role() == Role.TENANT_EMPLOYEE || user.role() == Role.TENANT_CASHIER) {
                    assertThat(user.branchKeys()).as(user.email()).isNotEmpty();
                }
                user.branchKeys().forEach(spec::branch);
            }
            assertThat(roles).contains(Role.TENANT_BOSS, Role.TENANT_ADMIN, Role.TENANT_EMPLOYEE);
            // Quien atiende la caja trabaja en esa sucursal (o es administrador).
            for (DemoWorld.BranchSpec branch : spec.branches()) {
                if (branch.staff() == null) {
                    continue;
                }
                for (String email : new String[] {branch.staff().morning(), branch.staff().afternoon(),
                        branch.staff().secondRegister()}) {
                    if (email == null) {
                        continue;
                    }
                    UserSpec user = spec.users().stream().filter(u -> u.email().equals(email)).findFirst()
                            .orElseThrow();
                    assertThat(user.role() == Role.TENANT_ADMIN || user.branchKeys().contains(branch.key()))
                            .as(email + " en " + branch.name()).isTrue();
                    assertThat(user.role()).isNotEqualTo(Role.TENANT_BOSS);
                }
            }
        }
        assertThat(emails).contains("jefe@donpepe.com", "admin@donpepe.com", "empleado@donpepe.com",
                "cajero@donpepe.com", "jefe@vidasana.com", "admin@vidasana.com", "empleado@vidasana.com",
                "jefe@elsol.com", "admin@elsol.com", "empleado@elsol.com", "empleado.echesortu@elsol.com",
                "cajero@elsol.com", "cajero.fisherton@elsol.com", "admin@laesquina.com", "dueno@gondolia.app",
                "socia@gondolia.app", "soporte@gondolia.app", "soporte2@gondolia.app");
    }

    @Test
    void scenariosReferenceExistingProductsAndBranches() {
        for (String key : List.of(DemoWorld.DON_PEPE, DemoWorld.VIDA_SANA, DemoWorld.EL_SOL)) {
            TenantSpec spec = tenant(key);
            Set<String> products = DemoWorldBuilder.templatesFor(spec).stream().map(DemoCatalog.Template::key)
                    .collect(Collectors.toSet());
            DemoScenarios.Scenario scenario = DemoScenarios.forTenant(key);
            Set<String> tags = new HashSet<>();
            for (DemoScenarios.ScriptedLot lot : scenario.lots()) {
                spec.branch(lot.branch());
                assertThat(products).as(key + " " + lot.product()).contains(lot.product());
                if (lot.tag() != null) {
                    tags.add(lot.branch() + ":" + lot.tag());
                }
            }
            scenario.discounts().forEach(d -> assertThat(tags).contains(d.branch() + ":" + d.lotTag()));
            scenario.recalls().forEach(r -> assertThat(tags).contains(r.branch() + ":" + r.lotTag()));
            scenario.supplyStops().forEach(s -> assertThat(products).contains(s.product()));
            scenario.spikes().forEach(s -> assertThat(products).contains(s.product()));
            scenario.reorders().forEach(r -> assertThat(products).contains(r.product()));
            scenario.discarded().forEach(d -> assertThat(products).contains(d.product()));
            for (DemoScenarios.ManualOrders orders : scenario.manualOrders()) {
                spec.branch(orders.branch());
                assertThat(DemoWorldBuilder.templatesFor(spec)).as(key + " " + orders)
                        .anyMatch(template -> orders.categories().contains(template.category()));
            }
        }
        // Ventas manuales (datos-demo §2 y §4): el mayorista de Vida Sana y los pedidos del club en El Sol.
        assertThat(DemoScenarios.forTenant(DemoWorld.VIDA_SANA).manualOrders()).isNotEmpty();
        assertThat(DemoScenarios.forTenant(DemoWorld.EL_SOL).manualOrders()).isNotEmpty();
        // El lote del recall en vivo solo se arma en Don Pepe y en El Sol Fisherton.
        assertThat(DemoScenarios.forTenant(DemoWorld.DON_PEPE).lots())
                .filteredOn(lot -> DemoScenarios.LIVE_RECALL_LOT.equals(lot.lotNumber()))
                .extracting(DemoScenarios.ScriptedLot::branch).containsExactly("PRI");
        assertThat(DemoScenarios.forTenant(DemoWorld.EL_SOL).lots())
                .filteredOn(lot -> DemoScenarios.LIVE_RECALL_LOT.equals(lot.lotNumber()))
                .extracting(DemoScenarios.ScriptedLot::branch).containsExactly("FIS");
        assertThat(DemoScenarios.forTenant(DemoWorld.VIDA_SANA).lots())
                .noneMatch(lot -> DemoScenarios.LIVE_RECALL_LOT.equals(lot.lotNumber()));
    }

    @Test
    void demoApiKeysHaveTheCoreFormat() {
        String key = DemoWorldBuilder.demoApiKey(DemoWorld.VIDA_SANA, "NCB");
        assertThat(key).startsWith(ApiKeyService.KEY_PREFIX)
                .hasSize(ApiKeyService.KEY_PREFIX.length() + ApiKeyService.RANDOM_LENGTH)
                .matches("gk_[A-Za-z0-9]{40}");
        assertThat(DemoWorldBuilder.demoApiKey(DemoWorld.VIDA_SANA, "CDR")).isNotEqualTo(key);
    }

    @Test
    void labelPhotoIsARealPng() throws Exception {
        byte[] png = DemoLabelImage.render("7791234500017", "30112028");
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(image).isNotNull();
        assertThat(image.getWidth()).isEqualTo(DemoLabelImage.WIDTH);
        assertThat(image.getHeight()).isEqualTo(DemoLabelImage.HEIGHT);
        assertThat(png.length).isLessThan(1_000_000);
        assertThatThrownBy(() -> DemoLabelImage.render("123", "0101")).isInstanceOf(IllegalArgumentException.class);
    }
}
