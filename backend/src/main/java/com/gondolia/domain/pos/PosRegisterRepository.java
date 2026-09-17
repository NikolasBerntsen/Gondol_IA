package com.gondolia.domain.pos;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosRegisterRepository extends JpaRepository<PosRegister, Long> {

    Optional<PosRegister> findByIdAndTenantId(Long id, Long tenantId);

    List<PosRegister> findByTenantIdOrderByBranchIdAscNameAsc(Long tenantId);

    List<PosRegister> findByTenantIdAndBranchIdInOrderByBranchIdAscNameAsc(Long tenantId, Collection<Long> branchIds);

    List<PosRegister> findByTenantIdAndBranchIdInAndActiveTrueOrderByBranchIdAscNameAsc(Long tenantId,
                                                                                        Collection<Long> branchIds);

    Optional<PosRegister> findByBranchIdAndNameIgnoreCase(Long branchId, String name);

    boolean existsByBranchIdAndNameIgnoreCase(Long branchId, String name);

    boolean existsByBranchIdAndNameIgnoreCaseAndIdNot(Long branchId, String name, Long id);

    long countByBranchIdAndActiveTrue(Long branchId);
}
