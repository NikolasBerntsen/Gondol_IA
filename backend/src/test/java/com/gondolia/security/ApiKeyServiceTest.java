package com.gondolia.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantStatus;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyServiceTest {

    @Mock
    private BranchRepository branchRepository;
    @Mock
    private TenantRepository tenantRepository;
    @InjectMocks
    private ApiKeyService service;

    @Test
    void generatesPrefixedBase62KeysWithHashAndDisplayPrefix() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            GeneratedApiKey key = service.generate();
            assertThat(key.rawKey()).matches("gk_[0-9A-Za-z]{40}");
            assertThat(key.prefix()).hasSize(10).isEqualTo(key.rawKey().substring(0, 10));
            assertThat(key.hash()).matches("[0-9a-f]{64}").isEqualTo(service.hash(key.rawKey()));
            assertThat(key.toString()).doesNotContain(key.rawKey());
            keys.add(key.rawKey());
        }
        assertThat(keys).hasSize(50);
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(service.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void resolvesActiveBranchOfActiveTenant() {
        GeneratedApiKey key = service.generate();
        Branch branch = branch(3L, 2L, true);
        when(branchRepository.findByPosApiKeyHash(key.hash())).thenReturn(Optional.of(branch));
        when(tenantRepository.findStatusById(2L)).thenReturn(Optional.of(TenantStatus.ACTIVE));

        assertThat(service.resolveBranch(" " + key.rawKey() + " ")).contains(new PosBranch(2L, 3L));
    }

    @Test
    void rejectsInactiveBranchBlockedTenantUnknownOrMalformedKeys() {
        GeneratedApiKey inactiveKey = service.generate();
        when(branchRepository.findByPosApiKeyHash(inactiveKey.hash())).thenReturn(Optional.of(branch(3L, 2L, false)));
        when(tenantRepository.findStatusById(2L)).thenReturn(Optional.of(TenantStatus.ACTIVE));

        GeneratedApiKey disabledTenantKey = service.generate();
        when(branchRepository.findByPosApiKeyHash(disabledTenantKey.hash()))
                .thenReturn(Optional.of(branch(4L, 7L, true)));
        when(tenantRepository.findStatusById(7L)).thenReturn(Optional.of(TenantStatus.DISABLED));

        assertThat(service.resolveBranch(inactiveKey.rawKey())).isEmpty();
        assertThat(service.resolveBranch(disabledTenantKey.rawKey())).isEmpty();
        assertThat(service.resolveBranch(service.generate().rawKey())).isEmpty();
        assertThat(service.resolveBranch("gk_short")).isEmpty();
        assertThat(service.resolveBranch("xx_" + "a".repeat(40))).isEmpty();
        assertThat(service.resolveBranch(null)).isEmpty();
    }

    @Test
    void authenticateReturnsTheBranchOfAValidKey() {
        GeneratedApiKey key = service.generate();
        when(branchRepository.findByPosApiKeyHash(key.hash())).thenReturn(Optional.of(branch(3L, 2L, true)));
        when(tenantRepository.findStatusById(2L)).thenReturn(Optional.of(TenantStatus.ACTIVE));

        assertThat(service.authenticate(" " + key.rawKey() + " ")).isEqualTo(new PosBranch(2L, 3L));
    }

    @Test
    void authenticateRejectsKeysThatDoNotWorkWith401() {
        GeneratedApiKey inactiveKey = service.generate();
        when(branchRepository.findByPosApiKeyHash(inactiveKey.hash())).thenReturn(Optional.of(branch(3L, 2L, false)));
        when(tenantRepository.findStatusById(2L)).thenReturn(Optional.of(TenantStatus.ACTIVE));

        for (String rawKey : new String[] {inactiveKey.rawKey(), service.generate().rawKey(), "gk_short",
                "xx_" + "a".repeat(40), "", null}) {
            assertThatThrownBy(() -> service.authenticate(rawKey))
                    .as("key %s", rawKey)
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(ex.getCode()).isEqualTo(ErrorCodes.INVALID_API_KEY);
                    });
        }
    }

    /** SPEC §3.2: la key sirve, lo que está bloqueado es el comercio; el integrador no tiene que regenerarla. */
    @Test
    void authenticateAnswersTheBlockOfTheTenantWith403() {
        GeneratedApiKey disabledKey = service.generate();
        when(branchRepository.findByPosApiKeyHash(disabledKey.hash())).thenReturn(Optional.of(branch(4L, 7L, true)));
        when(tenantRepository.findStatusById(7L)).thenReturn(Optional.of(TenantStatus.DISABLED));
        GeneratedApiKey cancelledKey = service.generate();
        when(branchRepository.findByPosApiKeyHash(cancelledKey.hash())).thenReturn(Optional.of(branch(5L, 8L, false)));
        when(tenantRepository.findStatusById(8L)).thenReturn(Optional.of(TenantStatus.CANCELLED));

        assertThatThrownBy(() -> service.authenticate(disabledKey.rawKey()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.TENANT_DISABLED);
                    assertThat(ex.getMessage()).isEqualTo(UserAccessValidator.MSG_TENANT_DISABLED);
                });
        assertThatThrownBy(() -> service.authenticate(cancelledKey.rawKey()))
                .as("el bloqueo del comercio pesa más que la sucursal desactivada")
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(ex.getCode()).isEqualTo(ErrorCodes.TENANT_CANCELLED);
                    assertThat(ex.getMessage()).isEqualTo(UserAccessValidator.MSG_TENANT_CANCELLED);
                });
        assertThat(service.resolveBranch(disabledKey.rawKey())).as("resolveBranch sigue sin devolverla").isEmpty();
    }

    private static Branch branch(Long id, Long tenantId, boolean active) {
        Branch branch = new Branch();
        branch.setId(id);
        branch.setTenantId(tenantId);
        branch.setName("Sucursal " + id);
        branch.setActive(active);
        return branch;
    }
}
