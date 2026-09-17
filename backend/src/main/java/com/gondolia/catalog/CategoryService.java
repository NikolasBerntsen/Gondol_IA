package com.gondolia.catalog;

import com.gondolia.catalog.dto.CategoryDto;
import com.gondolia.catalog.dto.CategoryRequest;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.inventory.Category;
import com.gondolia.domain.inventory.CategoryRepository;
import com.gondolia.domain.inventory.Product;
import com.gondolia.domain.inventory.ProductRepository;
import com.gondolia.security.CurrentUser;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Categorías del catálogo (SPEC §6.3). Son del comercio: no dependen de la sucursal.
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    static final String MSG_DUPLICATE = "Ya tenés una categoría con ese nombre.";
    static final String MSG_NOT_FOUND = "La categoría no existe.";
    static final String MSG_HAS_PRODUCTS =
            "No podés eliminar una categoría con productos. Cambiá primero la categoría de esos productos.";

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<CategoryDto> list() {
        Long tenantId = CurrentUser.tenantId();
        Map<Long, Long> counts = productCounts(tenantId);
        return categoryRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(category -> new CategoryDto(category.getId(), category.getName(),
                        counts.getOrDefault(category.getId(), 0L)))
                .toList();
    }

    @Transactional
    public CategoryDto create(CategoryRequest request) {
        Long tenantId = CurrentUser.tenantId();
        String name = normalize(request.name());
        categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, name).ifPresent(existing -> {
            throw new ConflictException(ErrorCodes.CONFLICT, MSG_DUPLICATE);
        });
        Category category = new Category();
        category.setTenantId(tenantId);
        category.setName(name);
        return new CategoryDto(categoryRepository.save(category).getId(), name, 0L);
    }

    @Transactional
    public CategoryDto update(Long id, CategoryRequest request) {
        Long tenantId = CurrentUser.tenantId();
        Category category = categoryRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        String name = normalize(request.name());
        categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, name)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(other -> {
                    throw new ConflictException(ErrorCodes.CONFLICT, MSG_DUPLICATE);
                });
        category.setName(name);
        categoryRepository.save(category);
        return new CategoryDto(category.getId(), name, productRepository.countByTenantIdAndCategoryId(tenantId, id));
    }

    @Transactional
    public void delete(Long id) {
        Long tenantId = CurrentUser.tenantId();
        Category category = categoryRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        if (productRepository.countByTenantIdAndCategoryId(tenantId, id) > 0) {
            throw new ConflictException(ErrorCodes.CONFLICT, MSG_HAS_PRODUCTS);
        }
        categoryRepository.delete(category);
    }

    /**
     * Id de la categoría con ese nombre en el comercio, creándola si no existe (creación inline desde el formulario
     * de producto y desde la carga de mercadería). Devuelve {@code null} si el nombre viene vacío.
     */
    @Transactional
    public Long resolveOrCreate(Long tenantId, String rawName) {
        String name = rawName == null ? null : rawName.trim();
        if (name == null || name.isEmpty()) {
            return null;
        }
        return categoryRepository.findByTenantIdAndNameIgnoreCase(tenantId, name)
                .map(Category::getId)
                .orElseGet(() -> {
                    Category category = new Category();
                    category.setTenantId(tenantId);
                    category.setName(name.length() > 100 ? name.substring(0, 100) : name);
                    return categoryRepository.save(category).getId();
                });
    }

    private Map<Long, Long> productCounts(Long tenantId) {
        Map<Long, Long> counts = new HashMap<>();
        for (Product product : productRepository.findByTenantId(tenantId)) {
            if (product.getCategoryId() != null) {
                counts.merge(product.getCategoryId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    private static String normalize(String raw) {
        return Objects.requireNonNullElse(raw, "").trim();
    }
}
