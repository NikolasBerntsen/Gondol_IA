package com.gondolia.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gondolia.common.error.ApiException;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.TenantEventType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.it.PostgresIntegrationTest;
import com.gondolia.it.TestData;
import com.gondolia.platform.dto.CreateTenantRequest;
import com.gondolia.platform.dto.CreateTenantRequest.BranchRequest;
import com.gondolia.platform.dto.CreateTenantRequest.NewUserRequest;
import com.gondolia.platform.dto.TemporaryPasswordResponse;
import com.gondolia.platform.dto.TenantDetail;
import com.gondolia.platform.dto.TenantEventDto;
import com.gondolia.platform.dto.TenantSummary;
import com.gondolia.platform.dto.UpdateTenantRequest;
import com.gondolia.security.AuthUser;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link TenantAdminService} contra PostgreSQL (SPEC §6.6): alta completa de un comercio, cambio de plan con el
 * límite de sucursales, transiciones de estado con su historial, eliminación definitiva y contraseña temporal del
 * administrador. Cada prueba se revierte.
 */
@Transactional
class TenantAdminServiceIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TenantAdminService service;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbc;

    private TestData data;
    private AuthUser owner;
    private String suffix;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        owner = data.user(null, Role.PLATFORM_OWNER, true);
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // ------------------------------------------------------------------ alta

    @Test
    void createBuildsTenantSettingsBranchUsersAndPresetModules() {
        TenantDetail detail = service.create(request(TenantPlan.BASICO, null), owner.id());

        assertThat(detail.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(detail.plan()).isEqualTo(TenantPlan.BASICO);
        assertThat(detail.stockRotation()).isEqualTo(StockRotation.FIFO);
        assertThat(detail.branches()).hasSize(1);
        assertThat(detail.branches().getFirst().name()).isEqualTo("Sucursal Centro");
        assertThat(detail.branches().getFirst().active()).isTrue();
        assertThat(detail.usersByRole())
                .containsEntry(Role.TENANT_BOSS, 1L)
                .containsEntry(Role.TENANT_ADMIN, 1L)
                .containsEntry(Role.TENANT_EMPLOYEE, 1L)
                .containsEntry(Role.TENANT_CASHIER, 0L);
        // Preset del plan BASICO (SPEC §14.1): los tres módulos.
        assertThat(detail.modules()).containsExactly(TenantModule.POS_GONDOLIA, TenantModule.POS_INTEGRATION,
                TenantModule.MULTI_BRANCH);
        assertThat(detail.maxBranches()).isEqualTo(3);
        // 1 sucursal activa × (25.000 del plan + 12.000 + 8.000 de los módulos)
        assertThat(detail.monthlyFee()).isEqualByComparingTo(new BigDecimal("45000"));
        // El historial arranca con el alta (el más viejo va último) y los módulos del preset quedan a nombre del
        // dueño que creó el comercio, no del "Sistema".
        assertThat(detail.events()).extracting(TenantEventDto::type)
                .containsExactly(TenantEventType.MODULE_ENABLED, TenantEventType.MODULE_ENABLED,
                        TenantEventType.MODULE_ENABLED, TenantEventType.CREATED);
        assertThat(detail.events()).extracting(TenantEventDto::actorName).containsOnly(owner.fullName());

        // El empleado queda asignado a la primera sucursal; el jefe y el administrador acceden a todas.
        Long branchId = detail.branches().getFirst().id();
        User employee = userByRole(detail.id(), Role.TENANT_EMPLOYEE);
        assertThat(jdbc.queryForObject("select count(*) from user_branches where user_id = ? and branch_id = ?",
                Long.class, employee.getId(), branchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from tenant_settings where tenant_id = ?", Long.class,
                detail.id())).isEqualTo(1);
    }

    @Test
    void createAcceptsExplicitModulesAndRotation() {
        CreateTenantRequest request = new CreateTenantRequest("Dietética " + suffix, null, null,
                BusinessType.DIETETICA, TenantPlan.PROFESIONAL, "Ana", "ana." + suffix + "@vidasana.com", null,
                null, "Córdoba", "Córdoba", null, null, user("jefe"), user("admin"), user("empleado"),
                List.of(TenantModule.MULTI_BRANCH), StockRotation.FEFO);

        TenantDetail detail = service.create(request, owner.id());

        assertThat(detail.modules()).containsExactly(TenantModule.MULTI_BRANCH);
        assertThat(detail.stockRotation()).isEqualTo(StockRotation.FEFO);
        // Sin adicionales: 1 sucursal × 55.000 del plan profesional.
        assertThat(detail.monthlyFee()).isEqualByComparingTo(new BigDecimal("55000"));
        assertThat(detail.maxBranches()).isEqualTo(10);
    }

    @Test
    void createRejectsRepeatedAndTakenEmails() {
        CreateTenantRequest request = request(TenantPlan.FREEMIUM, null);
        service.create(request, owner.id());

        CreateTenantRequest sameEmails = new CreateTenantRequest("Otro comercio " + suffix, null, null,
                BusinessType.KIOSCO, TenantPlan.FREEMIUM, null, null, null, null, null, null, null, null,
                request.boss(), user("admin2"), user("empleado2"), null, null);
        assertThatThrownBy(() -> service.create(sameEmails, owner.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining(request.boss().email());

        CreateTenantRequest repeated = new CreateTenantRequest("Tercero " + suffix, null, null, BusinessType.KIOSCO,
                TenantPlan.FREEMIUM, null, null, null, null, null, null, null, null, user("mismo"), user("mismo"),
                user("empleado3"), null, null);
        assertThatThrownBy(() -> service.create(repeated, owner.id()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("repetido");
    }

    // ------------------------------------------------------------------ edición

    @Test
    void planDowngradeBelowActiveBranchesFails() {
        TenantDetail detail = service.create(request(TenantPlan.PROFESIONAL, null), owner.id());
        data.branch(detail.id(), "Sucursal Norte " + suffix, true);

        assertThatThrownBy(() -> service.update(detail.id(), update(detail.name(), TenantPlan.FREEMIUM), owner.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode()).isEqualTo("BRANCH_LIMIT_REACHED"));

        // Con un plan que alcanza, el cambio queda registrado en el historial.
        TenantDetail updated = service.update(detail.id(), update(detail.name(), TenantPlan.BASICO), owner.id());
        assertThat(updated.plan()).isEqualTo(TenantPlan.BASICO);
        assertThat(updated.events().getFirst().type()).isEqualTo(TenantEventType.PLAN_CHANGED);
        assertThat(updated.events().getFirst().fromValue()).isEqualTo("PROFESIONAL");
        assertThat(updated.events().getFirst().toValue()).isEqualTo("BASICO");
        assertThat(updated.events().getFirst().actorName()).isEqualTo(owner.fullName());
    }

    // ------------------------------------------------------------------ estado

    @Test
    void statusFlowRecordsEventsAndBlocksInvalidTransitions() {
        TenantDetail detail = service.create(request(TenantPlan.BASICO, null), owner.id());
        Long id = detail.id();

        TenantSummary disabled = service.disable(id, "Falta de pago", owner.id());
        assertThat(disabled.status()).isEqualTo(TenantStatus.DISABLED);
        assertThat(disabled.statusReason()).isEqualTo("Falta de pago");
        assertThat(disabled.statusChangedAt()).isNotNull();
        // Un comercio bloqueado no factura.
        assertThat(disabled.monthlyFee()).isEqualByComparingTo(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.disable(id, "otra vez", owner.id())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.reactivate(id, null, owner.id())).isInstanceOf(ApiException.class);

        assertThat(service.enable(id, null, owner.id()).status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(service.cancel(id, "El cliente cerró", owner.id()).status()).isEqualTo(TenantStatus.CANCELLED);
        assertThat(service.reactivate(id, "Volvió", owner.id()).status()).isEqualTo(TenantStatus.ACTIVE);

        assertThat(service.detail(id).events()).extracting(event -> event.type())
                .containsSubsequence(TenantEventType.REACTIVATED, TenantEventType.CANCELLED, TenantEventType.ENABLED,
                        TenantEventType.DISABLED, TenantEventType.CREATED);
    }

    @Test
    void deleteNeedsCancelledTenantAndExactName() {
        TenantDetail detail = service.create(request(TenantPlan.BASICO, null), owner.id());
        Long id = detail.id();

        assertThatThrownBy(() -> service.delete(id, detail.name(), owner.id())).isInstanceOf(ApiException.class);
        service.cancel(id, "Baja del servicio", owner.id());
        assertThatThrownBy(() -> service.delete(id, "otro nombre", owner.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(TenantAdminService.CODE_CONFIRM_NAME));

        service.delete(id, detail.name(), owner.id());

        assertThat(jdbc.queryForObject("select count(*) from tenants where id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from users where tenant_id = ?", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from branches where tenant_id = ?", Long.class, id)).isZero();
        // El historial queda para las métricas, sin comercio asociado pero con su id en deleted_tenant_id.
        assertThat(jdbc.queryForObject("""
                select count(*) from tenant_events where tenant_id is null and type = 'DELETED' and to_value = ?
                """, Long.class, detail.name())).isEqualTo(1);
        assertThat(jdbc.queryForList("""
                select type from tenant_events where tenant_id is null and deleted_tenant_id = ? order by id
                """, String.class, id))
                .startsWith("CREATED")
                .endsWith("CANCELLED", "DELETED");
    }

    // ------------------------------------------------------------------ contraseñas

    @Test
    void resetAdminPasswordReturnsTemporaryOneAndForcesChange() {
        TenantDetail detail = service.create(request(TenantPlan.BASICO, null), owner.id());
        User before = userByRole(detail.id(), Role.TENANT_ADMIN);
        int tokenVersion = before.getTokenVersion();

        TemporaryPasswordResponse response = service.resetAdminPassword(detail.id(), owner.id());

        assertThat(response.email()).isEqualTo(before.getEmail());
        assertThat(response.temporaryPassword()).hasSizeGreaterThanOrEqualTo(8);
        User after = userRepository.findById(before.getId()).orElseThrow();
        assertThat(passwordEncoder.matches(response.temporaryPassword(), after.getPasswordHash())).isTrue();
        assertThat(after.isMustChangePassword()).isTrue();
        assertThat(after.getTokenVersion()).isEqualTo(tokenVersion + 1);
    }

    @Test
    void resetAdminPasswordNeedsAnActiveAdmin() {
        TenantDetail detail = service.create(request(TenantPlan.BASICO, null), owner.id());
        jdbc.update("update users set active = false where tenant_id = ? and role = 'TENANT_ADMIN'", detail.id());

        assertThatThrownBy(() -> service.resetAdminPassword(detail.id(), owner.id()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo(TenantAdminService.CODE_NO_ADMIN));
    }

    // ------------------------------------------------------------------ listado

    @Test
    void listFiltersByModuleStatusAndText() {
        TenantDetail withPos = service.create(request(TenantPlan.BASICO, null), owner.id());
        CreateTenantRequest other = new CreateTenantRequest("Kiosco " + suffix, null, null, BusinessType.KIOSCO,
                TenantPlan.FREEMIUM, null, null, null, null, "Rosario", "Santa Fe", null, null, user("jefe2"),
                user("admin3"), user("empleado4"), List.of(), null);
        TenantDetail withoutModules = service.create(other, owner.id());

        assertThat(service.list(null, null, null, null, TenantModule.POS_GONDOLIA, null, 0, 50).content())
                .extracting(TenantSummary::id)
                .contains(withPos.id())
                .doesNotContain(withoutModules.id());
        assertThat(service.list(suffix, null, null, BusinessType.KIOSCO, null, "name,asc", 0, 50).content())
                .extracting(TenantSummary::id)
                .containsExactly(withoutModules.id());

        service.disable(withPos.id(), "Prueba", owner.id());
        assertThat(service.list(suffix, TenantStatus.DISABLED, null, null, null, null, 0, 50).content())
                .extracting(TenantSummary::id)
                .containsExactly(withPos.id());
    }

    @Test
    void searchIgnoresAccentsAndCaseAndTakesWildcardsLiterally() {
        CreateTenantRequest request = new CreateTenantRequest("Almacén Ñandú " + suffix, "Pérez & Hijos 100%", null,
                BusinessType.ALMACEN, TenantPlan.FREEMIUM, null, null, null, null, "Córdoba", "Córdoba", null, null,
                user("jefe5"), user("admin5"), user("empleado5"), null, null);
        Long id = service.create(request, owner.id()).id();

        assertThat(ids("almacen nandu " + suffix)).containsExactly(id);
        assertThat(ids("ALMACÉN ÑANDÚ " + suffix.toUpperCase())).containsExactly(id);
        assertThat(ids("perez & hijos 100%")).contains(id);
        assertThat(ids("cordoba")).contains(id);
        // % y _ se buscan como texto, no como comodines de LIKE.
        assertThat(ids("almac_n nandu " + suffix)).isEmpty();
        assertThat(ids("%" + suffix)).isEmpty();
        assertThat(ids("almacen%" + suffix)).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private List<Long> ids(String q) {
        return service.list(q, null, null, null, null, null, 0, 100).content().stream().map(TenantSummary::id)
                .toList();
    }

    private CreateTenantRequest request(TenantPlan plan, List<TenantModule> modules) {
        return new CreateTenantRequest("Almacén " + suffix, "Almacén SRL", "30-1234-9", BusinessType.ALMACEN, plan,
                "José Pérez", "contacto." + suffix + "@donpepe.com", "11-5555-1234", "Av. Rivadavia 4321", "CABA",
                "Buenos Aires", "Cliente de prueba",
                new BranchRequest("Sucursal Centro", "CEN", "Av. Rivadavia 4321", "CABA", "Buenos Aires"),
                user("jefe"), user("admin"), user("empleado"), modules, null);
    }

    private UpdateTenantRequest update(String name, TenantPlan plan) {
        return new UpdateTenantRequest(name, null, null, BusinessType.ALMACEN, plan, "José Pérez",
                "contacto." + suffix + "@donpepe.com", null, null, "CABA", "Buenos Aires", null, "Ajuste de plan",
                null);
    }

    private NewUserRequest user(String prefix) {
        return new NewUserRequest("Usuario " + prefix, prefix + "." + suffix + "@donpepe.com", "Demo2026!");
    }

    private User userByRole(Long tenantId, Role role) {
        return userRepository.findByTenantIdAndRoleInAndActiveTrue(tenantId, List.of(role)).getFirst();
    }
}
