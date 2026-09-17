package com.gondolia.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gondolia.analytics.AnalyticsSql;
import com.gondolia.analytics.BranchScopeService.Scope;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.events.StockChangedEvent;
import com.gondolia.domain.ai.Recommendation;
import com.gondolia.domain.ai.RecommendationRepository;
import com.gondolia.domain.ai.RecommendationStatus;
import com.gondolia.domain.ai.RecommendationType;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.insights.dto.RecommendationDecisionDto;
import com.gondolia.insights.dto.RecommendationDto;
import com.gondolia.security.BranchAccessService;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.AdjustCommand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomendaciones de la IA (SPEC §6.5): listado por alcance de sucursales y decisión del administrador.
 * <p>
 * Aceptar una recomendación hace el trabajo de verdad:
 * <ul>
 *   <li>{@code DISCOUNT} → pone {@code lots.discount_pct} en el lote, así pasa a venderse primero (SPEC §4.2);</li>
 *   <li>{@code REMOVE_EXPIRED} → descarta el remanente con {@code WASTE_EXPIRED} vía {@code StockService};</li>
 *   <li>{@code REORDER} → arma el texto del pedido al proveedor para mandar por WhatsApp;</li>
 *   <li>el resto solo queda registrado como aceptado.</li>
 * </ul>
 * En las de descuento se guarda una foto de las ventas previas para que el trabajo diario pueda medir el resultado a
 * los 7 días y devolvérselo a la IA como {@code feedback} (§8.2).
 */
@Service
@RequiredArgsConstructor
public class RecommendationService {

    static final String MSG_NOT_FOUND = "No encontramos esa recomendación";
    static final String MSG_ALREADY_DECIDED = "Esa recomendación ya fue decidida";
    static final String MSG_LOT_GONE = "El lote de la recomendación ya no tiene stock";
    static final String MSG_NO_LOT = "La recomendación no apunta a ningún lote";

    /** Ventana de medición del resultado de un descuento (SPEC §6.5). */
    static final int OUTCOME_WINDOW_DAYS = 7;

    /** Campos propios del JSON {@code recommendations.outcome}. */
    static final String FIELD_APPLIED_DISCOUNT = "appliedDiscountPct";
    static final String FIELD_UNITS_BEFORE = "unitsBefore7d";
    static final String FIELD_UNITS_AFTER = "unitsAfter7d";
    static final String FIELD_LIFT = "lift";
    static final String FIELD_LOT_UNITS_SOLD = "lotUnitsSold";
    static final String FIELD_LOT_UNITS_REMAINING = "lotUnitsRemaining";
    static final String FIELD_LOT_UNITS_AT_ACCEPT = "lotUnitsAtAccept";
    static final String FIELD_MEASURED_AT = "measuredAt";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String SELECT_RECOMMENDATION = """
            select r.id, r.branch_id, r.type, r.status, r.product_id, p.name as product_name, p.brand,
                   r.lot_id, l.lot_number, l.expiry_date, r.title, r.explanation, r.suggested_quantity,
                   r.suggested_discount_pct, r.suggested_date, r.priority, r.confidence, r.expected_impact,
                   r.created_at, r.decided_at, u.full_name as decided_by_name, r.decision_note,
                   r.outcome::text as outcome
            from recommendations r
            left join products p on p.id = r.product_id
            left join lots l on l.id = r.lot_id
            left join users u on u.id = r.decided_by
            """;

    private final RecommendationRepository recommendationRepository;
    private final LotRepository lotRepository;
    private final ProductRepository productRepository;
    private final SupplierRepository supplierRepository;
    private final TenantSettingsRepository settingsRepository;
    private final StockService stockService;
    private final BranchAccessService branchAccess;
    private final NamedParameterJdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /** Filtro del listado. {@code status} null = todas. */
    public record RecommendationQuery(RecommendationStatus status, RecommendationType type, Long productId,
                                      String q, int page, int size) {
    }

