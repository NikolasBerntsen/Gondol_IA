package com.gondolia.ai.dto;

import java.util.List;

/** Respuesta de {@code POST /v1/ocr} (SPEC §8.3); los candidatos vienen ordenados por confianza descendente. */
public record OcrResponse(
        String text,
        List<String> lines,
        List<DateCandidate> expiryDates,
        List<LotCandidate> lotNumbers,
        List<DateCandidate> manufactureDates,
        List<BarcodeValue> barcodes,
        List<String> productNameCandidates,
        Long processingMs) {

    public OcrResponse {
        lines = lines == null ? List.of() : List.copyOf(lines);
        expiryDates = expiryDates == null ? List.of() : List.copyOf(expiryDates);
        lotNumbers = lotNumbers == null ? List.of() : List.copyOf(lotNumbers);
        manufactureDates = manufactureDates == null ? List.of() : List.copyOf(manufactureDates);
        barcodes = barcodes == null ? List.of() : List.copyOf(barcodes);
        productNameCandidates = productNameCandidates == null ? List.of() : List.copyOf(productNameCandidates);
    }
}
