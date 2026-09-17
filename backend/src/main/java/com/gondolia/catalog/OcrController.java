package com.gondolia.catalog;

import com.gondolia.ai.dto.BarcodeResponse;
import com.gondolia.catalog.dto.OcrLabelResponse;
import com.gondolia.security.Roles;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Lectura de etiquetas y códigos con la cámara (SPEC §6.3). Proxy del servicio de IA: la foto se procesa y se
 * descarta, no se guarda ningún adjunto.
 */
@Tag(name = "Catálogo · lectura con cámara")
@RestController
@RequestMapping("/api/tenant/ocr")
@RequiredArgsConstructor
public class OcrController {

    private final CatalogOcrService catalogOcrService;

    /** Vencimientos, lotes y códigos de una etiqueta, con el producto del catálogo si se reconoció el código. */
    @PostMapping(value = "/label", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public OcrLabelResponse label(@RequestParam("file") MultipartFile file) {
        return catalogOcrService.readLabel(file);
    }

    /** Códigos de barras detectados en una foto (alternativa al escáner en vivo). */
    @PostMapping(value = "/barcode", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(Roles.TENANT_INVENTORY)
    public BarcodeResponse barcode(@RequestParam("file") MultipartFile file) {
        return catalogOcrService.readBarcodes(file);
    }
}
