package com.gondolia.seed;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.pos.CashMovementType;
import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.pos.PosSessionStatus;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.seed.DemoCatalog.Pattern;
import com.gondolia.seed.DemoCatalog.Template;
import com.gondolia.seed.DemoScenarios.Scenario;
import com.gondolia.seed.DemoWorld.Channel;
import com.gondolia.seed.SimModel.Lot;
import com.gondolia.seed.SimModel.Movement;
import com.gondolia.seed.SimModel.Product;
import com.gondolia.seed.SimModel.Sale;
import com.gondolia.seed.SimModel.SaleItem;
import com.gondolia.seed.SimModel.Session;
import com.gondolia.seed.SimModel.Take;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Simulación día a día de un comercio (SPEC §11 y §4.2): demanda con patrones por producto, tickets por canal
 * (POS GondolIA con turnos y arqueos, POS externo, CSV, venta manual), consumo de lotes con la rotación del comercio
 * (FIFO/FEFO, lotes en liquidación primero, vencidos nunca), reposiciones que crean un lote nuevo por ingreso,
 * mermas por vencimiento y roturas, ajustes, transferencias entre sucursales y los escenarios armados de
 * {@link DemoScenarios}. No toca la base: todo queda en {@link SimOutput}.
 */
final class StoreSimulator {

    static final LocalTime STORE_OPEN = LocalTime.of(8, 0);
    static final LocalTime SHIFT_CHANGE = LocalTime.of(14, 30);
    static final LocalTime STORE_CLOSE = LocalTime.of(21, 0);

    /** Inflación mensual usada para "envejecer" precios y costos históricos. */
    static final double MONTHLY_INFLATION = 0.021;

    /** Elasticidad precio usada en la simulación (+2 % de ventas por cada 1 % de descuento, como la IA). */
    static final double ELASTICITY = 2.0;

    /** Días de ventas importadas por CSV en Echesortu antes de conectar el POS por API. */
    static final int CSV_DAYS = 60;

    /**
     * Anticipación con la que un proveedor avisa que deja de entregar un producto ({@code SupplyStop}): desde ahí no
     * hay compras de oportunidad y los pedidos cubren solo hasta el corte.
     */
    static final int SUPPLY_STOP_NOTICE_DAYS = 45;

    private static final double[] HOUR_WEIGHTS = {4, 6, 8, 9, 8, 5, 3, 3, 5, 8, 10, 9, 5};
    private static final double[] WEEK_STABLE = {1.0, 0.97, 1.0, 1.0, 1.05, 1.12, 0.86};
    private static final double[] WEEK_MILD = {0.92, 0.9, 0.95, 1.0, 1.1, 1.25, 0.88};
    private static final double[] WEEK_WEEKEND = {0.72, 0.7, 0.78, 0.9, 1.3, 1.85, 1.45};
    private static final Set<String> FRAGILE = Set.of("agua", "cola", "lima", "huevos", "yogur-bebible", "vino",
            "cerveza", "leche", "kombucha", "jugo-naranja", "miel", "soda", "leche-almendras");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String REF_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final List<String> VOID_REASONS = List.of("El cliente se arrepintió",
            "Error al cargar un producto", "Cobro duplicado", "Precio mal cargado en la góndola");

    // ------------------------------------------------------------------ entrada

    static final class BranchRun {
        final DemoWorld.BranchSpec spec;
        final long id;
        final SimModel.Register register1;
        final SimModel.Register register2;
        final long receiverId;
        final List<Long> usersWithAccess;
        final boolean longShiftToday;

        BranchRun(DemoWorld.BranchSpec spec, long id, SimModel.Register register1, SimModel.Register register2,
                  long receiverId, List<Long> usersWithAccess, boolean longShiftToday) {
            this.spec = spec;
            this.id = id;
            this.register1 = register1;
            this.register2 = register2;
            this.receiverId = receiverId;
            this.usersWithAccess = usersWithAccess;
            this.longShiftToday = longShiftToday;
        }
    }

    static final class TenantRun {
        final DemoWorld.TenantSpec spec;
        final long tenantId;
        final List<BranchRun> branches;
        final List<Product> products;
        final long adminId;
        final Map<String, Long> userIds;
        final Instant importAppliedAt;
        final Long importJobId;
        final Scenario scenario;

        TenantRun(DemoWorld.TenantSpec spec, long tenantId, List<BranchRun> branches, List<Product> products,
                  long adminId, Map<String, Long> userIds, Instant importAppliedAt, Long importJobId,
                  Scenario scenario) {
            this.spec = spec;
            this.tenantId = tenantId;
            this.branches = branches;
            this.products = products;
            this.adminId = adminId;
            this.userIds = userIds;
            this.importAppliedAt = importAppliedAt;
            this.importJobId = importJobId;
            this.scenario = scenario;
        }

        long user(String email) {
            Long id = userIds.get(email);
            if (id == null) {
                throw new IllegalStateException("Usuario demo inexistente: " + email);
            }
            return id;
        }
    }

    // ------------------------------------------------------------------ estado

    /** Stock de un producto en una sucursal. */
    private static final class Stock {
        final BranchRun branch;
        final Product product;
        final List<Lot> live = new ArrayList<>();
        int pendingQuantity;
        LocalDate pendingArrival;
        LocalDate reorderBlockedFrom;
        int[] dailyUnits;

        Stock(BranchRun branch, Product product) {
            this.branch = branch;
            this.product = product;
        }
    }

    private record Timed(Instant at, int order, Runnable action) {
    }

    private record Line(Stock stock, int quantity) {
    }

    private final ZoneId zone;
    private final LocalDate today;
    private final Instant now;
    private final SimOutput out;
    private final Set<String> usedRefs = new HashSet<>();

    private TenantRun run;
    private SeedRandom rnd;
    private StockRotation rotation;
    private LocalDate start;
    private LocalDate end;
    private final Map<Long, Map<Long, Stock>> stocks = new HashMap<>();
    private final Map<String, Lot> tagged = new HashMap<>();
    private final Map<Long, Long> externalCounters = new HashMap<>();
    private final Set<Long> openSessionUsers = new HashSet<>();
    private final Set<Long> openRegisters = new HashSet<>();

    StoreSimulator(ZoneId zone, LocalDate today, Instant now, SimOutput out) {
        this.zone = zone;
        this.today = today;
        this.now = now;
        this.out = out;
    }

    // ------------------------------------------------------------------ simulación de un comercio

