package com.gondolia.domain.pos;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosPaymentRepository extends JpaRepository<PosPayment, Long> {

    /** Total cobrado por medio de pago (reporte de cierre de turno y estadísticas del POS). */
    interface MethodTotal {
        PaymentMethod getMethod();

        BigDecimal getTotal();
    }

    List<PosPayment> findBySaleIdOrderByIdAsc(Long saleId);

    List<PosPayment> findBySaleIdInOrderBySaleIdAscIdAsc(Collection<Long> saleIds);

    /** Totales por medio de pago de un turno, contando solo las ventas del estado indicado. */
    @Query("""
            select p.method as method, sum(p.amount) as total from PosPayment p, PosSale s
            where p.saleId = s.id
              and s.sessionId = :sessionId
              and s.status = :status
            group by p.method
            """)
    List<MethodTotal> totalsByMethod(@Param("sessionId") Long sessionId, @Param("status") PosSaleStatus status);
}
