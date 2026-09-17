package com.gondolia.catalog.dto;

import com.gondolia.recall.RecallMatchingService.RecallInfo;
import java.util.List;

/**
 * Resultado de una carga de mercadería (SPEC §6.3).
 *
 * @param lot             lote creado
 * @param quarantined     {@code true} si quedó {@code RECALLED} por coincidir con un recall (no vendible)
 * @param recalls         recalls que alcanzan al lote
 * @param rotationWarning aviso de FIFO cuando el lote nuevo vence antes que mercadería más vieja ({@code null} si no aplica)
 * @param existingLots    otros lotes vendibles del producto en esa sucursal, en orden de rotación
 */
public record ReceiveLotResponse(LotDto lot, boolean quarantined, List<RecallInfo> recalls, String rotationWarning,
                                 List<LotDto> existingLots) {

    public ReceiveLotResponse {
        recalls = recalls == null ? List.of() : List.copyOf(recalls);
        existingLots = existingLots == null ? List.of() : List.copyOf(existingLots);
    }
}
