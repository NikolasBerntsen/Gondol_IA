package com.gondolia.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gondolia.domain.common.Severity;
import com.gondolia.domain.support.Attachment;
import com.gondolia.domain.support.AttachmentPurpose;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.recall.RecallMatchingService;
import com.gondolia.seed.DemoWorldBuilder.Platform;
import com.gondolia.seed.DemoWorldBuilder.TenantRecord;
import com.gondolia.seed.SimModel.Lot;
import com.gondolia.storage.AttachmentStorageService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Las "historias" del mundo demo que no salen de la simulación: avisos generales y el recall histórico con sus
 * notificaciones, recomendaciones de la IA ya decididas (con el resultado medido que la IA recibe como feedback),
 * coincidencias de recall resueltas, la importación inicial de El Sol y las conversaciones de soporte.
 */
final class DemoStories {

    private static final Locale ES_AR = Locale.forLanguageTag("es-AR");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("dd/MM");

    /** Aviso publicado y a quién le llegó. */
    record Published(long id, String title, String body, Severity severity, Instant publishedAt,
                     Set<BusinessType> targets, double readShare) {
    }

    record Announcements(long oldRecallId, String oldRecallTitle, Published oldRecall, List<Published> general) {
    }

    private final SeedJdbc db;
    private final DemoWorldBuilder world;
    private final ObjectMapper objectMapper;
    private final AttachmentStorageService attachments;
    private final SeedRandom rnd;

    DemoStories(SeedJdbc db, DemoWorldBuilder world, ObjectMapper objectMapper,
                AttachmentStorageService attachments) {
        this.db = db;
        this.world = world;
        this.objectMapper = objectMapper;
        this.attachments = attachments;
        this.rnd = new SeedRandom(StoreSimulator.seedOf("historias", world.today()));
    }

    // ------------------------------------------------------------------ avisos y recall histórico

    Announcements announcements(Platform platform) {
        long owner = platform.id("dueno@gondolia.app");
        long partner = platform.id("socia@gondolia.app");
        List<Published> general = new ArrayList<>();

        general.add(publish(owner, Severity.INFO, "Llegó el POS GondolIA: cobrá con escáner y cerrá la caja en minutos",
                "Ya podés vender desde GondolIA: abrí tu caja, escaneá los productos con el lector o la cámara, cobrá "
                        + "con efectivo, débito, crédito, transferencia o QR (combinados) e imprimí el ticket. Al cerrar "
                        + "el turno, el arqueo te muestra el efectivo esperado y las diferencias. Pedile a GondolIA que "
                        + "active el módulo «Punto de venta GondolIA» para tu comercio.",
                120, null, 0.9));
        general.add(publish(partner, Severity.INFO, "Nueva función: importá tu planilla de Excel o CSV",
                "Ya podés cargar todo tu catálogo y el stock inicial desde la planilla que usabas. GondolIA reconoce "
                        + "las columnas, te muestra los errores fila por fila para que los corrijas antes de confirmar y "
                        + "respeta la fecha de ingreso de cada lote para que el orden de salida sea el correcto. Entrá a "
                        + "«Importar Excel/CSV» en el menú.",
                45, null, 0.85));
        general.add(publish(partner, Severity.INFO, "Con el calor, cuidá la cadena de frío",
                "Se vienen días de calor: revisá la temperatura de las heladeras dos veces por día, cargá los lácteos "
                        + "y fiambres con su vencimiento real y mirá la pantalla de Vencimientos cada mañana. La IA te "
                        + "va a sugerir descuentos para los lotes con riesgo de quedar sin vender.",
                20, EnumSet.of(BusinessType.ALMACEN, BusinessType.MINIMERCADO, BusinessType.DIETETICA,
                        BusinessType.KIOSCO), 0.6));
        LocalDate maintenance = world.today().plusDays(3).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        general.add(publish(owner, Severity.WARNING,
                "Mantenimiento programado: domingo " + maintenance.format(SHORT_DAY) + " de 2 a 4 h",
                "El domingo " + maintenance.format(DAY) + " entre las 2 y las 4 de la mañana vamos a actualizar los "
                        + "servidores. Puede haber cortes breves de uno o dos minutos. Las ventas que envíe tu POS por "
                        + "API se reintentan solas y el POS GondolIA guarda las operaciones en curso.",
                2, null, 0.3));

        // Aviso archivado y un borrador (solo los ve la consola de dueños).
        Instant archivedPublished = world.daysAgo(150, LocalTime.of(12, 0));
        db.insert("""
                insert into announcements (kind, severity, status, title, body, created_by, published_at, created_at,
                                           updated_at, recipients_count)
                values ('GENERAL', 'INFO', 'ARCHIVED', ?, ?, ?, ?, ?, ?, ?)""",
                "Actualizamos los términos y condiciones del servicio",
                "Actualizamos los términos y condiciones para incluir los módulos por comercio y la facturación por "
                        + "sucursal activa. Podés leerlos completos desde tu perfil.",
                owner, archivedPublished, archivedPublished.minus(Duration.ofHours(2)),
                world.daysAgo(100, LocalTime.of(9, 0)), 0);
        Instant draftCreated = world.daysAgo(1, LocalTime.of(18, 40));
        db.insert("""
                insert into announcements (kind, severity, status, title, body, created_by, created_at, updated_at)
                values ('GENERAL', 'INFO', 'DRAFT', ?, ?, ?, ?, ?)""",
                "Novedades de octubre: estadísticas por categoría",
                "Borrador: en octubre sumamos el ranking de categorías con mayor merma y la comparación entre "
                        + "sucursales por categoría.", partner, draftCreated, draftCreated);

        // Recall histórico (ya resuelto por los comercios alcanzados).
        DemoCatalog.Template product = DemoCatalog.byKey(DemoScenarios.OLD_RECALL_PRODUCT);
        String title = "Retiro preventivo: Dulce de leche Dulce Valle 400 g, lote " + DemoScenarios.OLD_RECALL_LOT;
        String reason = "Falla en el sellado de los potes detectada por el fabricante (posible contaminación)";
        String instructions = "Retirá todas las unidades del lote " + DemoScenarios.OLD_RECALL_LOT
                + " de la góndola y del depósito, separalas y coordiná la devolución con tu proveedor. No las vendas.";
        String body = "Dulce Valle informó una falla en el sellado de los potes del lote " + DemoScenarios.OLD_RECALL_LOT
                + " que podría afectar la inocuidad del producto. Si lo tenés en stock, GondolIA ya lo puso en "
                + "cuarentena: retiralo de la venta y seguí las instrucciones.";
        Instant published = world.daysAgo(DemoScenarios.OLD_RECALL_DAYS_AGO, LocalTime.of(10, 0));
        long recallId = db.insert("""
                insert into announcements (kind, severity, status, title, body, recall_product_name, recall_brand,
                                           recall_barcode, recall_all_lots, recall_reason, recall_instructions,
                                           affected_tenants_count, recipients_count, created_by, published_at,
                                           created_at, updated_at)
                values ('RECALL', 'CRITICAL', 'PUBLISHED', ?, ?, ?, ?, ?, false, ?, ?, 0, 0, ?, ?, ?, ?)""",
                title, body, product.name(), product.brand(), product.barcode(), reason, instructions, partner,
                published, published.minus(Duration.ofMinutes(25)), published);
        db.update("""
                insert into announcement_recall_lots (announcement_id, lot_number, lot_number_normalized)
                values (?, ?, ?)""", recallId, DemoScenarios.OLD_RECALL_LOT, DemoScenarios.OLD_RECALL_LOT);
        Published oldRecall = new Published(recallId, title, body, Severity.CRITICAL, published, null, 0.95);
        return new Announcements(recallId, title, oldRecall, general);
    }

