package com.gondolia.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gondolia.common.util.Emails;
import com.gondolia.common.util.LotNumbers;
import com.gondolia.domain.pos.PosBranchCounter;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.security.ApiKeyService;
import com.gondolia.seed.DemoWorld.BranchSpec;
import com.gondolia.seed.DemoWorld.Channel;
import com.gondolia.seed.DemoWorld.EventSpec;
import com.gondolia.seed.DemoWorld.TenantSpec;
import com.gondolia.seed.DemoWorld.UserSpec;
import com.gondolia.seed.SimModel.Lot;
import com.gondolia.seed.SimModel.Movement;
import com.gondolia.seed.SimModel.Product;
import com.gondolia.seed.SimModel.Register;
import com.gondolia.seed.SimModel.Sale;
import com.gondolia.seed.SimModel.SaleItem;
import com.gondolia.seed.SimModel.Session;
import com.gondolia.seed.SimModel.Take;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Arma el mundo demo completo dentro de una transacción: plataforma, comercios, sucursales, usuarios, módulos,
 * eventos, catálogos, la simulación de 180 días y la escritura en bloque de lotes, movimientos y POS. Los avisos,
 * recalls, recomendaciones decididas, soporte e importación los completa {@link DemoStories}.
 */
final class DemoWorldBuilder {

    /** Resultado de armar un comercio: ids y datos que usan las historias. */
    static final class TenantRecord {
        final TenantSpec spec;
        long id;
        Instant createdAt;
        final Map<String, Long> branchIds = new LinkedHashMap<>();
        final Map<Long, String> branchNames = new LinkedHashMap<>();
        final Map<String, Long> users = new LinkedHashMap<>();
        final Map<String, UserSpec> userSpecs = new LinkedHashMap<>();
        final List<Product> products = new ArrayList<>();
        final Map<String, Product> productsByKey = new LinkedHashMap<>();
        final Map<Long, List<Long>> usersWithAccess = new LinkedHashMap<>();
        Long importJobId;
        Instant importCreatedAt;
        Instant importAppliedAt;
        final Map<String, Long> categoryIds = new LinkedHashMap<>();
        final Map<String, Long> supplierIds = new LinkedHashMap<>();

        TenantRecord(TenantSpec spec) {
            this.spec = spec;
        }

        long user(String email) {
            Long id = users.get(email);
            if (id == null) {
                throw new IllegalStateException("Usuario demo inexistente: " + email);
            }
            return id;
        }

        long admin() {
            return users.entrySet().stream()
                    .filter(entry -> userSpecs.get(entry.getKey()).role() == Role.TENANT_ADMIN)
                    .map(Map.Entry::getValue).findFirst().orElseThrow();
        }

        String fullName(String email) {
            return userSpecs.get(email).fullName();
        }
    }

    /** Ids de la plataforma. */
    record Platform(Map<String, Long> users, Map<String, String> names) {

        long id(String email) {
            Long id = users.get(email);
            if (id == null) {
                throw new IllegalStateException("Usuario de plataforma inexistente: " + email);
            }
            return id;
        }
    }

    /** Antigüedad del equipo de la plataforma (dueños y soporte): alta hace 400 días. */
    static final int PLATFORM_TEAM_DAYS_AGO = 400;

    private final SeedJdbc db;
    private final PasswordEncoder passwordEncoder;
    private final ApiKeyService apiKeyService;
    private final ObjectMapper objectMapper;
    private final ZoneId zone;
    private final LocalDate today;
    private final Instant now;
    private final SeedRandom rnd;
    private String tenantHash;
    private String platformHash;

    DemoWorldBuilder(SeedJdbc db, PasswordEncoder passwordEncoder, ApiKeyService apiKeyService,
                     ObjectMapper objectMapper, Clock clock) {
        this.db = db;
        this.passwordEncoder = passwordEncoder;
        this.apiKeyService = apiKeyService;
        this.objectMapper = objectMapper;
        this.zone = clock.getZone();
        this.now = clock.instant();
        this.today = LocalDate.now(clock);
        this.rnd = new SeedRandom(StoreSimulator.seedOf("mundo", today));
    }

    LocalDate today() {
        return today;
    }

    Instant now() {
        return now;
    }

