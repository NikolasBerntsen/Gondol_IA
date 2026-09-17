package com.gondolia.storage;

import com.gondolia.domain.support.Attachment;
import com.gondolia.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Archivos")
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentStorageService storageService;

    @Operation(summary = "Descargar un adjunto",
            description = "Imagen de soporte (agentes o usuarios del comercio del ticket) u OCR (usuarios del mismo "
                    + "comercio). Si no existe o no tenés acceso responde 404.")
    @GetMapping("/{id}")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        Attachment attachment = storageService.findReadable(id, CurrentUser.get());
        Resource resource = storageService.load(attachment);
        String filename = attachment.getOriginalName() != null ? attachment.getOriginalName() : "imagen";
        ContentDisposition disposition = StandardCharsets.US_ASCII.newEncoder().canEncode(filename)
                ? ContentDisposition.inline().filename(filename).build()
                : ContentDisposition.inline().filename(filename, StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(attachment.getContentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }
}
