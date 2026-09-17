package com.gondolia.bootstrap;

import com.gondolia.common.util.Emails;
import com.gondolia.domain.inventory.Category;
import com.gondolia.domain.inventory.CategoryRepository;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.ProductUnit;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.pos.PosRegister;
import com.gondolia.domain.pos.PosRegisterRepository;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantEvent;
import com.gondolia.domain.tenant.TenantEventRepository;
import com.gondolia.domain.tenant.TenantEventType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserBranch;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.modules.ModuleService;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.ReceiveLotCommand;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Datos mínimos para desarrollo local ({@code APP_DEV_FIXTURE=true}). Idempotente: cada elemento se crea solo si no
 * existe.
 * <ul>
 *   <li>"Comercio de Prueba" (ALMACEN, BASICO, FIFO) con "Sucursal Centro" y "Sucursal Norte";
 *       {@code jefe@prueba.com}, {@code admin@prueba.com}, {@code empleado@prueba.com} y {@code cajero@prueba.com}
 *       (estos dos últimos solo en Centro). Tiene los tres módulos habilitados y una caja "Caja 1" en Centro.</li>
 *   <li>{@code soporte@gondolia.app} (SUPPORT_AGENT).</li>
 *   <li>"Otro Comercio" (KIOSCO, FREEMIUM) con {@code admin@otro.com}, para probar aislamiento; solo con el módulo
 *       {@code POS_INTEGRATION}.</li>
 *   <li>3 productos con 2 lotes (distintas fechas) en Centro y 1 lote en Norte. El yogur tiene en Centro un lote más
 *       nuevo que vence antes que el más viejo (aviso de FIFO).</li>
 * </ul>
 * Todas las contraseñas son {@code Demo2026!}.
 */
@Slf4j
@Order(20)
@Component
@ConditionalOnProperty(name = "app.dev-fixture", havingValue = "true")
@RequiredArgsConstructor
public class DevFixtureRunner implements ApplicationRunner {

    static final String PASSWORD = "Demo2026!";
    static final String TENANT_NAME = "Comercio de Prueba";
    static final String OTHER_TENANT_NAME = "Otro Comercio";
    static final String CENTRO = "Sucursal Centro";
    static final String NORTE = "Sucursal Norte";
    static final String REGISTER_NAME = "Caja 1";

    private record LotSpec(String branch, String lotNumber, int expiresInDays, int receivedDaysAgo, int quantity) {
    }

    private record ProductSpec(String barcode, String name, String brand, String category, String cost, String sale,
                               int minStock, List<LotSpec> lots) {
    }

    private static final List<ProductSpec> PRODUCTS = List.of(
            new ProductSpec("7791234000012", "Leche entera La Pradera 1 L", "La Pradera", "Lácteos", "950", "1400", 12,
                    List.of(new LotSpec(CENTRO, "LP2409A", 20, 6, 24),
                            new LotSpec(CENTRO, "LP2410B", 35, 1, 30),
                            new LotSpec(NORTE, "LP2409C", 18, 5, 20))),
            new ProductSpec("7791234000029", "Yogur bebible frutilla Vaquita 1 L", "Vaquita", "Lácteos", "1400", "2100",
                    8, List.of(new LotSpec(CENTRO, "YV0925", 25, 10, 15),
                            new LotSpec(CENTRO, "YV0930", 8, 2, 18),
                            new LotSpec(NORTE, "YV0926", 12, 4, 10))),
            new ProductSpec("7791234000036", "Galletitas de agua Crocantes 200 g", "Crocantes", "Almacén", "650",
                    "1000", 10, List.of(new LotSpec(CENTRO, "GC2603", 120, 30, 40),
                            new LotSpec(CENTRO, "GC2605", 180, 3, 36),
                            new LotSpec(NORTE, "GC2604", 150, 7, 25))));

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final TenantEventRepository tenantEventRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final UserBranchRepository userBranchRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final LotRepository lotRepository;
    private final PosRegisterRepository posRegisterRepository;
    private final StockService stockService;
    private final ModuleService moduleService;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    private String passwordHash;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        Tenant tenant = ensureTenant(TENANT_NAME, BusinessType.ALMACEN, TenantPlan.BASICO, "Ana Rodríguez",
                "admin@prueba.com", "CABA", "Buenos Aires");
        ensureSettings(tenant.getId());
        Branch centro = ensureBranch(tenant.getId(), CENTRO, "CEN", "Av. Corrientes 1234", "CABA", "Buenos Aires");
        Branch norte = ensureBranch(tenant.getId(), NORTE, "NOR", "Av. Cabildo 2100", "CABA", "Buenos Aires");
        ensureUser("jefe@prueba.com", "Julián Pérez", Role.TENANT_BOSS, tenant.getId());
        User admin = ensureUser("admin@prueba.com", "Ana Rodríguez", Role.TENANT_ADMIN, tenant.getId());
        User employee = ensureUser("empleado@prueba.com", "Emiliano Gómez", Role.TENANT_EMPLOYEE, tenant.getId());
        assignBranch(employee, centro);
        User cashier = ensureUser("cajero@prueba.com", "Carla Giménez", Role.TENANT_CASHIER, tenant.getId());
        assignBranch(cashier, centro);
        ensureUser("soporte@gondolia.app", "Sofía Martínez", Role.SUPPORT_AGENT, null);
        ensureModules(tenant.getId(), TenantModule.POS_GONDOLIA, TenantModule.POS_INTEGRATION,
                TenantModule.MULTI_BRANCH);
        ensureRegister(tenant.getId(), centro, REGISTER_NAME);

