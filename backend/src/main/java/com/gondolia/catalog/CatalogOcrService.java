package com.gondolia.catalog;

import com.gondolia.ai.AiClient;
import com.gondolia.ai.dto.BarcodeResponse;
import com.gondolia.ai.dto.BarcodeValue;
import com.gondolia.ai.dto.OcrResponse;
import com.gondolia.catalog.dto.OcrLabelResponse;
import com.gondolia.catalog.dto.ProductListItem;
import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lectura de etiquetas y de códigos de barras con el servicio de IA (SPEC §6.3, §8.3 y §8.4).
 * <p>
 * La foto <b>no se guarda</b>: se manda al servicio y se descarta. Si el servicio no está disponible se responde 503
 * {@code AI_UNAVAILABLE} con un mensaje que explica el camino alternativo (cargar a mano), porque la pantalla de
 * carga tiene que seguir siendo usable sin IA.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogOcrService {

    static final String MSG_LABEL_UNAVAILABLE =
            "No pudimos leer la etiqueta: el servicio de IA no está disponible. Cargá el vencimiento y el lote a mano.";
    static final String MSG_BARCODE_UNAVAILABLE =
            "No pudimos leer el código: el servicio de IA no está disponible. Ingresá el código a mano.";
    static final String MSG_NO_FILE = "Adjuntá una foto de la etiqueta.";

    private final AiClient aiClient;
    private final ProductService productService;

    /** Vencimientos, lotes y códigos detectados en la foto, más el producto del catálogo si se reconoció el código. */
    public OcrLabelResponse readLabel(MultipartFile file) {
        OcrResponse ocr = call(file, MSG_LABEL_UNAVAILABLE,
                bytes -> aiClient.ocr(bytes, filename(file), file.getContentType()));
        ProductListItem matched = ocr.barcodes().stream()
                .map(BarcodeValue::value)
                .map(productService::findListItemByBarcode)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
        return OcrLabelResponse.of(ocr, matched);
    }

    /** Códigos de barras detectados en la foto. */
    public BarcodeResponse readBarcodes(MultipartFile file) {
        return call(file, MSG_BARCODE_UNAVAILABLE,
                bytes -> aiClient.barcode(bytes, filename(file), file.getContentType()));
    }

    private <T> T call(MultipartFile file, String unavailableMessage, ImageCall<T> call) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException(ErrorCodes.INVALID_FILE, MSG_NO_FILE);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        try {
            return call.apply(bytes);
        } catch (ApiException ex) {
            if (ErrorCodes.AI_UNAVAILABLE.equals(ex.getCode())) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.AI_UNAVAILABLE, unavailableMessage);
            }
            throw ex;
        }
    }

    private static String filename(MultipartFile file) {
        String original = file.getOriginalFilename();
        return original == null || original.isBlank() ? "etiqueta.jpg" : original;
    }

    @FunctionalInterface
    private interface ImageCall<T> {
        T apply(byte[] bytes);
    }
}
