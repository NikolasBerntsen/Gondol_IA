package com.gondolia.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gondolia.common.error.ApiException;
import com.gondolia.config.AppProperties;
import com.gondolia.domain.support.Attachment;
import com.gondolia.domain.support.AttachmentPurpose;
import com.gondolia.domain.support.AttachmentRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.security.AuthUser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class AttachmentStorageServiceTest {

    private static final byte[] PNG = bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R');
    private static final byte[] JPEG = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16, 'J', 'F', 'I', 'F');
    private static final byte[] GIF = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] WEBP = "RIFF\u0024\u0000\u0000\u0000WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);

    private static final AuthUser TENANT_USER = new AuthUser(10L, "a@b.com", "A", Role.TENANT_EMPLOYEE, 5L);
    private static final AuthUser OTHER_TENANT_USER = new AuthUser(11L, "c@d.com", "C", Role.TENANT_ADMIN, 6L);
    private static final AuthUser AGENT = new AuthUser(2L, "s@gondolia.app", "S", Role.SUPPORT_AGENT, null);
    private static final AuthUser OWNER = new AuthUser(1L, "o@gondolia.app", "O", Role.PLATFORM_OWNER, null);

    @TempDir
    Path storageDir;

    private final AttachmentRepository repository = mock(AttachmentRepository.class);
    private AttachmentStorageService service;

    @BeforeEach
    void setUp() {
        when(repository.save(any(Attachment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("x".repeat(40), 12),
                new AppProperties.Ai("http://localhost:8000"),
                new AppProperties.Storage(storageDir.toString()),
                "America/Argentina/Buenos_Aires", false, false, false,
                new AppProperties.Bootstrap(null, null),
                new AppProperties.Cors(List.of()));
        Clock clock = Clock.fixed(Instant.parse("2026-09-17T12:00:00Z"), ZoneId.of("America/Argentina/Buenos_Aires"));
        service = new AttachmentStorageService(repository, properties, clock);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void storesValidImagesUnderYearAndMonthUsingTheRealType() throws Exception {
        Attachment attachment = service.store(new MockMultipartFile("file", "C:\\fotos\\etiqueta.png", "image/jpeg", PNG),
                AttachmentPurpose.SUPPORT, 5L, 10L);

        assertThat(attachment.getStorageKey()).matches("2026/09/[0-9a-f-]{36}\\.png");
        assertThat(attachment.getContentType()).isEqualTo("image/png");
        assertThat(attachment.getOriginalName()).isEqualTo("etiqueta.png");
        assertThat(attachment.getSizeBytes()).isEqualTo(PNG.length);
        assertThat(attachment).extracting(Attachment::getTenantId, Attachment::getUploadedBy, Attachment::getPurpose)
                .containsExactly(5L, 10L, AttachmentPurpose.SUPPORT);
        assertThat(Files.readAllBytes(storageDir.resolve(attachment.getStorageKey()))).isEqualTo(PNG);
        assertThat(service.load(attachment).getContentAsByteArray()).isEqualTo(PNG);
    }

    @Test
    void detectsEveryAcceptedFormatByItsSignature() {
        assertThat(AttachmentStorageService.detect(PNG)).isEqualTo(AttachmentStorageService.ImageType.PNG);
        assertThat(AttachmentStorageService.detect(JPEG)).isEqualTo(AttachmentStorageService.ImageType.JPEG);
        assertThat(AttachmentStorageService.detect(GIF)).isEqualTo(AttachmentStorageService.ImageType.GIF);
        assertThat(AttachmentStorageService.detect(WEBP)).isEqualTo(AttachmentStorageService.ImageType.WEBP);
        assertThat(AttachmentStorageService.detect("RIFF0000WAVE".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(AttachmentStorageService.detect("<svg></svg>".getBytes(StandardCharsets.US_ASCII))).isNull();
        assertThat(AttachmentStorageService.detect(new byte[] {(byte) 0xFF})).isNull();

        Attachment webp = service.store(new MockMultipartFile("file", "foto", null, WEBP), AttachmentPurpose.OCR, 5L, 10L);
        assertThat(webp.getStorageKey()).endsWith(".webp");
        assertThat(webp.getOriginalName()).isEqualTo("foto");
    }

    @Test
    void rejectsFilesThatAreNotAcceptedImages() {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        assertInvalid(() -> service.store(new MockMultipartFile("file", "x.png", "image/png", html),
                AttachmentPurpose.SUPPORT, 5L, 10L), AttachmentStorageService.MSG_INVALID_TYPE);
        assertInvalid(() -> service.store(new MockMultipartFile("file", "x.html", "text/html", PNG),
                AttachmentPurpose.SUPPORT, 5L, 10L), AttachmentStorageService.MSG_INVALID_TYPE);
        assertInvalid(() -> service.store(new MockMultipartFile("file", "x.png", "image/png", new byte[0]),
                AttachmentPurpose.SUPPORT, 5L, 10L), AttachmentStorageService.MSG_EMPTY);
        assertInvalid(() -> service.store(null, AttachmentPurpose.SUPPORT, 5L, 10L), AttachmentStorageService.MSG_EMPTY);
        byte[] huge = new byte[(int) AttachmentStorageService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, huge, 0, PNG.length);
        assertInvalid(() -> service.store(new MockMultipartFile("file", "big.png", "image/png", huge),
                AttachmentPurpose.SUPPORT, 5L, 10L), AttachmentStorageService.MSG_TOO_LARGE);
    }

    @Test
    void deletesTheFileWhenTheTransactionRollsBack() throws Exception {
        TransactionSynchronizationManager.initSynchronization();
        Attachment attachment = service.store(new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG),
                AttachmentPurpose.SUPPORT, 5L, 10L);
        Path file = storageDir.resolve(attachment.getStorageKey());
        assertThat(file).exists();

        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationUtils.invokeAfterCompletion(synchronizations,
                TransactionSynchronization.STATUS_ROLLED_BACK);

        assertThat(file).doesNotExist();
        assertThatThrownBy(() -> service.load(attachment)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getCode()).isEqualTo("NOT_FOUND"));
    }

    @Test
    void accessRulesByPurpose() {
        Attachment support = attachment(AttachmentPurpose.SUPPORT, 5L);
        Attachment ocr = attachment(AttachmentPurpose.OCR, 5L);
        Attachment agentUpload = attachment(AttachmentPurpose.SUPPORT, null);

        assertThat(AttachmentStorageService.canRead(support, TENANT_USER)).isTrue();
        assertThat(AttachmentStorageService.canRead(support, AGENT)).isTrue();
        assertThat(AttachmentStorageService.canRead(support, OTHER_TENANT_USER)).isFalse();
        assertThat(AttachmentStorageService.canRead(support, OWNER)).isFalse();
        assertThat(AttachmentStorageService.canRead(ocr, TENANT_USER)).isTrue();
        assertThat(AttachmentStorageService.canRead(ocr, AGENT)).isFalse();
        assertThat(AttachmentStorageService.canRead(ocr, OTHER_TENANT_USER)).isFalse();
        assertThat(AttachmentStorageService.canRead(agentUpload, AGENT)).isTrue();
        assertThat(AttachmentStorageService.canRead(agentUpload, TENANT_USER)).isFalse();

        when(repository.findById(1L)).thenReturn(Optional.of(support));
        assertThat(service.findReadable(1L, TENANT_USER)).isSameAs(support);
        assertThatThrownBy(() -> service.findReadable(1L, OTHER_TENANT_USER)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
        assertThatThrownBy(() -> service.findReadable(2L, TENANT_USER)).isInstanceOf(ApiException.class);
    }

    @Test
    void storageKeysCannotEscapeTheStorageDirectory() {
        Attachment evil = attachment(AttachmentPurpose.SUPPORT, 5L);
        evil.setStorageKey("../../etc/passwd");

        assertThatThrownBy(() -> service.load(evil)).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.getStatus().value()).isEqualTo(404));
    }

    private static Attachment attachment(AttachmentPurpose purpose, Long tenantId) {
        Attachment attachment = new Attachment();
        attachment.setPurpose(purpose);
        attachment.setTenantId(tenantId);
        attachment.setStorageKey("2026/09/x.png");
        attachment.setContentType("image/png");
        return attachment;
    }

    private static void assertInvalid(ThrowingCallable call, String message) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, ex -> {
            assertThat(ex.getStatus().value()).isEqualTo(400);
            assertThat(ex.getCode()).isEqualTo("INVALID_FILE");
            assertThat(ex.getMessage()).isEqualTo(message);
        });
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }
}
