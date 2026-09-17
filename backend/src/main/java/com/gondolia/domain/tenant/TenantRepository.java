package com.gondolia.domain.tenant;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TenantRepository extends JpaRepository<Tenant, Long>, JpaSpecificationExecutor<Tenant> {

    List<Tenant> findByStatusOrderByIdAsc(TenantStatus status);

    long countByStatus(TenantStatus status);

    boolean existsByNameIgnoreCase(String name);

    Optional<Tenant> findByNameIgnoreCase(String name);

    @Query("select t.status from Tenant t where t.id = :id")
    Optional<TenantStatus> findStatusById(@Param("id") Long id);

    @Query("select t.id from Tenant t where t.status = :status order by t.id")
    List<Long> findIdsByStatus(@Param("status") TenantStatus status);
}
