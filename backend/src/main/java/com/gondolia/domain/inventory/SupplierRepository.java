package com.gondolia.domain.inventory;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByIdAndTenantId(Long id, Long tenantId);

    List<Supplier> findByTenantIdOrderByNameAsc(Long tenantId);

    List<Supplier> findByTenantIdAndActiveTrueOrderByNameAsc(Long tenantId);
}
