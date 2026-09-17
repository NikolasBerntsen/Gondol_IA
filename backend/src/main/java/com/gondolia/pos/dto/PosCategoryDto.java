package com.gondolia.pos.dto;

/** Categoría con productos vendibles en la sucursal del turno (filtros del mostrador). */
public record PosCategoryDto(Long id, String name, int productCount) {
}