    /** Cuerpo de {@code POST /api/tenant/recommendations/{id}/accept}. */
    public record AcceptRequest(String note, Integer quantity, Integer discountPct) {
    }

    // ------------------------------------------------------------------ lectura

    @Transactional(readOnly = true)
    public PageResponse<RecommendationDto> list(Long tenantId, Scope scope, RecommendationQuery query) {
        if (scope.isEmpty()) {
            return PageResponse.empty(query.page(), query.size());
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds());
        List<String> conditions = new ArrayList<>(List.of("r.tenant_id = :tenantId",
                "r.branch_id in (:branchIds)"));
        if (query.status() != null) {
            conditions.add("r.status = :status");
            params.addValue("status", query.status().name());
        }
        if (query.type() != null) {
            conditions.add("r.type = :type");
            params.addValue("type", query.type().name());
        }
        if (query.productId() != null) {
            conditions.add("r.product_id = :productId");
            params.addValue("productId", query.productId());
        }
        if (query.q() != null && !query.q().isBlank()) {
            conditions.add("(lower(r.title) like :q or lower(coalesce(p.name, '')) like :q)");
            params.addValue("q", "%" + query.q().strip().toLowerCase(Locale.ROOT) + "%");
        }
        String where = "where " + String.join(" and ", conditions) + "\n";

        Long total = jdbc.queryForObject("""
                select count(*) from recommendations r
                left join products p on p.id = r.product_id
                """ + where, params, Long.class);
        if (total == null || total == 0) {
            return PageResponse.empty(query.page(), query.size());
        }
        params.addValue("rowLimit", query.size()).addValue("rowOffset", (long) query.page() * query.size());
        List<RecommendationDto> rows = jdbc.query(SELECT_RECOMMENDATION + where + """
                 order by case r.status when 'PENDING' then 0 else 1 end, r.priority desc,
                          r.created_at desc, r.id desc
                 limit :rowLimit offset :rowOffset
                """, params, (rs, rowNum) -> map(rs, scope));
        return PageResponse.of(rows, query.page(), query.size(), total);
    }

