package com.gondolia.domain.tenant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    Optional<Branch> findByIdAndTenantId(Long id, Long tenantId);

    List<Branch> findByTenantIdOrderByNameAsc(Long tenantId);

    List<Branch> findByTenantIdAndActiveTrueOrderByNameAsc(Long tenantId);

    List<Branch> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndActiveTrue(Long tenantId);

    boolean existsByTenantIdAndNameIgnoreCase(Long tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndIdNot(Long tenantId, String name, Long id);

    /** El hash es {@code ApiKeyService.hash(rawKey)}. */
    Optional<Branch> findByPosApiKeyHash(String posApiKeyHash);

    @Query("select b.id from Branch b where b.tenantId = :tenantId and b.active = true order by b.name")
    List<Long> findActiveIdsByTenantId(@Param("tenantId") Long tenantId);

    /** Sucursales activas del tenant asignadas a un usuario (empleado), ordenadas por nombre. */
    @Query("""
            select b from Branch b
            where b.tenantId = :tenantId
              and b.active = true
              and exists (select 1 from UserBranch ub where ub.branchId = b.id and ub.userId = :userId)
            order by b.name
            """)
    List<Branch> findActiveAssignedToUser(@Param("tenantId") Long tenantId, @Param("userId") Long userId);

    /** Sucursales activas de tenants ACTIVE (procesos programados: alertas, IA). */
    @Query("""
            select b from Branch b, Tenant t
            where b.tenantId = t.id
              and b.active = true
              and t.status = com.gondolia.domain.tenant.TenantStatus.ACTIVE
            order by b.tenantId, b.id
            """)
    List<Branch> findActiveBranchesOfActiveTenants();
}
