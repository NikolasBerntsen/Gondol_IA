package com.gondolia.ai.dto;

import java.time.LocalDate;

/** Fecha detectada en una etiqueta: valor, texto original y confianza (0..1). */
public record DateCandidate(LocalDate value, String raw, double confidence) {
}
