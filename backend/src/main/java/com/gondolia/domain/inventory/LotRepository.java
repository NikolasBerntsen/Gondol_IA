package com.gondolia.domain.inventory;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lotes. "Vendible" = {@code ACTIVE}, {@code quantity > 0} y ({@code expiryDate} NULL o {@code >= today}); "hoy" se
 * calcula siempre con el {@code Clock} de la aplicación.
 * <p>
 * Orden de rotación (SPEC §4.2): primero los lotes en liquidación (con {@code discount_pct} activo, es decir no nulo y
 * mayor a cero) y dentro de cada grupo FIFO ({@code received_at, id}) o FEFO
 * ({@code expiry_date NULLS LAST, received_at, id}).
 */
public interface LotRepository extends JpaRepository<Lot, Long>, JpaSpecificationExecutor<Lot> {

    /** Cantidad agregada por sucursal y producto. */
    interface BranchProductQuantity {
        Long getBranchId();

        Long getProductId();

        Long getQuantity();
    }

    /** Cantidad agregada por producto. */
    interface ProductQuantity {
        Long getProductId();

        Long getQuantity();
    }

    Optional<Lot> findByIdAndTenantId(Long id, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lot l where l.id = :id and l.tenantId = :tenantId")
    Optional<Lot> findByIdAndTenantIdForUpdate(@Param("id") Long id, @Param("tenantId") Long tenantId);

    List<Lot> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    List<Lot> findByTenantIdAndProductIdOrderByBranchIdAscReceivedAtAscIdAsc(Long tenantId, Long productId);

    List<Lot> findByTenantIdAndBranchIdAndProductIdOrderByReceivedAtAscIdAsc(Long tenantId, Long branchId,
                                                                            Long productId);

    List<Lot> findByTenantIdAndBranchIdInAndProductIdOrderByBranchIdAscReceivedAtAscIdAsc(
            Long tenantId, Collection<Long> branchIds, Long productId);

    List<Lot> findByOriginLotId(Long originLotId);

    boolean existsByBranchIdAndQuantityGreaterThanAndStatusIn(Long branchId, int quantity,
                                                              Collection<LotStatus> statuses);

    /** {@code true} si la sucursal tiene stock físico (lotes ACTIVE o RECALLED con remanente). */
    default boolean hasPhysicalStock(Long branchId) {
        return existsByBranchIdAndQuantityGreaterThanAndStatusIn(branchId, 0,
                EnumSet.of(LotStatus.ACTIVE, LotStatus.RECALLED));
    }

    /** Lotes vendibles de un producto en una sucursal, en orden de rotación FIFO (los descuentos primero). */
    @Query("""
            select l from Lot l
            where l.tenantId = :tenantId
              and l.branchId = :branchId
              and l.productId = :productId
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and l.quantity > 0
              and (l.expiryDate is null or l.expiryDate >= :today)
            order by case when l.discountPct is null or l.discountPct <= 0 then 1 else 0 end asc,
                     l.receivedAt asc, l.id asc
            """)
    List<Lot> findSellableFifo(@Param("tenantId") Long tenantId, @Param("branchId") Long branchId,
                               @Param("productId") Long productId, @Param("today") LocalDate today);

    /** Lotes vendibles de un producto en una sucursal, en orden de rotación FEFO (los descuentos primero). */
    @Query("""
            select l from Lot l
            where l.tenantId = :tenantId
              and l.branchId = :branchId
              and l.productId = :productId
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and l.quantity > 0
              and (l.expiryDate is null or l.expiryDate >= :today)
            order by case when l.discountPct is null or l.discountPct <= 0 then 1 else 0 end asc,
                     l.expiryDate asc nulls last, l.receivedAt asc, l.id asc
            """)
    List<Lot> findSellableFefo(@Param("tenantId") Long tenantId, @Param("branchId") Long branchId,
                               @Param("productId") Long productId, @Param("today") LocalDate today);

    /**
     * Lotes vendibles de un producto en una sucursal, bloqueados para descontar stock ({@code FOR UPDATE}). Se
     * bloquean en orden de id, el mismo que usan las transferencias y los barridos de recall, para que dos operaciones
     * concurrentes nunca se bloqueen en orden inverso; el orden de rotación lo aplica {@code StockService} en memoria.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select l from Lot l
            where l.tenantId = :tenantId
              and l.branchId = :branchId
              and l.productId = :productId
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and l.quantity > 0
              and (l.expiryDate is null or l.expiryDate >= :today)
            order by l.id asc
            """)
    List<Lot> findSellableForUpdate(@Param("tenantId") Long tenantId, @Param("branchId") Long branchId,
                                    @Param("productId") Long productId, @Param("today") LocalDate today);

    @Query("""
            select coalesce(sum(l.quantity), 0) from Lot l
            where l.tenantId = :tenantId
              and l.branchId = :branchId
              and l.productId = :productId
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and (l.expiryDate is null or l.expiryDate >= :today)
            """)
    long sumSellableQuantity(@Param("tenantId") Long tenantId, @Param("branchId") Long branchId,
                             @Param("productId") Long productId, @Param("today") LocalDate today);

    /** Stock vendible por sucursal y producto (solo combinaciones con stock). */
    @Query("""
            select l.branchId as branchId, l.productId as productId, sum(l.quantity) as quantity from Lot l
            where l.tenantId = :tenantId
              and l.branchId in :branchIds
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and l.quantity > 0
              and (l.expiryDate is null or l.expiryDate >= :today)
            group by l.branchId, l.productId
            """)
    List<BranchProductQuantity> sumSellableQuantityByBranchAndProduct(@Param("tenantId") Long tenantId,
                                                                      @Param("branchIds") Collection<Long> branchIds,
                                                                      @Param("today") LocalDate today);

    /** Stock vendible por producto, sumado sobre las sucursales indicadas (solo productos con stock). */
    @Query("""
            select l.productId as productId, sum(l.quantity) as quantity from Lot l
            where l.tenantId = :tenantId
              and l.branchId in :branchIds
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and l.quantity > 0
              and (l.expiryDate is null or l.expiryDate >= :today)
            group by l.productId
            """)
    List<ProductQuantity> sumSellableQuantityByProduct(@Param("tenantId") Long tenantId,
                                                       @Param("branchIds") Collection<Long> branchIds,
                                                       @Param("today") LocalDate today);

    /**
     * Lotes vendibles del mismo producto y sucursal que ingresaron antes que {@code lotId} (orden FIFO) y vencen
     * después de {@code expiryDate} o no tienen vencimiento: con FIFO se venden antes que ese lote.
     * <p>
     * Los lotes en liquidación también cuentan: con el descuento salen todavía antes, así que el lote nuevo —que vence
     * antes— igual se va a vender después (SPEC §4.2, aviso {@code rotationWarning}).
     */
    @Query("""
            select count(l) from Lot l
            where l.tenantId = :tenantId
              and l.branchId = :branchId
              and l.productId = :productId
              and l.id <> :lotId
              and l.status = com.gondolia.domain.inventory.LotStatus.ACTIVE
              and l.quantity > 0
              and (l.expiryDate is null or l.expiryDate >= :today)
              and (l.receivedAt < :receivedAt or (l.receivedAt = :receivedAt and l.id < :lotId))
              and (l.expiryDate is null or l.expiryDate > :expiryDate)
            """)
    long countOlderSellableExpiringAfter(@Param("tenantId") Long tenantId, @Param("branchId") Long branchId,
                                         @Param("productId") Long productId, @Param("lotId") Long lotId,
                                         @Param("receivedAt") Instant receivedAt,
                                         @Param("expiryDate") LocalDate expiryDate,
                                         @Param("today") LocalDate today);

    /**
     * Lotes con remanente ({@code ACTIVE} o {@code RECALLED}) de un comercio cuyo producto tiene código de barras:
     * candidatos a chequear contra todos los recalls publicados (p. ej. cuando el comercio vuelve a estar ACTIVE).
     */
    @Query("""
            select l from Lot l, Product p
            where l.productId = p.id
              and l.tenantId = :tenantId
              and p.barcode is not null
              and l.quantity > 0
              and l.status in (com.gondolia.domain.inventory.LotStatus.ACTIVE,
                               com.gondolia.domain.inventory.LotStatus.RECALLED)
            order by l.branchId, l.id
            """)
    List<Lot> findStockedLotsWithBarcodeByTenant(@Param("tenantId") Long tenantId);

    /**
     * Lotes con remanente (ACTIVE o RECALLED) de un código de barras en tenants ACTIVE: candidatos de un recall.
     */
    @Query("""
            select l from Lot l, Product p, com.gondolia.domain.tenant.Tenant t
            where l.productId = p.id
              and l.tenantId = t.id
              and p.barcode = :barcode
              and l.quantity > 0
              and l.status in (com.gondolia.domain.inventory.LotStatus.ACTIVE,
                               com.gondolia.domain.inventory.LotStatus.RECALLED)
              and t.status = com.gondolia.domain.tenant.TenantStatus.ACTIVE
            order by l.tenantId, l.branchId, l.id
            """)
    List<Lot> findStockedLotsByBarcodeInActiveTenants(@Param("barcode") String barcode);
}
