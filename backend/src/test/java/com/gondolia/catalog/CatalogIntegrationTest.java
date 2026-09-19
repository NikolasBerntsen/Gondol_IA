package com.gondolia.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.gondolia.catalog.dto.BranchStockDto;
import com.gondolia.catalog.dto.LotDto;
import com.gondolia.catalog.dto.LotRequest;
import com.gondolia.catalog.dto.LotUpdateRequest;
import com.gondolia.catalog.dto.ProductDetail;
import com.gondolia.catalog.dto.ProductListItem;
import com.gondolia.catalog.dto.ProductMovementDto;
import com.gondolia.catalog.dto.ProductRequest;
import com.gondolia.catalog.dto.ReceiveLotResponse;
import com.gondolia.common.PageResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.domain.inventory.LotStatus;
import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;
import com.gondolia.domain.inventory.ProductUnit;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.stock.StockService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Catálogo y carga de mercadería contra PostgreSQL: stock por sucursal, orden de rotación, aviso de FIFO y
 * <b>aislamiento</b> entre comercios y entre sucursales (SPEC §3.4, §3.5, §4.2 y §6.3). Cada prueba se revierte.
 */
@Transactional
class CatalogIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private ProductService productService;
    @Autowired
    private LotService lotService;
    @Autowired
    private ProductMovementService productMovementService;
    @Autowired
    private CategoryService categoryService;
    @Autowired
    private SupplierService supplierService;
    @Autowired
    private StockService stockService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;

    private TestData data;
    private long tenant;
    private long otherTenant;
    private long centro;
    private long norte;
    private long yogurId;
    private long lecheId;
    private AuthUser admin;
    private AuthUser employee;
    private AuthUser otherAdmin;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        today = LocalDate.now(clock);

        tenant = data.tenant("Comercio A1");
        centro = data.branch(tenant, "Sucursal Centro", true);
        norte = data.branch(tenant, "Sucursal Norte", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        employee = data.user(tenant, Role.TENANT_EMPLOYEE, true, centro);

        otherTenant = data.tenant("Otro Comercio A1");
        long otherBranch = data.branch(otherTenant, "Sucursal Única", true);
        otherAdmin = data.user(otherTenant, Role.TENANT_ADMIN, true, otherBranch);

        yogurId = data.product(tenant, data.barcode(), "Yogur bebible frutilla 1 L", "1400", "2100");
        lecheId = data.product(tenant, data.barcode(), "Leche entera 1 L", "950", "1400");
        jdbc.update("update products set min_stock = 20 where id = ?", lecheId);

        // Centro: el lote viejo vence después que el nuevo (caso del aviso de FIFO).
        data.lot(tenant, centro, yogurId, "YV0925", "YV0925", today.plusDays(25), 15, "ACTIVE", 10);
        data.lot(tenant, centro, yogurId, "YV0930", "YV0930", today.plusDays(8), 18, "ACTIVE", 2);
        // Norte: un solo lote.
        data.lot(tenant, norte, yogurId, "YV0926", "YV0926", today.plusDays(12), 10, "ACTIVE", 5);
        // Leche: stock solo en Centro, por debajo del mínimo.
        data.lot(tenant, centro, lecheId, "LP01", "LP01", today.plusDays(4), 6, "ACTIVE", 1);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ------------------------------------------------------------------ lecturas y alcance

    @Test
    void el_inventario_suma_el_stock_del_alcance_y_detalla_cada_sucursal() {
        authenticate(admin, null);

        PageResponse<ProductListItem> page = productService.list(query(null, null));

        ProductListItem yogur = row(page, yogurId);
        assertThat(yogur.sellableStock()).isEqualTo(43);
        assertThat(yogur.lotsCount()).isEqualTo(3);
        assertThat(yogur.nextExpiryDate()).isEqualTo(today.plusDays(8));
        assertThat(yogur.stockByBranch())
                .extracting(BranchStockDto::branchName, BranchStockDto::sellableStock, BranchStockDto::stockStatus)
                .containsExactly(
                        tuple("Sucursal Centro", 33, StockStatuses.OK),
                        tuple("Sucursal Norte", 10, StockStatuses.OK));
    }

    @Test
    void el_estado_consolidado_es_el_peor_de_las_sucursales_que_manejan_el_producto() {
        // Norte también trabaja la leche pero se le agotó el último lote.
        data.lot(tenant, norte, lecheId, "LN01", "LN01", today.plusDays(2), 0, "DEPLETED", 60);
        authenticate(admin, null);

        ProductListItem leche = row(productService.list(query(null, null)), lecheId);

        // Centro tiene 6 u. con mínimo 20 (LOW) y Norte se quedó sin stock (OUT): el consolidado es OUT.
        assertThat(leche.sellableStock()).isEqualTo(6);
        assertThat(leche.stockStatus()).isEqualTo(StockStatuses.OUT);
        assertThat(leche.stockByBranch())
                .extracting(BranchStockDto::branchName, BranchStockDto::stockStatus)
                .containsExactly(tuple("Sucursal Centro", StockStatuses.LOW),
                        tuple("Sucursal Norte", StockStatuses.OUT));
    }

    @Test
    void una_sucursal_que_nunca_tuvo_el_producto_no_lo_deja_sin_stock() {
        authenticate(admin, null);

        // La leche solo se trabaja en Centro (6 u., mínimo 20): en "todas" es LOW, igual que en el Inicio.
        ProductListItem leche = row(productService.list(query(null, null)), lecheId);
        assertThat(leche.stockStatus()).isEqualTo(StockStatuses.LOW);
        assertThat(leche.stockByBranch())
                .extracting(BranchStockDto::branchName, BranchStockDto::sellableStock, BranchStockDto::stockStatus)
                .containsExactly(tuple("Sucursal Centro", 6, StockStatuses.LOW));
        assertThat(productService.list(query(null, StockStatuses.OUT)).content())
                .extracting(ProductListItem::id).doesNotContain(lecheId);
        assertThat(productService.list(query(null, StockStatuses.LOW)).content())
                .extracting(ProductListItem::id).contains(lecheId);
    }

    @Test
    void un_producto_sin_lotes_en_el_alcance_queda_sin_stock() {
        long nuevo = data.product(tenant, data.barcode(), "Manteca 200 g", "900", "1500");

        authenticate(admin, null);
        ProductListItem manteca = row(productService.list(query(null, null)), nuevo);
        assertThat(manteca.stockStatus()).isEqualTo(StockStatuses.OUT);
        assertThat(manteca.stockByBranch()).isEmpty();

        // En Norte la leche no se trabaja: no hay stock ahí, pero tampoco una fila de sucursal inventada.
        authenticate(admin, String.valueOf(norte));
        ProductListItem lecheNorte = row(productService.list(query(null, null)), lecheId);
        assertThat(lecheNorte.sellableStock()).isZero();
        assertThat(lecheNorte.stockStatus()).isEqualTo(StockStatuses.OUT);
        assertThat(lecheNorte.stockByBranch()).isEmpty();
    }

    @Test
    void al_elegir_una_sucursal_solo_se_informa_esa() {
        authenticate(admin, String.valueOf(norte));

        ProductListItem yogur = row(productService.list(query(null, null)), yogurId);

        assertThat(yogur.sellableStock()).isEqualTo(10);
        assertThat(yogur.stockByBranch()).extracting(BranchStockDto::branchName)
                .containsExactly("Sucursal Norte");
    }

    @Test
    void los_filtros_de_texto_y_de_stock_acotan_el_listado() {
        data.lot(tenant, norte, lecheId, "LN01", "LN01", today.plusDays(2), 0, "DEPLETED", 60);
        authenticate(admin, null);

        assertThat(productService.list(query("yogur", null)).content())
                .extracting(ProductListItem::id).containsExactly(yogurId);
        assertThat(productService.list(query(null, StockStatuses.OUT)).content())
                .extracting(ProductListItem::id).containsExactly(lecheId);
        assertThat(productService.list(query(null, ProductQuery.STATUS_EXPIRING)).content())
                .extracting(ProductListItem::id).contains(yogurId, lecheId);
    }

    @Test
    void la_ficha_ordena_los_lotes_por_sucursal_y_en_orden_de_rotacion() {
        authenticate(admin, null);

        ProductDetail detail = productService.get(yogurId);

        // Con FIFO manda la fecha de ingreso: el lote más viejo sale primero aunque venza después.
        assertThat(detail.lots())
                .extracting(LotDto::branchName, LotDto::lotNumber, LotDto::rotationRank)
                .containsExactly(
                        tuple("Sucursal Centro", "YV0925", 1),
                        tuple("Sucursal Centro", "YV0930", 2),
                        tuple("Sucursal Norte", "YV0926", 1));
    }

    @Test
    void un_lote_en_liquidacion_se_vende_primero() {
        jdbc.update("""
                update lots set discount_pct = 20, discount_started_at = now()
                 where product_id = ? and lot_number = ?
                """, yogurId, "YV0930");
        authenticate(admin, String.valueOf(centro));

        ProductDetail detail = productService.get(yogurId);

        assertThat(detail.lots())
                .extracting(LotDto::lotNumber, LotDto::rotationRank)
                .containsExactly(tuple("YV0930", 1), tuple("YV0925", 2));
    }

    @Test
    void los_lotes_vencidos_y_en_cuarentena_no_tienen_orden_de_rotacion() {
        data.lot(tenant, centro, yogurId, "VENCIDO", "VENCIDO", today.minusDays(3), 4, "ACTIVE", 20);
        data.lot(tenant, centro, yogurId, "RETIRADO", "RETIRADO", today.plusDays(40), 5, "RECALLED", 1);
        authenticate(admin, String.valueOf(centro));

        ProductDetail detail = productService.get(yogurId);

        assertThat(detail.expiredStock()).isEqualTo(4);
        assertThat(detail.quarantinedStock()).isEqualTo(5);
        assertThat(detail.sellableStock()).isEqualTo(33);
        assertThat(detail.lots()).filteredOn(lot -> lot.rotationRank() == null)
                .extracting(LotDto::lotNumber)
                .containsExactlyInAnyOrder("VENCIDO", "RETIRADO");
    }

    @Test
    void la_ficha_muestra_el_lote_retirado_hoy_aunque_haya_ingresado_hace_meses() {
        // Lote viejo retirado hoy por un recall: quedó RECALLED con 0 u.
        long retirado = data.lot(tenant, centro, lecheId, "L2409A", "L2409A", today.plusDays(20), 0, "RECALLED", 90);
        movement(centro, lecheId, retirado, "RECALL_REMOVAL", 8, null, null, null, 0);
        // Lote viejo descartado por vencido hace una semana: también sigue a la vista.
        long descartado = data.lot(tenant, centro, lecheId, "LDESC", "LDESC", today.minusDays(9), 0,
                "EXPIRED_DISCARDED", 70);
        movement(centro, lecheId, descartado, "WASTE_EXPIRED", 2, null, null, null, 7);
        // Lote viejo que se agotó hace dos meses: ya no es reciente.
        long agotado = data.lot(tenant, centro, lecheId, "LVIEJO", "LVIEJO", today.minusDays(30), 0, "DEPLETED", 120);
        movement(centro, lecheId, agotado, "SALE", 3, "1400", "4200", "S-VIEJA", 60);
        // Lote viejo que se terminó de vender hace días: vender no lo vuelve "reciente" (si no, la ficha de un
        // producto de alta rotación se llena de lotes agotados).
        long vendido = data.lot(tenant, centro, lecheId, "LVEND", "LVEND", today.plusDays(10), 0, "DEPLETED", 45);
        movement(centro, lecheId, vendido, "SALE", 5, "1400", "7000", "S-RECIENTE", 3);
        authenticate(admin, String.valueOf(centro));

        ProductDetail detail = productService.get(lecheId);

        assertThat(detail.lots()).extracting(LotDto::lotNumber, LotDto::status, LotDto::quantity)
                .contains(tuple("L2409A", LotStatus.RECALLED, 0), tuple("LDESC", LotStatus.EXPIRED_DISCARDED, 0));
        assertThat(detail.lots()).extracting(LotDto::lotNumber).doesNotContain("LVIEJO", "LVEND");
    }

    // ------------------------------------------------------------------ movimientos de la ficha

    @Test
    void el_empleado_ve_los_movimientos_del_producto_sin_los_importes_de_la_venta() {
        long lot = jdbc.queryForObject("select id from lots where product_id = ? and lot_number = ?", Long.class,
                lecheId, "LP01");
        movement(centro, lecheId, lot, "SALE", 2, "1400", "2800", "P-000123", 0);

        authenticate(employee, null);
        List<ProductMovementDto> forEmployee = productMovementService.recent(lecheId, 10);
        assertThat(forEmployee).singleElement().satisfies(movement -> {
            assertThat(movement.type()).isEqualTo(MovementType.SALE);
            assertThat(movement.quantity()).isEqualTo(2);
            assertThat(movement.lotNumber()).isEqualTo("LP01");
            assertThat(movement.unitPrice()).isNull();
            assertThat(movement.totalAmount()).isNull();
            assertThat(movement.discountPct()).isNull();
            assertThat(movement.batchRef()).isNull();
        });

        authenticate(admin, null);
        assertThat(productMovementService.recent(lecheId, 10)).singleElement().satisfies(movement -> {
            assertThat(movement.unitPrice()).isEqualByComparingTo("1400");
            assertThat(movement.totalAmount()).isEqualByComparingTo("2800");
            assertThat(movement.batchRef()).isEqualTo("P-000123");
        });
    }

    // ------------------------------------------------------------------ carga de mercadería

    @Test
    void la_carga_crea_un_lote_nuevo_y_devuelve_los_que_ya_estaban() {
        authenticate(employee, null); // el empleado tiene una sola sucursal: no hace falta el encabezado

        ReceiveLotResponse response = lotService.receive(new LotRequest(null, yogurId, "l-2409/a ",
                today.plusDays(40), 24, new BigDecimal("1450"), null, MovementSource.SCAN));

        assertThat(response.lot().branchName()).isEqualTo("Sucursal Centro");
        assertThat(response.lot().quantity()).isEqualTo(24);
        assertThat(response.lot().rotationRank()).isEqualTo(3);
        assertThat(response.quarantined()).isFalse();
        assertThat(response.rotationWarning()).isNull();
        assertThat(response.existingLots())
                .extracting(LotDto::lotNumber, LotDto::rotationRank)
                .containsExactly(tuple("YV0925", 1), tuple("YV0930", 2));
        assertThat(jdbc.queryForObject("select lot_number_normalized from lots where id = ?", String.class,
                response.lot().id())).isEqualTo("L2409A");
    }

    @Test
    void avisa_cuando_el_lote_nuevo_vence_antes_que_mercaderia_mas_vieja() {
        authenticate(employee, null);

        ReceiveLotResponse response = lotService.receive(new LotRequest(null, yogurId, "NUEVO",
                today.plusDays(3), 6, null, null, MovementSource.MANUAL));

        assertThat(response.rotationWarning()).isEqualTo(StockService.ROTATION_WARNING);
        assertThat(response.lot().expiryBucket()).isEqualTo(ExpiryBuckets.CRITICAL);
    }

    @Test
    void con_alcance_todas_y_mas_de_una_sucursal_pide_elegir_una() {
        authenticate(admin, null);

        assertApiError(() -> lotService.receive(new LotRequest(null, yogurId, "X", today.plusDays(10), 5, null, null,
                MovementSource.MANUAL)), 400, "BRANCH_REQUIRED");
    }

    @Test
    void el_branchId_del_cuerpo_manda_sobre_el_encabezado() {
        authenticate(admin, String.valueOf(centro));

        ReceiveLotResponse response = lotService.receive(new LotRequest(norte, yogurId, "N1", today.plusDays(10), 5,
                null, null, MovementSource.MANUAL));

        assertThat(response.lot().branchName()).isEqualTo("Sucursal Norte");
        assertThat(response.existingLots()).extracting(LotDto::lotNumber).containsExactly("YV0926");
    }

    @Test
    void no_se_carga_mercaderia_de_un_producto_dado_de_baja() {
        jdbc.update("update products set active = false where id = ?", yogurId);
        authenticate(employee, null);

        assertApiError(() -> lotService.receive(new LotRequest(null, yogurId, null, today.plusDays(10), 5, null, null,
                MovementSource.MANUAL)), 400, "VALIDATION_ERROR");
    }

    @Test
    void corregir_un_lote_actualiza_el_numero_normalizado_y_el_bucket() {
        authenticate(employee, null);
        long lotId = jdbc.queryForObject("select id from lots where product_id = ? and lot_number = ?", Long.class,
                yogurId, "YV0930");

        LotDto updated = lotService.update(lotId, new LotUpdateRequest("l 9931/b", today.plusDays(2)));

        assertThat(updated.lotNumber()).isEqualTo("l 9931/b");
        assertThat(updated.expiryBucket()).isEqualTo(ExpiryBuckets.CRITICAL);
        assertThat(jdbc.queryForObject("select lot_number_normalized from lots where id = ?", String.class, lotId))
                .isEqualTo("L9931B");
    }

    // ------------------------------------------------------------------ aislamiento

    @Test
    void un_comercio_no_ve_ni_toca_productos_de_otro() {
        authenticate(otherAdmin, null);

        assertApiError(() -> productService.get(yogurId), 404, "NOT_FOUND");
        assertApiError(() -> productService.update(yogurId, new ProductRequest(null, "Robado", null, null, null, null,
                null, ProductUnit.UNIDAD, null, null, null, null, null)), 404, "NOT_FOUND");
        assertApiError(() -> productService.delete(yogurId), 404, "NOT_FOUND");
        assertThat(productService.list(query(null, null)).content()).isEmpty();
    }

    @Test
    void un_comercio_no_carga_mercaderia_en_la_sucursal_de_otro() {
        authenticate(otherAdmin, String.valueOf(centro));

        // Una sucursal de otro comercio se responde como inexistente: no se revela que existe (SPEC §3.4).
        assertApiError(() -> lotService.receive(new LotRequest(centro, yogurId, "X", today.plusDays(10), 5, null, null,
                MovementSource.MANUAL)), 404, "NOT_FOUND");
    }

    @Test
    void el_empleado_solo_ve_el_stock_de_sus_sucursales() {
        authenticate(employee, null);

        ProductDetail detail = productService.get(yogurId);

        assertThat(detail.sellableStock()).isEqualTo(33);
        assertThat(detail.stockByBranch()).extracting(BranchStockDto::branchName)
                .containsExactly("Sucursal Centro");
        assertThat(detail.lots()).extracting(LotDto::branchName).containsOnly("Sucursal Centro");
    }

    @Test
    void el_empleado_no_puede_pedir_una_sucursal_que_no_tiene_asignada() {
        authenticate(employee, String.valueOf(norte));

        assertApiError(() -> productService.list(query(null, null)), 403, "BRANCH_FORBIDDEN");
    }

    @Test
    void el_empleado_no_puede_corregir_un_lote_de_otra_sucursal() {
        authenticate(employee, null);
        long norteLot = jdbc.queryForObject("select id from lots where product_id = ? and branch_id = ?", Long.class,
                yogurId, norte);

        assertApiError(() -> lotService.update(norteLot, new LotUpdateRequest("X", today.plusDays(5))),
                403, "BRANCH_FORBIDDEN");
    }

    @Test
    void los_lotes_de_un_producto_se_listan_solo_dentro_del_alcance() {
        authenticate(employee, null);

        List<LotDto> lots = lotService.list(yogurId, false);

        assertThat(lots).extracting(LotDto::lotNumber).containsExactly("YV0925", "YV0930");
    }

    // ------------------------------------------------------------------ catálogo

    @Test
    void crear_un_producto_con_categoria_nueva_la_da_de_alta() {
        authenticate(admin, null);

        ProductDetail created = productService.create(new ProductRequest("779 1234 0000 55", "Fideos guiseros 500 g",
                "Doña Rosa", "Paquete de 500 g", null, "Pastas secas", null, ProductUnit.PAQUETE,
                new BigDecimal("800"), new BigDecimal("1250"), 6, false, null));

        assertThat(created.barcode()).isEqualTo("7791234000055");
        assertThat(created.categoryName()).isEqualTo("Pastas secas");
        assertThat(created.stockStatus()).isEqualTo(StockStatuses.OUT);
        assertThat(categoryService.list()).extracting("name").contains("Pastas secas");
    }

    @Test
    void no_se_repite_el_codigo_de_barras_dentro_del_comercio() {
        authenticate(admin, null);
        String barcode = jdbc.queryForObject("select barcode from products where id = ?", String.class, yogurId);

        assertApiError(() -> productService.create(new ProductRequest(barcode, "Copia", null, null, null, null, null,
                ProductUnit.UNIDAD, null, null, null, null, null)), 409, ProductService.DUPLICATE_BARCODE_CODE);
    }

    @Test
    void dos_comercios_pueden_usar_el_mismo_codigo_de_barras() {
        String barcode = jdbc.queryForObject("select barcode from products where id = ?", String.class, yogurId);
        authenticate(otherAdmin, null);

        ProductDetail created = productService.create(new ProductRequest(barcode, "Mismo código, otro comercio", null,
                null, null, null, null, ProductUnit.UNIDAD, null, null, null, null, null));

        assertThat(created.barcode()).isEqualTo(barcode);
    }

    @Test
    void un_producto_con_movimientos_se_desactiva_en_vez_de_borrarse() {
        authenticate(admin, null);

        boolean deactivated = productService.delete(yogurId);

        assertThat(deactivated).isTrue();
        assertThat(productService.get(yogurId).active()).isFalse();
    }

    @Test
    void un_producto_sin_uso_se_borra() {
        authenticate(admin, null);
        ProductDetail nuevo = productService.create(new ProductRequest(null, "Sin uso", null, null, null, null, null,
                ProductUnit.UNIDAD, null, null, null, null, null));

        assertThat(productService.delete(nuevo.id())).isFalse();
        assertApiError(() -> productService.get(nuevo.id()), 404, "NOT_FOUND");
    }

    @Test
    void no_se_borra_una_categoria_con_productos() {
        authenticate(admin, null);
        long categoryId = categoryService.create(new com.gondolia.catalog.dto.CategoryRequest("Lácteos")).id();
        jdbc.update("update products set category_id = ? where id = ?", categoryId, yogurId);

        assertApiError(() -> categoryService.delete(categoryId), 409, "CONFLICT");
    }

    @Test
    void un_proveedor_usado_se_desactiva_en_vez_de_borrarse() {
        authenticate(admin, null);
        long supplierId = supplierService.create(new com.gondolia.catalog.dto.SupplierRequest("La Pampa", null, null,
                null, 3, null, null)).id();
        jdbc.update("update products set supplier_id = ? where id = ?", supplierId, lecheId);

        assertThat(supplierService.delete(supplierId).deactivated()).isTrue();
        assertThat(supplierService.list(true)).filteredOn(s -> s.id().equals(supplierId))
                .singleElement()
                .satisfies(s -> assertThat(s.active()).isFalse());
        assertThat(supplierService.list(false)).extracting("id").doesNotContain(supplierId);
    }

    @Test
    void la_carga_con_un_recall_publicado_deja_el_lote_en_cuarentena() {
        String barcode = jdbc.queryForObject("select barcode from products where id = ?", String.class, yogurId);
        data.recall(barcode, false, null, null, "PUBLISHED", "L2409A");
        authenticate(employee, null);

        ReceiveLotResponse response = lotService.receive(new LotRequest(null, yogurId, "l-2409/a",
                today.plusDays(30), 12, null, null, MovementSource.SCAN));

        assertThat(response.quarantined()).isTrue();
        assertThat(response.lot().status()).isEqualTo(LotStatus.RECALLED);
        assertThat(response.lot().rotationRank()).isNull();
        assertThat(response.recalls()).singleElement()
                .satisfies(recall -> assertThat(recall.reason()).isNotBlank());
        assertThat(response.rotationWarning()).isNull();
    }

    // ------------------------------------------------------------------ helpers

    private void movement(long branchId, long productId, long lotId, String type, int quantity, String unitPrice,
                          String totalAmount, String batchRef, int daysAgo) {
        jdbc.update("""
                insert into stock_movements (tenant_id, branch_id, product_id, lot_id, type, quantity, unit_price,
                                             total_amount, source, batch_ref, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, now() - make_interval(days => ?))
                """, tenant, branchId, productId, lotId, type, quantity,
                unitPrice == null ? null : new BigDecimal(unitPrice),
                totalAmount == null ? null : new BigDecimal(totalAmount), batchRef, daysAgo);
    }

    private static ProductQuery query(String q, String stockStatus) {
        return new ProductQuery(q, null, stockStatus, null, 0, 50, "name,asc");
    }

    private static ProductListItem row(PageResponse<ProductListItem> page, long productId) {
        return page.content().stream().filter(item -> item.id() == productId).findFirst()
                .orElseThrow(() -> new AssertionError("El producto " + productId + " no está en el listado"));
    }

    private static void authenticate(AuthUser user, String branchHeader) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tenant/products");
        if (branchHeader != null) {
            request.addHeader(BranchAccessService.HEADER, branchHeader);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static void assertApiError(ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(status);
            assertThat(ex.getCode()).isEqualTo(code);
            assertThat(ex.getMessage()).isNotBlank();
        });
    }
}
