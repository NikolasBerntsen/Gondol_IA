package com.gondolia.catalog.dto;

import com.gondolia.recall.RecallMatchingService.RecallInfo;
import java.util.List;

/** Chequeo previo a la carga: ¿este código/lote/vencimiento está alcanzado por un recall? (SPEC §6.3). */
public record RecallCheckResponse(boolean recalled, List<RecallInfo> recalls) {

    public RecallCheckResponse {
        recalls = recalls == null ? List.of() : List.copyOf(recalls);
    }

    public static RecallCheckResponse of(List<RecallInfo> recalls) {
        return new RecallCheckResponse(recalls != null && !recalls.isEmpty(), recalls);
    }
}
