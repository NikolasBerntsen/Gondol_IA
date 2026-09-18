package com.gondolia.support.dto;

import com.gondolia.domain.support.Attachment;

/**
 * Imagen adjunta a un mensaje de soporte. {@code url} es la ruta autenticada del núcleo
 * ({@code GET /api/attachments/{id}}): el frontend la descarga con el token (componente {@code AuthImage}).
 */
public record AttachmentDto(Long id, String url, String contentType, String originalName, long sizeBytes) {

    public static AttachmentDto of(Attachment attachment) {
        if (attachment == null) {
            return null;
        }
        return new AttachmentDto(attachment.getId(), "/api/attachments/" + attachment.getId(),
                attachment.getContentType(), attachment.getOriginalName(), attachment.getSizeBytes());
    }
}
