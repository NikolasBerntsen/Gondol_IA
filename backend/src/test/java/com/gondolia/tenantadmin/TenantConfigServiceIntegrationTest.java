package com.gondolia.tenantadmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.AuthUser;
import com.gondolia.tenantadmin.dto.TenantAccountDto;
import com.gondolia.tenantadmin.dto.TenantSettingsDto;
import com.gondolia.tenantadmin.dto.TenantSettingsRequest;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantConfigService} contra PostgreSQL: rotación FIFO/FEFO, coherencia de los umbrales de vencimiento y
 * datos administrativos del comercio (con módulos y abono estimado).
 */
@Transactional
class TenantConfigServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TenantConfigService configService;
    @Autowired
    private ModuleService moduleService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EntityManager entityManager;

    private TestData data;
    private long tenant;
    private AuthUser admin;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        tenant = data.tenant("Configuración", "DIETETICA", "PROFESIONAL", "ACTIVE", "FIFO");
        data.branch(tenant, "Centro", true);
        admin = data.user(tenant, Role.TENANT_ADMIN, true);
        authenticate(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsTheDefaultsFromTheSchema() {
        TenantSettingsDto settings = configService.settings();

        assertThat(settings.currency()).isEqualTo("ARS");
        assertThat(settings.stockRotation()).isEqualTo(StockRotation.FIFO);
        assertThat(settings.expiryWarningDays()).isEqualTo(15);
        assertThat(settings.expiryCriticalDays()).isEqualTo(5);
        assertThat(settings.serviceLevel()).isEqualByComparingTo("0.950");
        assertThat(settings.maxDiscountPct()).isEqualTo(40);
    }

    @Test
    void savesTheRotationAndTheThresholds() {
        TenantSettingsDto saved = configService.updateSettings(new TenantSettingsRequest(null, StockRotation.FEFO,
                20, 7, 5, 21, new BigDecimal("0.980"), 30));

        assertThat(saved.stockRotation()).isEqualTo(StockRotation.FEFO);
        assertThat(saved.expiryWarningDays()).isEqualTo(20);
        assertThat(saved.expiryCriticalDays()).isEqualTo(7);
        assertThat(saved.defaultLeadTimeDays()).isEqualTo(5);
        assertThat(saved.targetCoverageDays()).isEqualTo(21);
        assertThat(saved.serviceLevel()).isEqualByComparingTo("0.980");
        assertThat(saved.maxDiscountPct()).isEqualTo(30);
        assertThat(configService.settings().stockRotation()).isEqualTo(StockRotation.FEFO);

        entityManager.flush();
        assertThat(jdbc.queryForObject("select stock_rotation from tenant_settings where tenant_id = ?", String.class,
                tenant)).isEqualTo("FEFO");
    }

    @Test
    void rejectsCriticalDaysGreaterThanOrEqualToWarningDays() {
        assertThatThrownBy(() -> configService.updateSettings(new TenantSettingsRequest("ARS", StockRotation.FIFO,
                5, 5, 3, 14, new BigDecimal("0.950"), 40)))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(400);
                    assertThat(error.getCode()).isEqualTo(ErrorCodes.VALIDATION_ERROR);
                });
    }

    @Test
    void accountShowsThePlanTheModulesAndTheEstimatedFee() {
        moduleService.setEnabled(tenant, TenantModule.POS_GONDOLIA, true, admin.id());

        TenantAccountDto account = configService.account();

        assertThat(account.id()).isEqualTo(tenant);
        assertThat(account.plan()).isEqualTo(TenantPlan.PROFESIONAL);
        assertThat(account.modules()).containsExactly(TenantModule.POS_GONDOLIA);
        assertThat(account.activeBranches()).isEqualTo(1);
        // Sin MULTI_BRANCH el máximo efectivo es 1 aunque el plan permita 10.
        assertThat(account.maxBranches()).isEqualTo(1);
        // (55.000 del plan + 12.000 del POS GondolIA) x 1 sucursal activa.
        assertThat(account.estimatedMonthlyFee()).isEqualByComparingTo("67000");
    }

    private static void authenticate(AuthUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.authorities()));
    }
}
