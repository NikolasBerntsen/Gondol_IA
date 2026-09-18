package com.gondolia.catalog;

import com.gondolia.catalog.dto.CategoryDto;
import com.gondolia.catalog.dto.CategoryRequest;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Categorías del catálogo (SPEC §6.3). Listar: cualquier usuario del comercio (las usan el POS y los tableros);
 * crear: administrador o empleado (creación inline desde el formulario de producto); editar y eliminar: administrador.
 */
@Tag(name = "Catálogo · categorías")
@RestController
@RequestMapping("/api/tenant/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    @GetMapping
    @PreAuthorize(Roles.TENANT_ANY)
    public List<CategoryDto> list() {
        return categoryService.list();
    }

    @PostMapping
    @PreAuthorize(Roles.TENANT_INVENTORY)
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryDto create(@Valid @RequestBody CategoryRequest request) {
        return categoryService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public CategoryDto update(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
        return categoryService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(Roles.TENANT_ADMIN)
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        categoryService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