        Tenant other = ensureTenant(OTHER_TENANT_NAME, BusinessType.KIOSCO, TenantPlan.FREEMIUM, "Oscar Otero",
                "admin@otro.com", "Rosario", "Santa Fe");
        ensureSettings(other.getId());
        ensureBranch(other.getId(), Branch.DEFAULT_NAME, "PRI", "Bv. Oroño 850", "Rosario", "Santa Fe");
        ensureUser("admin@otro.com", "Oscar Otero", Role.TENANT_ADMIN, other.getId());
        ensureModules(other.getId(), TenantModule.POS_INTEGRATION);

        ensureCatalog(tenant.getId(), centro, norte, admin != null ? admin.getId() : null);
        log.info("Fixture de desarrollo listo: {} (Centro y Norte) y {}", TENANT_NAME, OTHER_TENANT_NAME);
    }

    private Tenant ensureTenant(String name, BusinessType businessType, TenantPlan plan, String contactName,
                                String contactEmail, String city, String province) {
        return tenantRepository.findByNameIgnoreCase(name).orElseGet(() -> {
            Tenant tenant = new Tenant();
            tenant.setName(name);
            tenant.setBusinessType(businessType);
            tenant.setPlan(plan);
            tenant.setContactName(contactName);
            tenant.setContactEmail(contactEmail);
            tenant.setCity(city);
            tenant.setProvince(province);
            tenant.setNotes("Creado por el fixture de desarrollo");
            tenantRepository.save(tenant);

            TenantEvent event = new TenantEvent();
            event.setTenantId(tenant.getId());
            event.setType(TenantEventType.CREATED);
            event.setToValue(plan.name());
            tenantEventRepository.save(event);
            return tenant;
        });
    }

    private void ensureSettings(Long tenantId) {
        if (!tenantSettingsRepository.existsById(tenantId)) {
            TenantSettings settings = TenantSettings.defaultsFor(tenantId);
            settings.setStockRotation(StockRotation.FIFO);
            tenantSettingsRepository.save(settings);
        }
    }

    private Branch ensureBranch(Long tenantId, String name, String code, String address, String city,
                                String province) {
        return branchRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .filter(branch -> branch.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> {
                    Branch branch = new Branch();
                    branch.setTenantId(tenantId);
                    branch.setName(name);
                    branch.setCode(code);
                    branch.setAddress(address);
                    branch.setCity(city);
                    branch.setProvince(province);
                    return branchRepository.save(branch);
                });
    }

    /** Crea el usuario si el email no existe; si existe en otro tenant o con otro rol, lo deja como está. */
    private User ensureUser(String rawEmail, String fullName, Role role, Long tenantId) {
        String email = Emails.normalize(rawEmail);
        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            if (!Objects.equals(existing.getTenantId(), tenantId) || existing.getRole() != role) {
                log.warn("El fixture no modificó {}: ya existe con otro comercio o rol", email);
                return null;
            }
            return existing;
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(fullName);
        user.setRole(role);
        user.setTenantId(tenantId);
        user.setPasswordHash(passwordHash());
        return userRepository.save(user);
    }

    /** Asigna la sucursal al usuario si todavía no la tiene (empleados y cajeros). */
    private void assignBranch(User user, Branch branch) {
        if (user != null && !userBranchRepository.existsByUserIdAndBranchId(user.getId(), branch.getId())) {
            userBranchRepository.save(new UserBranch(user.getId(), branch.getId()));
        }
    }

    /** Deja habilitados exactamente los módulos indicados (idempotente: {@code setEnabled} no repite eventos). */
    private void ensureModules(Long tenantId, TenantModule... enabled) {
        Set<TenantModule> wanted = enabled.length == 0 ? EnumSet.noneOf(TenantModule.class)
                : EnumSet.copyOf(List.of(enabled));
        for (TenantModule module : TenantModule.values()) {
            moduleService.setEnabled(tenantId, module, wanted.contains(module), null);
        }
    }

    /** Caja del POS GondolIA en una sucursal (idempotente por nombre). */
    private void ensureRegister(Long tenantId, Branch branch, String name) {
        if (posRegisterRepository.existsByBranchIdAndNameIgnoreCase(branch.getId(), name)) {
            return;
        }
        PosRegister register = new PosRegister();
        register.setTenantId(tenantId);
        register.setBranchId(branch.getId());
        register.setName(name);
        posRegisterRepository.save(register);
    }

    private void ensureCatalog(Long tenantId, Branch centro, Branch norte, Long adminId) {
        Supplier supplier = supplierRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .filter(existing -> existing.getName().equalsIgnoreCase("Distribuidora La Pampa"))
                .findFirst()
                .orElseGet(() -> {
                    Supplier created = new Supplier();
                    created.setTenantId(tenantId);
                    created.setName("Distribuidora La Pampa");
                    created.setContactName("Marta Suárez");
                    created.setPhone("11 4555-0101");
                    created.setEmail("pedidos@lapampa.example");
                    created.setLeadTimeDays(3);
                    return supplierRepository.save(created);
                });

        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = LocalDate.now(clock);
        for (ProductSpec spec : PRODUCTS) {
            Product product = productRepository.findByTenantIdAndBarcode(tenantId, spec.barcode())
                    .orElseGet(() -> createProduct(tenantId, spec, supplier.getId()));
            if (!lotRepository.findByTenantIdAndProductIdOrderByBranchIdAscReceivedAtAscIdAsc(tenantId, product.getId())
                    .isEmpty()) {
                continue;
            }
            for (LotSpec lot : spec.lots()) {
                Branch branch = lot.branch().equals(CENTRO) ? centro : norte;
                StockService.ReceiveLotResult result = stockService.receiveLot(new ReceiveLotCommand(tenantId,
                        branch.getId(), product.getId(), lot.lotNumber(), today.plusDays(lot.expiresInDays()),
                        lot.quantity(), product.getCostPrice(), supplier.getId(),
                        now.minus(Duration.ofDays(lot.receivedDaysAgo())), MovementSource.SEED, adminId,
                        "Carga inicial del fixture"));
                if (result.rotationWarning() != null) {
                    log.debug("Fixture: lote {} de {} con aviso de rotación", lot.lotNumber(), product.getName());
                }
            }
        }
    }

    private Product createProduct(Long tenantId, ProductSpec spec, Long supplierId) {
        Category category = categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, spec.category())
                .orElseGet(() -> {
                    Category created = new Category();
                    created.setTenantId(tenantId);
                    created.setName(spec.category());
                    return categoryRepository.save(created);
                });
        Product product = new Product();
        product.setTenantId(tenantId);
        product.setBarcode(spec.barcode());
        product.setName(spec.name());
        product.setBrand(spec.brand());
        product.setCategoryId(category.getId());
        product.setSupplierId(supplierId);
        product.setUnit(ProductUnit.UNIDAD);
        product.setCostPrice(new BigDecimal(spec.cost()));
        product.setSalePrice(new BigDecimal(spec.sale()));
        product.setMinStock(spec.minStock());
        product.setPerishable(true);
        return productRepository.save(product);
    }

    private String passwordHash() {
        if (passwordHash == null) {
            passwordHash = passwordEncoder.encode(PASSWORD);
        }
        return passwordHash;
    }
}
