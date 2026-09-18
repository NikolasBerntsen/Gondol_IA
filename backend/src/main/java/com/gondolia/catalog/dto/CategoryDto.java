package com.gondolia.catalog.dto;

/** Categoría del catálogo con la cantidad de productos que la usan (SPEC §6.3). */
public record CategoryDto(Long id, String name, long productCount) {
}
