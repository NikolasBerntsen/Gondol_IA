package com.gondolia.domain.user;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserBranchRepository extends JpaRepository<UserBranch, Long> {

    List<UserBranch> findByUserId(Long userId);

    List<UserBranch> findByUserIdIn(Collection<Long> userIds);

    List<UserBranch> findByBranchId(Long branchId);

    boolean existsByUserIdAndBranchId(Long userId, Long branchId);

    long countByBranchId(Long branchId);

    @Query("select ub.branchId from UserBranch ub where ub.userId = :userId")
    List<Long> findBranchIdsByUserId(@Param("userId") Long userId);

    @Query("select ub.userId from UserBranch ub where ub.branchId = :branchId")
    List<Long> findUserIdsByBranchId(@Param("branchId") Long branchId);

    /** Usuarios activos asignados a una sucursal (empleados y cajeros). */
    @Query("""
            select count(ub) from UserBranch ub, User u
            where ub.userId = u.id
              and ub.branchId = :branchId
              and u.active = true
              and u.role in (com.gondolia.domain.user.Role.TENANT_EMPLOYEE,
                             com.gondolia.domain.user.Role.TENANT_CASHIER)
            """)
    long countActiveEmployeesByBranchId(@Param("branchId") Long branchId);

    @Transactional
    @Modifying
    @Query("delete from UserBranch ub where ub.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