    void simulate(TenantRun tenant, long seed) {
        this.run = tenant;
        this.rnd = new SeedRandom(seed);
        this.rotation = tenant.spec.rotation();
        this.stocks.clear();
        this.tagged.clear();
        this.externalCounters.clear();
        this.usedRefs.clear();
        this.openSessionUsers.clear();
        this.openRegisters.clear();

        int history = tenant.spec.historyDays();
        this.end = today.minusDays(tenant.spec.lastSimulatedDaysAgo());
        this.start = end.minusDays(history);
        int days = (int) ChronoUnit.DAYS.between(start, end) + 1;

        for (BranchRun branch : tenant.branches) {
            Map<Long, Stock> byProduct = new LinkedHashMap<>();
            for (Product product : tenant.products) {
                Stock stock = new Stock(branch, product);
                stock.dailyUnits = new int[days];
                byProduct.put(product.id, stock);
            }
            stocks.put(branch.id, byProduct);
            externalCounters.put(branch.id, 1000L + rnd.between(2000, 9000));
        }
        applySupplyStops();
        initialStock();

        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (tenant.spec.modules().contains(TenantModule.MULTI_BRANCH) && tenant.branches.size() > 1
                    && isTransferDay(day)) {
                transfers(day);
            }
            for (BranchRun branch : tenant.branches) {
                branchDay(branch, day);
            }
        }
        finishDecisions();
    }

    // ------------------------------------------------------------------ stock inicial

    private void initialStock() {
        LocalDate first = start.minusDays(1);
        int row = 0;
        for (BranchRun branch : run.branches) {
            for (Stock stock : stocks.get(branch.id).values()) {
                Template template = stock.product.template;
                Instant receivedAt;
                MovementSource source;
                String reason;
                long userId;
                if (run.importAppliedAt != null) {
                    row++;
                    receivedAt = run.importAppliedAt.plusMillis(row + 1L);
                    source = MovementSource.IMPORT;
                    reason = "Importación masiva #" + run.importJobId;
                    userId = run.adminId;
                } else {
                    receivedAt = at(first, LocalTime.of(17, 0).plusMinutes(rnd.between(0, 90)));
                    source = MovementSource.MANUAL;
                    reason = "Stock inicial";
                    userId = run.adminId;
                }
                int quantity;
                if (template.pattern() == Pattern.NONE) {
                    quantity = 6 + rnd.between(0, 4);
                } else {
                    double daily = expectedDaily(stock, start);
                    quantity = roundUp(Math.max(daily * (coverDays(template) + stock.product.leadTimeDays) * 0.8,
                            template.packSize()), template.packSize());
                }
                Lot lot = newLot(stock, first, receivedAt, receivedAt, quantity, null, null, source, userId, reason,
                        null);
                if (run.importAppliedAt != null) {
                    out.importedLots.add(new SimOutput.ImportedLot(run.tenantId, branch.id, branch.spec.name(),
                            stock.product, lot, row + 1));
                }
                // Algunos productos de larga duración arrancan con un segundo lote más viejo.
                if (run.importAppliedAt == null && template.shelfLifeDays() > 180 && rnd.chance(0.3)
                        && template.pattern() != Pattern.NONE) {
                    LocalDate older = first.minusDays(rnd.between(15, 40));
                    Instant olderAt = at(older, LocalTime.of(10, rnd.between(0, 59)));
                    newLot(stock, older, olderAt, olderAt, template.packSize(), null, null, MovementSource.MANUAL,
                            userId, "Stock inicial", null);
                }
            }
        }
    }

    // ------------------------------------------------------------------ un día de una sucursal

    private void branchDay(BranchRun branch, LocalDate day) {
        boolean isToday = day.equals(today);
        int dayIndex = (int) ChronoUnit.DAYS.between(start, day);
        Map<Long, Stock> branchStocks = stocks.get(branch.id);
        List<Timed> events = new ArrayList<>();

        applyDiscountDecisions(branch, day);

        // Llegadas de pedidos (si el proveedor ya dejó de entregar, el pedido pendiente no llega).
        for (Stock stock : branchStocks.values()) {
            if (stock.pendingArrival != null && stock.pendingArrival.equals(day) && stock.pendingQuantity > 0) {
                if (supplyStopped(stock, day)) {
                    stock.pendingQuantity = 0;
                    stock.pendingArrival = null;
                    continue;
                }
                Instant when = at(day, LocalTime.of(8, 30).plusMinutes(rnd.between(0, 150)));
                events.add(new Timed(when, 1, () -> receiveOrder(stock, day, when)));
            }
        }
        // Lotes armados del escenario.
        for (DemoScenarios.ScriptedLot scripted : run.scenario.lots()) {
            if (scripted.branch().equals(branch.spec.key()) && today.minusDays(scripted.daysAgo()).equals(day)) {
                Stock stock = stock(branch, scripted.product());
                Instant when = at(day, LocalTime.of(scripted.hour(), rnd.between(0, 40)));
                events.add(new Timed(when, 1, () -> receiveScripted(stock, scripted, day, when)));
            }
        }
        // Recall pendiente: la coincidencia pone el lote en cuarentena (nadie la resuelve).
        for (DemoScenarios.RecallCase recall : run.scenario.recalls()) {
            if (recall.branch().equals(branch.spec.key()) && today.minusDays(recall.daysAgo()).equals(day)) {
                events.add(new Timed(at(day, recall.matchedAt()), 0, () -> matchRecall(branch, recall, day)));
            }
        }
        // Descarte de vencidos a primera hora.
        events.add(new Timed(at(day, LocalTime.of(9, 30)), 2, () -> discardExpired(branch, day)));
        // Decisiones sobre recomendaciones de reposición.
        for (DemoScenarios.ReorderDecision decision : run.scenario.reorders()) {
            if (decision.branch().equals(branch.spec.key()) && today.minusDays(decision.daysAgo()).equals(day)) {
                events.add(new Timed(at(day, LocalTime.of(9, 40)), 2, () -> reorderDecision(branch, decision, day)));
            }
        }
        for (DemoScenarios.DiscardedDiscount decision : run.scenario.discarded()) {
            if (decision.branch().equals(branch.spec.key()) && today.minusDays(decision.daysAgo()).equals(day)) {
                events.add(new Timed(at(day, LocalTime.of(9, 50)), 2, () -> discardedDiscount(branch, decision,
                        day)));
            }
        }
        // Roturas y ajustes de inventario.
        if (rnd.chance(0.18)) {
            Instant when = at(day, LocalTime.of(10, 0).plusMinutes(rnd.between(0, 600)));
            events.add(new Timed(when, 3, () -> damage(branch, day, when)));
        }
        if (dayIndex % 19 == (int) (branch.id % 19) && dayIndex > 3) {
            Instant when = at(day, LocalTime.of(20, 45));
            events.add(new Timed(when, 3, () -> inventoryAdjustment(branch, day, when)));
        }

        // Demanda del día y ventas según el canal.
        Map<Stock, Integer> demand = demand(branch, day, dayIndex);
        Channel channel = branch.spec.channel();
        if (channel == Channel.CSV_THEN_EXTERNAL) {
            channel = dayIndex < CSV_DAYS ? Channel.MANUAL : Channel.EXTERNAL_POS;
            if (dayIndex < CSV_DAYS) {
                events.add(new Timed(at(day, LocalTime.NOON), 4, () -> aggregatedSale(branch, day,
                        at(day, LocalTime.NOON), demand, MovementSource.CSV,
                        at(day.plusDays(1), LocalTime.of(9, 40)))));
            }
        }
        switch (channel) {
            case POS_GONDOLIA -> posDay(branch, day, isToday, demand, events);
            case EXTERNAL_POS -> {
                for (Map.Entry<Instant, List<Line>> ticket : tickets(demand, day).entrySet()) {
                    events.add(new Timed(ticket.getKey(), 5, () -> externalTicket(branch, day, ticket.getKey(),
                            ticket.getValue())));
                }
            }
            case MANUAL -> {
                if (branch.spec.channel() == Channel.MANUAL) {
                    Instant when = at(day, LocalTime.of(20, 30));
                    events.add(new Timed(when, 4, () -> aggregatedSale(branch, day, when, demand,
                            MovementSource.MANUAL, when)));
                }
            }
            default -> {
            }
        }
        // Ventas manuales quincenales del administrador (mayorista de Vida Sana, pedidos del club en El Sol).
        for (DemoScenarios.ManualOrders orders : run.scenario.manualOrders()) {
            if (orders.branch().equals(branch.spec.key()) && day.getDayOfWeek() == orders.weekday()
                    && dayIndex % 14 < 7) {
                Instant when = at(day, orders.time());
                events.add(new Timed(when, 5, () -> manualOrder(branch, orders, day, when)));
            }
        }
        // Pedidos al cierre.
        if (!isToday) {
            events.add(new Timed(at(day, LocalTime.of(21, 30)), 9, () -> reorders(branch, day)));
        }

        events.sort(Comparator.comparing(Timed::at).thenComparingInt(Timed::order));
        for (Timed event : events) {
            if (isToday && !event.at().isBefore(now)) {
                continue;
            }
            event.action().run();
        }
    }

    // ------------------------------------------------------------------ demanda

    private Map<Stock, Integer> demand(BranchRun branch, LocalDate day, int dayIndex) {
        Map<Stock, Integer> demand = new LinkedHashMap<>();
        double progress = ChronoUnit.DAYS.between(start, day) / (double) Math.max(1, ChronoUnit.DAYS.between(start,
                end));
        for (Stock stock : stocks.get(branch.id).values()) {
            Template template = stock.product.template;
            double base = template.baseDaily() * branch.spec.scale();
            int units;
            switch (template.pattern()) {
                case NONE -> units = 0;
                case INTERMITTENT -> {
                    // Pocos días con venta y compras "de a varios" (demanda irregular, promedio ≈ 2,4 u por venta).
                    double probability = Math.min(0.5, base / 2.4);
                    if (rnd.chance(probability)) {
                        double r = rnd.nextDouble();
                        units = r < 0.35 ? 1 : r < 0.65 ? 2 : r < 0.85 ? 3 : rnd.between(4, 6);
                    } else {
                        units = 0;
                    }
                }
                default -> {
                    double[] week = switch (template.pattern()) {
                        case STABLE -> WEEK_STABLE;
                        case WEEKEND -> WEEK_WEEKEND;
                        default -> WEEK_MILD;
                    };
                    double cv = template.pattern() == Pattern.STABLE ? 0.1 : 0.22;
                    double lambda = base * week[day.getDayOfWeek().getValue() - 1] * trend(template, progress);
                    lambda *= discountLift(stock, day);
                    lambda *= Math.max(0.05, 1 + cv * rnd.gaussian());
                    units = rnd.poisson(lambda);
                }
            }
            double spike = spikeFactor(branch, template.key(), day);
            if (spike > 1 && units >= 0) {
                units = (int) Math.round(Math.max(units, template.baseDaily() * branch.spec.scale()) * spike);
            }
            if (units > 0) {
                demand.put(stock, units);
            }
        }
        return demand;
    }

    private static double trend(Template template, double progress) {
        return switch (template.pattern()) {
            case GROWING -> 0.45 + 1.1 * progress;
            case DECLINING -> 1.55 - 1.1 * progress;
            default -> 1.0;
        };
    }

    private double expectedDaily(Stock stock, LocalDate day) {
        Template template = stock.product.template;
        if (template.pattern() == Pattern.NONE) {
            return 0;
        }
        double progress = ChronoUnit.DAYS.between(start, day) / (double) Math.max(1, ChronoUnit.DAYS.between(start,
                end));
        return template.baseDaily() * stock.branch.spec.scale() * trend(template, Math.max(0, progress));
    }

    private double discountLift(Stock stock, LocalDate day) {
        Instant noon = at(day, LocalTime.NOON);
        for (Lot lot : stock.live) {
            if (lot.quantity > 0 && lot.discountActiveAt(noon) && sellableOn(lot, day)) {
                return 1 + ELASTICITY * lot.discountPct.doubleValue() / 100.0;
            }
        }
        return 1;
    }

    private double spikeFactor(BranchRun branch, String productKey, LocalDate day) {
        for (DemoScenarios.Spike spike : run.scenario.spikes()) {
            if (spike.branch().equals(branch.spec.key()) && spike.product().equals(productKey)
                    && today.minusDays(spike.daysAgo()).equals(day)) {
                return spike.factor();
            }
        }
        return 1;
    }

    /** Arma los tickets del día: líneas de 1 a 6 unidades agrupadas de a 1–7 productos, en horario comercial. */
    private TreeMap<Instant, List<Line>> tickets(Map<Stock, Integer> demand, LocalDate day) {
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<Stock, Integer> entry : demand.entrySet()) {
            int remaining = entry.getValue();
            while (remaining > 0) {
                double r = rnd.nextDouble();
                int quantity = r < 0.62 ? 1 : r < 0.87 ? 2 : r < 0.96 ? 3 : rnd.between(4, 6);
                quantity = Math.min(quantity, remaining);
                lines.add(new Line(entry.getKey(), quantity));
                remaining -= quantity;
            }
        }
        for (int i = lines.size() - 1; i > 0; i--) {
            int j = rnd.between(0, i);
            Line swap = lines.get(i);
            lines.set(i, lines.get(j));
            lines.set(j, swap);
        }
        TreeMap<Instant, List<Line>> tickets = new TreeMap<>();
        int index = 0;
        while (index < lines.size()) {
            int size = 1;
            while (size < 7 && rnd.chance(0.55)) {
                size++;
            }
            Map<Stock, Integer> merged = new LinkedHashMap<>();
            for (int k = 0; k < size && index < lines.size(); k++, index++) {
                merged.merge(lines.get(index).stock(), lines.get(index).quantity(), Integer::sum);
            }
            List<Line> ticket = new ArrayList<>();
            merged.forEach((stock, quantity) -> ticket.add(new Line(stock, quantity)));
            ticket.sort(Comparator.comparingLong(line -> line.stock().product.id));
            Instant when;
            do {
                int hour = 8 + rnd.weighted(HOUR_WEIGHTS);
                when = at(day, LocalTime.of(hour, rnd.between(0, 59), rnd.between(0, 59)));
            } while (tickets.containsKey(when));
            tickets.put(when, ticket);
        }
        return tickets;
    }

    // ------------------------------------------------------------------ lotes

    private Stock stock(BranchRun branch, String productKey) {
        for (Stock stock : stocks.get(branch.id).values()) {
            if (stock.product.template.key().equals(productKey)) {
                return stock;
            }
        }
        throw new IllegalStateException("El producto " + productKey + " no está en " + run.spec.name());
    }

    private Lot newLot(Stock stock, LocalDate receiptDay, Instant receivedAt, Instant availableAt, int quantity,
                       LocalDate forcedExpiry, String forcedLotNumber, MovementSource source, long userId,
                       String reason, Lot origin) {
        Template template = stock.product.template;
        String lotNumber;
        LocalDate expiry;
        if (origin != null) {
            lotNumber = origin.lotNumber;
            expiry = origin.expiryDate;
        } else {
            LocalDate production = productionDate(template, receiptDay);
            lotNumber = forcedLotNumber != null ? forcedLotNumber : lotNumber(template, production);
            expiry = forcedExpiry != null ? forcedExpiry : expiry(template, production);
        }
        BigDecimal cost = origin != null ? origin.costPrice : cost(stock.product, receiptDay);
        Lot lot = new Lot(out.nextLotSeq(), run.tenantId, stock.branch.id, stock.product, origin,
                origin != null ? origin.supplierId : stock.product.supplierId, lotNumber, expiry, quantity, cost,
                origin != null ? origin.receivedAt : receivedAt, availableAt, source, userId);
        out.lots.add(lot);
        stock.live.add(lot);
        MovementType type = origin != null ? MovementType.TRANSFER_IN : MovementType.ENTRY;
        if (origin == null) {
            addCostMovement(lot, type, quantity, source, null, reason, userId, availableAt);
        }
        return lot;
    }

    private LocalDate productionDate(Template template, LocalDate receipt) {
        int shelf = template.shelfLifeDays();
        int back = shelf > 0 && shelf <= 180 ? rnd.between(1, Math.max(2, shelf / 6))
                : rnd.between(15, shelf > 0 ? Math.min(120, shelf / 4) : 90);
        return receipt.minusDays(back);
    }

    private LocalDate expiry(Template template, LocalDate production) {
        int shelf = template.shelfLifeDays();
        if (shelf <= 0) {
            return null;
        }
        int jitter = (int) Math.round(shelf * 0.03 * (rnd.nextDouble() * 2 - 1));
        LocalDate expiry = production.plusDays(shelf + jitter);
        return shelf > 90 ? expiry.withDayOfMonth(expiry.lengthOfMonth()) : expiry;
    }

    private String lotNumber(Template template, LocalDate production) {
        if (rnd.chance(0.05)) {
            return null;
        }
        String yy = String.format("%02d", production.getYear() % 100);
        String mm = String.format("%02d", production.getMonthValue());
        String dd = String.format("%02d", production.getDayOfMonth());
        String doy = String.format("%03d", production.getDayOfYear());
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = switch (template.brand()) {
                case "La Pradera" -> "LP" + mm + dd + rnd.letter("AB");
                case "Vaquita" -> "V" + doy + "-" + rnd.between(1, 4);
                case "Chacarero" -> "CH" + yy + doy;
                case "Don Trigo" -> "DT" + doy;
                case "Granja Feliz" -> "GF" + mm + dd;
                case "Dulce Valle" -> "DV" + yy + mm + rnd.letter("ABCD");
                case "Vida Verde", "Campo Sano", "Cosecha Natural" -> "LT" + yy + mm + dd;
                case "Brillo Hogar", "Suavecito", "Cura+", "Bebito" -> "BH" + yy + doy + rnd.between(1, 9);
                default -> "L" + yy + mm + rnd.letter("ABCD");
            };
            if (!DemoScenarios.RESERVED_LOT_NUMBERS.contains(candidate)) {
                return candidate;
            }
        }
        return "L" + yy + mm + "Z";
    }

    private BigDecimal cost(Product product, LocalDate day) {
        double factor = inflationFactor(day) * (1 + 0.02 * (rnd.nextDouble() * 2 - 1));
        return product.template.cost().multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP);
    }

    BigDecimal listPrice(Product product, LocalDate day) {
        BigDecimal raw = product.template.price().multiply(BigDecimal.valueOf(inflationFactor(day)));
        BigDecimal rounded = raw.divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN);
        return rounded.max(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP);
    }

    private double inflationFactor(LocalDate day) {
        long months = (today.getYear() * 12L + today.getMonthValue()) - (day.getYear() * 12L + day.getMonthValue());
        return Math.pow(1 + MONTHLY_INFLATION, -Math.max(0, months));
    }

    private static boolean sellableOn(Lot lot, LocalDate day) {
        return !lot.recalled && !lot.discarded && lot.quantity > 0
                && (lot.expiryDate == null || !lot.expiryDate.isBefore(day));
    }

    private List<Lot> sellableLots(Stock stock, LocalDate day, Instant moment) {
        List<Lot> lots = new ArrayList<>();
        for (Lot lot : stock.live) {
            if (sellableOn(lot, day) && !lot.availableAt.isAfter(moment)) {
                lots.add(lot);
            }
        }
        Comparator<Lot> fifo = Comparator.comparing((Lot lot) -> lot.receivedAt).thenComparingInt(lot -> lot.seq);
        Comparator<Lot> fefo = Comparator.comparing((Lot lot) -> lot.expiryDate,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(lot -> lot.receivedAt).thenComparingInt(lot -> lot.seq);
        lots.sort(Comparator.comparing((Lot lot) -> !lot.discountActiveAt(moment))
                .thenComparing(rotation == StockRotation.FEFO ? fefo : fifo));
        return lots;
    }

    private int sellableQuantity(Stock stock, LocalDate day, Instant moment) {
        int total = 0;
        for (Lot lot : stock.live) {
            if (sellableOn(lot, day) && !lot.availableAt.isAfter(moment)) {
                total += lot.quantity;
            }
        }
        return total;
    }

    private void prune(Stock stock) {
        stock.live.removeIf(lot -> lot.quantity == 0);
    }

    /** Si la sucursal tiene unidades del producto en cuarentena (lote de un recall con remanente). */
    private static boolean quarantined(Stock stock) {
        for (Lot lot : stock.live) {
            if (lot.recalled && lot.quantity > 0) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ingresos

    private void receiveOrder(Stock stock, LocalDate day, Instant when) {
        int quantity = stock.pendingQuantity;
        stock.pendingQuantity = 0;
        stock.pendingArrival = null;
        if (quantity <= 0) {
            return;
        }
        double r = rnd.nextDouble();
        boolean perishable = stock.product.template.perishable();
        MovementSource source = r < 0.68 ? MovementSource.SCAN
                : (r < 0.8 && perishable) ? MovementSource.OCR : MovementSource.MANUAL;
        long userId = source == MovementSource.MANUAL ? run.adminId : stock.branch.receiverId;
        newLot(stock, day, when, when, quantity, null, null, source, userId, remito(), null);
    }

    private void receiveScripted(Stock stock, DemoScenarios.ScriptedLot scripted, LocalDate day, Instant when) {
        LocalDate expiry = scripted.fixedExpiry() != null ? scripted.fixedExpiry()
                : stock.product.template.perishable() ? today.plusDays(scripted.expiryDaysFromToday()) : null;
        Lot lot = newLot(stock, day, when, when, scripted.quantity(), expiry, scripted.lotNumber(),
                MovementSource.SCAN, stock.branch.receiverId, remito(), null);
        if (scripted.keepExpired()) {
            lot.discardOn = LocalDate.MAX;
        }
        if (scripted.tag() != null) {
            tagged.put(stock.branch.spec.key() + ":" + scripted.tag(), lot);
        }
    }

    private String remito() {
        return "Remito 0002-" + String.format("%08d", rnd.between(10_000, 99_999_999));
    }

    private void reorders(BranchRun branch, LocalDate day) {
        for (Stock stock : stocks.get(branch.id).values()) {
            if (supplyStopped(stock, day)) {
                continue;
            }
            if (stock.pendingQuantity > 0 || stock.product.template.pattern() == Pattern.NONE) {
                continue;
            }
            int quantity = orderQuantity(stock, day);
            if (quantity > 0) {
                placeOrder(stock, day, quantity);
            }
        }
    }

    /** Cantidad a pedir hoy según punto de pedido y cobertura objetivo (0 si todavía no hace falta). */
    private int orderQuantity(Stock stock, LocalDate day) {
        Template template = stock.product.template;
        double daily = expectedDaily(stock, day);
        if (daily <= 0) {
            return 0;
        }
        int lead = stock.product.leadTimeDays;
        int position = coveringStock(stock, day.plusDays(lead)) + stock.pendingQuantity;
        // Stock de seguridad en días: corto en perecederos (evita merma), holgado en el resto (evita quiebres).
        double safety = (template.perishable() && template.shelfLifeDays() <= 30 ? 3 : 7) + (lead >= 5 ? 2 : 0);
        double reorderPoint = Math.max(2, daily * (lead + safety));
        if (position > reorderPoint) {
            return 0;
        }
        double target = daily * (coverDays(template) + lead + safety * 0.5);
        int quantity = roundUp(Math.max(target - position, template.packSize()), template.packSize());
        LocalDate stop = stock.reorderBlockedFrom;
        if (stop != null && day.isAfter(stop.minusDays(SUPPLY_STOP_NOTICE_DAYS))) {
            // El proveedor ya avisó que deja de entregar: no hace ofertas y solo manda cajas completas hasta cubrir lo
            // que falta vender hasta el corte. Así el faltante del escenario llega siempre (hoy sin stock o bajo
            // mínimo), sea cual sea el día de la siembra.
            double untilStop = daily * Math.max(0, ChronoUnit.DAYS.between(day, stop)) - position;
            int pack = Math.max(1, template.packSize());
            return Math.min(quantity, (int) Math.floor(Math.max(0, untilStop) / pack) * pack);
        }
        // Compras de oportunidad (oferta del proveedor): más lotes vivos y, en perecederos, riesgo de merma.
        double bonus = template.perishable() && template.shelfLifeDays() <= 60 ? 0.12 : 0.07;
        if (rnd.chance(bonus)) {
            quantity = roundUp(quantity * (1.6 + rnd.nextDouble() * 0.7), template.packSize());
        }
        return quantity;
    }

    private int coveringStock(Stock stock, LocalDate until) {
        int total = 0;
        for (Lot lot : stock.live) {
            if (!lot.recalled && !lot.discarded && lot.quantity > 0
                    && (lot.expiryDate == null || !lot.expiryDate.isBefore(until))) {
                total += lot.quantity;
            }
        }
        return total;
    }

    private void placeOrder(Stock stock, LocalDate day, int quantity) {
        LocalDate arrival = day.plusDays(stock.product.leadTimeDays);
        if (arrival.getDayOfWeek() == DayOfWeek.SUNDAY) {
            arrival = arrival.plusDays(1);
        }
        stock.pendingQuantity = quantity;
        stock.pendingArrival = arrival;
    }

    private static double coverDays(Template template) {
        int shelf = template.shelfLifeDays();
        if (shelf > 0 && shelf <= 30) {
            return Math.min(8, Math.max(3, shelf * 0.35));
        }
        return 12;
    }

    private static int roundUp(double value, int multiple) {
        int step = Math.max(1, multiple);
        return (int) (Math.ceil(value / step) * step);
    }

    /** El proveedor dejó de entregar este producto en esta sucursal (escenario {@code SupplyStop}). */
    private static boolean supplyStopped(Stock stock, LocalDate day) {
        return stock.reorderBlockedFrom != null && !day.isBefore(stock.reorderBlockedFrom);
    }

    private void applySupplyStops() {
        for (DemoScenarios.SupplyStop stop : run.scenario.supplyStops()) {
            for (BranchRun branch : run.branches) {
                if (branch.spec.key().equals(stop.branch())) {
                    stock(branch, stop.product()).reorderBlockedFrom = today.minusDays(stop.fromDaysAgo());
                }
            }
        }
    }

    // ------------------------------------------------------------------ consumo

    /** Descuenta {@code quantity} unidades en el orden de rotación. Con {@code allowShortage} registra el faltante. */
    private List<Take> consume(Stock stock, LocalDate day, Instant moment, int quantity, boolean allowShortage) {
        List<Take> takes = new ArrayList<>();
        BigDecimal list = listPrice(stock.product, day);
        int remaining = quantity;
        for (Lot lot : sellableLots(stock, day, moment)) {
            if (remaining == 0) {
                break;
            }
            int taken = Math.min(remaining, lot.quantity);
            lot.quantity -= taken;
            lot.lastMovementType = MovementType.SALE;
            lot.updatedAt = moment;
            BigDecimal pct = lot.discountActiveAt(moment) ? lot.discountPct : null;
            BigDecimal price = pct == null ? list
                    : list.multiply(HUNDRED.subtract(pct)).divide(HUNDRED, 2, RoundingMode.HALF_UP);
            takes.add(new Take(lot, taken, price, pct));
            remaining -= taken;
        }
        if (remaining > 0 && allowShortage) {
            takes.add(new Take(null, remaining, list, null));
        }
        prune(stock);
        return takes;
    }

    private void recordSaleMovements(Stock stock, List<Take> takes, MovementSource source, String batchRef,
                                     Long userId, Instant moment, LocalDate day) {
        for (Take take : takes) {
            BigDecimal total = take.unitPrice().multiply(BigDecimal.valueOf(take.quantity()))
                    .setScale(2, RoundingMode.HALF_UP);
            out.movements.add(new Movement(run.tenantId, stock.branch.id, stock.product, take.lot(),
                    MovementType.SALE, take.quantity(), take.unitPrice(), take.discountPct(), total, source,
                    batchRef, take.lot() == null ? "Venta sin stock registrado" : null, userId, moment));
            stock.dailyUnits[(int) ChronoUnit.DAYS.between(start, day)] += take.quantity();
        }
    }

    private void externalTicket(BranchRun branch, LocalDate day, Instant moment, List<Line> lines) {
        long number = externalCounters.merge(branch.id, 1L, Long::sum);
        String batchRef = "S-EXT-" + branch.spec.externalPrefix() + "-" + String.format("%07d", number);
        usedRefs.add(batchRef);
        for (Line line : lines) {
            List<Take> takes = consume(line.stock(), day, moment, line.quantity(), true);
            recordSaleMovements(line.stock(), takes, MovementSource.POS, batchRef, null, moment, day);
        }
    }

    /** Venta agregada del día: CSV importado al día siguiente o venta manual del administrador. */
    private void aggregatedSale(BranchRun branch, LocalDate day, Instant moment, Map<Stock, Integer> demand,
                                MovementSource source, Instant loadedAt) {
        if (demand.isEmpty()) {
            return;
        }
        String batchRef = ref("S", loadedAt);
        List<Stock> ordered = new ArrayList<>(demand.keySet());
        ordered.sort(Comparator.comparingLong(stock -> stock.product.id));
        for (Stock stock : ordered) {
            List<Take> takes = consume(stock, day, moment, demand.get(stock), true);
            recordSaleMovements(stock, takes, source, batchRef, run.adminId, moment, day);
        }
    }

    private void manualOrder(BranchRun branch, DemoScenarios.ManualOrders orders, LocalDate day, Instant moment) {
        List<Stock> candidates = new ArrayList<>();
        for (Stock stock : stocks.get(branch.id).values()) {
            if (orders.categories().contains(stock.product.template.category())) {
                candidates.add(stock);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        String batchRef = ref("S", moment);
        Set<Stock> chosen = new java.util.TreeSet<>(Comparator.comparingLong(stock -> stock.product.id));
        int lines = rnd.between(3, 5);
        for (int i = 0; i < lines; i++) {
            chosen.add(rnd.pick(candidates));
        }
        for (Stock stock : chosen) {
            int available = sellableQuantity(stock, day, moment);
            int quantity = Math.min(available, rnd.between(orders.minUnits(), orders.maxUnits()));
            if (quantity <= 0) {
                continue;
            }
            List<Take> takes = consume(stock, day, moment, quantity, false);
            recordSaleMovements(stock, takes, MovementSource.MANUAL, batchRef, run.adminId, moment, day);
        }
    }

    // ------------------------------------------------------------------ POS GondolIA

    private void posDay(BranchRun branch, LocalDate day, boolean isToday, Map<Stock, Integer> demand,
                        List<Timed> events) {
        DemoWorld.PosStaff staff = branch.spec.staff();
        List<Session> sessions = new ArrayList<>();
        if (isToday && branch.longShiftToday) {
            LocalTime open = STORE_OPEN.minusMinutes(rnd.between(3, 12));
            Instant openedAt = at(day, open);
            if (openedAt.isAfter(now.minusSeconds(900))) {
                openedAt = now.minusSeconds(900);
            }
            // El turno de hoy tiene que quedar ABIERTO aunque se siembre fuera del horario del comercio
            // (una demo a la noche también necesita una caja abierta): el cierre programado va siempre al futuro.
            Instant scheduledClose = at(day, STORE_CLOSE).plus(Duration.ofMinutes(10));
            if (!scheduledClose.isAfter(now)) {
                scheduledClose = now.plus(Duration.ofHours(2));
            }
            addSession(sessions, branch, branch.register1, staff.morning(), openedAt, scheduledClose,
                    BigDecimal.valueOf(20_000), day, isToday, true);
        } else {
            addSession(sessions, branch, branch.register1, staff.morning(),
                    at(day, STORE_OPEN.minusMinutes(rnd.between(2, 12))),
                    at(day, SHIFT_CHANGE.plusMinutes(rnd.between(4, 16))), BigDecimal.valueOf(20_000), day, isToday,
                    false);
            addSession(sessions, branch, branch.register1, staff.afternoon(),
                    at(day, SHIFT_CHANGE.minusMinutes(rnd.between(2, 10))),
                    at(day, STORE_CLOSE.plusMinutes(rnd.between(5, 18))), BigDecimal.valueOf(20_000), day, isToday,
                    false);
        }
        if (staff.secondRegister() != null && branch.register2 != null
                && staff.secondRegisterDays().contains(day.getDayOfWeek())) {
            addSession(sessions, branch, branch.register2, staff.secondRegister(),
                    at(day, LocalTime.of(staff.secondFromHour(), 0).minusMinutes(rnd.between(2, 8))),
                    at(day, LocalTime.of(staff.secondToHour(), 0).plusMinutes(rnd.between(3, 12))),
                    BigDecimal.valueOf(10_000), day, isToday, false);
        }
        if (sessions.isEmpty()) {
            return;
        }
        for (Map.Entry<Instant, List<Line>> ticket : tickets(demand, day).entrySet()) {
            Instant moment = ticket.getKey();
            events.add(new Timed(moment, 5, () -> {
                Session session = sessionAt(sessions, moment);
                if (session != null) {
                    posTicket(branch, session, day, moment, ticket.getValue());
                }
            }));
        }
        for (Session session : sessions) {
            boolean morning = session.register == branch.register1
                    && session.scheduledClose.isBefore(at(day, LocalTime.of(16, 0)));
            if (morning && rnd.chance(0.22)) {
                int amount = rnd.between(4, 12) * 1000;
                String reason = rnd.chance(0.6) ? "Pago a proveedor: pan del día" : "Pago de flete";
                events.add(new Timed(at(day, LocalTime.of(9, 10)), 6, () -> cashMovement(session,
                        CashMovementType.CASH_OUT, amount, reason, at(day, LocalTime.of(9, 10)), true)));
            }
            if (rnd.chance(0.1)) {
                Instant when = session.openedAt.plus(Duration.ofMinutes(110));
                events.add(new Timed(when, 6, () -> cashMovement(session, CashMovementType.CASH_IN, 10_000,
                        "Refuerzo de cambio", when, false)));
            }
            if (!morning && session.scheduledClose.isAfter(at(day, LocalTime.of(19, 45)))) {
                Instant when = at(day, LocalTime.of(19, 30).plusMinutes(rnd.between(0, 20)));
                events.add(new Timed(when, 6, () -> {
                    BigDecimal drawer = drawer(session);
                    if (drawer.compareTo(BigDecimal.valueOf(60_000)) > 0) {
                        int amount = drawer.subtract(BigDecimal.valueOf(20_000)).intValue() / 10_000 * 10_000;
                        cashMovement(session, CashMovementType.CASH_OUT, amount, "Retiro de efectivo para depósito",
                                when, true);
                    }
                }));
            }
            if (session.status == PosSessionStatus.OPEN && !(isToday && !session.scheduledClose.isBefore(now))) {
                events.add(new Timed(session.scheduledClose, 8, () -> closeSession(session)));
            }
        }
    }

    private void addSession(List<Session> sessions, BranchRun branch, SimModel.Register register, String email,
                            Instant openedAt, Instant scheduledClose, BigDecimal openingCash, LocalDate day,
                            boolean isToday, boolean forceOpen) {
        if (email == null || register == null) {
            return;
        }
        if (isToday && openedAt.isAfter(now)) {
            return;
        }
        long userId = run.user(email);
        boolean stillOpen = isToday && (forceOpen || !scheduledClose.isBefore(now));
        if (stillOpen && (openSessionUsers.contains(userId) || openRegisters.contains(register.seq + 0L))) {
            return;
        }
        Session session = new Session(out.nextSessionSeq(), run.tenantId, branch.id, register, userId, openedAt,
                scheduledClose, openingCash);
        if (stillOpen) {
            openSessionUsers.add(userId);
            openRegisters.add(register.seq + 0L);
        }
        out.sessions.add(session);
        sessions.add(session);
    }

    private Session sessionAt(List<Session> sessions, Instant moment) {
        List<Session> covering = new ArrayList<>();
        for (Session session : sessions) {
            boolean started = !session.openedAt.isAfter(moment);
            boolean notClosed = session.status == PosSessionStatus.OPEN && moment.isBefore(session.scheduledClose);
            if (started && notClosed) {
                covering.add(session);
            }
        }
        if (covering.isEmpty()) {
            return null;
        }
        Session second = covering.stream().filter(session -> session.register.name.equals("Caja 2")).findFirst()
                .orElse(null);
        if (second != null && (covering.size() == 1 || rnd.chance(0.4))) {
            return second;
        }
        return covering.stream().filter(session -> !session.register.name.equals("Caja 2"))
                .max(Comparator.comparing(session -> session.openedAt)).orElse(covering.getFirst());
    }

    private void posTicket(BranchRun branch, Session session, LocalDate day, Instant moment, List<Line> lines) {
        List<SaleItem> items = new ArrayList<>();
        Map<Stock, List<Take>> takesByStock = new LinkedHashMap<>();
        for (Line line : lines) {
            // Como PosSaleService: con unidades en cuarentena por un recall, el POS no vende el producto (de ningún
            // lote) hasta que se resuelva el retiro.
            if (quarantined(line.stock())) {
                continue;
            }
            int available = sellableQuantity(line.stock(), day, moment);
            int quantity = Math.min(line.quantity(), available);
            if (quantity <= 0) {
                continue;
            }
            List<Take> takes = consume(line.stock(), day, moment, quantity, false);
            BigDecimal list = listPrice(line.stock().product, day);
            BigDecimal lineTotal = BigDecimal.ZERO;
            for (Take take : takes) {
                lineTotal = lineTotal.add(take.unitPrice().multiply(BigDecimal.valueOf(take.quantity())));
            }
            items.add(new SaleItem(line.stock().product, quantity, list, lineTotal.setScale(2, RoundingMode.HALF_UP),
                    takes));
            takesByStock.put(line.stock(), takes);
        }
        if (items.isEmpty()) {
            return;
        }
        long number = out.ticketCounters.merge(branch.id, 1L, Long::sum);
        String batchRef = ref("P", moment);
        Sale sale = new Sale(out.nextSaleSeq(), session, number, batchRef, session.openedBy, moment);
        for (SaleItem item : items) {
            sale.items.add(item);
            sale.subtotal = sale.subtotal.add(item.listUnitPrice.multiply(BigDecimal.valueOf(item.quantity)));
            sale.total = sale.total.add(item.lineTotal);
            sale.units += item.quantity;
        }
        sale.subtotal = sale.subtotal.setScale(2, RoundingMode.HALF_UP);
        sale.total = sale.total.setScale(2, RoundingMode.HALF_UP);
        sale.discountTotal = sale.subtotal.subtract(sale.total);
        payments(sale);
        for (Map.Entry<Stock, List<Take>> entry : takesByStock.entrySet()) {
            recordSaleMovements(entry.getKey(), entry.getValue(), MovementSource.POS_GONDOLIA, batchRef,
                    session.openedBy, moment, day);
        }
        session.sales.add(sale);
        out.sales.add(sale);

        Instant voidedAt = moment.plus(Duration.ofMinutes(rnd.between(2, 20)));
        if (rnd.chance(0.008) && voidedAt.isBefore(now)) {
            voidSale(sale, takesByStock, voidedAt, day);
        } else {
            session.salesCount++;
            session.salesTotal = session.salesTotal.add(sale.total);
        }
    }

    private void voidSale(Sale sale, Map<Stock, List<Take>> takesByStock, Instant voidedAt, LocalDate day) {
        Session session = sale.session;
        long voider = rnd.chance(0.75) ? sale.cashierId : run.adminId;
        String reason = rnd.pick(VOID_REASONS);
        sale.status = PosSaleStatus.VOIDED;
        sale.voidedAt = voidedAt;
        sale.voidedBy = voider;
        sale.voidReason = reason;
        session.voidedCount++;
        session.voidedTotal = session.voidedTotal.add(sale.total);
        for (Map.Entry<Stock, List<Take>> entry : takesByStock.entrySet()) {
            Stock stock = entry.getKey();
            for (Take take : entry.getValue()) {
                BigDecimal total = take.unitPrice().multiply(BigDecimal.valueOf(take.quantity()))
                        .setScale(2, RoundingMode.HALF_UP);
                out.movements.add(new Movement(run.tenantId, stock.branch.id, stock.product, take.lot(),
                        MovementType.SALE_VOID, take.quantity(), take.unitPrice(), take.discountPct(), total,
                        MovementSource.POS_GONDOLIA, sale.batchRef, reason, voider, voidedAt));
                if (take.lot() != null) {
                    Lot lot = take.lot();
                    lot.quantity += take.quantity();
                    lot.lastMovementType = MovementType.SALE_VOID;
                    lot.updatedAt = voidedAt;
                    if (!stock.live.contains(lot)) {
                        stock.live.add(lot);
                    }
                }
                stock.dailyUnits[(int) ChronoUnit.DAYS.between(start, day)] -= take.quantity();
            }
        }
    }

    private void payments(Sale sale) {
        BigDecimal total = sale.total;
        double r = rnd.nextDouble();
        boolean small = total.compareTo(BigDecimal.valueOf(2_500)) < 0;
        if (!small && total.compareTo(BigDecimal.valueOf(5_000)) >= 0 && rnd.chance(0.06)) {
            BigDecimal cash = total.multiply(BigDecimal.valueOf(0.4)).divide(BigDecimal.valueOf(1000), 0,
                    RoundingMode.DOWN).multiply(BigDecimal.valueOf(1000)).max(BigDecimal.valueOf(1000));
            BigDecimal rest = total.subtract(cash);
            sale.payments.add(new SimModel.Payment(PaymentMethod.CASH, cash.setScale(2, RoundingMode.HALF_UP), null));
            PaymentMethod other = rnd.chance(0.6) ? PaymentMethod.DEBIT : PaymentMethod.QR;
            sale.payments.add(new SimModel.Payment(other, rest.setScale(2, RoundingMode.HALF_UP), reference(other)));
        } else {
            PaymentMethod method;
            if (small) {
                method = r < 0.6 ? PaymentMethod.CASH : r < 0.8 ? PaymentMethod.QR : r < 0.95 ? PaymentMethod.DEBIT
                        : PaymentMethod.TRANSFER;
            } else {
                method = r < 0.38 ? PaymentMethod.CASH : r < 0.66 ? PaymentMethod.DEBIT
                        : r < 0.76 ? PaymentMethod.CREDIT : r < 0.92 ? PaymentMethod.QR : PaymentMethod.TRANSFER;
            }
            if (method == PaymentMethod.CASH) {
                int[] steps = {100, 1000, 2000, 10_000, 20_000};
                double[] weights = {15, 35, 15, 20, 15};
                BigDecimal received = total;
                if (!rnd.chance(0.3)) {
                    BigDecimal step = BigDecimal.valueOf(steps[rnd.weighted(weights)]);
                    received = total.divide(step, 0, RoundingMode.CEILING).multiply(step);
                }
                sale.payments.add(new SimModel.Payment(PaymentMethod.CASH, received.setScale(2, RoundingMode.HALF_UP),
                        null));
                sale.changeAmount = received.subtract(total).setScale(2, RoundingMode.HALF_UP);
            } else {
                sale.payments.add(new SimModel.Payment(method, total, reference(method)));
            }
        }
        sale.paidTotal = sale.payments.stream().map(payment -> payment.amount).reduce(BigDecimal.ZERO,
                BigDecimal::add);
    }

    private String reference(PaymentMethod method) {
        String digits = String.format("%04d", rnd.between(0, 9999));
        return switch (method) {
            case DEBIT -> "Débito ****" + digits;
            case CREDIT -> (rnd.chance(0.6) ? "Visa" : "Mastercard") + " ****" + digits + " · 1 cuota";
            case QR -> "QR billetera #" + rnd.between(100_000, 999_999) + digits;
            case TRANSFER -> "Transferencia CVU ****" + digits;
            case CASH -> null;
        };
    }

    private BigDecimal drawer(Session session) {
        BigDecimal cash = session.openingCash;
        for (Sale sale : session.sales) {
            if (sale.status == PosSaleStatus.COMPLETED) {
                cash = cash.add(sale.cashReceived()).subtract(sale.changeAmount);
            }
        }
        for (SimModel.CashMovement movement : session.cashMovements) {
            cash = movement.type == CashMovementType.CASH_IN ? cash.add(movement.amount) : cash.subtract(movement.amount);
        }
        return cash;
    }

    private void cashMovement(Session session, CashMovementType type, int amount, String reason, Instant when,
                              boolean requireFunds) {
        if (amount <= 0 || session.status != PosSessionStatus.OPEN) {
            return;
        }
        if (requireFunds && drawer(session).compareTo(BigDecimal.valueOf(amount + 5_000L)) < 0) {
            return;
        }
        long userId = type == CashMovementType.CASH_OUT && reason.startsWith("Retiro") && rnd.chance(0.5)
                ? run.adminId : session.openedBy;
        session.cashMovements.add(new SimModel.CashMovement(type, BigDecimal.valueOf(amount).setScale(2), reason,
                userId, when));
    }

    private void closeSession(Session session) {
        BigDecimal expected = drawer(session).setScale(2, RoundingMode.HALF_UP);
        double r = rnd.nextDouble();
        BigDecimal difference;
        String note = null;
        if (r < 0.84) {
            difference = BigDecimal.ZERO;
        } else if (r < 0.92) {
            difference = BigDecimal.valueOf(-50L * rnd.between(1, 30));
            note = "Faltante de $ " + difference.abs().toPlainString() + ". Se revisó el arqueo con el encargado.";
        } else if (r < 0.97) {
            difference = BigDecimal.valueOf(50L * rnd.between(1, 12));
            note = "Sobrante de $ " + difference.toPlainString() + ", quedó en la caja.";
        } else {
            difference = BigDecimal.valueOf(-100L * rnd.between(20, 60));
            note = "Faltante de $ " + difference.abs().toPlainString() + ": se detectó un vuelto mal dado.";
        }
        session.status = PosSessionStatus.CLOSED;
        session.closedAt = session.scheduledClose;
        session.closedBy = rnd.chance(0.94) ? session.openedBy : run.adminId;
        session.expectedCash = expected;
        session.countedCash = expected.add(difference).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        session.cashDifference = session.countedCash.subtract(expected);
        session.closingNote = note;
    }

    // ------------------------------------------------------------------ mermas, ajustes y transferencias

    private void discardExpired(BranchRun branch, LocalDate day) {
        Instant moment = at(day, LocalTime.of(9, 30));
        for (Stock stock : stocks.get(branch.id).values()) {
            for (Lot lot : new ArrayList<>(stock.live)) {
                if (lot.quantity <= 0 || lot.recalled || lot.discarded || lot.expiryDate == null
                        || !lot.expiryDate.isBefore(day)) {
                    continue;
                }
                if (lot.discardOn == null) {
                    lot.discardOn = lot.expiryDate.plusDays(1 + (rnd.chance(0.8) ? rnd.between(0, 2)
                            : rnd.between(3, 6)));
                }
                if (!day.isBefore(lot.discardOn)) {
                    int quantity = lot.quantity;
                    lot.quantity = 0;
                    lot.discarded = true;
                    lot.lastMovementType = MovementType.WASTE_EXPIRED;
                    lot.updatedAt = moment;
                    addCostMovement(lot, MovementType.WASTE_EXPIRED, quantity, MovementSource.MANUAL,
                            ref("A", moment), "Descarte por vencimiento", branch.receiverId, moment);
                }
            }
            prune(stock);
        }
    }

    private void damage(BranchRun branch, LocalDate day, Instant moment) {
        List<Stock> fragile = new ArrayList<>();
        for (Stock stock : stocks.get(branch.id).values()) {
            if (FRAGILE.contains(stock.product.template.key()) && sellableQuantity(stock, day, moment) > 2) {
                fragile.add(stock);
            }
        }
        if (fragile.isEmpty()) {
            return;
        }
        Stock stock = rnd.pick(fragile);
        Lot lot = sellableLots(stock, day, moment).getFirst();
        int quantity = stock.product.template.key().equals("huevos") ? Math.min(2, lot.quantity) : 1;
        lot.quantity -= quantity;
        lot.lastMovementType = MovementType.WASTE_DAMAGED;
        lot.updatedAt = moment;
        String reason = rnd.chance(0.6) ? "Rotura en góndola" : "Envase dañado en la descarga";
        addCostMovement(lot, MovementType.WASTE_DAMAGED, quantity, MovementSource.MANUAL, ref("A", moment), reason,
                branch.receiverId, moment);
        prune(stock);
    }

    private void inventoryAdjustment(BranchRun branch, LocalDate day, Instant moment) {
        List<Stock> candidates = new ArrayList<>();
        for (Stock stock : stocks.get(branch.id).values()) {
            if (!stock.product.template.perishable() && sellableQuantity(stock, day, moment) > 3) {
                candidates.add(stock);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        Stock stock = rnd.pick(candidates);
        Lot lot = sellableLots(stock, day, moment).getFirst();
        boolean out = rnd.chance(0.6);
        int quantity = out ? Math.min(lot.quantity, rnd.between(1, 2)) : rnd.between(1, 3);
        if (out) {
            lot.quantity -= quantity;
        } else {
            lot.quantity += quantity;
        }
        lot.lastMovementType = out ? MovementType.ADJUSTMENT_OUT : MovementType.ADJUSTMENT_IN;
        lot.updatedAt = moment;
        addCostMovement(lot, out ? MovementType.ADJUSTMENT_OUT : MovementType.ADJUSTMENT_IN, quantity,
                MovementSource.MANUAL, ref("A", moment), out ? "Diferencia en el recuento de inventario"
                        : "Recuento: aparecieron unidades en el depósito", run.adminId, moment);
        prune(stock);
    }

    private boolean isTransferDay(LocalDate day) {
        long week = ChronoUnit.WEEKS.between(start, day);
        return day.getDayOfWeek() == DayOfWeek.TUESDAY && week % 2 == 0 && !day.equals(today);
    }

    /**
     * Reparto quincenal entre sucursales: lo que sobra en una va a la que tiene menos cobertura. Si ese día no hay
     * un caso claro, se mueve igual el producto con más diferencia de cobertura (el administrador aprovecha el
     * viaje).
     */
    private void transfers(LocalDate day) {
        Instant moment = at(day, LocalTime.of(7, 30));
        record Candidate(Stock source, Stock target, double gap, boolean clear) {
        }
        Map<String, List<Candidate>> byPair = new LinkedHashMap<>();
        Candidate fallback = null;
        for (BranchRun from : run.branches) {
            for (BranchRun to : run.branches) {
                if (from == to) {
                    continue;
                }
                for (Product product : run.products) {
                    Template template = product.template;
                    if (template.pattern() == Pattern.NONE
                            || (template.perishable() && template.shelfLifeDays() <= 30)) {
                        continue;
                    }
                    Stock source = stocks.get(from.id).get(product.id);
                    Stock target = stocks.get(to.id).get(product.id);
                    // Un faltante por proveedor queda a la vista (en la demo se puede transferir en vivo).
                    if (supplyStopped(target, day)) {
                        continue;
                    }
                    double fromCover = sellableQuantity(source, day, moment) / Math.max(0.3, expectedDaily(source,
                            day));
                    double toCover = (sellableQuantity(target, day, moment) + target.pendingQuantity)
                            / Math.max(0.3, expectedDaily(target, day));
                    if (sellableQuantity(source, day, moment) < 8) {
                        continue;
                    }
                    boolean clear = fromCover > 14 && toCover < 6;
                    Candidate candidate = new Candidate(source, target, fromCover - toCover, clear);
                    if (clear) {
                        byPair.computeIfAbsent(from.spec.key() + ">" + to.spec.key(), key -> new ArrayList<>())
                                .add(candidate);
                    } else if (fromCover > toCover + 5 && (fallback == null || candidate.gap() > fallback.gap())) {
                        fallback = candidate;
                    }
                }
            }
        }
        if (byPair.isEmpty() && fallback != null) {
            byPair.put("fallback", new ArrayList<>(List.of(fallback)));
        }
        for (List<Candidate> candidates : byPair.values()) {
            candidates.sort(Comparator.comparingDouble(candidate -> -candidate.gap()));
            String batchRef = ref("T", moment);
            for (Candidate candidate : candidates.subList(0, Math.min(2, candidates.size()))) {
                Stock source = candidate.source();
                Stock target = candidate.target();
                int quantity = Math.max(2, sellableQuantity(source, day, moment) / 3);
                for (Lot lot : sellableLots(source, day, moment)) {
                    if (quantity == 0) {
                        break;
                    }
                    // Los lotes de los recalls armados se quedan donde los puso el escenario (SPEC §11).
                    if (lot.expiryDate != null && lot.expiryDate.isBefore(day.plusDays(10))
                            || lot.lotNumber != null && DemoScenarios.RESERVED_LOT_NUMBERS.contains(lot.lotNumber)) {
                        continue;
                    }
                    int taken = Math.min(quantity, lot.quantity);
                    lot.quantity -= taken;
                    lot.lastMovementType = MovementType.TRANSFER_OUT;
                    lot.updatedAt = moment;
                    addCostMovement(lot, MovementType.TRANSFER_OUT, taken, MovementSource.MANUAL, batchRef,
                            "Reparto entre sucursales", run.adminId, moment);
                    Lot destination = newLot(target, day, lot.receivedAt, moment, taken, null, null,
                            MovementSource.MANUAL, run.adminId, "Reparto entre sucursales", lot);
                    addCostMovement(destination, MovementType.TRANSFER_IN, taken, MovementSource.MANUAL, batchRef,
                            "Reparto entre sucursales", run.adminId, moment);
                    quantity -= taken;
                }
                prune(source);
            }
        }
    }

    private void addCostMovement(Lot lot, MovementType type, int quantity, MovementSource source, String batchRef,
                                 String reason, long userId, Instant moment) {
        BigDecimal unit = lot.costPrice != null ? lot.costPrice : lot.product.template.cost();
        out.movements.add(new Movement(run.tenantId, lot.branchId, lot.product, lot, type, quantity, unit, null,
                unit.multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP), source, batchRef,
                reason, userId, moment));
    }

    // ------------------------------------------------------------------ escenarios: recall y decisiones

    /**
     * Como {@code RecallMatchingService}: el lote con remanente queda en cuarentena ({@code RECALLED}) desde la
     * coincidencia. No se resuelve: el retiro lo hace el comercio en vivo durante la demo.
     */
    private void matchRecall(BranchRun branch, DemoScenarios.RecallCase recall, LocalDate day) {
        Lot lot = tagged.get(branch.spec.key() + ":" + recall.lotTag());
        if (lot == null || lot.quantity <= 0) {
            return;
        }
        Instant matchedAt = at(day, recall.matchedAt());
        lot.recalled = true;
        lot.updatedAt = matchedAt;
        out.recalls.add(new SimOutput.RecallOutcome(run.tenantId, branch.id, branch.spec.name(), lot.product, lot,
                lot.quantity, matchedAt, branch.usersWithAccess));
    }

    private void applyDiscountDecisions(BranchRun branch, LocalDate day) {
        for (DemoScenarios.DiscountDecision decision : run.scenario.discounts()) {
            if (!decision.branch().equals(branch.spec.key()) || !today.minusDays(decision.daysAgo()).equals(day)) {
                continue;
            }
            Lot lot = tagged.get(branch.spec.key() + ":" + decision.lotTag());
            if (lot == null || lot.quantity <= 0) {
                continue;
            }
            Instant decidedAt = at(day, LocalTime.of(9, 0).plusMinutes(rnd.between(0, 25)));
            lot.discountPct = BigDecimal.valueOf(decision.pct()).setScale(2, RoundingMode.HALF_UP);
            lot.discountStartedAt = decidedAt;
            lot.updatedAt = decidedAt;
            Stock stock = stock(branch, lot.product.template.key());
            int dayIndex = (int) ChronoUnit.DAYS.between(start, day);
            int before = 0;
            for (int i = Math.max(0, dayIndex - 7); i < dayIndex; i++) {
                before += stock.dailyUnits[i];
            }
            int toExpiry = lot.expiryDate == null ? 0 : (int) ChronoUnit.DAYS.between(day, lot.expiryDate);
            out.discounts.add(new SimOutput.DiscountOutcome(run.tenantId, branch.id, branch.spec.name(), lot.product,
                    lot, at(day, LocalTime.of(3, 20).plusMinutes(rnd.between(0, 30))), decidedAt, run.adminId,
                    decision.pct(), decision.note(), lot.quantity, Math.max(0, before), null, null, null, null,
                    toExpiry, before / 7.0));
        }
    }

    private void discardedDiscount(BranchRun branch, DemoScenarios.DiscardedDiscount decision, LocalDate day) {
        Stock stock = stock(branch, decision.product());
        Instant moment = at(day, LocalTime.of(9, 50));
        Lot lot = sellableLots(stock, day, moment).stream()
                .filter(candidate -> candidate.expiryDate != null)
                .min(Comparator.comparing(candidate -> candidate.expiryDate)).orElse(null);
        int toExpiry = lot == null ? 0 : (int) ChronoUnit.DAYS.between(day, lot.expiryDate);
        out.discardedDiscounts.add(new SimOutput.DiscardedDiscountOutcome(run.tenantId, branch.id, stock.product, lot,
                at(day, LocalTime.of(3, 25)), moment, run.adminId, decision.pct(), decision.note(),
                lot == null ? 0 : lot.quantity, toExpiry, expectedDaily(stock, day)));
    }

    private void reorderDecision(BranchRun branch, DemoScenarios.ReorderDecision decision, LocalDate day) {
        Stock stock = stock(branch, decision.product());
        Instant moment = at(day, LocalTime.of(9, 40));
        double daily = expectedDaily(stock, day);
        int lead = stock.product.leadTimeDays;
        int position = sellableQuantity(stock, day, moment);
        int suggested = roundUp(Math.max(daily * (coverDays(stock.product.template) + lead) - position,
                stock.product.template.packSize()), stock.product.template.packSize());
        if (decision.accepted() && stock.pendingQuantity == 0) {
            placeOrder(stock, day, suggested);
        }
        out.reorders.add(new SimOutput.ReorderOutcome(run.tenantId, branch.id, stock.product,
                at(day, LocalTime.of(3, 30)), moment, run.adminId, decision.accepted(), decision.note(), suggested,
                position, daily, day.plusDays(1)));
    }

    /** Completa el resultado a 7 días de los descuentos aceptados (lo que mide el job diario del módulo B). */
    private void finishDecisions() {
        List<SimOutput.DiscountOutcome> updated = new ArrayList<>();
        for (SimOutput.DiscountOutcome outcome : out.discounts) {
            if (outcome.tenantId() != run.tenantId) {
                updated.add(outcome);
                continue;
            }
            LocalDate decided = outcome.decidedAt().atZone(zone).toLocalDate();
            LocalDate measureDay = decided.plusDays(7);
            if (measureDay.isAfter(today)) {
                updated.add(outcome);
                continue;
            }
            Stock stock = stocks.get(outcome.branchId()).get(outcome.product().id);
            int decidedIndex = (int) ChronoUnit.DAYS.between(start, decided);
            int after = 0;
            for (int i = decidedIndex; i < Math.min(decidedIndex + 7, stock.dailyUnits.length); i++) {
                after += stock.dailyUnits[i];
            }
            Lot lot = outcome.lot();
            updated.add(new SimOutput.DiscountOutcome(outcome.tenantId(), outcome.branchId(), outcome.branchName(),
                    outcome.product(), lot, outcome.createdAt(), outcome.decidedAt(), outcome.decidedBy(),
                    outcome.pct(), outcome.note(), outcome.lotUnitsAtAccept(), outcome.unitsBefore7d(), after,
                    Math.max(outcome.lotUnitsAtAccept() - lot.quantity, 0), lot.quantity,
                    at(measureDay, LocalTime.of(2, 30)), outcome.daysToExpiry(), outcome.avgDailyBefore()));
        }
        out.discounts.clear();
        out.discounts.addAll(updated);
    }

    // ------------------------------------------------------------------ utilidades

    private Instant at(LocalDate day, LocalTime time) {
        return day.atTime(time).atZone(zone).toInstant();
    }

    /** Referencia {@code <prefijo>-yyyyMMddHHmmss-XXXXXX} única en el comercio (como {@code BatchRefs}). */
    private String ref(String prefix, Instant moment) {
        String stamp = STAMP.format(moment.atZone(zone));
        while (true) {
            StringBuilder ref = new StringBuilder(prefix).append('-').append(stamp).append('-');
            for (int i = 0; i < 6; i++) {
                ref.append(rnd.letter(REF_ALPHABET));
            }
            if (usedRefs.add(ref.toString())) {
                return ref.toString();
            }
        }
    }

    /** Semilla estable por comercio (los datos demo son siempre los mismos para la misma fecha). */
    static long seedOf(String tenantKey, LocalDate today) {
        return tenantKey.hashCode() * 31L + today.toEpochDay();
    }
}
