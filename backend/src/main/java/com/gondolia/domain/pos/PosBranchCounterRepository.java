package com.gondolia.domain.pos;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosBranchCounterRepository extends JpaRepository<PosBranchCounter, Long> {

    /**
     * Contador de la sucursal bloqueado para emitir el próximo número ({@code SELECT ... FOR UPDATE}). Si no existe
     * hay que crearlo (la primera venta de la sucursal) y volver a leerlo bloqueado.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from PosBranchCounter c where c.branchId = :branchId")
    Optional<PosBranchCounter> lockByBranchId(@Param("branchId") Long branchId);
}
