package com.gondolia.security;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.Branch;
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
import org.springframework.http.HttpStatus;
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
    public static final String MSG_INVALID_KEY = "La API key no es válida o la sucursal no está habilitada";

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
        return findBranch(rawKey)
                .filter(Branch::isActive)
                .filter(branch -> tenantStatus(branch).map(status -> status == TenantStatus.ACTIVE).orElse(false))
                .map(ApiKeyService::toPosBranch);
    }

    /**
     * Autentica un pedido del webhook POS (SPEC §6.4) distinguiendo por qué se rechaza: una key que no sirve es 401,
     * pero una key válida de un comercio bloqueado es 403 con el código del bloqueo (SPEC §3.2), para que el
     * integrador no regenere keys que están bien.
     *
     * @throws ApiException 401 {@code INVALID_API_KEY} si la key falta, no tiene el formato, no existe o su sucursal
     *                      está desactivada; 403 {@code TENANT_DISABLED} / {@code TENANT_CANCELLED} si es de un
     *                      comercio deshabilitado o dado de baja
     */
    @Transactional(readOnly = true)
    public PosBranch authenticate(String rawKey) {
        Branch branch = findBranch(rawKey).orElseThrow(ApiKeyService::invalidKey);
        TenantStatus status = tenantStatus(branch).orElseThrow(ApiKeyService::invalidKey);
        UserAccessValidator.requireTenantActive(status);
        if (!branch.isActive()) {
            throw invalidKey();
        }
        return toPosBranch(branch);
    }

    /** Sucursal cuya key coincide (activa o no); vacío si la key falta, no tiene el formato o no existe. */
    private Optional<Branch> findBranch(String rawKey) {
        if (rawKey == null) {
            return Optional.empty();
        }
        String key = rawKey.strip();
        if (!key.startsWith(KEY_PREFIX) || key.length() != KEY_PREFIX.length() + RANDOM_LENGTH) {
            return Optional.empty();
        }
        return branchRepository.findByPosApiKeyHash(hash(key));
    }

    private Optional<TenantStatus> tenantStatus(Branch branch) {
        return tenantRepository.findStatusById(branch.getTenantId());
    }

    private static PosBranch toPosBranch(Branch branch) {
        return new PosBranch(branch.getTenantId(), branch.getId());
    }

    private static ApiException invalidKey() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.INVALID_API_KEY, MSG_INVALID_KEY);
    }
}
