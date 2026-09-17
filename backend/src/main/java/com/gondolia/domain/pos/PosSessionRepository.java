package com.gondolia.domain.pos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Turnos de caja. La base garantiza un único turno {@code OPEN} por caja ({@code uq_pos_sessions_open_register}) y
 * por usuario ({@code uq_pos_sessions_open_user}): al abrir uno conviene chequear antes para devolver 409
 * {@code REGISTER_BUSY} o {@code SESSION_ALREADY_OPEN} con un mensaje claro.
 */
public interface PosSessionRepository extends JpaRepository<PosSession, Long>,
        JpaSpecificationExecutor<PosSession> {

    Optional<PosSession> findByIdAndTenantId(Long id, Long tenantId);

    /** Turno abierto de una caja (a lo sumo uno). */
    Optional<PosSession> findByRegisterIdAndStatus(Long registerId, PosSessionStatus status);

    /** Turno abierto de un usuario (a lo sumo uno). */
    Optional<PosSession> findByOpenedByAndStatus(Long openedBy, PosSessionStatus status);

    List<PosSession> findByTenantIdAndBranchIdInAndStatusOrderByOpenedAtDesc(Long tenantId,
                                                                             Collection<Long> branchIds,
                                                                             PosSessionStatus status);

    List<PosSession> findByTenantIdAndBranchIdInOrderByOpenedAtDesc(Long tenantId, Collection<Long> branchIds);

    List<PosSession> findByRegisterIdOrderByOpenedAtDesc(Long registerId);

    boolean existsByRegisterIdAndStatus(Long registerId, PosSessionStatus status);

    boolean existsByOpenedByAndStatus(Long openedBy, PosSessionStatus status);

    long countByBranchIdAndStatus(Long branchId, PosSessionStatus status);
}
