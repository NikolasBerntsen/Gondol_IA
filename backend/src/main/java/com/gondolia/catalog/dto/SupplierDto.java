package com.gondolia.catalog.dto;

/** Proveedor del comercio con la cantidad de productos asociados (SPEC §6.3). */
public record SupplierDto(Long id, String name, String contactName, String phone, String email,
                          Integer leadTimeDays, String notes, boolean active, long productCount) {
}
