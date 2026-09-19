package com.gondolia.announcements.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Datos del retiro de un recall tal como se muestran a los dueños y a los comercios.
 * {@code lotNumbers} viene vacío cuando el recall alcanza a todos los lotes.
 */
public record RecallDetailDto(
        String productName,
        String brand,
        String barcode,
        List<String> lotNumbers,
        boolean allLots,
        LocalDate expiryFrom,
        LocalDate expiryTo,
        String reason,
        String instructions) {
}
