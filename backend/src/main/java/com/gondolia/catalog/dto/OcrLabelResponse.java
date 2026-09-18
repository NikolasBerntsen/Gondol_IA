package com.gondolia.catalog.dto;

import com.gondolia.ai.dto.BarcodeValue;
import com.gondolia.ai.dto.DateCandidate;
import com.gondolia.ai.dto.LotCandidate;
import com.gondolia.ai.dto.OcrResponse;
import java.util.List;

/**
 * Lectura de una etiqueta (SPEC §6.3 y §8.3): la respuesta del servicio de IA más el producto del catálogo que
 * coincide con alguno de los códigos detectados ({@code null} si no se reconoció ninguno). La imagen no se guarda.
 */
public record OcrLabelResponse(
        String text,
        List<String> lines,
        List<DateCandidate> expiryDates,
        List<LotCandidate> lotNumbers,
        List<DateCandidate> manufactureDates,
        List<BarcodeValue> barcodes,
        List<String> productNameCandidates,
        Long processingMs,
        ProductListItem matchedProduct) {

    public static OcrLabelResponse of(OcrResponse ocr, ProductListItem matchedProduct) {
        return new OcrLabelResponse(ocr.text(), ocr.lines(), ocr.expiryDates(), ocr.lotNumbers(),
                ocr.manufactureDates(), ocr.barcodes(), ocr.productNameCandidates(), ocr.processingMs(),
                matchedProduct);
    }
}
