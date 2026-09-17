package com.gondolia.storage;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.config.AppProperties;
import com.gondolia.domain.support.Attachment;
import com.gondolia.domain.support.AttachmentPurpose;
import com.gondolia.domain.support.AttachmentRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * Imágenes subidas (adjuntos de soporte y fotos de OCR que se quieran conservar). Solo PNG, JPEG, WEBP y GIF de hasta
 * 10 MB: el tipo se verifica por la firma del archivo (magic bytes), no solo por el content-type declarado. Se guardan
 * en {@code ${app.storage.dir}/yyyy/MM/<uuid>.<ext>}; {@code attachments.storage_key} es esa ruta relativa.
 */
@Slf4j
@Service
public class AttachmentStorageService {

    public static final long MAX_BYTES = 10L * 1024 * 1024;

    static final String MSG_EMPTY = "Adjuntá una imagen";
    static final String MSG_TOO_LARGE = "La imagen supera el máximo de 10 MB";
    static final String MSG_INVALID_TYPE = "Solo se aceptan imágenes PNG, JPG, WEBP o GIF";
    static final String MSG_NOT_FOUND = "El archivo no existe";
    static final String MSG_READ_ERROR = "No se pudo leer el archivo enviado. Probá nuevamente";

    private static final int MAX_ORIGINAL_NAME = 255;
    /** Content-types declarados aceptados; el tipo real lo define la firma del archivo. */
    private static final Set<String> ACCEPTED_DECLARED_TYPES = Set.of("image/png", "image/jpeg", "image/jpg",
            "image/pjpeg", "image/webp", "image/gif", "application/octet-stream");

    /** Formatos de imagen aceptados. */
    enum ImageType {
        PNG("image/png", "png"),
        JPEG("image/jpeg", "jpg"),
        WEBP("image/webp", "webp"),
        GIF("image/gif", "gif");

        final String contentType;
        final String extension;

        ImageType(String contentType, String extension) {
            this.contentType = contentType;
            this.extension = extension;
        }
    }

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_SIGNATURE = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87 = "GIF87a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF89 = "GIF89a".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = "WEBP".getBytes(StandardCharsets.US_ASCII);

    private final AttachmentRepository attachmentRepository;
    private final Clock clock;
    private final Path baseDir;

    public AttachmentStorageService(AttachmentRepository attachmentRepository, AppProperties properties, Clock clock) {
        this.attachmentRepository = attachmentRepository;
        this.clock = clock;
        this.baseDir = Path.of(properties.storage().dir()).toAbsolutePath().normalize();
    }

    /**
     * Valida y guarda la imagen y registra el {@link Attachment}. Si la transacción se revierte, el archivo se borra.
     *
     * @throws BadRequestException 400 {@code INVALID_FILE} si falta, supera 10 MB o no es PNG/JPEG/WEBP/GIF
     */
    @Transactional
    public Attachment store(MultipartFile file, AttachmentPurpose purpose, Long tenantId, Long uploadedBy) {
        Objects.requireNonNull(purpose, "purpose");
        if (file == null || file.isEmpty()) {
            throw invalid(MSG_EMPTY);
        }
        if (file.getSize() > MAX_BYTES) {
            throw invalid(MSG_TOO_LARGE);
        }
        String declared = file.getContentType();
        if (declared != null && !declared.isBlank() && !ACCEPTED_DECLARED_TYPES.contains(baseType(declared))) {
            throw invalid(MSG_INVALID_TYPE);
        }
        byte[] bytes = readBytes(file);
        if (bytes.length == 0) {
            throw invalid(MSG_EMPTY);
        }
        if (bytes.length > MAX_BYTES) {
            throw invalid(MSG_TOO_LARGE);
        }
        ImageType type = detect(bytes);
        if (type == null) {
            throw invalid(MSG_INVALID_TYPE);
        }

        LocalDate today = LocalDate.now(clock);
        String storageKey = "%04d/%02d/%s.%s".formatted(today.getYear(), today.getMonthValue(), UUID.randomUUID(),
                type.extension);
        Path target = resolve(storageKey);
        write(target, bytes);
        deleteOnRollback(target);

        Attachment attachment = new Attachment();
        attachment.setTenantId(tenantId);
        attachment.setUploadedBy(uploadedBy);
        attachment.setPurpose(purpose);
        attachment.setOriginalName(cleanOriginalName(file.getOriginalFilename(), type));
        attachment.setContentType(type.contentType);
        attachment.setSizeBytes(bytes.length);
        attachment.setStorageKey(storageKey);
        return attachmentRepository.save(attachment);
    }

