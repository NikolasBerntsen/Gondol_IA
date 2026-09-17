package com.gondolia.it;

import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Inserta datos de prueba con SQL directo (sin pasar por los servicios bajo prueba). Nombres, emails y códigos de
 * barras llevan un sufijo aleatorio para no chocar con otros datos de la base.
 */
public final class TestData {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final JdbcTemplate jdbc;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    public TestData(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long tenant(String name, String businessType, String plan, String status, String rotation) {
        long id = jdbc.queryForObject("""
                insert into tenants (name, business_type, plan, status) values (?, ?, ?, ?) returning id
                """, Long.class, name + " " + suffix, businessType, plan, status);
        jdbc.update("insert into tenant_settings (tenant_id, stock_rotation) values (?, ?)", id, rotation);
        return id;
    }

    public long tenant(String name) {
        return tenant(name, "ALMACEN", "PROFESIONAL", "ACTIVE", "FIFO");
    }

    public void rotation(long tenantId, String rotation) {
        jdbc.update("update tenant_settings set stock_rotation = ? where tenant_id = ?", rotation, tenantId);
    }

    public long branch(long tenantId, String name, boolean active) {
        return jdbc.queryForObject("""
                insert into branches (tenant_id, name, code, active) values (?, ?, ?, ?) returning id
                """, Long.class, tenantId, name, name.substring(0, Math.min(3, name.length())).toUpperCase(), active);
    }

    public AuthUser user(Long tenantId, Role role, boolean active, long... branchIds) {
        String email = role.name().toLowerCase() + "." + SEQUENCE.incrementAndGet() + "." + suffix + "@test.gondolia";
        long id = jdbc.queryForObject("""
                insert into users (tenant_id, email, password_hash, full_name, role, active)
                values (?, ?, 'x', ?, ?, ?) returning id
                """, Long.class, tenantId, email, "Usuario " + role.name(), role.name(), active);
        for (long branchId : branchIds) {
            jdbc.update("insert into user_branches (user_id, branch_id) values (?, ?)", id, branchId);
        }
        return new AuthUser(id, email, "Usuario " + role.name(), role, tenantId);
    }

    public String barcode() {
        long number = Math.abs(UUID.randomUUID().getMostSignificantBits()) % 1_000_000_000L;
        String base = "779" + String.format("%09d", number);
        int sum = 0;
        for (int i = 0; i < base.length(); i++) {
            int digit = base.charAt(i) - '0';
            sum += i % 2 == 0 ? digit : digit * 3;
        }
        return base + ((10 - sum % 10) % 10);
    }

    public long product(long tenantId, String barcode, String name, String cost, String sale) {
        return jdbc.queryForObject("""
                insert into products (tenant_id, barcode, name, cost_price, sale_price, min_stock)
                values (?, ?, ?, ?::numeric, ?::numeric, 5) returning id
                """, Long.class, tenantId, barcode, name, cost, sale);
    }

    public long supplier(long tenantId, String name) {
        return jdbc.queryForObject("insert into suppliers (tenant_id, name) values (?, ?) returning id", Long.class,
                tenantId, name);
    }

    /** Lote insertado directamente (sin chequeo de recall), con {@code received_at = now() - daysAgo}. */
    public long lot(long tenantId, long branchId, long productId, String lotNumber, String normalized,
                    LocalDate expiry, int quantity, String status, int receivedDaysAgo) {
        return jdbc.queryForObject("""
                insert into lots (tenant_id, branch_id, product_id, lot_number, lot_number_normalized, expiry_date,
                                  initial_quantity, quantity, status, received_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, now() - make_interval(days => ?)) returning id
                """, Long.class, tenantId, branchId, productId, lotNumber, normalized,
                expiry == null ? null : Date.valueOf(expiry), quantity, quantity, status, receivedDaysAgo);
    }

    /** Recall con los lotes indicados ({@code status} PUBLISHED o ARCHIVED). */
    public long recall(String barcode, boolean allLots, LocalDate expiryFrom, LocalDate expiryTo, String status,
                       String... lotNumbers) {
        long id = jdbc.queryForObject("""
                insert into announcements (kind, severity, status, title, body, recall_product_name, recall_barcode,
                                           recall_all_lots, recall_expiry_from, recall_expiry_to, recall_reason,
                                           recall_instructions, published_at)
                values ('RECALL', 'CRITICAL', ?, ?, 'Aviso de retiro', 'Sopa de tomate', ?, ?, ?, ?,
                        'Posible contaminación', 'Retirá el producto de la góndola', now())
                returning id
                """, Long.class, status, "Recall de prueba " + suffix, barcode, allLots,
                expiryFrom == null ? null : Date.valueOf(expiryFrom), expiryTo == null ? null : Date.valueOf(expiryTo));
        for (String lotNumber : lotNumbers) {
            jdbc.update("""
                    insert into announcement_recall_lots (announcement_id, lot_number, lot_number_normalized)
                    values (?, ?, ?)
                    """, id, lotNumber, com.gondolia.common.util.LotNumbers.normalize(lotNumber));
        }
        return id;
    }

    public String suffix() {
        return suffix;
    }
}
