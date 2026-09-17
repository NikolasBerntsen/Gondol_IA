package com.gondolia.ai.dto;

/** Código de barras detectado ({@code format}: p. ej. {@code EAN_13}). */
public record BarcodeValue(String value, String format) {
}