    /** Contenido del archivo; 404 {@code NOT_FOUND} si ya no está en disco. */
    public Resource load(Attachment attachment) {
        Path path = resolve(attachment.getStorageKey());
        if (!Files.isRegularFile(path)) {
            log.warn("Falta en disco el archivo del adjunto {} ({})", attachment.getId(), attachment.getStorageKey());
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return new FileSystemResource(path);
    }

    /**
     * Adjunto legible por el usuario: {@code SUPPORT} → agentes de soporte o usuarios del mismo tenant; {@code OCR} →
     * usuarios del mismo tenant. En cualquier otro caso 404 (no se revela si existe).
     */
    @Transactional(readOnly = true)
    public Attachment findReadable(Long attachmentId, AuthUser user) {
        return attachmentRepository.findById(attachmentId)
                .filter(attachment -> canRead(attachment, user))
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
    }

    static boolean canRead(Attachment attachment, AuthUser user) {
        if (user == null || attachment.getPurpose() == null) {
            return false;
        }
        boolean sameTenant = user.isTenantUser() && user.tenantId() != null
                && user.tenantId().equals(attachment.getTenantId());
        return switch (attachment.getPurpose()) {
            case SUPPORT -> user.role() == Role.SUPPORT_AGENT || sameTenant;
            case OCR -> sameTenant;
        };
    }

    /** Formato real según la firma del archivo, o null si no es una imagen aceptada. */
    static ImageType detect(byte[] bytes) {
        if (startsWith(bytes, 0, PNG_SIGNATURE)) {
            return ImageType.PNG;
        }
        if (startsWith(bytes, 0, JPEG_SIGNATURE)) {
            return ImageType.JPEG;
        }
        if (startsWith(bytes, 0, GIF87) || startsWith(bytes, 0, GIF89)) {
            return ImageType.GIF;
        }
        if (bytes.length >= 12 && startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP)) {
            return ImageType.WEBP;
        }
        return null;
    }

    private Path resolve(String storageKey) {
        Path path = baseDir.resolve(storageKey).normalize();
        if (!path.startsWith(baseDir)) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return path;
    }

    private static void write(Path target, byte[] bytes) {
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try {
                Files.write(temp, bytes);
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo guardar el archivo en " + target, ex);
        }
    }

    private static void deleteOnRollback(Path target) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        Files.deleteIfExists(target);
                    } catch (IOException ex) {
                        log.warn("No se pudo borrar el archivo huérfano {}: {}", target, ex.getMessage());
                    }
                }
            }
        });
    }

    private static byte[] readBytes(MultipartFile file) {
        try (InputStream input = file.getInputStream()) {
            return input.readNBytes((int) MAX_BYTES + 1);
        } catch (IOException ex) {
            throw invalid(MSG_READ_ERROR);
        }
    }

    private static String cleanOriginalName(String originalName, ImageType type) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").strip();
        if (name.isEmpty()) {
            name = "imagen." + type.extension;
        }
        return name.length() <= MAX_ORIGINAL_NAME ? name : name.substring(name.length() - MAX_ORIGINAL_NAME);
    }

    private static String baseType(String contentType) {
        int separator = contentType.indexOf(';');
        String base = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return base.strip().toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        return bytes.length >= offset + prefix.length
                && Arrays.equals(bytes, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(ErrorCodes.INVALID_FILE, message);
    }
}
