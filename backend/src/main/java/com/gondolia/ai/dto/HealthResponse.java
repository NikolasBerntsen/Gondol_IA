package com.gondolia.ai.dto;

/** Respuesta de {@code GET /health} (SPEC §8.1). */
public record HealthResponse(String status, String version, String modelVersion, boolean tesseract) {
}