    /** Recomendaciones PENDING del alcance, ordenadas por prioridad (para el Inicio). */
    @Transactional(readOnly = true)
    public List<RecommendationDto> pending(Long tenantId, Scope scope, int limit) {
        if (scope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchIds", scope.branchIds())
                .addValue("rowLimit", limit);
        return jdbc.query(SELECT_RECOMMENDATION + """
                where r.tenant_id = :tenantId and r.branch_id in (:branchIds) and r.status = 'PENDING'
                order by r.priority desc, r.created_at desc, r.id desc
                limit :rowLimit
                """, params, (rs, rowNum) -> map(rs, scope));
    }

    /** Recomendaciones abiertas de un producto en una sucursal (ficha de la IA). */
    @Transactional(readOnly = true)
    public List<RecommendationDto> forProduct(Long tenantId, Scope scope, Long branchId, Long productId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("branchId", branchId)
                .addValue("productId", productId);
        return jdbc.query(SELECT_RECOMMENDATION + """
                where r.tenant_id = :tenantId and r.branch_id = :branchId and r.product_id = :productId
                  and r.status in ('PENDING', 'ACCEPTED')
                order by case r.status when 'PENDING' then 0 else 1 end, r.priority desc, r.created_at desc
                limit 20
                """, params, (rs, rowNum) -> map(rs, scope));
    }

    @Transactional(readOnly = true)
    public RecommendationDto byId(Long tenantId, Long id, Scope scope) {
        List<RecommendationDto> rows = jdbc.query(
                SELECT_RECOMMENDATION + " where r.tenant_id = :tenantId and r.id = :id",
                new MapSqlParameterSource("tenantId", tenantId).addValue("id", id),
                (rs, rowNum) -> map(rs, scope));
        if (rows.isEmpty()) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return rows.getFirst();
    }

    // ------------------------------------------------------------------ decisiones

    /** Acepta la recomendación y ejecuta la acción que corresponde a su tipo. */
    @Transactional
    public RecommendationDecisionDto accept(Long tenantId, Long id, Long userId, Scope scope, AcceptRequest request) {
        Recommendation recommendation = load(tenantId, id);
        AcceptRequest body = request == null ? new AcceptRequest(null, null, null) : request;

        BigDecimal appliedDiscount = null;
        Integer discardedQuantity = null;
        Integer orderedQuantity = null;
        String whatsappText = null;
        String whatsappUrl = null;
        String message;
        ObjectNode outcome = objectMapper.createObjectNode();

        switch (recommendation.getType()) {
            case DISCOUNT -> {
                Lot lot = lockLot(recommendation);
                BigDecimal pct = resolveDiscount(tenantId, recommendation, body.discountPct());
                int before = unitsSold(recommendation.getBranchId(), recommendation.getProductId(),
                        LocalDate.now(clock).minusDays(OUTCOME_WINDOW_DAYS), LocalDate.now(clock));
                lot.setDiscountPct(pct);
                lot.setDiscountStartedAt(clock.instant());
                lotRepository.save(lot);
                events.publishEvent(new StockChangedEvent(tenantId, lot.getBranchId(), lot.getProductId()));
                appliedDiscount = pct;
                outcome.put(FIELD_APPLIED_DISCOUNT, pct);
                outcome.put(FIELD_UNITS_BEFORE, before);
                outcome.put(FIELD_LOT_UNITS_AT_ACCEPT, lot.getQuantity());
                message = "Aplicamos %s%% de descuento al lote %s: ahora se vende primero.".formatted(
                        plain(pct), lotLabel(lot));
            }
            case REMOVE_EXPIRED -> {
                Lot lot = lockLot(recommendation);
                int quantity = body.quantity() == null ? lot.getQuantity() : body.quantity();
                if (quantity <= 0) {
                    throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "La cantidad a descartar debe ser mayor a 0");
                }
                if (quantity > lot.getQuantity()) {
                    throw new ConflictException(ErrorCodes.INSUFFICIENT_STOCK,
                            "El lote tiene %d unidades: no se pueden descartar %d".formatted(lot.getQuantity(),
                                    quantity));
                }
                stockService.adjust(new AdjustCommand(tenantId, lot.getId(), MovementType.WASTE_EXPIRED, quantity,
                        "Descarte por recomendación de la IA", MovementSource.SYSTEM, userId));
                discardedQuantity = quantity;
                outcome.put("discardedQuantity", quantity);
                message = "Descartamos %d unidades vencidas del lote %s.".formatted(quantity, lotLabel(lot));
            }
            case REORDER -> {
                int quantity = body.quantity() != null ? body.quantity()
                        : recommendation.getSuggestedQuantity() == null ? 0 : recommendation.getSuggestedQuantity();
                if (quantity <= 0) {
                    throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                            "Indicá cuántas unidades vas a pedir");
                }
                orderedQuantity = quantity;
                outcome.put("orderedQuantity", quantity);
                Order order = buildOrder(tenantId, recommendation, quantity, scope);
                whatsappText = order.text();
                whatsappUrl = order.url();
                message = order.supplierName() == null
                        ? "Anotamos el pedido de %d unidades. Copiá el texto para mandárselo al proveedor.".formatted(
                                quantity)
                        : "Anotamos el pedido de %d unidades a %s.".formatted(quantity, order.supplierName());
            }
            case REVIEW_ANOMALY -> message = "Anotamos que ya revisaste la anomalía.";
            case REDUCE_PURCHASE -> message = "Anotamos que vas a comprar menos de este producto.";
            default -> message = "Recomendación aceptada.";
        }

        recommendation.setStatus(RecommendationStatus.ACCEPTED);
        recommendation.setDecidedBy(userId);
        recommendation.setDecidedAt(clock.instant());
        recommendation.setDecisionNote(trim(body.note()));
        if (!outcome.isEmpty()) {
            recommendation.setOutcome(outcome);
        }
        recommendationRepository.saveAndFlush(recommendation);

