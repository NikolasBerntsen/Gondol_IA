package com.gondolia.pos;

import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.util.Barcodes;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.ProductUnit;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.pos.dto.PosCategoryDto;
import com.gondolia.pos.dto.PosLotRef;
import com.gondolia.pos.dto.PosProductDto;
import com.gondolia.security.BranchAccessService;
import com.gondolia.stock.StockService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mostrador del POS: buscar productos por código, nombre o marca y responder con el stock, el próximo lote y las
 * banderas que bloquean o avisan al cajero (SPEC §15.2).
 * <p>
 * Todo se resuelve sobre <b>una</b> sucursal (la del turno): se toma del parámetro {@code branchId}, del encabezado
 * {@code X-Branch-Id} o de la única sucursal accesible ({@code BranchAccessService.requireSingleBranch}).
 */
@Service
@RequiredArgsConstructor
public class PosCatalogService {

    /** Máximo de resultados del buscador (SPEC §15.2: top 20). */
    public static final int MAX_RESULTS = 20;

    private static final String ACCENTS = "áéíóúüñÁÉÍÓÚÜÑ";
    private static final String PLAIN = "aeiouunAEIOUUN";

    private final ProductRepository productRepository;
    private final PosStockQueries stockQueries;
    private final StockService stockService;
    private final BranchAccessService branchAccess;
    private final PosDirectory directory;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    /** Sucursal sobre la que opera el POS (obligatoria: las ventas y el stock son por sucursal). */
    public Long resolveBranch(Long explicitBranchId) {
        return branchAccess.requireSingleBranch(explicitBranchId);
    }

    /** Producto por código de barras exacto (lector USB o cámara). 404 si no existe o está dado de baja. */
    @Transactional(readOnly = true)
    public PosProductDto lookup(Long tenantId, Long branchId, String rawCode) {
        String code = Barcodes.normalize(rawCode);
        if (code == null || code.isBlank()) {
            throw new NotFoundException("Escaneá o escribí un código de barras.");
        }
        Product product = productRepository.findByTenantIdAndBarcode(tenantId, code)
                .filter(Product::isActive)
                .orElseThrow(() -> new NotFoundException(
                        "No encontramos ningún producto con el código " + code + "."));
        return enrich(tenantId, branchId, rowsByIds(tenantId, List.of(product.getId()))).getFirst();
    }

