package com.gondolia.domain.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    /** El email debe llegar normalizado ({@code Emails.normalize}). */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByRole(Role role);

    Optional<User> findByIdAndTenantId(Long id, Long tenantId);

    List<User> findByTenantIdOrderByFullNameAsc(Long tenantId);

    List<User> findByTenantIdAndActiveTrue(Long tenantId);

    List<User> findByTenantIdAndRoleInAndActiveTrue(Long tenantId, Collection<Role> roles);

    long countByTenantId(Long tenantId);

    long countByTenantIdAndRoleAndActiveTrue(Long tenantId, Role role);

    List<User> findByRoleInOrderByFullNameAsc(Collection<Role> roles);

    List<User> findByRoleAndActiveTrue(Role role);

    /**
     * Ids de usuarios activos del tenant con acceso a la sucursal: todos los jefes y administradores más los empleados
     * y cajeros asignados. No verifica que la sucursal pertenezca al tenant ni que esté activa.
     */
    @Query("""
            select u.id from User u
            where u.tenantId = :tenantId
              and u.active = true
              and (u.role in (com.gondolia.domain.user.Role.TENANT_ADMIN, com.gondolia.domain.user.Role.TENANT_BOSS)
                   or (u.role in (com.gondolia.domain.user.Role.TENANT_EMPLOYEE,
                                  com.gondolia.domain.user.Role.TENANT_CASHIER)
                       and exists (select 1 from UserBranch ub where ub.userId = u.id and ub.branchId = :branchId)))
            order by u.id
            """)
    List<Long> findActiveUserIdsWithBranchAccess(@Param("tenantId") Long tenantId, @Param("branchId") Long branchId);

    /** Usuarios activos de tenants ACTIVE (para avisos masivos). */
    @Query("""
            select u from User u, com.gondolia.domain.tenant.Tenant t
            where u.tenantId = t.id
              and u.active = true
              and t.status = com.gondolia.domain.tenant.TenantStatus.ACTIVE
            order by u.tenantId, u.id
            """)
    List<User> findActiveUsersOfActiveTenants();
}
