package com.gondolia.domain.tenant;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Módulos por comercio. "Habilitado" = existe la fila con {@code enabled = true}.
 */
public interface TenantModuleConfigRepository extends JpaRepository<TenantModuleConfig, Long> {

    /** Módulo habilitado de un tenant (para las matrices de la consola de dueños). */
    interface TenantModuleRow {
        Long getTenantId();

        TenantModule getModule();
    }

    List<TenantModuleConfig> findByTenantIdOrderByModuleAsc(Long tenantId);

    Optional<TenantModuleConfig> findByTenantIdAndModule(Long tenantId, TenantModule module);

    boolean existsByTenantIdAndModuleAndEnabledTrue(Long tenantId, TenantModule module);

    List<TenantModuleConfig> findByTenantIdAndEnabledTrue(Long tenantId);

    long countByModuleAndEnabledTrue(TenantModule module);

    void deleteByTenantId(Long tenantId);

    /** Módulos habilitados de varios comercios (matriz clientes × módulos). */
    @Query("""
            select c.tenantId as tenantId, c.module as module from TenantModuleConfig c
            where c.tenantId in :tenantIds and c.enabled = true
            order by c.tenantId, c.module
            """)
    List<TenantModuleRow> findEnabledByTenantIds(@Param("tenantIds") Collection<Long> tenantIds);

    /** Ids de comercios con un módulo habilitado. */
    @Query("select c.tenantId from TenantModuleConfig c where c.module = :module and c.enabled = true order by c.tenantId")
    List<Long> findTenantIdsWithModule(@Param("module") TenantModule module);
}
