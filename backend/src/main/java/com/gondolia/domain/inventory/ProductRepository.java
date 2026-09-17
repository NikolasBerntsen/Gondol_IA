package com.gondolia.domain.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findByIdAndTenantId(Long id, Long tenantId);

    /** El código debe llegar normalizado con {@code Barcodes.normalize}. */
    Optional<Product> findByTenantIdAndBarcode(Long tenantId, String barcode);

    boolean existsByTenantIdAndBarcode(Long tenantId, String barcode);

    List<Product> findByTenantId(Long tenantId);

    List<Product> findByTenantIdAndActiveTrue(Long tenantId);

    List<Product> findByTenantIdAndIdIn(Long tenantId, Collection<Long> ids);

    List<Product> findByTenantIdAndBarcodeIn(Long tenantId, Collection<String> barcodes);

    long countByTenantIdAndActiveTrue(Long tenantId);

    long countByTenantIdAndCategoryId(Long tenantId, Long categoryId);

    long countByTenantIdAndSupplierId(Long tenantId, Long supplierId);

    /** Productos con ese código en todos los tenants (búsqueda de recalls). */
    List<Product> findByBarcode(String barcode);
}
