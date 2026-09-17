package com.gondolia.domain.pos;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosCashMovementRepository extends JpaRepository<PosCashMovement, Long> {

    Optional<PosCashMovement> findByIdAndTenantId(Long id, Long tenantId);

    List<PosCashMovement> findBySessionIdOrderByCreatedAtAscIdAsc(Long sessionId);

    /** Total de ingresos o retiros de efectivo de un turno (0 si no hubo). */
    @Query("""
            select coalesce(sum(m.amount), 0) from PosCashMovement m
            where m.sessionId = :sessionId and m.type = :type
            """)
    BigDecimal sumByType(@Param("sessionId") Long sessionId, @Param("type") CashMovementType type);
}
