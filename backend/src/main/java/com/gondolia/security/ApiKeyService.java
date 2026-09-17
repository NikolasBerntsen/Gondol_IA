package com.gondolia.security;

import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API keys del webhook POS, una por sucursal: formato {@code gk_} + 40 caracteres base62. En
 * {@code branches.pos_api_key_hash} se guarda solo el SHA-256 y en {@code pos_api_key_prefix} los primeros 10
 * caracteres (para mostrar).
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    /** Encabezado HTTP con la API key del POS. */
    public static final String HEADER = "X-API-Key";
    public static final String KEY_PREFIX = "gk_";
    public static final int RANDOM_LENGTH = 40;
    public static final int DISPLAY_PREFIX_LENGTH = 10;

    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    private final SecureRandom secureRandom = new SecureRandom();
    private final BranchRepository branchRepository;
    private final TenantRepository tenantRepository;

    /** Genera una key nueva (no la persiste). */
    public GeneratedApiKey generate() {
        StringBuilder raw = new StringBuilder(KEY_PREFIX.length() + RANDOM_LENGTH).append(KEY_PREFIX);
        for (int i = 0; i < RANDOM_LENGTH; i++) {
            raw.append(BASE62[secureRandom.nextInt(BASE62.length)]);
        }
        String rawKey = raw.toString();
        return new GeneratedApiKey(rawKey, rawKey.substring(0, DISPLAY_PREFIX_LENGTH), hash(rawKey));
    }

    /** SHA-256 en hexadecimal (64 caracteres, minúsculas). */
    public String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    /**
     * Sucursal dueña de la key, solo si la sucursal está activa y su tenant está {@code ACTIVE}; vacío si la key no
     * tiene el formato esperado o no corresponde a ninguna sucursal habilitada.
     */
    @Transactional(readOnly = true)
    public Optional<PosBranch> resolveBranch(String rawKey) {
        if (rawKey == null) {
            return Optional.empty();
        }
        String key = rawKey.strip();
        if (!key.startsWith(KEY_PREFIX) || key.length() != KEY_PREFIX.length() + RANDOM_LENGTH) {
            return Optional.empty();
        }
        return branchRepository.findByPosApiKeyHash(hash(key))
                .filter(branch -> branch.isActive())
                .filter(branch -> tenantRepository.findStatusById(branch.getTenantId())
                        .map(status -> status == TenantStatus.ACTIVE)
                        .orElse(false))
                .map(branch -> new PosBranch(branch.getTenantId(), branch.getId()));
    }
}