    private List<Row> rowsByIds(Long tenantId, List<Long> ids) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("ids", ids);
        return jdbc.query("""
                select p.id as id, p.barcode as barcode, p.name as name, p.brand as brand,
                       p.category_id as category_id, c.name as category_name, p.unit as unit,
                       p.sale_price as sale_price
                  from products p
                  left join categories c on c.id = p.category_id and c.tenant_id = p.tenant_id
                 where p.tenant_id = :tenantId and p.id in (:ids)
                 order by p.name
                """, params, (rs, index) -> new Row(rs.getLong("id"), rs.getString("barcode"), rs.getString("name"),
                rs.getString("brand"), (Long) rs.getObject("category_id"), rs.getString("category_name"),
                ProductUnit.valueOf(rs.getString("unit")), rs.getBigDecimal("sale_price")));
    }

    /**
     * Búsqueda del mostrador: código exacto primero, después los nombres que empiezan con el texto y el resto.
     * Sin texto devuelve los primeros productos por nombre (mosaicos iniciales).
     */
    @Transactional(readOnly = true)
    public List<PosProductDto> search(Long tenantId, Long branchId, String query, Long categoryId, int limit) {
        String text = query == null ? "" : query.strip();
        String code = Barcodes.normalize(text);
        String like = "%" + plain(text) + "%";
        String prefix = plain(text) + "%";

        StringBuilder sql = new StringBuilder("""
                select p.id as id, p.barcode as barcode, p.name as name, p.brand as brand,
                       p.category_id as category_id, c.name as category_name, p.unit as unit,
                       p.sale_price as sale_price
                  from products p
                  left join categories c on c.id = p.category_id and c.tenant_id = p.tenant_id
                 where p.tenant_id = :tenantId and p.active
                """);
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("tenantId", tenantId);
        if (categoryId != null) {
            sql.append(" and p.category_id = :categoryId");
            params.addValue("categoryId", categoryId);
        }
        if (!text.isEmpty()) {
            sql.append("""
                     and (p.barcode = :code
                          or translate(lower(p.name), :accents, :plain) like :like
                          or translate(lower(coalesce(p.brand, '')), :accents, :plain) like :like)
                    """);
            params.addValue("code", code == null ? "" : code)
                    .addValue("like", like)
                    .addValue("accents", ACCENTS)
                    .addValue("plain", PLAIN);
            sql.append("""
                     order by case when p.barcode = :code then 0
                                   when translate(lower(p.name), :accents, :plain) like :prefix then 1
                                   else 2 end,
                              p.name
                    """);
            params.addValue("prefix", prefix);
        } else {
            sql.append(" order by p.name");
        }
        sql.append(" limit :limit");
        params.addValue("limit", Math.min(Math.max(limit, 1), MAX_RESULTS));

        List<Row> rows = jdbc.query(sql.toString(), params, (rs, index) -> new Row(rs.getLong("id"),
                rs.getString("barcode"), rs.getString("name"), rs.getString("brand"),
                (Long) rs.getObject("category_id"), rs.getString("category_name"),
                ProductUnit.valueOf(rs.getString("unit")), rs.getBigDecimal("sale_price")));
        return enrich(tenantId, branchId, rows);
    }

    /** Categorías con stock vendible en la sucursal (filtros rápidos del mostrador). */
    @Transactional(readOnly = true)
    public List<PosCategoryDto> categories(Long tenantId, Long branchId) {
        return stockQueries.categoriesWithStock(tenantId, branchId, today()).stream()
                .map(row -> new PosCategoryDto(row.id(), row.name(), row.productCount()))
                .toList();
    }

    /** Stock de un producto en la sucursal (lo usa la venta para validar antes de descontar). */
    @Transactional(readOnly = true)
    public Map<Long, PosStockQueries.BranchStock> stockOf(Long tenantId, Long branchId, Collection<Long> productIds) {
        return stockQueries.stockByProduct(tenantId, branchId, productIds, today());
    }

    private List<PosProductDto> enrich(Long tenantId, Long branchId, List<Row> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        LocalDate today = today();
        List<Long> ids = rows.stream().map(Row::id).toList();
        Map<Long, PosStockQueries.BranchStock> stock = stockQueries.stockByProduct(tenantId, branchId, ids, today);
        StockRotation rotation = stockService.rotationFor(tenantId);
        Map<Long, PosStockQueries.NextLot> nextLots =
                stockQueries.nextLotByProduct(tenantId, branchId, ids, today, rotation);
        String branchName = directory.branchNames(tenantId).get(branchId);

        List<PosProductDto> products = new ArrayList<>(rows.size());
        for (Row row : rows) {
            PosStockQueries.BranchStock counts = stock.getOrDefault(row.id(), PosStockQueries.BranchStock.EMPTY);
            PosStockQueries.NextLot lot = nextLots.get(row.id());
            BigDecimal listPrice = PosMoney.orZero(row.salePrice());
            PosLotRef lotRef = lot == null ? null : new PosLotRef(lot.lotId(), lot.lotNumber(), lot.expiryDate(),
                    activeDiscount(lot.discountPct()), PosMoney.withDiscount(listPrice, lot.discountPct()));
            products.add(new PosProductDto(row.id(), row.barcode(), row.name(), row.brand(), row.categoryId(),
                    row.categoryName(), row.unit(), listPrice, counts.sellable(), lotRef, counts.quarantined() > 0,
                    counts.expired() > 0, counts.sellable() <= 0, branchId, branchName));
        }
        return products;
    }

    private static BigDecimal activeDiscount(BigDecimal discountPct) {
        return discountPct == null || discountPct.signum() <= 0 ? null : discountPct;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static String plain(String text) {
        String lower = text.toLowerCase();
        StringBuilder out = new StringBuilder(lower.length());
        for (char character : lower.toCharArray()) {
            int index = ACCENTS.indexOf(character);
            out.append(index < 0 ? character : PLAIN.charAt(index));
        }
        return out.toString();
    }

    private record Row(Long id, String barcode, String name, String brand, Long categoryId, String categoryName,
                       ProductUnit unit, BigDecimal salePrice) {
    }
}
