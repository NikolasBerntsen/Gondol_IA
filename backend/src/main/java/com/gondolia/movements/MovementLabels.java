package com.gondolia.movements;

import com.gondolia.domain.inventory.MovementSource;
import com.gondolia.domain.inventory.MovementType;

/**
 * Etiquetas en español rioplatense de los enums de movimientos, para que la UI y los CSV muestren lo mismo
 * que el backend (SPEC §4.1).
 */
public final class MovementLabels {

    private MovementLabels() {
    }

    /** Etiqueta del tipo de movimiento ("Venta", "Anulación de venta", "Baja por vencimiento"…). */
    public static String type(MovementType type) {
        if (type == null) {
            return "—";
        }
        return switch (type) {
            case ENTRY -> "Ingreso";
            case SALE -> "Venta";
            case SALE_VOID -> "Anulación de venta";
            case ADJUSTMENT_IN -> "Ajuste positivo";
            case ADJUSTMENT_OUT -> "Ajuste negativo";
            case WASTE_EXPIRED -> "Baja por vencimiento";
            case WASTE_DAMAGED -> "Baja por daño";
            case RECALL_REMOVAL -> "Retiro por recall";
            case TRANSFER_OUT -> "Transferencia enviada";
            case TRANSFER_IN -> "Transferencia recibida";
        };
    }

    /** Etiqueta del origen del movimiento; distingue el POS propio del cliente del POS GondolIA. */
    public static String source(MovementSource source) {
        if (source == null) {
            return "—";
        }
        return switch (source) {
            case MANUAL -> "Manual";
            case SCAN -> "Escáner";
            case OCR -> "Lectura de etiqueta";
            case CSV -> "Importación CSV";
            case POS -> "POS externo";
            case POS_GONDOLIA -> "POS GondolIA";
            case IMPORT -> "Importación masiva";
            case SEED -> "Datos demo";
            case SYSTEM -> "Sistema";
        };
    }
}
