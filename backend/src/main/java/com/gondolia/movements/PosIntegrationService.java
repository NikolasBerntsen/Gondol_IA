package com.gondolia.movements;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.util.Barcodes;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.movements.dto.PosIntegrationDtos.PosApiKeyDto;
import com.gondolia.movements.dto.PosIntegrationDtos.PosIntegrationDto;
import com.gondolia.movements.dto.PosIntegrationDtos.PosSaleItemRequest;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookRequest;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookResponse;
import com.gondolia.movements.dto.PosIntegrationDtos.ShortageDto;
import com.gondolia.movements.dto.PosIntegrationDtos.SimulateResultDto;
import com.gondolia.security.ApiKeyService;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import com.gondolia.security.GeneratedApiKey;
import com.gondolia.stock.BatchRefs;
import com.gondolia.stock.StockService;
import com.gondolia.stock.StockService.SaleCommand;
import com.gondolia.stock.StockService.SaleResult;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integración con el POS propio del cliente (SPEC §6.4): API key por sucursal, simulador de ventas y
 * procesamiento del webhook público {@code POST /api/integrations/pos/sales}.
 * <p>
 * Todas las ventas entran por el mismo camino ({@link StockService#registerSale}) con origen
 * {@code MovementSource.POS} ("POS externo"), así el historial, las estadísticas y la IA las ven igual que
 * cualquier otra venta.
 */
@Service
@RequiredArgsConstructor
public class PosIntegrationService {

    /** Prefijo del {@code batchRef} de una venta idempotente por {@code externalId}. */
    static final String EXTERNAL_PREFIX = "S-EXT-";
    private static final int MAX_BATCH_REF = 40;
    private static final Pattern EXTERNAL_ID_CLEAN = Pattern.compile("[^A-Za-z0-9-]");
    private static final int SIMULATOR_MAX_LINES = 4;
    private static final int SIMULATOR_MAX_UNITS = 3;

    private final StockService stockService;
    private final ApiKeyService apiKeyService;
    private final BranchAccessService branchAccessService;
    private final BranchRepository branchRepository;
    private final ProductRepository productRepository;
    private final StockMovementRepository movementRepository;
    private final MovementSupport support;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    // ------------------------------------------------------------------ estado por sucursal

    /** Estado de la integración en cada sucursal accesible del alcance. */
    @Transactional(readOnly = true)
    public List<PosIntegrationDto> status() {
        Long tenantId = CurrentUser.tenantId();
        List<Long> branchIds = support.scope();
        Instant since = clock.instant().minus(24, ChronoUnit.HOURS);

        Map<Long, long[]> stats = new LinkedHashMap<>();
        jdbc.query("""
                select m.branch_id                        as branch_id,
                       count(distinct m.batch_ref)        as sales,
                       coalesce(sum(m.quantity), 0)       as units
                from stock_movements m
                where m.tenant_id = :tenantId
                  and m.branch_id in (:branchIds)
                  and m.type = 'SALE'
                  and m.source = 'POS'
                  and m.occurred_at >= :since
                group by m.branch_id
                """, new MapSqlParameterSource().addValue("tenantId", tenantId)
                .addValue("branchIds", branchIds).addValue("since", java.sql.Timestamp.from(since)),
                (rs, i) -> stats.put(rs.getLong("branch_id"),
                        new long[] {rs.getLong("sales"), rs.getLong("units")}));

        List<PosIntegrationDto> result = new ArrayList<>();
        for (Branch branch : branchRepository.findByTenantIdAndIdIn(tenantId, branchIds)) {
            Instant lastSaleAt = movementRepository
                    .findFirstByTenantIdAndBranchIdAndSourceAndTypeOrderByOccurredAtDesc(tenantId, branch.getId(),
                            MovementSource.POS, MovementType.SALE)
                    .map(StockMovement::getOccurredAt).orElse(null);
            long[] counters = stats.getOrDefault(branch.getId(), new long[] {0, 0});
            result.add(new PosIntegrationDto(branch.getId(), branch.getName(), branch.getCode(),
                    branch.hasPosApiKey(), branch.getPosApiKeyPrefix(), branch.getPosApiKeyCreatedAt(), lastSaleAt,
                    counters[0], counters[1]));
        }
        result.sort((a, b) -> a.branchName().compareToIgnoreCase(b.branchName()));
        return result;
    }

    /** Genera (o regenera) la API key de una sucursal. La key en claro se devuelve una sola vez. */
    @Transactional
    public PosApiKeyDto generateKey(Long branchId) {
        Long tenantId = CurrentUser.tenantId();
        branchAccessService.assertAccess(branchId);
        Branch branch = branchRepository.findByIdAndTenantId(branchId, tenantId)
                .orElseThrow(() -> new NotFoundException("No encontramos la sucursal #" + branchId));

        GeneratedApiKey generated = apiKeyService.generate();
        Instant createdAt = clock.instant();
        branch.setPosApiKeyHash(generated.hash());
        branch.setPosApiKeyPrefix(generated.prefix());
        branch.setPosApiKeyCreatedAt(createdAt);
        branchRepository.save(branch);

        return new PosApiKeyDto(branch.getId(), branch.getName(), generated.rawKey(), generated.prefix(), createdAt);
    }

    // ------------------------------------------------------------------ simulador

    /**
     * Genera ventas aleatorias realistas en una sucursal por el mismo camino que el webhook: productos con
     * stock vendible, 1 a 4 líneas y 1 a 3 unidades por línea.
     */
    public SimulateResultDto simulate(Long branchId, int sales) {
        Long tenantId = CurrentUser.tenantId();
        Long branch = branchAccessService.requireSingleBranch(branchId);

        List<Long> candidates = jdbc.queryForList("""
                select distinct l.product_id from lots l
                join products p on p.id = l.product_id
                where l.tenant_id = :tenantId
                  and l.branch_id = :branchId
                  and l.status = 'ACTIVE'
                  and l.quantity > 0
                  and p.active = true
                  and (l.expiry_date is null or l.expiry_date >= current_date)
                """, new MapSqlParameterSource().addValue("tenantId", tenantId).addValue("branchId", branch),
                Long.class);
        if (candidates.isEmpty()) {
            throw new ConflictException(ErrorCodes.CONFLICT,
                    "No hay productos con stock vendible en esta sucursal: cargá mercadería antes de simular ventas");
        }

        Map<Long, Product> products = new LinkedHashMap<>();
        productRepository.findByTenantIdAndIdIn(tenantId, candidates).forEach(p -> products.put(p.getId(), p));

        int lines = 0;
        int units = 0;
        int shortages = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<String> batchRefs = new ArrayList<>();

        for (int i = 0; i < sales; i++) {
            List<Long> shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled, random);
            int lineCount = 1 + random.nextInt(Math.min(SIMULATOR_MAX_LINES, shuffled.size()));
            List<Long> chosen = new ArrayList<>(shuffled.subList(0, lineCount));
            Collections.sort(chosen);

            String batchRef = BatchRefs.sale(clock);
            Instant occurredAt = clock.instant().minusSeconds(random.nextInt(8 * 3600));
            boolean any = false;
            for (Long productId : chosen) {
                int quantity = 1 + random.nextInt(SIMULATOR_MAX_UNITS);
                try {
                    SaleResult result = stockService.registerSale(new SaleCommand(tenantId, branch, productId,
                            quantity, null, occurredAt, MovementSource.POS, CurrentUser.id(), batchRef));
                    lines++;
                    units += quantity;
                    shortages += result.shortageQuantity();
                    total = total.add(result.totalAmount() == null ? BigDecimal.ZERO : result.totalAmount());
                    any = true;
                } catch (ApiException ignored) {
                    // producto sin stock o dado de baja: la simulación sigue con los demás
                }
            }
            if (any) {
                batchRefs.add(batchRef);
            }
        }

        Map<Long, String> branchNames = support.branchNames();
        return new SimulateResultDto(branch, support.branchName(branchNames, branch), batchRefs.size(), lines, units,
                total, shortages, batchRefs);
    }

    // ------------------------------------------------------------------ webhook

    /**
     * Procesa una venta del POS del cliente. La sucursal y el comercio ya vienen resueltos por la API key;
     * el módulo {@code POS_INTEGRATION} lo chequea el controlador (el interceptor no llega al webhook).
     */
    @Transactional
    public PosWebhookResponse receive(Long tenantId, Long branchId, PosWebhookRequest request) {
        String batchRef = batchRef(tenantId, request.externalId());
        Instant occurredAt = resolveOccurredAt(request.occurredAt());

        Map<String, Integer> quantities = new LinkedHashMap<>();
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        for (PosSaleItemRequest item : request.items()) {
            String barcode = Barcodes.normalize(item.barcode());
            if (barcode == null) {
                continue;
            }
            quantities.merge(barcode, item.quantity(), Integer::sum);
            if (item.unitPrice() != null) {
                prices.putIfAbsent(barcode, item.unitPrice());
            }
        }
        if (quantities.isEmpty()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "Ningún ítem trae un código de barras válido");
        }

        Map<String, Product> products = new LinkedHashMap<>();
        productRepository.findByTenantIdAndBarcodeIn(tenantId, quantities.keySet())
                .forEach(product -> products.put(product.getBarcode(), product));

        List<String> unknown = new ArrayList<>();
        Map<Long, String> byProduct = new LinkedHashMap<>();
        for (String barcode : quantities.keySet()) {
            Product product = products.get(barcode);
            if (product == null || !product.isActive()) {
                unknown.add(barcode);
            } else {
                byProduct.put(product.getId(), barcode);
            }
        }

        List<Long> orderedProductIds = new ArrayList<>(byProduct.keySet());
        Collections.sort(orderedProductIds);

        int processed = 0;
        int units = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<ShortageDto> shortages = new ArrayList<>();
        for (Long productId : orderedProductIds) {
            String barcode = byProduct.get(productId);
            int quantity = quantities.get(barcode);
            SaleResult result = stockService.registerSale(new SaleCommand(tenantId, branchId, productId, quantity,
                    prices.get(barcode), occurredAt, MovementSource.POS, null, batchRef));
            processed++;
            units += quantity;
            total = total.add(result.totalAmount() == null ? BigDecimal.ZERO : result.totalAmount());
            if (result.shortageQuantity() > 0) {
                shortages.add(new ShortageDto(barcode, products.get(barcode).getName(), result.shortageQuantity()));
            }
        }

        if (processed == 0) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "Ninguno de los códigos enviados corresponde a un producto activo del comercio");
        }
        return new PosWebhookResponse(batchRef, processed, units, total, unknown, shortages);
    }

    /**
     * {@code batchRef} de la venta. Con {@code externalId} es determinístico para que el reintento del POS no
     * duplique la venta: si ya existe → 409 {@code DUPLICATE_SALE}.
     */
    private String batchRef(Long tenantId, String externalId) {
        if (externalId == null || externalId.isBlank()) {
            return BatchRefs.sale(clock);
        }
        String clean = EXTERNAL_ID_CLEAN.matcher(externalId.strip()).replaceAll("").toUpperCase();
        if (clean.isEmpty()) {
            return BatchRefs.sale(clock);
        }
        String candidate = EXTERNAL_PREFIX + clean;
        String batchRef = candidate.length() > MAX_BATCH_REF ? candidate.substring(0, MAX_BATCH_REF) : candidate;
        if (movementRepository.existsByTenantIdAndBatchRef(tenantId, batchRef)) {
            throw new ConflictException("DUPLICATE_SALE", "Ya registramos una venta con el externalId «"
                    + externalId + "»");
        }
        return batchRef;
    }

    private Instant resolveOccurredAt(Instant requested) {
        Instant now = clock.instant();
        return requested == null || requested.isAfter(now) ? now : requested;
    }
}
