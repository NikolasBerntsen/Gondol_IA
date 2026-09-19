package com.gondolia.imports.parse;

/**
 * Tipo de dato de un campo importable: define cómo se parsea y valida la celda (SPEC §16.2).
 */
public enum ImportFieldType {
    /** Texto libre. */
    TEXT,
    /** Número con decimales en formato es-AR o en ("$ 1.234,50", "1234.5"). */
    NUMBER,
    /** Número entero (cantidades). */
    INTEGER,
    /** Booleano (si/sí/no/true/false/1/0/x). */
    BOOLEAN,
    /** Fecha (dd/mm/aaaa, dd-mm-aa, aaaa-mm-dd o serial de Excel). */
    DATE,
    /** Unidad de medida del producto ({@code ProductUnit}). */
    UNIT,
    /** Sucursal del comercio (nombre o código). */
    BRANCH;

    public String jsonName() {
        return name().toLowerCase();
    }
}
