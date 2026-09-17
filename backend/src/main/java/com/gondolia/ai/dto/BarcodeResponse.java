package com.gondolia.ai.dto;

import java.util.List;

/** Respuesta de {@code POST /v1/barcode} (SPEC §8.4). */
public record BarcodeResponse(List<BarcodeValue> barcodes) {

    public BarcodeResponse {
        barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
    }
}
