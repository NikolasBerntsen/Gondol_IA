package com.gondolia.platform;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.ForbiddenException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.events.TenantStatusChangedEvent;
import com.gondolia.common.util.Emails;
import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.StockRotation;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantEvent;
import com.gondolia.domain.tenant.TenantEventRepository;
import com.gondolia.domain.tenant.TenantEventType;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.domain.tenant.TenantStatus;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserBranch;
import com.gondolia.domain.user.UserBranchRepository;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.modules.ModuleCatalog;
import com.gondolia.modules.ModuleService;
import com.gondolia.platform.PlatformQueries.TenantFilters;
import com.gondolia.platform.PlatformQueries.TenantRow;
import com.gondolia.platform.dto.CreateTenantRequest;
import com.gondolia.platform.dto.TemporaryPasswordResponse;
import com.gondolia.platform.dto.TenantBranchDto;
import com.gondolia.platform.dto.TenantDetail;
import com.gondolia.platform.dto.TenantEventDto;
import com.gondolia.platform.dto.TenantSummary;
import com.gondolia.platform.dto.TenantUserDto;
import com.gondolia.platform.dto.UpdateTenantRequest;
import com.gondolia.realtime.SessionTerminationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Alta, edición, bloqueo, baja y eliminación de comercios desde la consola de dueños (SPEC §6.6).
 * <p>
 * Expone <b>solo datos administrativos</b>: nombre, contacto, plan, estado, módulos, cantidad de usuarios y
 * sucursales, última actividad e historial. Nunca productos, stock, ventas, alertas ni chats (SPEC §3.4.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TenantAdminService {

    static final String MSG_NOT_FOUND = "El comercio no existe";
    static final String MSG_DISABLED = "El acceso de tu comercio está deshabilitado. Comunicate con GondolIA.";
    static final String MSG_CANCELLED = "Tu comercio fue dado de baja del servicio. Comunicate con GondolIA.";
    static final String CODE_EMAIL_TAKEN = "EMAIL_TAKEN";
    static final String CODE_NAME_TAKEN = "NAME_TAKEN";
    static final String CODE_NO_ADMIN = "NO_ADMIN";
    static final String CODE_CONFIRM_NAME = "CONFIRM_NAME_MISMATCH";
    static final String CODE_INVALID_STATUS = "INVALID_STATUS_CHANGE";

    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository settingsRepository;
    private final TenantEventRepository eventRepository;
    private final BranchRepository branchRepository;
    private final UserRepository userRepository;
    private final UserBranchRepository userBranchRepository;
    private final ModuleService moduleService;
    private final PlatformQueries queries;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher events;
    private final SessionTerminationService sessionTermination;
    private final Clock clock;

    // ------------------------------------------------------------------ lecturas

    /** Listado paginado de comercios (SPEC §6.6). */
    public PageResponse<TenantSummary> list(String q, TenantStatus status, TenantPlan plan, BusinessType businessType,
                                            TenantModule module, String sort, int page, int size) {
        TenantFilters filters = TenantFilters.of(q, status, plan, businessType, module);
        long total = queries.count(filters);
        if (total == 0) {
            return PageResponse.empty(page, size);
        }
        List<TenantRow> rows = queries.search(filters, sort, page, size);
        Map<Long, Set<TenantModule>> modules = queries.enabledModules(rows.stream().map(TenantRow::id).toList());
        List<TenantSummary> content = rows.stream()
                .map(row -> toSummary(row, modules.getOrDefault(row.id(), EnumSet.noneOf(TenantModule.class))))
                .toList();
        return PageResponse.of(content, page, size, total);
    }

    /** Detalle administrativo de un comercio. */
    public TenantDetail detail(Long tenantId) {
        TenantRow row = requireRow(tenantId);
        Set<TenantModule> modules = queries.enabledModules(List.of(tenantId))
                .getOrDefault(tenantId, EnumSet.noneOf(TenantModule.class));
        TenantSummary summary = toSummary(row, modules);
        StockRotation rotation = settingsRepository.findById(tenantId)
                .map(TenantSettings::getStockRotation)
                .orElse(StockRotation.FIFO);
        List<TenantBranchDto> branches = branchRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .map(branch -> new TenantBranchDto(branch.getId(), branch.getName(), branch.getCode(),
                        branch.getCity(), branch.isActive(), branch.getCreatedAt()))
                .toList();
        List<User> users = userRepository.findByTenantIdOrderByFullNameAsc(tenantId);
        List<TenantUserDto> userDtos = users.stream()
                .sorted(Comparator.comparing((User user) -> user.getRole().ordinal())
                        .thenComparing(User::getFullName, Comparator.nullsLast(String::compareToIgnoreCase)))
                .map(user -> new TenantUserDto(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
                        user.isActive(), user.getLastLoginAt(), user.getCreatedAt()))
                .toList();
        return TenantDetail.of(summary, row.legalName(), row.taxId(), row.address(), row.notes(),
                moduleService.effectiveMaxBranches(tenantId), rotation, queries.usersByRole(tenantId), branches,
                userDtos, eventsOf(tenantId));
    }

    /** Resumen de un comercio (lo usan las acciones de estado para devolver la fila actualizada). */
    public TenantSummary summary(Long tenantId) {
        TenantRow row = requireRow(tenantId);
        return toSummary(row, queries.enabledModules(List.of(tenantId))
                .getOrDefault(tenantId, EnumSet.noneOf(TenantModule.class)));
    }

    // ------------------------------------------------------------------ alta y edición

    /** Alta de comercio: tenant + configuración + primera sucursal + jefe, administrador y empleado + módulos. */
    @Transactional
    public TenantDetail create(CreateTenantRequest request, Long actorUserId) {
        String name = request.name().strip();
        if (tenantRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException(CODE_NAME_TAKEN, "Ya hay un comercio registrado con el nombre «" + name + "»");
        }
        Map<String, CreateTenantRequest.NewUserRequest> byEmail = new HashMap<>();
        checkEmail(request.boss(), byEmail);
        checkEmail(request.admin(), byEmail);
        checkEmail(request.employee(), byEmail);

        Tenant tenant = new Tenant();
        tenant.setName(name);
        applyAdminData(tenant, name, request.legalName(), request.taxId(), request.businessType(),
                request.contactName(), request.contactEmail(), request.contactPhone(), request.address(),
                request.city(), request.province(), request.notes());
        tenant.setPlan(request.plan());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setStatusChangedAt(Instant.now(clock));
        tenantRepository.saveAndFlush(tenant);

        TenantSettings settings = TenantSettings.defaultsFor(tenant.getId());
        if (request.stockRotation() != null) {
            settings.setStockRotation(request.stockRotation());
        }
        settingsRepository.save(settings);

        Branch branch = createFirstBranch(tenant, request.firstBranch());
        createUser(tenant.getId(), request.boss(), Role.TENANT_BOSS, null);
        createUser(tenant.getId(), request.admin(), Role.TENANT_ADMIN, null);
        createUser(tenant.getId(), request.employee(), Role.TENANT_EMPLOYEE, branch.getId());

        // El alta va primero en el historial y los módulos iniciales (los pedidos o el preset del plan, SPEC §14.1)
        // quedan a nombre del dueño que creó el comercio, no del "Sistema".
        record(tenant.getId(), TenantEventType.CREATED, null, request.plan().name(), null, actorUserId);
        Set<TenantModule> initialModules = request.modules() == null ? ModuleCatalog.preset(request.plan())
                : request.modules().stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(TenantModule.class)));
        for (TenantModule module : TenantModule.values()) {
            moduleService.setEnabled(tenant.getId(), module, initialModules.contains(module), actorUserId);
        }
        log.info("Alta del comercio {} ({}) con plan {}", tenant.getId(), tenant.getName(), tenant.getPlan());
        return detail(tenant.getId());
    }

    /**
     * Edición de datos administrativos y del plan. Cambiar el plan registra {@code PLAN_CHANGED} y es una decisión
     * comercial: solo la toma un dueño. Soporte edita los datos mandando el plan actual; otro plan responde 403.
     */
    @Transactional
    public TenantDetail update(Long tenantId, UpdateTenantRequest request, Long actorUserId) {
        Tenant tenant = requireTenant(tenantId);
        String name = request.name().strip();
        if (!tenant.getName().equalsIgnoreCase(name) && tenantRepository.existsByNameIgnoreCase(name)) {
            throw new ConflictException(CODE_NAME_TAKEN, "Ya hay un comercio registrado con el nombre «" + name + "»");
        }
        TenantPlan previousPlan = tenant.getPlan();
        if (previousPlan != request.plan()) {
            if (!isOwner(actorUserId)) {
                throw new ForbiddenException(ErrorCodes.FORBIDDEN, "El plan de un cliente lo cambia un dueño de "
                        + "GondolIA: pedíselo y guardá el resto de los datos");
            }
            long activeBranches = branchRepository.countByTenantIdAndActiveTrue(tenantId);
            if (activeBranches > request.plan().maxBranches()) {
                throw new ConflictException(ErrorCodes.BRANCH_LIMIT_REACHED, "El plan "
                        + planLabel(request.plan()) + " permite " + request.plan().maxBranches()
                        + (request.plan().maxBranches() == 1 ? " sucursal activa" : " sucursales activas")
                        + " y el comercio tiene " + activeBranches
                        + ": desactivá las sucursales que sobran antes de bajar el plan");
            }
        }
        applyAdminData(tenant, name, request.legalName(), request.taxId(), request.businessType(),
                request.contactName(), request.contactEmail(), request.contactPhone(), request.address(),
                request.city(), request.province(), request.notes());
        tenant.setPlan(request.plan());
        tenantRepository.saveAndFlush(tenant);

        if (request.stockRotation() != null) {
            TenantSettings settings = settingsRepository.findById(tenantId)
                    .orElseGet(() -> TenantSettings.defaultsFor(tenantId));
            settings.setStockRotation(request.stockRotation());
            settingsRepository.save(settings);
        }
        if (previousPlan != request.plan()) {
            record(tenantId, TenantEventType.PLAN_CHANGED, previousPlan.name(), request.plan().name(),
                    request.planChangeReason(), actorUserId);
            log.info("El comercio {} pasó del plan {} al plan {}", tenantId, previousPlan, request.plan());
        }
        return detail(tenantId);
    }

    // ------------------------------------------------------------------ estado

    /** Deshabilita el acceso del comercio: sus usuarios quedan bloqueados y se cierran sus sesiones. */
    @Transactional
    public TenantSummary disable(Long tenantId, String reason, Long actorUserId) {
        return changeStatus(tenantId, TenantStatus.DISABLED, EnumSet.of(TenantStatus.ACTIVE),
                TenantEventType.DISABLED, reason, actorUserId,
                "El comercio ya está deshabilitado", ErrorCodes.TENANT_DISABLED, MSG_DISABLED);
    }

    /** Vuelve a habilitar un comercio deshabilitado. */
    @Transactional
    public TenantSummary enable(Long tenantId, String reason, Long actorUserId) {
        return changeStatus(tenantId, TenantStatus.ACTIVE, EnumSet.of(TenantStatus.DISABLED),
                TenantEventType.ENABLED, reason, actorUserId,
                "Solo se puede habilitar un comercio deshabilitado", null, null);
    }

    /** Da de baja el servicio (churn). Reversible con {@link #reactivate}. */
    @Transactional
    public TenantSummary cancel(Long tenantId, String reason, Long actorUserId) {
        return changeStatus(tenantId, TenantStatus.CANCELLED, EnumSet.of(TenantStatus.ACTIVE, TenantStatus.DISABLED),
                TenantEventType.CANCELLED, reason, actorUserId,
                "El comercio ya está dado de baja", ErrorCodes.TENANT_CANCELLED, MSG_CANCELLED);
    }

    /** Reactiva un comercio dado de baja. */
    @Transactional
    public TenantSummary reactivate(Long tenantId, String reason, Long actorUserId) {
        return changeStatus(tenantId, TenantStatus.ACTIVE, EnumSet.of(TenantStatus.CANCELLED),
                TenantEventType.REACTIVATED, reason, actorUserId,
                "Solo se puede reactivar un comercio dado de baja", null, null);
    }

    /**
     * Eliminación definitiva (solo comercios dados de baja y escribiendo el nombre exacto). Borra usuarios,
     * sucursales y todos sus datos; el historial queda en {@code tenant_events} con {@code tenant_id} nulo y el id
     * del comercio en {@code deleted_tenant_id}, para las métricas de crecimiento.
     */
    @Transactional
    public void delete(Long tenantId, String confirmName, Long actorUserId) {
        Tenant tenant = requireTenant(tenantId);
        if (tenant.getStatus() != TenantStatus.CANCELLED) {
            throw new ConflictException(CODE_INVALID_STATUS,
                    "Solo se puede eliminar un comercio dado de baja: primero dalo de baja");
        }
        if (confirmName == null || !confirmName.strip().equals(tenant.getName())) {
            throw new BadRequestException(CODE_CONFIRM_NAME,
                    "Escribí el nombre exacto del comercio («" + tenant.getName() + "») para confirmar");
        }
        record(tenantId, TenantEventType.DELETED, tenant.getStatus().name(), tenant.getName(),
                "Eliminación definitiva", actorUserId);
        // Al borrar el comercio su historial pierde el tenant_id: se guarda el id para que las métricas sigan
        // contando su alta y su baja una sola vez (V250).
        queries.keepHistoryOfDeletedTenant(tenantId);
        sessionTermination.forceLogoutTenant(tenantId, ErrorCodes.TENANT_CANCELLED, MSG_CANCELLED);
        tenantRepository.delete(tenant);
        tenantRepository.flush();
        log.info("Eliminación definitiva del comercio {} ({})", tenantId, tenant.getName());
    }

    /**
     * Genera una contraseña temporal para el administrador del comercio: se muestra una sola vez, obliga a cambiarla
     * al entrar y corta las sesiones abiertas de ese usuario.
     */
    @Transactional
    public TemporaryPasswordResponse resetAdminPassword(Long tenantId, Long actorUserId) {
        requireTenant(tenantId);
        User admin = userRepository.findByTenantIdAndRoleInAndActiveTrue(tenantId, List.of(Role.TENANT_ADMIN)).stream()
                .min(Comparator.comparing(User::getId))
                .orElseThrow(() -> new ConflictException(CODE_NO_ADMIN,
                        "El comercio no tiene un administrador activo: creá uno desde su cuenta"));
        String temporary = TemporaryPasswords.generate();
        admin.setPasswordHash(passwordEncoder.encode(temporary));
        admin.setMustChangePassword(true);
        admin.incrementTokenVersion();
        userRepository.saveAndFlush(admin);
        // Lo hace un dueño o soporte (el "no puedo entrar" de un ticket): el mensaje no nombra a ninguno.
        sessionTermination.forceLogoutUser(admin.getId(), "PASSWORD_RESET",
                "El equipo de GondolIA restableció tu contraseña. Volvé a iniciar sesión.");
        log.info("Contraseña temporal generada para el administrador {} del comercio {} (usuario de plataforma {})",
                admin.getId(), tenantId, actorUserId);
        return new TemporaryPasswordResponse(admin.getEmail(), admin.getFullName(), temporary);
    }

    // ------------------------------------------------------------------ internos

    private TenantSummary changeStatus(Long tenantId, TenantStatus target, Set<TenantStatus> allowedFrom,
                                       TenantEventType eventType, String reason, Long actorUserId,
                                       String conflictMessage, String logoutCode, String logoutMessage) {
        Tenant tenant = requireTenant(tenantId);
        TenantStatus from = tenant.getStatus();
        if (!allowedFrom.contains(from)) {
            throw new ConflictException(CODE_INVALID_STATUS, conflictMessage);
        }
        tenant.setStatus(target);
        tenant.setStatusReason(reason);
        tenant.setStatusChangedAt(Instant.now(clock));
        tenantRepository.saveAndFlush(tenant);
        record(tenantId, eventType, from.name(), target.name(), reason, actorUserId);
        events.publishEvent(new TenantStatusChangedEvent(tenantId, from, target));
        if (logoutCode != null) {
            sessionTermination.forceLogoutTenant(tenantId, logoutCode, logoutMessage);
        }
        log.info("El comercio {} pasó de {} a {}", tenantId, from, target);
        return summary(tenantId);
    }

    private void applyAdminData(Tenant tenant, String name, String legalName, String taxId, BusinessType businessType,
                                String contactName, String contactEmail, String contactPhone, String address,
                                String city, String province, String notes) {
        tenant.setName(name);
        tenant.setLegalName(trimToNull(legalName));
        tenant.setTaxId(trimToNull(taxId));
        tenant.setBusinessType(businessType);
        tenant.setContactName(trimToNull(contactName));
        tenant.setContactEmail(Emails.normalize(contactEmail));
        tenant.setContactPhone(trimToNull(contactPhone));
        tenant.setAddress(trimToNull(address));
        tenant.setCity(trimToNull(city));
        tenant.setProvince(trimToNull(province));
        tenant.setNotes(trimToNull(notes));
    }

    private Branch createFirstBranch(Tenant tenant, CreateTenantRequest.BranchRequest request) {
        Branch branch = new Branch();
        branch.setTenantId(tenant.getId());
        String name = request == null ? null : trimToNull(request.name());
        branch.setName(name == null ? Branch.DEFAULT_NAME : name);
        String code = request == null ? null : trimToNull(request.code());
        branch.setCode(code != null ? code.toUpperCase(Locale.ROOT) : defaultCode(branch.getName()));
        branch.setAddress(request == null ? tenant.getAddress() : trimToNull(request.address()));
        branch.setCity(request == null || trimToNull(request.city()) == null ? tenant.getCity()
                : trimToNull(request.city()));
        branch.setProvince(request == null || trimToNull(request.province()) == null ? tenant.getProvince()
                : trimToNull(request.province()));
        branch.setActive(true);
        return branchRepository.saveAndFlush(branch);
    }

    private static String defaultCode(String branchName) {
        String letters = branchName.replaceAll("[^\\p{IsAlphabetic}]", "");
        String base = letters.isEmpty() ? branchName : letters;
        return base.substring(0, Math.min(3, base.length())).toUpperCase(Locale.ROOT);
    }

    private void createUser(Long tenantId, CreateTenantRequest.NewUserRequest request, Role role, Long branchId) {
        User user = new User();
        user.setTenantId(tenantId);
        user.setEmail(Emails.normalize(request.email()));
        user.setFullName(request.fullName().strip());
        user.setRole(role);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(true);
        userRepository.saveAndFlush(user);
        if (branchId != null) {
            userBranchRepository.save(new UserBranch(user.getId(), branchId));
        }
    }

    private void checkEmail(CreateTenantRequest.NewUserRequest request, Map<String, CreateTenantRequest.NewUserRequest> seen) {
        String email = Emails.normalize(request.email());
        if (email == null) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "Cargá el email de cada usuario");
        }
        if (seen.put(email, request) != null) {
            throw new ConflictException(CODE_EMAIL_TAKEN,
                    "El email «" + email + "» está repetido: cada usuario necesita el suyo");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(CODE_EMAIL_TAKEN, "Ya hay una cuenta con el email «" + email + "»");
        }
    }

    /** Historial del comercio, del más nuevo al más viejo (a igual instante, el último registrado primero). */
    private List<TenantEventDto> eventsOf(Long tenantId) {
        List<TenantEvent> all = new ArrayList<>(eventRepository.findByTenantIdOrderByCreatedAtDesc(tenantId));
        all.sort(Comparator.comparing(TenantEvent::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(TenantEvent::getId, Comparator.nullsLast(Comparator.reverseOrder())));
        Set<Long> actorIds = new HashSet<>();
        all.forEach(event -> {
            if (event.getActorUserId() != null) {
                actorIds.add(event.getActorUserId());
            }
        });
        Map<Long, String> actorNames = new HashMap<>();
        if (!actorIds.isEmpty()) {
            userRepository.findAllById(actorIds).forEach(user -> actorNames.put(user.getId(), user.getFullName()));
        }
        List<TenantEventDto> dtos = new ArrayList<>(all.size());
        for (TenantEvent event : all) {
            dtos.add(new TenantEventDto(event.getId(), event.getType(), event.getFromValue(), event.getToValue(),
                    event.getReason(), actorNames.get(event.getActorUserId()), event.getCreatedAt()));
        }
        return dtos;
    }

    private void record(Long tenantId, TenantEventType type, String fromValue, String toValue, String reason,
                        Long actorUserId) {
        TenantEvent event = new TenantEvent();
        event.setTenantId(tenantId);
        event.setType(type);
        event.setFromValue(fromValue);
        event.setToValue(toValue);
        event.setReason(reason);
        event.setActorUserId(actorUserId);
        eventRepository.saveAndFlush(event);
    }

    private TenantSummary toSummary(TenantRow row, Set<TenantModule> modules) {
        List<TenantModule> ordered = Arrays.stream(TenantModule.values()).filter(modules::contains).toList();
        BigDecimal fee = row.status() == TenantStatus.ACTIVE
                ? ModuleCatalog.monthlyFee(row.plan(), modules, row.activeBranchCount())
                : BigDecimal.ZERO;
        return new TenantSummary(row.id(), row.name(), row.businessType(), row.plan(), row.status(), row.city(),
                row.province(), row.contactName(), row.contactEmail(), row.contactPhone(), row.userCount(),
                row.branchCount(), row.activeBranchCount(), ordered, fee, row.lastActivityAt(), row.createdAt(),
                row.statusChangedAt(), row.statusReason());
    }

    private TenantRow requireRow(Long tenantId) {
        TenantRow row = tenantId == null ? null : queries.byId(tenantId);
        if (row == null) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return row;
    }

    /** {@code true} si quien hace el cambio es un dueño de GondolIA (no soporte ni el sistema). */
    private boolean isOwner(Long actorUserId) {
        return actorUserId != null && userRepository.findById(actorUserId)
                .map(user -> user.getRole() == Role.PLATFORM_OWNER)
                .orElse(false);
    }

    Tenant requireTenant(Long tenantId) {
        if (tenantId == null) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return tenantRepository.findById(tenantId).orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
    }

    private static String planLabel(TenantPlan plan) {
        return switch (plan) {
            case FREEMIUM -> "Freemium";
            case BASICO -> "Básico";
            case PROFESIONAL -> "Profesional";
        };
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