    ZoneId zone() {
        return zone;
    }

    Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(zone).toInstant();
    }

    Instant daysAgo(int days, LocalTime time) {
        return at(today.minusDays(days), time);
    }

    // ------------------------------------------------------------------ plataforma

    Platform platform() {
        tenantHash = passwordEncoder.encode(DemoWorld.TENANT_PASSWORD);
        platformHash = passwordEncoder.encode(DemoWorld.PLATFORM_PASSWORD);
        Map<String, Long> ids = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        // El equipo de GondolIA (también el dueño inicial) es anterior a todos los comercios.
        Instant teamCreatedAt = daysAgo(PLATFORM_TEAM_DAYS_AGO, LocalTime.of(9, 0));
        for (DemoWorld.PlatformUser user : DemoWorld.PLATFORM_USERS) {
            String email = Emails.normalize(user.email());
            List<Long> existing = db.jdbc().queryForList("select id from users where email = ?", Long.class, email);
            long id;
            if (existing.isEmpty()) {
                id = db.insert("""
                        insert into users (email, password_hash, full_name, role, active, created_at, updated_at,
                                           last_login_at)
                        values (?, ?, ?, ?, true, ?, ?, ?)""", email, platformHash, user.fullName(), user.role(),
                        teamCreatedAt, now, daysAgo(user.lastLoginDaysAgo(), LocalTime.of(9, 12)));
            } else {
                id = existing.getFirst();
                // Solo se completa la presentación del dueño inicial (lo crea BootstrapRunner al arrancar): nombre y
                // fecha de alta como el resto del equipo, que es anterior a todos los comercios; su contraseña no se
                // toca.
                db.update("""
                        update users set full_name = case when full_name = 'Dueño GondolIA' then ? else full_name end,
                                         created_at = least(created_at, ?),
                                         last_login_at = coalesce(last_login_at, ?)
                        where id = ?""", user.fullName(), teamCreatedAt,
                        daysAgo(user.lastLoginDaysAgo(), LocalTime.of(9, 5)), id);
            }
            ids.put(email, id);
            names.put(email, db.jdbc().queryForObject("select full_name from users where id = ?", String.class, id));
        }
        return new Platform(ids, names);
    }

    // ------------------------------------------------------------------ comercios

    TenantRecord tenant(TenantSpec spec, Platform platform) {
        TenantRecord record = new TenantRecord(spec);
        long ownerId = platform.id("dueno@gondolia.app");
        Instant created = daysAgo(spec.createdDaysAgo(), LocalTime.of(10, 0).plusMinutes(rnd.between(0, 240)));
        record.createdAt = created;
        Instant statusChangedAt = spec.status() == TenantStatus.ACTIVE ? null
                : daysAgo(spec.statusChangedDaysAgo(), LocalTime.of(16, rnd.between(0, 59)));
        record.id = db.insert("""
                insert into tenants (name, legal_name, tax_id, business_type, plan, status, contact_name, contact_email,
                                     contact_phone, address, city, province, notes, status_reason, status_changed_at,
                                     created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", spec.name(), spec.legalName(),
                spec.taxId(), spec.businessType(), spec.plan(), spec.status(), spec.contactName(),
                spec.contactEmail(), spec.contactPhone(), spec.address(), spec.city(), spec.province(), spec.notes(),
                spec.statusReason(), statusChangedAt, created,
                statusChangedAt != null ? statusChangedAt : created.plus(Duration.ofDays(1)));

        boolean dietetica = spec.key().equals(DemoWorld.VIDA_SANA);
        db.update("""
                insert into tenant_settings (tenant_id, currency, stock_rotation, expiry_warning_days,
                                             expiry_critical_days, default_lead_time_days, target_coverage_days,
                                             service_level, max_discount_pct, updated_at)
                values (?, 'ARS', ?, ?, ?, 3, ?, 0.950, ?, ?)""", record.id, spec.rotation(), dietetica ? 20 : 15,
                dietetica ? 7 : 5, dietetica ? 21 : 14, dietetica ? 30 : 40, created);

        // Sucursales (las del POS externo con su API key de demo).
        for (int i = 0; i < spec.branches().size(); i++) {
            BranchSpec branch = spec.branches().get(i);
            Instant branchCreated = created.plus(Duration.ofMinutes(5L + i));
            String keyHash = null;
            String keyPrefix = null;
            Instant keyCreated = null;
            if (branch.channel() == Channel.EXTERNAL_POS || branch.channel() == Channel.CSV_THEN_EXTERNAL) {
                String rawKey = demoApiKey(spec.key(), branch.key());
                keyHash = apiKeyService.hash(rawKey);
                keyPrefix = rawKey.substring(0, ApiKeyService.DISPLAY_PREFIX_LENGTH);
                keyCreated = branch.channel() == Channel.CSV_THEN_EXTERNAL
                        ? daysAgo(DemoWorld.RICH_HISTORY_DAYS - StoreSimulator.CSV_DAYS, LocalTime.of(18, 20))
                        : created.plus(Duration.ofHours(3));
            }
            long branchId = db.insert("""
                    insert into branches (tenant_id, name, code, address, city, province, phone, active,
                                          pos_api_key_hash, pos_api_key_prefix, pos_api_key_created_at, created_at,
                                          updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, ?)""", record.id, branch.name(), branch.code(),
                    branch.address(), branch.city(), branch.province(), branch.phone(), keyHash, keyPrefix,
                    keyCreated, branchCreated, keyCreated != null ? keyCreated : branchCreated);
            record.branchIds.put(branch.key(), branchId);
            record.branchNames.put(branchId, branch.name());
            record.usersWithAccess.put(branchId, new ArrayList<>());
        }

        // Usuarios (misma contraseña para todos los de comercio).
        int index = 0;
        for (UserSpec user : spec.users()) {
            Instant userCreated = created.plus(Duration.ofMinutes(10L + index++));
            Instant lastLogin = daysAgo(user.lastLoginDaysAgo(), LocalTime.of(8, 0).plusMinutes(rnd.between(0, 600)));
            if (lastLogin.isAfter(now)) {
                lastLogin = now.minus(Duration.ofMinutes(rnd.between(5, 90)));
            }
            long userId = db.insert("""
                    insert into users (tenant_id, email, password_hash, full_name, role, active, token_version,
                                       must_change_password, last_login_at, created_at, updated_at)
                    values (?, ?, ?, ?, ?, true, 0, false, ?, ?, ?)""", record.id, Emails.normalize(user.email()),
                    tenantHash, user.fullName(), user.role(), lastLogin, userCreated, userCreated);
            record.users.put(user.email(), userId);
            record.userSpecs.put(user.email(), user);
            for (String branchKey : user.branchKeys()) {
                db.update("insert into user_branches (user_id, branch_id, created_at) values (?, ?, ?)", userId,
                        record.branchIds.get(branchKey), userCreated);
            }
            for (Map.Entry<String, Long> branch : record.branchIds.entrySet()) {
                boolean all = user.role() == Role.TENANT_ADMIN || user.role() == Role.TENANT_BOSS;
                if (all || user.branchKeys().contains(branch.getKey())) {
                    record.usersWithAccess.get(branch.getValue()).add(userId);
                }
            }
        }

        // Módulos habilitados y eventos (crecimiento, churn, cambios de plan y de módulos).
        for (EventSpec event : spec.events()) {
            Instant when = event.daysAgo() == spec.createdDaysAgo()
                    ? created.plus(Duration.ofMinutes(event.type().name().startsWith("MODULE") ? 2 : 0))
                    : daysAgo(event.daysAgo(), LocalTime.of(15, 0).plusMinutes(rnd.between(0, 120)));
            if (spec.status() != TenantStatus.ACTIVE && event.daysAgo() == spec.statusChangedDaysAgo()
                    && statusChangedAt != null) {
                when = statusChangedAt;
            }
            long actor = event.type().name().equals("CREATED") ? ownerId
                    : platform.id(rnd.chance(0.5) ? "dueno@gondolia.app" : "socia@gondolia.app");
            db.update("""
                    insert into tenant_events (tenant_id, type, from_value, to_value, reason, actor_user_id, created_at)
                    values (?, ?, ?, ?, ?, ?, ?)""", record.id, event.type(), event.fromValue(), event.toValue(),
                    event.reason(), actor, when);
            if (event.type().name().equals("MODULE_ENABLED")) {
                db.update("""
                        insert into tenant_modules (tenant_id, module, enabled, updated_by, created_at, updated_at)
                        values (?, ?, true, ?, ?, ?)""", record.id, event.fromValue(), actor, when, when);
            }
        }

        catalog(record);
        return record;
    }

    /** API key de demo por sucursal (documentada en docs/datos-demo.md para probar el webhook del POS). */
    static String demoApiKey(String tenantKey, String branchKey) {
        String body = ("Demo" + tenantKey + branchKey).replaceAll("[^A-Za-z0-9]", "");
        StringBuilder key = new StringBuilder(body);
        String filler = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ";
        int i = 0;
        while (key.length() < ApiKeyService.RANDOM_LENGTH) {
            key.append(filler.charAt(i++ % filler.length()));
        }
        return ApiKeyService.KEY_PREFIX + key.substring(0, ApiKeyService.RANDOM_LENGTH);
    }

    /** Productos del catálogo maestro que vende el comercio (su rubro, sin los excluidos, hasta su límite). */
    static List<DemoCatalog.Template> templatesFor(TenantSpec spec) {
        List<DemoCatalog.Template> templates = new ArrayList<>(DemoCatalog.forTag(spec.catalogTag()));
        templates.removeIf(template -> spec.excludedProducts().contains(template.key()));
        if (templates.size() > spec.productLimit()) {
            templates = spreadPick(templates, spec.productLimit());
        }
        return templates;
    }

    private void catalog(TenantRecord record) {
        TenantSpec spec = record.spec;
        List<DemoCatalog.Template> templates = templatesFor(spec);
        Instant productsCreated;
        if (spec.key().equals(DemoWorld.EL_SOL)) {
            record.importCreatedAt = daysAgo(DemoWorld.RICH_HISTORY_DAYS + 2, LocalTime.of(10, 5));
            record.importAppliedAt = record.importCreatedAt.plus(Duration.ofMinutes(26));
            record.importJobId = db.nextIds("import_jobs", 1)[0];
            productsCreated = record.importAppliedAt;
        } else {
            productsCreated = record.createdAt.plus(spec.rich() ? Duration.ofDays(1) : Duration.ofHours(1));
        }
        for (DemoCatalog.Template template : templates) {
            long categoryId = record.categoryIds.computeIfAbsent(template.category(), name -> db.insert(
                    "insert into categories (tenant_id, name, created_at) values (?, ?, ?)", record.id, name,
                    productsCreated));
            DemoCatalog.Supplier supplier = DemoCatalog.supplier(template.supplierKey());
            long supplierId = record.supplierIds.computeIfAbsent(supplier.key(), key -> db.insert("""
                    insert into suppliers (tenant_id, name, contact_name, phone, email, lead_time_days, notes, active,
                                           created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, true, ?, ?)""", record.id, supplier.name(), supplier.contactName(),
                    supplier.phone(), supplier.email(), supplier.leadTimeDays(), supplier.notes(), productsCreated,
                    productsCreated));
            long productId = db.insert("""
                    insert into products (tenant_id, barcode, name, brand, description, category_id, supplier_id, unit,
                                          cost_price, sale_price, min_stock, perishable, active, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?, ?)""", record.id, template.barcode(),
                    template.name(), template.brand(), null, categoryId, supplierId, template.unit(), template.cost(),
                    template.price(), minStock(template, spec), template.perishable(), productsCreated,
                    productsCreated);
            Product product = new Product(productId, record.id, template, supplierId, supplier.leadTimeDays());
            record.products.add(product);
            record.productsByKey.put(template.key(), product);
        }
    }

    /** El mínimo de los comercios chicos se ajusta a su escala de ventas. */
    private static int minStock(DemoCatalog.Template template, TenantSpec spec) {
        return spec.rich() ? template.minStock() : Math.max(1, (int) Math.round(template.minStock() * 0.5));
    }

    /** Elige {@code count} productos repartidos a lo largo del catálogo (variedad de categorías). */
    private static List<DemoCatalog.Template> spreadPick(List<DemoCatalog.Template> templates, int count) {
        List<DemoCatalog.Template> picked = new ArrayList<>();
        double step = templates.size() / (double) count;
        for (int i = 0; i < count; i++) {
            picked.add(templates.get((int) Math.floor(i * step)));
        }
        return picked;
    }

    // ------------------------------------------------------------------ simulación

    StoreSimulator.TenantRun simulationRun(TenantRecord record) {
        TenantSpec spec = record.spec;
        List<StoreSimulator.BranchRun> branches = new ArrayList<>();
        for (BranchSpec branch : spec.branches()) {
            long branchId = record.branchIds.get(branch.key());
            Register register1 = null;
            Register register2 = null;
            if (branch.channel() == Channel.POS_GONDOLIA) {
                Instant registerCreated = record.createdAt.plus(Duration.ofHours(2));
                register1 = register(record, branchId, "Caja 1", registerCreated);
                if (branch.staff() != null && branch.staff().secondRegister() != null) {
                    register2 = register(record, branchId, "Caja 2", registerCreated.plus(Duration.ofMinutes(3)));
                }
            }
            long receiver = spec.users().stream()
                    .filter(user -> user.role() == Role.TENANT_EMPLOYEE && user.branchKeys().contains(branch.key()))
                    .map(user -> record.user(user.email())).findFirst().orElse(record.admin());
            boolean longShift = spec.key().equals(DemoWorld.EL_SOL) && branch.key().equals("CEN");
            branches.add(new StoreSimulator.BranchRun(branch, branchId, register1, register2, receiver,
                    List.copyOf(record.usersWithAccess.get(branchId)), longShift));
        }
        return new StoreSimulator.TenantRun(spec, record.id, branches, record.products, record.admin(),
                record.users, record.importAppliedAt, record.importJobId, DemoScenarios.forTenant(spec.key()));
    }

    private Register register(TenantRecord record, long branchId, String name, Instant created) {
        long id = db.insert("""
                insert into pos_registers (tenant_id, branch_id, name, active, created_at, updated_at)
                values (?, ?, ?, true, ?, ?)""", record.id, branchId, name, created, created);
        Register register = new Register((int) id, record.id, branchId, name, created);
        register.id = id;
        return register;
    }

    // ------------------------------------------------------------------ escritura en bloque

    void writeSimulation(SimOutput out) {
        // Lotes: ids en orden de creación (desempate FIFO por id, como el núcleo).
        long[] lotIds = db.nextIds("lots", out.lots.size());
        for (int i = 0; i < out.lots.size(); i++) {
            out.lots.get(i).id = lotIds[i];
        }
        try (SeedJdbc.Copy copy = db.copy("lots", "id, tenant_id, branch_id, product_id, origin_lot_id, supplier_id, "
                + "lot_number, lot_number_normalized, expiry_date, initial_quantity, quantity, cost_price, received_at, "
                + "status, source, discount_pct, discount_started_at, created_by, created_at, updated_at")) {
            for (Lot lot : out.lots) {
                copy.row(lot.id, lot.tenantId, lot.branchId, lot.product.id, lot.origin == null ? null : lot.origin.id,
                        lot.supplierId, lot.lotNumber, LotNumbers.normalize(lot.lotNumber), lot.expiryDate,
                        lot.initialQuantity, lot.quantity, lot.costPrice, lot.receivedAt, lot.finalStatus(), lot.source,
                        lot.discountPct, lot.discountStartedAt, lot.createdBy, lot.availableAt,
                        lot.updatedAt == null ? lot.availableAt : lot.updatedAt);
            }
        }

        List<Movement> movements = new ArrayList<>(out.movements);
        movements.sort(Comparator.comparing(movement -> movement.occurredAt));
        try (SeedJdbc.Copy copy = db.copy("stock_movements", "tenant_id, branch_id, product_id, lot_id, type, quantity, "
                + "unit_price, discount_pct, total_amount, source, batch_ref, reason, user_id, occurred_at, created_at")) {
            for (Movement movement : movements) {
                copy.row(movement.tenantId, movement.branchId, movement.product.id,
                        movement.lot == null ? null : movement.lot.id, movement.type, movement.quantity,
                        movement.unitPrice, movement.discountPct, movement.totalAmount, movement.source,
                        movement.batchRef, movement.reason, movement.userId, movement.occurredAt,
                        movement.occurredAt);
            }
        }

        List<Session> sessions = new ArrayList<>(out.sessions);
        sessions.sort(Comparator.comparing((Session session) -> session.openedAt).thenComparingInt(s -> s.seq));
        long[] sessionIds = db.nextIds("pos_sessions", sessions.size());
        for (int i = 0; i < sessions.size(); i++) {
            sessions.get(i).id = sessionIds[i];
        }
        try (SeedJdbc.Copy copy = db.copy("pos_sessions", "id, tenant_id, branch_id, register_id, status, opened_by, "
                + "closed_by, opening_cash, expected_cash, counted_cash, cash_difference, sales_count, sales_total, "
                + "voided_count, voided_total, closing_note, opened_at, closed_at")) {
            for (Session session : sessions) {
                copy.row(session.id, session.tenantId, session.branchId, session.register.id, session.status,
                        session.openedBy, session.closedBy, session.openingCash.setScale(2), session.expectedCash,
                        session.countedCash, session.cashDifference, session.salesCount, session.salesTotal,
                        session.voidedCount, session.voidedTotal, session.closingNote, session.openedAt,
                        session.closedAt);
            }
        }

        List<Sale> sales = new ArrayList<>(out.sales);
        sales.sort(Comparator.comparing((Sale sale) -> sale.createdAt).thenComparingInt(sale -> sale.seq));
        long[] saleIds = db.nextIds("pos_sales", sales.size());
        for (int i = 0; i < sales.size(); i++) {
            sales.get(i).id = saleIds[i];
        }
        try (SeedJdbc.Copy copy = db.copy("pos_sales", "id, tenant_id, branch_id, register_id, session_id, number, "
                + "ticket_code, status, subtotal, discount_total, total, items_count, units, paid_total, change_amount, "
                + "customer_name, customer_doc, cashier_id, batch_ref, has_shortage, created_at, voided_at, voided_by, "
                + "void_reason")) {
            for (Sale sale : sales) {
                Session session = sale.session;
                copy.row(sale.id, session.tenantId, session.branchId, session.register.id, session.id, sale.number,
                        PosBranchCounter.ticketCode(session.branchId, sale.number), sale.status, sale.subtotal,
                        sale.discountTotal, sale.total, sale.items.size(), sale.units, sale.paidTotal,
                        sale.changeAmount, null, null, sale.cashierId, sale.batchRef, false, sale.createdAt,
                        sale.voidedAt, sale.voidedBy, sale.voidReason);
            }
        }
        try (SeedJdbc.Copy items = db.copy("pos_sale_items", "sale_id, product_id, barcode, product_name, quantity, "
                + "list_unit_price, discount_amount, line_total, shortage_quantity, lots");
             SeedJdbc.Copy payments = db.copy("pos_payments", "sale_id, method, amount, reference, created_at")) {
            for (Sale sale : sales) {
                for (SaleItem item : sale.items) {
                    items.row(sale.id, item.product.id, item.product.template.barcode(), item.product.template.name(),
                            item.quantity, item.listUnitPrice, item.discountAmount(), item.lineTotal, 0,
                            lotsJson(item.takes));
                }
                for (SimModel.Payment payment : sale.payments) {
                    payments.row(sale.id, payment.method, payment.amount, payment.reference, sale.createdAt);
                }
            }
        }
        try (SeedJdbc.Copy copy = db.copy("pos_cash_movements", "tenant_id, session_id, type, amount, reason, user_id, "
                + "created_at")) {
            for (Session session : sessions) {
                for (SimModel.CashMovement movement : session.cashMovements) {
                    copy.row(session.tenantId, session.id, movement.type, movement.amount, movement.reason,
                            movement.userId, movement.createdAt);
                }
            }
        }
        for (Map.Entry<Long, Long> counter : out.ticketCounters.entrySet()) {
            db.update("insert into pos_branch_counters (branch_id, last_number) values (?, ?)", counter.getKey(),
                    counter.getValue());
        }
    }

    private String lotsJson(List<Take> takes) {
        ArrayNode array = objectMapper.createArrayNode();
        for (Take take : takes) {
            if (take.lot() == null) {
                continue;
            }
            ObjectNode node = array.addObject();
            node.put("lotId", take.lot().id);
            node.put("lotNumber", take.lot().lotNumber);
            node.put("expiryDate", take.lot().expiryDate == null ? null : take.lot().expiryDate.toString());
            node.put("quantity", take.quantity());
            node.put("unitPrice", take.unitPrice());
            node.put("discountPct", take.discountPct());
        }
        return array.toString();
    }


}
