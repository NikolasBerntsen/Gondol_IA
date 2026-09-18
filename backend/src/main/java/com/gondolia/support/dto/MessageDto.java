package com.gondolia.support.dto;

import com.gondolia.domain.support.MessageSenderType;
import java.time.Instant;

/** Mensaje de una conversación de soporte (SPEC §6.8). */
public record MessageDto(Long id, Long ticketId, Long senderId, String senderName, MessageSenderType senderType,
                         String body, AttachmentDto attachment, Instant createdAt) {
}