    private Published publish(long author, Severity severity, String title, String body, int daysAgo,
                              Set<BusinessType> targets, double readShare) {
        Instant published = world.daysAgo(daysAgo, LocalTime.of(11, 0).plusMinutes(rnd.between(0, 90)));
        String targetCsv = targets == null ? null
                : targets.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
        long id = db.insert("""
                insert into announcements (kind, severity, status, title, body, target_business_types, created_by,
                                           published_at, created_at, updated_at, recipients_count)
                values ('GENERAL', ?, 'PUBLISHED', ?, ?, ?, ?, ?, ?, ?, 0)""", severity, title, body, targetCsv,
                author, published, published.minus(Duration.ofMinutes(15)), published);
        return new Published(id, title, body, severity, published, targets, readShare);
    }

    /** Notificaciones y lecturas de los avisos para los usuarios de los comercios activos en ese momento. */
    void announcementNotifications(List<TenantRecord> tenants, Announcements announcements) {
        List<Published> all = new ArrayList<>(announcements.general());
        all.add(announcements.oldRecall());
        try (SeedJdbc.Copy notifications = db.copy("notifications", "user_id, tenant_id, type, severity, title, body, "
                + "link, reference_type, reference_id, read_at, created_at");
             SeedJdbc.Copy reads = db.copy("announcement_reads", "announcement_id, user_id, read_at")) {
            for (Published published : all) {
                int recipients = 0;
                for (TenantRecord tenant : tenants) {
                    if (!activeAt(tenant, published.publishedAt())) {
                        continue;
                    }
                    if (published.targets() != null && !published.targets().contains(tenant.spec.businessType())) {
                        continue;
                    }
                    for (Map.Entry<String, Long> user : tenant.users.entrySet()) {
                        recipients++;
                        Instant readAt = null;
                        if (rnd.chance(published.readShare())) {
                            readAt = published.publishedAt().plus(Duration.ofMinutes(rnd.between(20, 3 * 24 * 60)));
                            if (readAt.isAfter(world.now())) {
                                readAt = null;
                            }
                        }
                        notifications.row(user.getValue(), tenant.id, "ANNOUNCEMENT", published.severity(),
                                published.title(), summary(published.body()), "/app/notices", "ANNOUNCEMENT",
                                published.id(), readAt, published.publishedAt());
                        if (readAt != null) {
                            reads.row(published.id(), user.getValue(), readAt);
                        }
                    }
                }
                db.update("update announcements set recipients_count = ? where id = ?", recipients, published.id());
            }
        }
    }

    /** Si el comercio estaba habilitado en ese momento (alta, bajas y rehabilitaciones de sus eventos). */
    private boolean activeAt(TenantRecord tenant, Instant moment) {
        if (tenant.createdAt.isAfter(moment)) {
            return false;
        }
        boolean active = true;
        List<DemoWorld.EventSpec> events = new ArrayList<>(tenant.spec.events());
        events.sort((a, b) -> Integer.compare(b.daysAgo(), a.daysAgo()));
        for (DemoWorld.EventSpec event : events) {
            if (world.daysAgo(event.daysAgo(), LocalTime.of(15, 0)).isAfter(moment)) {
                break;
            }
            switch (event.type()) {
                case DISABLED, CANCELLED -> active = false;
                case ENABLED, REACTIVATED -> active = true;
                default -> {
                }
            }
        }
        return active;
    }

    private static String summary(String body) {
        String text = body.strip();
        return text.length() <= 300 ? text : text.substring(0, 297).strip() + "…";
    }

    // ------------------------------------------------------------------ recall histórico: coincidencias

