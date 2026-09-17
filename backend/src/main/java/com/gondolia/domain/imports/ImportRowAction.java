package com.gondolia.domain.imports;

/**
 * Qué hará la fila al aplicarse: crear el producto, actualizarlo u omitirla (SPEC §16.2).
 */
public enum ImportRowAction {
    CREATE,
    UPDATE,
    SKIP
}