        return new RecommendationDecisionDto(byId(tenantId, id, scope), message, appliedDiscount, discardedQuantity,
                orderedQuantity, whatsappText, whatsappUrl);
    }

    /** Descarta la recomendación: la IA la vuelve a evaluar en el próximo análisis. */
    @Transactional
    public RecommendationDecisionDto discard(Long tenantId, Long id, Long userId, Scope scope, String note) {
        Recommendation recommendation = load(tenantId, id);
        recommendation.setStatus(RecommendationStatus.DISCARDED);
        recommendation.setDecidedBy(userId);
        recommendation.setDecidedAt(clock.instant());
        recommendation.setDecisionNote(trim(note));
        recommendationRepository.saveAndFlush(recommendation);
        return new RecommendationDecisionDto(byId(tenantId, id, scope),
                "Descartamos la recomendación. La IA la vuelve a evaluar en el próximo análisis.",
                null, null, null, null, null);
    }

    // ------------------------------------------------------------------ apoyo

    private Recommendation load(Long tenantId, Long id) {
        Recommendation recommendation = recommendationRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        branchAccess.assertAccess(recommendation.getBranchId());
        if (recommendation.getStatus() != RecommendationStatus.PENDING) {
            throw new ConflictException(ErrorCodes.CONFLICT, MSG_ALREADY_DECIDED);
        }
        return recommendation;
    }

    private Lot lockLot(Recommendation recommendation) {
        if (recommendation.getLotId() == null) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, MSG_NO_LOT);
        }
        Lot lot = lotRepository.findByIdAndTenantIdForUpdate(recommendation.getLotId(), recommendation.getTenantId())
                .orElseThrow(() -> new NotFoundException(MSG_LOT_GONE));
        if (lot.getQuantity() <= 0 || lot.getStatus() == LotStatus.RECALLED) {
            throw new ConflictException(ErrorCodes.CONFLICT, MSG_LOT_GONE);
        }
        return lot;
    }

    /** Descuento a aplicar: el elegido por el usuario o el sugerido, siempre dentro del tope del comercio. */
    private BigDecimal resolveDiscount(Long tenantId, Recommendation recommendation, Integer requested) {
        BigDecimal pct = requested != null ? BigDecimal.valueOf(requested) : recommendation.getSuggestedDiscountPct();
        if (pct == null || pct.signum() <= 0) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "Indicá un porcentaje de descuento mayor a 0");
        }
        TenantSettings settings = settingsRepository.findById(tenantId)
                .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
        BigDecimal max = BigDecimal.valueOf(settings.getMaxDiscountPct());
        if (pct.compareTo(max) > 0) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El descuento máximo configurado es %d%%".formatted(settings.getMaxDiscountPct()));
        }
        return pct.setScale(2, RoundingMode.HALF_UP);
    }

    /** Texto del pedido al proveedor y su link de WhatsApp (si el proveedor tiene teléfono). */
    private record Order(String supplierName, String text, String url) {
    }

    private Order buildOrder(Long tenantId, Recommendation recommendation, int quantity, Scope scope) {
        Product product = recommendation.getProductId() == null ? null
                : productRepository.findByIdAndTenantId(recommendation.getProductId(), tenantId).orElse(null);
        Supplier supplier = product == null || product.getSupplierId() == null ? null
                : supplierRepository.findByIdAndTenantId(product.getSupplierId(), tenantId).orElse(null);
        String branchName = scope.nameOf(recommendation.getBranchId());
        StringBuilder text = new StringBuilder();
        text.append("¡Hola");
        if (supplier != null && supplier.getContactName() != null && !supplier.getContactName().isBlank()) {
            text.append(' ').append(supplier.getContactName().strip());
        }
        text.append("! Te hago un pedido:\n\n");
        text.append("• ").append(product == null ? "Producto" : product.getName());
        if (product != null && product.getBrand() != null && !product.getBrand().isBlank()) {
            text.append(" (").append(product.getBrand().strip()).append(')');
        }
        text.append(" — ").append(quantity).append(" unidades\n");
        if (product != null && product.getBarcode() != null && !product.getBarcode().isBlank()) {
            text.append("  Código: ").append(product.getBarcode()).append('\n');
        }
        if (recommendation.getSuggestedDate() != null) {
            text.append("\nLo necesitamos para el ").append(recommendation.getSuggestedDate().format(DAY)).append('.');
        }
        if (branchName != null) {
            text.append("\nEntrega en ").append(branchName).append('.');
        }
        text.append("\n\n¡Gracias!");
        String body = text.toString();
        String phone = supplier == null ? null : digits(supplier.getPhone());
        String url = phone == null || phone.isBlank() ? null
                : "https://wa.me/" + phone + "?text=" + URLEncoder.encode(body, StandardCharsets.UTF_8);
        return new Order(supplier == null ? null : supplier.getName(), body, url);
    }

    /** Unidades netas vendidas de un producto en una sucursal entre dos fechas (inclusive/exclusive). */
    int unitsSold(Long branchId, Long productId, LocalDate from, LocalDate to) {
        if (productId == null || branchId == null) {
            return 0;
        }
        Integer units = jdbc.queryForObject("""
                select coalesce(sum(case when m.type = 'SALE' then m.quantity else -m.quantity end), 0)::int
                from stock_movements m
                where m.branch_id = :branchId and m.product_id = :productId
                  and m.type in ('SALE', 'SALE_VOID')
                  and (m.occurred_at at time zone :zone)::date >= :from
                  and (m.occurred_at at time zone :zone)::date < :to
                """, new MapSqlParameterSource("branchId", branchId)
                .addValue("productId", productId)
                .addValue("zone", clock.getZone().getId())
                .addValue("from", Date.valueOf(from))
                .addValue("to", Date.valueOf(to)), Integer.class);
        return units == null ? 0 : Math.max(units, 0);
    }

    Optional<Lot> lot(Long tenantId, Long lotId) {
        return lotId == null ? Optional.empty() : lotRepository.findByIdAndTenantId(lotId, tenantId);
    }

    ObjectMapper mapper() {
        return objectMapper;
    }

    private RecommendationDto map(java.sql.ResultSet rs, Scope scope) throws java.sql.SQLException {
        Long branchId = (Long) rs.getObject("branch_id");
        return new RecommendationDto(rs.getLong("id"), branchId, scope.nameOf(branchId),
                RecommendationType.valueOf(rs.getString("type")),
                RecommendationStatus.valueOf(rs.getString("status")), (Long) rs.getObject("product_id"),
                rs.getString("product_name"), rs.getString("brand"), (Long) rs.getObject("lot_id"),
                rs.getString("lot_number"), rs.getObject("expiry_date", LocalDate.class), rs.getString("title"),
                rs.getString("explanation"), (Integer) rs.getObject("suggested_quantity"),
                rs.getBigDecimal("suggested_discount_pct"), rs.getObject("suggested_date", LocalDate.class),
                rs.getInt("priority"), rs.getBigDecimal("confidence"), rs.getBigDecimal("expected_impact"),
                AnalyticsSql.instant(rs, "created_at"), AnalyticsSql.instant(rs, "decided_at"),
                rs.getString("decided_by_name"), rs.getString("decision_note"), readJson(rs.getString("outcome")));
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private static String lotLabel(Lot lot) {
        return lot.getLotNumber() == null || lot.getLotNumber().isBlank()
                ? "#" + lot.getId()
                : lot.getLotNumber();
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String digits(String phone) {
        return phone == null ? null : phone.replaceAll("\\D", "");
    }

    private static String trim(String note) {
        if (note == null) {
            return null;
        }
        String stripped = note.strip();
        if (stripped.isEmpty()) {
            return null;
        }
        return stripped.length() <= 500 ? stripped : stripped.substring(0, 500);
    }
}
