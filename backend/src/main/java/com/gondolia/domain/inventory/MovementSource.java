package com.gondolia.domain.inventory;

/**
 * Origen de un movimiento de stock. {@code POS} es el POS externo del cliente (webhook, CSV o simulador);
 * {@code POS_GONDOLIA}, nuestro punto de venta (SPEC §15); {@code IMPORT}, la importación masiva (SPEC §16).
 */
public enum MovementSource {
    MANUAL,
    SCAN,
    OCR,
    CSV,
    POS,
    POS_GONDOLIA,
    IMPORT,
    SEED,
    SYSTEM
}
