package com.gondolia.ai.dto;

/** Número de lote detectado en una etiqueta: valor, texto original y confianza (0..1). */
public record LotCandidate(String value, String raw, double confidence) {
}
