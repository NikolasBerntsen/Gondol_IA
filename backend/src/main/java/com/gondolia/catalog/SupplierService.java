package com.gondolia.catalog;

import com.gondolia.catalog.dto.SupplierDto;
import com.gondolia.catalog.dto.SupplierRequest;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.domain.inventory.Supplier;
import com.gondolia.domain.inventory.SupplierRepository;
import com.gondolia.security.CurrentUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proveedores del comercio (SPEC §6.3). Son del comercio: no dependen de la sucursal.
 * <p>
 * Un proveedor que ya está usado en productos o lotes no se borra: se <b>desactiva</b> (deja de ofrecerse en la carga
 * de mercadería pero se conserva la historia). Si no tiene nada asociado, se elimina.
 */
@Service
@RequiredArgsConstructor
public class SupplierService {

    static final String MSG_NOT_FOUND = "El proveedor no existe.";
    static final String MSG_DUPLICATE = "Ya tenés un proveedor con ese nombre.";

    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final LotRepository lotRepository;

    /** Resultado de eliminar: {@code deactivated} cuando el proveedor quedó desactivado en vez de borrado. */
    public record DeleteResult(boolean deactivated) {
    }

    @Transactional(readOnly = true)
    public List<SupplierDto> list(boolean includeInactive) {
        Long tenantId = CurrentUser.tenantId();
        Map<Long, Long> counts = productCounts(tenantId);
        return supplierRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .filter(supplier -> includeInactive || supplier.isActive())
                .map(supplier -> toDto(supplier, counts.getOrDefault(supplier.getId(), 0L)))
                .toList();
    }

    @Transactional
    public SupplierDto create(SupplierRequest request) {
        Long tenantId = CurrentUser.tenantId();
        String name = request.name().trim();
        assertNameAvailable(tenantId, name, null);
        Supplier supplier = new Supplier();
        supplier.setTenantId(tenantId);
        apply(supplier, request, name);
        return toDto(supplierRepository.save(supplier), 0L);
    }

    @Transactional
    public SupplierDto update(Long id, SupplierRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Supplier supplier = supplierRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        String name = request.name().trim();
        assertNameAvailable(tenantId, name, id);
        apply(supplier, request, name);
        supplierRepository.save(supplier);
        return toDto(supplier, productRepository.countByTenantIdAndSupplierId(tenantId, id));
    }

    @Transactional
    public DeleteResult delete(Long id) {
        Long tenantId = CurrentUser.tenantId();
        Supplier supplier = supplierRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        boolean used = productRepository.countByTenantIdAndSupplierId(tenantId, id) > 0
                || lotRepository.exists((root, query, cb) -> cb.and(
                        cb.equal(root.get("tenantId"), tenantId), cb.equal(root.get("supplierId"), id)));
        if (used) {
            supplier.setActive(false);
            supplierRepository.save(supplier);
            return new DeleteResult(true);
        }
        supplierRepository.delete(supplier);
        return new DeleteResult(false);
    }

    private void assertNameAvailable(Long tenantId, String name, Long currentId) {
        boolean taken = supplierRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .anyMatch(other -> other.getName().equalsIgnoreCase(name)
                        && (currentId == null || !other.getId().equals(currentId)));
        if (taken) {
            throw new ConflictException(ErrorCodes.CONFLICT, MSG_DUPLICATE);
        }
    }

    private static void apply(Supplier supplier, SupplierRequest request, String name) {
        supplier.setName(name);
        supplier.setContactName(trimToNull(request.contactName()));
        supplier.setPhone(trimToNull(request.phone()));
        supplier.setEmail(trimToNull(request.email()));
        supplier.setLeadTimeDays(request.leadTimeDays());
        supplier.setNotes(trimToNull(request.notes()));
        if (request.active() != null) {
            supplier.setActive(request.active());
        }
    }

    private Map<Long, Long> productCounts(Long tenantId) {
        Map<Long, Long> counts = new HashMap<>();
        for (Product product : productRepository.findByTenantId(tenantId)) {
            if (product.getSupplierId() != null) {
                counts.merge(product.getSupplierId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static SupplierDto toDto(Supplier supplier, long productCount) {
        return new SupplierDto(supplier.getId(), supplier.getName(), supplier.getContactName(), supplier.getPhone(),
                supplier.getEmail(), supplier.getLeadTimeDays(), supplier.getNotes(), supplier.isActive(),
                productCount);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