    void recallMatches(List<TenantRecord> tenants, SimOutput out, Announcements announcements) {
        Set<Long> affectedTenants = new java.util.HashSet<>();
        for (SimOutput.RecallOutcome recall : out.recalls) {
            TenantRecord tenant = tenants.stream().filter(record -> record.id == recall.tenantId()).findFirst()
                    .orElseThrow();
            affectedTenants.add(recall.tenantId());
            Lot lot = recall.lot();
            long matchId = db.insert("""
                    insert into recall_matches (announcement_id, tenant_id, branch_id, product_id, lot_id,
                                                quantity_at_match, status, resolution, resolution_note, matched_at,
                                                acknowledged_at, acknowledged_by, resolved_at, resolved_by)
                    values (?, ?, ?, ?, ?, ?, 'RESOLVED', ?, ?, ?, ?, ?, ?, ?)""", announcements.oldRecallId(),
                    recall.tenantId(), recall.branchId(), recall.product().id, lot.id, recall.quantityAtMatch(),
                    recall.resolution(), recall.note(), recall.matchedAt(), recall.acknowledgedAt(),
                    recall.acknowledgedBy(), recall.resolvedAt(), recall.resolvedBy());
            String productName = recall.product().template.name();
            String message = recall.branchName() + ": el lote " + lot.lotNumber + " de " + productName
                    + (lot.expiryDate == null ? "" : " (vence " + lot.expiryDate.format(DAY) + ")")
                    + " está alcanzado por el recall \"" + announcements.oldRecallTitle()
                    + "\". Quedó en cuarentena y no se puede vender.";
            db.update("""
                    insert into alerts (tenant_id, branch_id, type, severity, status, product_id, lot_id, announcement_id,
                                        title, message, dedupe_key, handled_by, resolved_at, created_at, updated_at)
                    values (?, ?, 'RECALL_MATCH', 'CRITICAL', 'RESOLVED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    recall.tenantId(), recall.branchId(), recall.product().id, lot.id, announcements.oldRecallId(),
                    "Recall: " + productName + " (lote " + lot.lotNumber + ")", message,
                    "RECALL:" + announcements.oldRecallId() + ":" + lot.id, recall.resolvedBy(), recall.resolvedAt(),
                    recall.matchedAt(), recall.resolvedAt());
            for (Long userId : recall.branchUserIds()) {
                Instant readAt = recall.matchedAt().plus(Duration.ofMinutes(rnd.between(2, 90)));
                db.update("""
                        insert into notifications (user_id, tenant_id, type, severity, title, body, link, reference_type,
                                                   reference_id, read_at, created_at)
                        values (?, ?, 'RECALL_ALERT', 'CRITICAL', ?, ?, ?, 'RECALL_MATCH', ?, ?, ?)""",
                        userId, tenant.id, "Alerta de recall: " + productName, message,
                        RecallMatchingService.recallMatchLink(matchId), matchId, readAt, recall.matchedAt());
            }
        }
        db.update("update announcements set affected_tenants_count = ? where id = ?", affectedTenants.size(),
                announcements.oldRecallId());
    }

    // ------------------------------------------------------------------ recomendaciones ya decididas

    void recommendations(SimOutput out) {
        for (SimOutput.DiscountOutcome discount : out.discounts) {
            SimModel.Product product = discount.product();
            Lot lot = discount.lot();
            BigDecimal price = product.template.price();
            int expectedSales = (int) Math.round(discount.avgDailyBefore() * Math.max(discount.daysToExpiry(), 0));
            int atRisk = Math.max(1, discount.lotUnitsAtAccept() - expectedSales);
            double lift = 1 + StoreSimulator.ELASTICITY * discount.pct() / 100.0;
            int withDiscount = (int) Math.min(discount.lotUnitsAtAccept(),
                    Math.round(discount.avgDailyBefore() * lift * Math.max(discount.daysToExpiry(), 1)));
            BigDecimal impact = price.multiply(BigDecimal.valueOf(100 - discount.pct())).divide(BigDecimal.valueOf(100))
                    .multiply(BigDecimal.valueOf(Math.min(atRisk, withDiscount))).setScale(2, RoundingMode.HALF_UP);
            String title = "Aplicá " + discount.pct() + "% de descuento a " + product.template.name() + " (lote "
                    + label(lot) + ")";
            String explanation = "El lote " + label(lot) + " vence el " + lot.expiryDate.format(DAY) + " y tiene "
                    + discount.lotUnitsAtAccept() + " u. Al ritmo actual (" + number(discount.avgDailyBefore())
                    + " u/día) se venderían unas " + expectedSales + " u. antes del vencimiento: " + atRisk
                    + " u. en riesgo. Con " + discount.pct() + "% de descuento estimamos vender " + withDiscount
                    + " u. y recuperar " + money(impact) + ".";
            ObjectNode outcome = objectMapper.createObjectNode();
            outcome.put("appliedDiscountPct", discount.pct());
            outcome.put("unitsBefore7d", discount.unitsBefore7d());
            outcome.put("lotUnitsAtAccept", discount.lotUnitsAtAccept());
            if (discount.unitsAfter7d() != null) {
                outcome.put("unitsAfter7d", discount.unitsAfter7d());
                if (discount.unitsBefore7d() > 0) {
                    outcome.put("lift", BigDecimal.valueOf(discount.unitsAfter7d())
                            .divide(BigDecimal.valueOf(discount.unitsBefore7d()), 3, RoundingMode.HALF_UP));
                } else {
                    outcome.putNull("lift");
                }
                outcome.put("lotUnitsSold", discount.lotUnitsSold());
                outcome.put("lotUnitsRemaining", discount.lotUnitsRemaining());
                outcome.put("measuredAt", discount.measuredAt().toString());
            }
            Instant updated = discount.measuredAt() != null ? discount.measuredAt() : discount.decidedAt();
            db.update("""
                    insert into recommendations (tenant_id, branch_id, type, status, product_id, lot_id, title,
                                                 explanation, suggested_discount_pct, suggested_date, priority,
                                                 confidence, expected_impact, dedupe_key, decided_by, decided_at,
                                                 decision_note, outcome, created_at, updated_at)
                    values (?, ?, 'DISCOUNT', 'ACCEPTED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""",
                    discount.tenantId(), discount.branchId(), product.id, lot.id, title, explanation,
                    BigDecimal.valueOf(discount.pct()), discount.decidedAt().atZone(world.zone()).toLocalDate(),
                    rnd.between(72, 88), new BigDecimal("0.7" + rnd.between(0, 9)), impact, "DISCOUNT:" + lot.id,
                    discount.decidedBy(), discount.decidedAt(), discount.note(), outcome.toString(),
                    discount.createdAt(), updated);
        }
        for (SimOutput.DiscardedDiscountOutcome discarded : out.discardedDiscounts) {
            Lot lot = discarded.lot();
            SimModel.Product product = discarded.product();
            String lotText = lot == null ? "" : " (lote " + label(lot) + ")";
            String explanation = lot == null
                    ? "Las ventas vienen más lentas que el stock disponible: con " + discarded.pct()
                    + "% de descuento se acelera la salida antes del vencimiento."
                    : "El lote " + label(lot) + " vence el " + lot.expiryDate.format(DAY) + " y tiene "
                    + discarded.lotQuantity() + " u. A " + number(discarded.avgDaily())
                    + " u/día no llega a venderse todo antes del vencimiento. Con " + discarded.pct()
                    + "% de descuento se vendería a tiempo.";
            db.update("""
                    insert into recommendations (tenant_id, branch_id, type, status, product_id, lot_id, title,
                                                 explanation, suggested_discount_pct, priority, confidence,
                                                 expected_impact, dedupe_key, decided_by, decided_at, decision_note,
                                                 created_at, updated_at)
                    values (?, ?, 'DISCOUNT', 'DISCARDED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    discarded.tenantId(), discarded.branchId(), product.id, lot == null ? null : lot.id,
                    "Aplicá " + discarded.pct() + "% de descuento a " + product.template.name() + lotText, explanation,
                    BigDecimal.valueOf(discarded.pct()), 64, new BigDecimal("0.66"),
                    product.template.price().multiply(BigDecimal.valueOf(Math.max(1, discarded.lotQuantity() / 2))),
                    "DISCOUNT:" + (lot == null ? "P" + product.id : lot.id), discarded.decidedBy(),
                    discarded.decidedAt(), discarded.note(), discarded.createdAt(), discarded.decidedAt());
        }
        for (SimOutput.ReorderOutcome reorder : out.reorders) {
            SimModel.Product product = reorder.product();
            double cover = reorder.avgDaily() <= 0 ? 0 : reorder.stockAtDecision() / reorder.avgDaily();
            String explanation = "Quedan " + reorder.stockAtDecision() + " u. y se venden " + number(reorder.avgDaily())
                    + " u/día: el stock alcanza para " + number(cover) + " días y el proveedor tarda "
                    + product.leadTimeDays + " días. Sugerimos pedir " + reorder.suggestedQuantity() + " u. antes del "
                    + reorder.suggestedDate().format(SHORT_DAY) + ".";
            db.update("""
                    insert into recommendations (tenant_id, branch_id, type, status, product_id, title, explanation,
                                                 suggested_quantity, suggested_date, priority, confidence,
                                                 expected_impact, dedupe_key, decided_by, decided_at, decision_note,
                                                 created_at, updated_at)
                    values (?, ?, 'REORDER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", reorder.tenantId(),
                    reorder.branchId(), reorder.accepted() ? "ACCEPTED" : "DISCARDED", product.id,
                    "Reponer " + product.template.name(), explanation, reorder.suggestedQuantity(),
                    reorder.suggestedDate(), rnd.between(70, 90), new BigDecimal("0.8" + rnd.between(0, 9)),
                    product.template.price().multiply(BigDecimal.valueOf(reorder.suggestedQuantity() / 3 + 1)),
                    "REORDER:" + product.id, reorder.decidedBy(), reorder.decidedAt(), reorder.note(),
                    reorder.createdAt(), reorder.decidedAt());
        }
    }

    private static String label(Lot lot) {
        return lot.lotNumber == null ? "sin número" : lot.lotNumber;
    }

    private static String number(double value) {
        NumberFormat format = NumberFormat.getNumberInstance(ES_AR);
        format.setMaximumFractionDigits(1);
        format.setMinimumFractionDigits(0);
        return format.format(value);
    }

    private static String money(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(ES_AR);
        format.setMaximumFractionDigits(0);
        return "$ " + format.format(value);
    }

    // ------------------------------------------------------------------ importación inicial de El Sol

    private static final List<String> IMPORT_HEADERS = List.of("Código", "Producto", "Marca", "Rubro", "Proveedor",
            "Costo", "Precio", "Stock mín.", "Cantidad", "Lote", "Vencimiento", "Sucursal");
    private static final Map<String, String> IMPORT_MAPPING = mapping();

    private static Map<String, String> mapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("barcode", "Código");
        mapping.put("name", "Producto");
        mapping.put("brand", "Marca");
        mapping.put("category", "Rubro");
        mapping.put("supplier", "Proveedor");
        mapping.put("costPrice", "Costo");
        mapping.put("salePrice", "Precio");
        mapping.put("minStock", "Stock mín.");
        mapping.put("quantity", "Cantidad");
        mapping.put("lotNumber", "Lote");
        mapping.put("expiryDate", "Vencimiento");
        mapping.put("branch", "Sucursal");
        return mapping;
    }

    void importJob(TenantRecord tenant, SimOutput out) {
        if (tenant.importJobId == null) {
            return;
        }
        List<SimOutput.ImportedLot> rows = out.importedLots.stream()
                .filter(row -> row.tenantId() == tenant.id).toList();
        Set<String> seenCategories = new java.util.HashSet<>();
        Set<String> seenSuppliers = new java.util.HashSet<>();
        int warningRows = 0;
        int validRows = 0;
        long units = 0;
        Set<Long> products = new java.util.HashSet<>();
        List<Object[]> rowValues = new ArrayList<>();
        int lastRow = 1;
        for (SimOutput.ImportedLot row : rows) {
            DemoCatalog.Template template = row.product().template;
            Lot lot = row.lot();
            String branchShort = row.branchName().replace("Sucursal ", "");
            Map<String, String> raw = new LinkedHashMap<>();
            raw.put("Código", template.barcode());
            raw.put("Producto", template.name());
            raw.put("Marca", template.brand());
            raw.put("Rubro", template.category());
            raw.put("Proveedor", DemoCatalog.supplier(template.supplierKey()).name());
            raw.put("Costo", decimalEs(lot.costPrice));
            raw.put("Precio", decimalEs(roundPrice(template.price(), lot.costPrice, template.cost())));
            raw.put("Stock mín.", String.valueOf(template.minStock()));
            raw.put("Cantidad", String.valueOf(lot.initialQuantity));
            raw.put("Lote", lot.lotNumber == null ? "" : lot.lotNumber);
            raw.put("Vencimiento", lot.expiryDate == null ? "" : lot.expiryDate.format(DAY));
            raw.put("Sucursal", branchShort);
            Map<String, String> data = new LinkedHashMap<>();
            IMPORT_MAPPING.forEach((field, header) -> {
                String value = raw.get(header);
                if (value != null && !value.isBlank()) {
                    data.put(field, value);
                }
            });
            ArrayNode messages = objectMapper.createArrayNode();
            if (seenCategories.add(template.category())) {
                messages.addObject().put("field", "category").put("level", "WARNING")
                        .put("message", "La categoría «" + template.category() + "» es nueva: se va a crear.");
            }
            String supplierName = DemoCatalog.supplier(template.supplierKey()).name();
            if (seenSuppliers.add(supplierName)) {
                messages.addObject().put("field", "supplier").put("level", "WARNING")
                        .put("message", "El proveedor «" + supplierName + "» es nuevo: se va a crear.");
            }
            boolean firstRow = products.add(row.product().id);
            if (messages.isEmpty()) {
                validRows++;
            } else {
                warningRows++;
            }
            units += lot.initialQuantity;
            rowValues.add(new Object[] {tenant.importJobId, row.rowNumber(), json(raw), json(data), "IMPORTED",
                    firstRow ? "CREATE" : "UPDATE", messages.toString(), row.product().id,
                    lot.id});
            lastRow = Math.max(lastRow, row.rowNumber());
        }
        // Filas que el administrador omitió en la revisión.
        List<Map<String, String>> skipped = List.of(
                Map.of("Código", "", "Producto", "", "Marca", "", "Rubro", "Almacén", "Costo", "0", "Precio", "0",
                        "Cantidad", "0", "Sucursal", "Centro"),
                Map.of("Código", "7790000000001", "Producto", "Bolsa de consorcio x 10", "Marca", "Genérica",
                        "Rubro", "Limpieza", "Costo", "abc", "Precio", "1.200", "Cantidad", "12", "Sucursal",
                        "Centro"),
                Map.of("Código", "7791234000999", "Producto", "Pan dulce con frutas 500 g", "Marca", "Don Trigo",
                        "Rubro", "Panificados", "Costo", "3.100", "Precio", "4.500", "Cantidad", "20", "Vencimiento",
                        "31/12/2025", "Sucursal", "Funes"));
        List<String> skippedMessages = List.of(
                "[{\"field\":\"name\",\"level\":\"ERROR\",\"message\":\"Falta el nombre del producto.\"}]",
                "[{\"field\":\"costPrice\",\"level\":\"ERROR\",\"message\":\"El precio de costo «abc» no es un número.\"}]",
                "[{\"field\":\"branch\",\"level\":\"ERROR\",\"message\":\"La sucursal «Funes» no existe o no tenés "
                        + "acceso. Sucursales válidas: Centro, Echesortu, Fisherton.\"},{\"field\":\"expiryDate\","
                        + "\"level\":\"WARNING\",\"message\":\"El vencimiento ya pasó: entra como vencido pendiente de "
                        + "descarte.\"}]");
        for (int i = 0; i < skipped.size(); i++) {
            Map<String, String> raw = new LinkedHashMap<>(skipped.get(i));
            Map<String, String> data = new LinkedHashMap<>();
            IMPORT_MAPPING.forEach((field, header) -> {
                String value = raw.get(header);
                if (value != null && !value.isBlank()) {
                    data.put(field, value);
                }
            });
            rowValues.add(new Object[] {tenant.importJobId, lastRow + 1 + i, json(raw), json(data), "SKIPPED",
                    "SKIP", skippedMessages.get(i), null, null});
        }
        int total = rowValues.size();
        ObjectNode options = objectMapper.createObjectNode();
        options.put("updateExisting", true);
        options.put("createCategories", true);
        options.put("createSuppliers", true);
        options.put("importStock", true);
        options.put("defaultBranchId", tenant.branchIds.get("CEN"));
        options.put("dateFormat", "DMY");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("productsCreated", products.size());
        result.put("productsUpdated", 0);
        result.put("lotsCreated", rows.size());
        result.put("unitsLoaded", units);
        result.put("categoriesCreated", seenCategories.size());
        result.put("suppliersCreated", seenSuppliers.size());
        result.put("rowsSkipped", skipped.size());
        result.put("rowsFailed", 0);
        result.put("recallMatches", 0);
        db.update("""
                insert into import_jobs (id, tenant_id, type, status, file_name, file_format, sheet_name, sheet_names,
                                         headers, column_mapping, options, total_rows, valid_rows, warning_rows,
                                         error_rows, skipped_rows, processed_rows, result, created_by, created_at,
                                         updated_at, applied_at)
                values (?, ?, 'PRODUCTS', 'APPLIED', ?, 'XLSX', 'Stock', ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?,
                        0, ?, ?, ?::jsonb, ?, ?, ?, ?)""", tenant.importJobId, tenant.id,
                "stock-el-sol-marzo-2026.xlsx", json(List.of("Stock", "Instrucciones")), json(IMPORT_HEADERS),
                json(IMPORT_MAPPING), options.toString(), total, validRows, warningRows, skipped.size(),
                total - skipped.size(), result.toString(), tenant.admin(), tenant.importCreatedAt,
                tenant.importAppliedAt.plus(Duration.ofSeconds(40)), tenant.importAppliedAt.plus(Duration.ofSeconds(40)));
        try (SeedJdbc.Copy copy = db.copy("import_job_rows", "job_id, row_number, raw, data, status, action, messages, "
                + "product_id, lot_id")) {
            for (Object[] row : rowValues) {
                copy.row(row);
            }
        }
        // Notificación de fin de la importación para el administrador.
        db.update("""
                insert into notifications (user_id, tenant_id, type, severity, title, body, link, reference_type,
                                           reference_id, read_at, created_at)
                values (?, ?, 'SYSTEM', 'INFO', ?, ?, ?, 'IMPORT_JOB', ?, ?, ?)""", tenant.admin(), tenant.id,
                "Importación terminada: stock-el-sol-marzo-2026.xlsx",
                products.size() + " productos nuevos, " + rows.size() + " lotes y " + units + " unidades cargadas.",
                "/app/imports/" + tenant.importJobId, tenant.importJobId,
                tenant.importAppliedAt.plus(Duration.ofMinutes(3)), tenant.importAppliedAt.plus(Duration.ofSeconds(40)));
    }

    private static BigDecimal roundPrice(BigDecimal currentPrice, BigDecimal historicCost, BigDecimal currentCost) {
        BigDecimal ratio = historicCost.divide(currentCost, 6, RoundingMode.HALF_UP);
        return currentPrice.multiply(ratio).divide(BigDecimal.TEN, 0, RoundingMode.HALF_UP).multiply(BigDecimal.TEN);
    }

    private static String decimalEs(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(ES_AR);
        format.setMinimumFractionDigits(value.stripTrailingZeros().scale() > 0 ? 2 : 0);
        format.setMaximumFractionDigits(2);
        return format.format(value);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ soporte

    private record Msg(char sender, String email, int minutes, String body, boolean image) {
    }

    private record Ticket(String tenantKey, String creator, String subject, String category, String priority,
                          String status, String channel, String agent, int daysAgo, LocalTime time, List<Msg> messages,
                          Integer rating, String ratingComment) {
    }

    void support(List<TenantRecord> tenants, Platform platform) {
        String sofia = "soporte@gondolia.app";
        String tomas = "soporte2@gondolia.app";
        List<Ticket> tickets = new ArrayList<>();
        tickets.add(new Ticket(DemoWorld.DON_PEPE, "admin@donpepe.com",
                "La cámara no lee el código de barras de la sopa La Huerta", "TECNICO", "ALTA", "RESOLVED", "CHAT",
                sofia, 16, LocalTime.of(11, 2), List.of(
                new Msg('C', "admin@donpepe.com", 0, "Hola, al cargar mercadería con la cámara del celular no me toma "
                        + "el código de las latas de sopa. Les mando una foto de la etiqueta.", true),
                new Msg('S', sofia, 2, "{agent} tomó la consulta.", false),
                new Msg('A', sofia, 4, "¡Hola Marta! Gracias por la foto. Se ve un reflejo fuerte del flash sobre "
                        + "las barras: la cámara no llega a leer los últimos dígitos.", false),
                new Msg('A', sofia, 5, "Probá inclinar un poco la lata para evitar el brillo o apagar el flash. Si "
                        + "igual no la toma, podés tipear los 13 números que están debajo de las barras en el buscador "
                        + "de Carga de mercadería.", false),
                new Msg('C', "admin@donpepe.com", 9, "¡Ahí funcionó! Inclinándola la leyó al toque. Gracias.", false),
                new Msg('A', sofia, 11, "Genial. Te dejo la consulta como resuelta; cualquier cosa nos escribís por "
                        + "acá.", false),
                new Msg('S', sofia, 11, "{agent} cambió el estado a «Resuelto».", false),
                new Msg('S', "admin@donpepe.com", 40, "{customer} calificó la atención con 5 de 5.", false)),
                5, "Súper rápidos, gracias"));
        tickets.add(new Ticket(DemoWorld.EL_SOL, "admin@elsol.com", "¿Cómo paso mercadería de Centro a Echesortu?",
                "USO", "MEDIA", "CLOSED", "TICKET", tomas, 40, LocalTime.of(9, 20), List.of(
                new Msg('C', "admin@elsol.com", 0, "Buenas, tenemos sobrestock de fideos en Centro y en Echesortu se "
                        + "están quedando sin. ¿Cómo registro el traspaso sin hacer un ajuste a mano?", false),
                new Msg('S', tomas, 12, "{agent} tomó la consulta.", false),
                new Msg('A', tomas, 18, "¡Hola Laura! Para eso está Transferencias, en el menú lateral. Elegís la "
                        + "sucursal de origen y la de destino, los lotes y las cantidades. El lote que llega a Echesortu "
                        + "conserva el vencimiento y la fecha de ingreso original, así no se rompe el orden FIFO.",
                        false),
                new Msg('C', "admin@elsol.com", 35, "Perfecto, lo hicimos y quedó en el historial de movimientos. "
                        + "¡Gracias!", false),
                new Msg('S', "admin@elsol.com", 36, "{customer} cerró la conversación.", false),
                new Msg('S', "admin@elsol.com", 37, "{customer} calificó la atención con 4 de 5.", false)),
                4, "Buena respuesta, clara"));
        tickets.add(new Ticket(DemoWorld.VIDA_SANA, "admin@vidasana.com",
                "El POS de Cerro de las Rosas devuelve 401 al enviar ventas", "TECNICO", "ALTA", "RESOLVED", "TICKET",
                sofia, 75, LocalTime.of(10, 5), List.of(
                new Msg('C', "admin@vidasana.com", 0, "Desde ayer el sistema de caja del local de Cerro no puede "
                        + "mandar las ventas: responde 401 INVALID_API_KEY.", false),
                new Msg('S', sofia, 20, "{agent} tomó la consulta.", false),
                new Msg('A', sofia, 25, "Hola Carolina. Ese error aparece cuando la API key se regeneró: la anterior "
                        + "deja de funcionar. ¿Alguien generó una nueva desde Integración POS?", false),
                new Msg('C', "admin@vidasana.com", 50, "Sí, fue mi socio para probar. ¿Hay que cargar la nueva en el "
                        + "sistema de caja?", false),
                new Msg('A', sofia, 58, "Exacto: copiá la key nueva (se muestra una sola vez) y configurala en el POS. "
                        + "Las ventas que quedaron sin enviar las podés subir con la importación CSV de ventas.", false),
                new Msg('C', "admin@vidasana.com", 125, "Listo, ya entran las ventas. Gracias.", false),
                new Msg('S', sofia, 130, "{agent} cambió el estado a «Resuelto».", false),
                new Msg('S', "admin@vidasana.com", 180, "{customer} calificó la atención con 5 de 5.", false)),
                5, null));
        tickets.add(new Ticket(DemoWorld.EL_SOL, "jefe@elsol.com", "Consulta por la cuota con una cuarta sucursal",
                "FACTURACION", "MEDIA", "WAITING_CUSTOMER", "TICKET", tomas, 3, LocalTime.of(17, 45), List.of(
                new Msg('C', "jefe@elsol.com", 0, "Estamos por abrir una cuarta sucursal en Funes. ¿Cuánto aumentaría "
                        + "la cuota mensual con el plan Profesional?", false),
                new Msg('S', tomas, 55, "{agent} tomó la consulta.", false),
                new Msg('A', tomas, 62, "¡Hola Roberto! El plan Profesional se cobra por sucursal activa: $ 55.000 "
                        + "más $ 12.000 del POS GondolIA y $ 8.000 de la integración con POS propio, si los usa en esa "
                        + "sucursal. ¿La nueva sucursal usaría el POS GondolIA o el suyo?", false),
                new Msg('S', tomas, 63, "{agent} cambió el estado a «Esperando respuesta».", false)),
                null, null));
        tickets.add(new Ticket(DemoWorld.DON_PEPE, "empleado@donpepe.com", "¿Puedo cargar productos sin número de lote?",
                "USO", "BAJA", "RESOLVED", "TICKET", tomas, 60, LocalTime.of(15, 30), List.of(
                new Msg('C', "empleado@donpepe.com", 0, "Algunos productos del mayorista no traen número de lote. "
                        + "¿Los cargo igual?", false),
                new Msg('S', tomas, 30, "{agent} tomó la consulta.", false),
                new Msg('A', tomas, 34, "¡Hola Lucas! Sí: el número de lote es opcional. Lo importante es cargar el "
                        + "vencimiento, porque con eso GondolIA arma las alertas y el orden de salida. Cada carga crea "
                        + "un lote nuevo aunque no tenga número.", false),
                new Msg('C', "empleado@donpepe.com", 50, "Buenísimo, gracias.", false),
                new Msg('S', tomas, 52, "{agent} cambió el estado a «Resuelto».", false)),
                null, null));
        tickets.add(new Ticket(DemoWorld.VIDA_SANA, "empleado@vidasana.com",
                "Sugerencia: aviso por WhatsApp cuando algo está por vencer", "SUGERENCIA", "BAJA", "IN_PROGRESS",
                "TICKET", sofia, 9, LocalTime.of(12, 10), List.of(
                new Msg('C', "empleado@vidasana.com", 0, "Estaría bueno que el aviso de vencimientos le llegue también "
                        + "por WhatsApp a la encargada, que no siempre mira la compu.", false),
                new Msg('S', sofia, 90, "{agent} tomó la consulta.", false),
                new Msg('A', sofia, 95, "¡Gracias por la idea, Agustina! La pasamos al equipo de producto. Mientras "
                        + "tanto, las alertas críticas llegan en tiempo real a la campana de notificaciones de cada "
                        + "usuario con acceso a la sucursal.", false)),
                null, null));
        int urgentMinutesAgo = 25;
        Instant urgentAt = world.now().minus(Duration.ofMinutes(urgentMinutesAgo));
        tickets.add(new Ticket(DemoWorld.EL_SOL, "cajero@elsol.com", "La impresora de tickets de Caja 1 imprime cortado",
                "TECNICO", "URGENTE", "OPEN", "CHAT", null, -1, urgentAt.atZone(world.zone()).toLocalTime(), List.of(
                new Msg('C', "cajero@elsol.com", 0, "Hola, en Centro la impresora de la Caja 1 corta el ticket por la "
                        + "mitad desde esta mañana. ¿Puede ser algo de la configuración?", false),
                new Msg('C', "cajero@elsol.com", 2, "Tengo cola de clientes, ¿me pueden ayudar?", false)),
                null, null));
        tickets.add(new Ticket("sanmartin", "admin@farmaciasanmartin.com.ar",
                "No puedo agregar una tercera sucursal", "USO", "MEDIA", "RESOLVED", "TICKET", sofia, 100,
                LocalTime.of(10, 40), List.of(
                new Msg('C', "admin@farmaciasanmartin.com.ar", 0, "Quiero dar de alta la sucursal de Luján de Cuyo y "
                        + "me aparece un error de límite.", false),
                new Msg('S', sofia, 15, "{agent} tomó la consulta.", false),
                new Msg('A', sofia, 22, "Hola Paula. Revisamos tu cuenta: el alta falló porque la sucursal de prueba "
                        + "que crearon la semana pasada seguía activa. Desactivala desde Sucursales y vas a poder "
                        + "crear la nueva sin problema.", false),
                new Msg('C', "admin@farmaciasanmartin.com.ar", 40, "Listo, era eso. Gracias.", false),
                new Msg('S', sofia, 41, "{agent} cambió el estado a «Resuelto».", false)),
                null, null));
        tickets.add(new Ticket("laabuela", "admin@almacenlaabuela.com.ar",
                "Error al importar la planilla: fecha inválida", "TECNICO", "MEDIA", "CLOSED", "TICKET", tomas, 150,
                LocalTime.of(16, 15), List.of(
                new Msg('C', "admin@almacenlaabuela.com.ar", 0, "Subí mi planilla y me marca 'fecha inválida' en casi "
                        + "todas las filas de vencimiento.", false),
                new Msg('S', tomas, 25, "{agent} tomó la consulta.", false),
                new Msg('A', tomas, 31, "Hola Silvina. Tu planilla tiene las fechas en formato mes/día/año. En el paso "
                        + "«Columnas» cambiá el formato de fecha a MM/DD/AAAA y se validan todas.", false),
                new Msg('C', "admin@almacenlaabuela.com.ar", 60, "Perfecto, ahora sí. Gracias.", false),
                new Msg('S', "admin@almacenlaabuela.com.ar", 61, "{customer} cerró la conversación.", false),
                new Msg('S', "admin@almacenlaabuela.com.ar", 62, "{customer} calificó la atención con 4 de 5.",
                        false)),
                4, null));
        tickets.add(new Ticket(DemoWorld.LA_ESQUINA, "admin@laesquina.com",
                "¿Puedo usar el mismo usuario en dos kioscos?", "USO", "MEDIA", "RESOLVED", "TICKET", sofia, 25,
                LocalTime.of(19, 5), List.of(
                new Msg('C', "admin@laesquina.com", 0, "Tengo otro kiosco en la otra cuadra. ¿Puedo usar el mismo "
                        + "usuario y cargar todo junto?", false),
                new Msg('S', sofia, 40, "{agent} tomó la consulta.", false),
                new Msg('A', sofia, 46, "Hola Natalia. Cada local tiene que ser una sucursal del comercio (con el "
                        + "módulo Multi-sucursal) o un comercio aparte. Compartir el mismo usuario entre locales mezcla "
                        + "el stock y no está permitido por los términos del servicio.", false),
                new Msg('S', sofia, 47, "{agent} cambió el estado a «Resuelto».", false)),
                null, null));

        Map<String, TenantRecord> byKey = tenants.stream()
                .collect(Collectors.toMap(record -> record.spec.key(), record -> record));
        for (Ticket ticket : tickets) {
            writeTicket(byKey.get(ticket.tenantKey()), ticket, platform);
        }
    }

    private void writeTicket(TenantRecord tenant, Ticket ticket, Platform platform) {
        Instant created = ticket.daysAgo() < 0
                ? world.now().minus(Duration.ofMinutes(25))
                : world.daysAgo(ticket.daysAgo(), ticket.time());
        Long agentId = ticket.agent() == null ? null : platform.id(ticket.agent());
        long creatorId = tenant.user(ticket.creator());
        Instant lastMessage = created;
        Instant firstResponse = null;
        Instant lastAgentMessage = null;
        for (Msg message : ticket.messages()) {
            Instant at = created.plus(Duration.ofMinutes(message.minutes()));
            lastMessage = at.isAfter(lastMessage) ? at : lastMessage;
            if (message.sender() == 'A') {
                firstResponse = firstResponse == null ? at : firstResponse;
                lastAgentMessage = at;
            }
        }
        boolean finished = ticket.status().equals("RESOLVED") || ticket.status().equals("CLOSED");
        Instant customerRead = ticket.status().equals("WAITING_CUSTOMER") && lastAgentMessage != null
                ? lastAgentMessage.minus(Duration.ofMinutes(20)) : lastMessage;
        Instant agentRead = agentId == null ? null : lastMessage;
        long ticketId = db.insert("""
                insert into support_tickets (tenant_id, created_by, assigned_to, subject, category, priority, status,
                                             channel, rating, rating_comment, last_message_at, first_response_at,
                                             customer_last_read_at, agent_last_read_at, resolved_at, created_at,
                                             updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", tenant.id, creatorId, agentId,
                ticket.subject(), ticket.category(), ticket.priority(), ticket.status(), ticket.channel(),
                ticket.rating(), ticket.ratingComment(), lastMessage, firstResponse, customerRead, agentRead,
                finished ? lastMessage : null, created, lastMessage);
        String agentName = ticket.agent() == null ? null : platform.names().get(ticket.agent());
        for (Msg message : ticket.messages()) {
            Instant at = created.plus(Duration.ofMinutes(message.minutes()));
            MessageSender sender = sender(message, tenant, platform);
            String body = message.body().replace("{agent}", agentName == null ? "" : agentName)
                    .replace("{customer}", tenant.userSpecs.containsKey(message.email())
                            ? tenant.fullName(message.email()) : "");
            Long attachmentId = null;
            if (message.image()) {
                attachmentId = storeLabelPhoto(tenant.id, creatorId, at);
            }
            db.update("""
                    insert into support_messages (ticket_id, sender_id, sender_type, body, attachment_id, created_at)
                    values (?, ?, ?, ?, ?, ?)""", ticketId, sender.id(), sender.type(), body, attachmentId, at);
        }
        String tenantName = tenant.spec.name();
        if (ticket.status().equals("OPEN") && agentId == null) {
            for (String agent : List.of("soporte@gondolia.app", "soporte2@gondolia.app")) {
                db.update("""
                        insert into notifications (user_id, type, severity, title, body, link, reference_type,
                                                   reference_id, created_at)
                        values (?, 'TICKET_MESSAGE', ?, ?, ?, ?, 'SUPPORT_TICKET', ?, ?)""", platform.id(agent),
                        severity(ticket.priority()), "Nuevo ticket · " + tenantName,
                        tenant.fullName(ticket.creator()) + " abrió «" + ticket.subject() + "»",
                        "/support/tickets/" + ticketId, ticketId, created);
            }
        }
        if (ticket.status().equals("WAITING_CUSTOMER") && lastAgentMessage != null) {
            db.update("""
                    insert into notifications (user_id, tenant_id, type, severity, title, body, link, reference_type,
                                               reference_id, created_at)
                    values (?, ?, 'TICKET_MESSAGE', 'INFO', ?, ?, ?, 'SUPPORT_TICKET', ?, ?)""", creatorId, tenant.id,
                    "Soporte respondió: " + ticket.subject(),
                    agentName + " escribió: ¡Hola! El plan Profesional se cobra por sucursal activa…",
                    "/app/support/" + ticketId, ticketId, lastAgentMessage);
        }
    }

    private record MessageSender(Long id, String type) {
    }

    private MessageSender sender(Msg message, TenantRecord tenant, Platform platform) {
        return switch (message.sender()) {
            case 'C' -> new MessageSender(tenant.user(message.email()), "CUSTOMER");
            case 'A' -> new MessageSender(platform.id(message.email()), "AGENT");
            default -> new MessageSender(tenant.users.containsKey(message.email()) ? tenant.user(message.email())
                    : platform.id(message.email()), "SYSTEM");
        };
    }

    private static String severity(String priority) {
        return switch (priority) {
            case "URGENTE" -> "CRITICAL";
            case "ALTA" -> "WARNING";
            default -> "INFO";
        };
    }

    /** Foto real (PNG generado) de la etiqueta de la sopa, guardada con el servicio de adjuntos del núcleo. */
    private Long storeLabelPhoto(long tenantId, long uploadedBy, Instant at) {
        DemoCatalog.Template soup = DemoCatalog.byKey(DemoScenarios.LIVE_RECALL_PRODUCT);
        byte[] png = DemoLabelImage.render(soup.barcode(), "30112028");
        Attachment attachment = attachments.store(new SeedMultipartFile("file", "etiqueta-sopa-la-huerta.png",
                "image/png", png), AttachmentPurpose.SUPPORT, tenantId, uploadedBy);
        db.update("update attachments set created_at = ? where id = ?", at, attachment.getId());
        return attachment.getId();
    }
}
