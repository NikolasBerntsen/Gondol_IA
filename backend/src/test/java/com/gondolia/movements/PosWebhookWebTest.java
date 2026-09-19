package com.gondolia.movements;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.config.AppProperties;
import com.gondolia.config.ClockConfig;
import com.gondolia.config.CorsConfig;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.modules.ModuleCatalog;
import com.gondolia.modules.ModuleService;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookRequest;
import com.gondolia.movements.dto.PosIntegrationDtos.PosWebhookResponse;
import com.gondolia.security.ApiKeyService;
import com.gondolia.security.GeneratedApiKey;
import com.gondolia.security.JwtService;
import com.gondolia.security.SecurityConfig;
import com.gondolia.security.SecurityErrorHandler;
import com.gondolia.security.UserAccessValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Webhook del POS externo (SPEC §6.4) sobre HTTP con el {@link ApiKeyService} real: la key y el módulo se verifican
 * antes de leer el cuerpo (401 {@code INVALID_API_KEY} sea cual sea el payload) y una key válida de un comercio
 * bloqueado responde el código del bloqueo (SPEC §3.2), no {@code INVALID_API_KEY}.
 */
@WebMvcTest(controllers = PosWebhookController.class)
@Import({SecurityConfig.class, SecurityErrorHandler.class, JwtService.class, ClockConfig.class, CorsConfig.class,
        ApiKeyService.class, PosWebhookWebTest.TestConfig.class})
class PosWebhookWebTest {

    private static final String URL = "/api/integrations/pos/sales";
    private static final String VALID_BODY = "{\"externalId\":\"T-1\",\"items\":[{\"barcode\":\"779\",\"quantity\":2}]}";
    private static final long TENANT = 2L;
    private static final long BRANCH = 3L;

    @TestConfiguration
    @EnableConfigurationProperties(AppProperties.class)
    static class TestConfig {
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ApiKeyService apiKeyService;
    @MockitoBean
    private UserAccessValidator userAccessValidator;
    @MockitoBean
    private BranchRepository branchRepository;
    @MockitoBean
    private TenantRepository tenantRepository;
    @MockitoBean
    private ModuleService moduleService;
    @MockitoBean
    private PosIntegrationService posIntegrationService;

    @Test
    void withoutAValidKeyTheAnswerIs401WhateverThePayload() throws Exception {
        GeneratedApiKey unknown = apiKeyService.generate();
        GeneratedApiKey inactiveBranch = keyFor(TenantStatus.ACTIVE, false);

        for (String key : new String[] {null, "", "gk_corta", unknown.rawKey(), inactiveBranch.rawKey()}) {
            for (String body : new String[] {"{}", "{no es json", VALID_BODY}) {
                mvc.perform(webhook(key).content(body))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.code").value(ErrorCodes.INVALID_API_KEY))
                        .andExpect(jsonPath("$.message").value(ApiKeyService.MSG_INVALID_KEY))
                        .andExpect(jsonPath("$.fieldErrors").isEmpty());
            }
        }
        mvc.perform(post(URL)).andExpect(status().isUnauthorized());

        verifyNoInteractions(moduleService, posIntegrationService);
    }

    @Test
    void aKeyOfADisabledTenantAnswersTenantDisabled() throws Exception {
        GeneratedApiKey key = keyFor(TenantStatus.DISABLED, true);

        for (String body : new String[] {"{}", VALID_BODY}) {
            mvc.perform(webhook(key.rawKey()).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(ErrorCodes.TENANT_DISABLED))
                    .andExpect(jsonPath("$.message").value(UserAccessValidator.MSG_TENANT_DISABLED));
        }
        verifyNoInteractions(posIntegrationService);
    }

    @Test
    void aKeyOfACancelledTenantAnswersTenantCancelled() throws Exception {
        GeneratedApiKey key = keyFor(TenantStatus.CANCELLED, true);

        mvc.perform(webhook(key.rawKey()).content(VALID_BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.TENANT_CANCELLED))
                .andExpect(jsonPath("$.message").value(UserAccessValidator.MSG_TENANT_CANCELLED));
        verifyNoInteractions(posIntegrationService);
    }

    @Test
    void theModuleIsCheckedBeforeTheBody() throws Exception {
        GeneratedApiKey key = keyFor(TenantStatus.ACTIVE, true);
        doThrow(new ForbiddenException(ErrorCodes.MODULE_DISABLED, ModuleCatalog.MSG_MODULE_DISABLED))
                .when(moduleService).require(TENANT, TenantModule.POS_INTEGRATION);

        mvc.perform(webhook(key.rawKey()).content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCodes.MODULE_DISABLED));
        verifyNoInteractions(posIntegrationService);
    }

    @Test
    void aValidKeyGetsTheValidationErrorsAndThenTheSale() throws Exception {
        GeneratedApiKey key = keyFor(TenantStatus.ACTIVE, true);
        when(posIntegrationService.receive(eq(TENANT), eq(BRANCH), any(PosWebhookRequest.class)))
                .thenReturn(new PosWebhookResponse("S-EXT-T-1", 1, 2, new BigDecimal("100.00"), List.of(),
                        List.of()));

        mvc.perform(webhook(key.rawKey()).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCodes.VALIDATION_ERROR))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("items"));
        verifyNoInteractions(posIntegrationService);

        mvc.perform(webhook(" " + key.rawKey() + " ").content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchRef").value("S-EXT-T-1"));
        verify(moduleService, times(2)).require(TENANT, TenantModule.POS_INTEGRATION);
        verify(posIntegrationService).receive(eq(TENANT), eq(BRANCH), any(PosWebhookRequest.class));
    }

    private GeneratedApiKey keyFor(TenantStatus status, boolean branchActive) {
        GeneratedApiKey key = apiKeyService.generate();
        Branch branch = new Branch();
        branch.setId(BRANCH);
        branch.setTenantId(TENANT);
        branch.setName("Centro");
        branch.setActive(branchActive);
        when(branchRepository.findByPosApiKeyHash(key.hash())).thenReturn(Optional.of(branch));
        when(tenantRepository.findStatusById(TENANT)).thenReturn(Optional.of(status));
        return key;
    }

    private static MockHttpServletRequestBuilder webhook(String apiKey) {
        MockHttpServletRequestBuilder request = post(URL).contentType(MediaType.APPLICATION_JSON);
        return apiKey == null ? request : request.header(ApiKeyService.HEADER, apiKey);
    }
}
