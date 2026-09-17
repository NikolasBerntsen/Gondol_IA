package com.gondolia.notification;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.notification.Notification;
import com.gondolia.domain.notification.NotificationRepository;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.user.Role;
import com.gondolia.realtime.Destinations;
import com.gondolia.realtime.RealtimePublisher;
import com.gondolia.security.BranchAccessService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notificaciones in-app. Cada método persiste una fila por destinatario en {@code notifications} con un único
 * {@code INSERT ... SELECT} (eficiente para envíos masivos) y hace push de {@link NotificationDto} a
 * {@code /user/queue/notifications} de cada destinatario cuando la transacción confirma.
 * <p>
 * Solo se notifica a usuarios activos. Los envíos a usuarios de comercio por tenant, sucursal o rubro solo alcanzan a
 * tenants {@code ACTIVE}. Los métodos se unen a la transacción en curso: desde un listener {@code AFTER_COMMIT} hay que
 * llamarlos en una transacción nueva ({@code @Transactional(propagation = REQUIRES_NEW)}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    static final String MSG_NOT_FOUND = "La notificación no existe";

    private static final String INSERT_PREFIX = """
            insert into notifications (user_id, tenant_id, type, severity, title, body, link, reference_type,
                                       reference_id, created_at)
            select u.id, u.tenant_id, cast(? as varchar), cast(? as varchar), cast(? as varchar), cast(? as text),
                   cast(? as varchar), cast(? as varchar), cast(? as bigint), cast(? as timestamptz)
            """;
    private static final String INSERT_SUFFIX = " order by u.id returning id, user_id";
    private static final int FIRST_FILTER_INDEX = 9;

    private final JdbcTemplate jdbcTemplate;
    private final NotificationRepository notificationRepository;
    private final BranchAccessService branchAccessService;
    private final RealtimePublisher realtimePublisher;

    // ------------------------------------------------------------------ envío

    /**
     * Notifica a un usuario activo (de plataforma o de comercio).
     *
     * @return la notificación creada, o {@code null} si el usuario no existe o está inactivo
     */
    @Transactional
    public NotificationDto notifyUser(Long userId, NotificationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (userId == null) {
            return null;
        }
        Map<Long, NotificationDto> created = insert(draft, "from users u where u.id = ? and u.active",
                (ps, index, connection) -> ps.setLong(index, userId));
        return created.get(userId);
    }

    /** Notifica a los usuarios activos de la lista (ids repetidos o nulos se ignoran). Devuelve cuántos recibieron. */
    @Transactional
    public int notifyUsers(Collection<Long> userIds, NotificationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        Long[] ids = distinctIds(userIds);
        if (ids.length == 0) {
            return 0;
        }
        return insert(draft, "from users u where u.id = any(?) and u.active",
                (ps, index, connection) -> ps.setArray(index, connection.createArrayOf("bigint", ids))).size();
    }

    /**
     * Notifica a los usuarios activos de un tenant {@code ACTIVE} con alguno de los roles ({@code null} = todos los
     * roles de comercio).
     */
    @Transactional
    public int notifyTenantUsers(Long tenantId, Set<Role> roles, NotificationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        String[] roleNames = tenantRoleNames(roles);
        if (tenantId == null || roleNames.length == 0) {
            return 0;
        }
        return insert(draft, """
                from users u join tenants t on t.id = u.tenant_id
                where u.tenant_id = ? and t.status = 'ACTIVE' and u.active and u.role = any(?)
                """, (ps, index, connection) -> {
            ps.setLong(index, tenantId);
            ps.setArray(index + 1, connection.createArrayOf("text", roleNames));
        }).size();
    }

    /**
     * Notifica a los usuarios activos con acceso a la sucursal (jefes y administradores del tenant + empleados
     * asignados, ver {@link BranchAccessService#userIdsWithAccess}) filtrados por rol ({@code null} = todos). Solo si
     * el tenant está {@code ACTIVE} y la sucursal activa.
     */
    @Transactional
    public int notifyBranchUsers(Long tenantId, Long branchId, Set<Role> roles, NotificationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        return notifyUsers(branchRecipients(tenantId, branchId, roles), draft);
    }

    /** Destinatarios que usaría {@link #notifyBranchUsers} (p. ej. para hacer push de otro mensaje a los mismos). */
    @Transactional(readOnly = true)
    public List<Long> branchRecipients(Long tenantId, Long branchId, Set<Role> roles) {
        String[] roleNames = tenantRoleNames(roles);
        if (tenantId == null || branchId == null || roleNames.length == 0) {
            return List.of();
        }
        Long[] ids = distinctIds(branchAccessService.userIdsWithAccess(tenantId, branchId));
        if (ids.length == 0) {
            return List.of();
        }
        return jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    select u.id from users u join tenants t on t.id = u.tenant_id
                    where u.id = any(?) and u.tenant_id = ? and t.status = 'ACTIVE' and u.active and u.role = any(?)
                    order by u.id
                    """);
            ps.setArray(1, connection.createArrayOf("bigint", ids));
            ps.setLong(2, tenantId);
            ps.setArray(3, connection.createArrayOf("text", roleNames));
            return ps;
        }, (rs, rowNum) -> rs.getLong(1));
    }

    /**
     * Notifica a todos los usuarios activos de comercio de tenants {@code ACTIVE}, opcionalmente solo de ciertos rubros
     * ({@code null} o vacío = todos).
     */
    @Transactional
    public int notifyAllActiveTenants(NotificationDraft draft, Set<BusinessType> businessTypes) {
        Objects.requireNonNull(draft, "draft");
        String[] roleNames = tenantRoleNames(null);
        String[] types = businessTypes == null ? new String[0]
                : businessTypes.stream().filter(Objects::nonNull).map(Enum::name).distinct().toArray(String[]::new);
        String filter = """
                from users u join tenants t on t.id = u.tenant_id
                where t.status = 'ACTIVE' and u.active and u.role = any(?)
                """ + (types.length > 0 ? " and t.business_type = any(?)" : "");
        int created = insert(draft, filter, (ps, index, connection) -> {
            ps.setArray(index, connection.createArrayOf("text", roleNames));
            if (types.length > 0) {
                ps.setArray(index + 1, connection.createArrayOf("text", types));
            }
        }).size();
        log.info("Notificación {} enviada a {} usuarios de comercios activos", draft.type(), created);
        return created;
    }

    /** Notifica a los usuarios activos de un rol de plataforma ({@code PLATFORM_OWNER} o {@code SUPPORT_AGENT}). */
    @Transactional
    public int notifyPlatformRole(Role role, NotificationDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (role == null || !role.isPlatformRole()) {
            throw new IllegalArgumentException("notifyPlatformRole solo acepta roles de plataforma: " + role);
        }
        return insert(draft, "from users u where u.tenant_id is null and u.role = ? and u.active",
                (ps, index, connection) -> ps.setString(index, role.name())).size();
    }

    // ------------------------------------------------------------------ bandeja del usuario

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(Long userId, boolean unreadOnly, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Notification> result = unreadOnly
                ? notificationRepository.findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(userId, pageable)
                : notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        return PageResponse.of(result, NotificationDto::of);
    }

    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    /** Marca como leída una notificación propia (404 si no existe o es de otro usuario). Idempotente. */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new NotFoundException(MSG_NOT_FOUND));
        if (notification.getReadAt() == null) {
            notification.setReadAt(Timestamps.now());
        }
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId, Timestamps.now());
    }

    // ------------------------------------------------------------------ internos

    @FunctionalInterface
    private interface FilterBinder {
        void bind(PreparedStatement ps, int firstIndex, Connection connection) throws SQLException;
    }

    /** Inserta una notificación por usuario que cumpla el filtro, programa el push y devuelve userId → DTO. */
    private Map<Long, NotificationDto> insert(NotificationDraft draft, String filter, FilterBinder binder) {
        Instant createdAt = Timestamps.now();
        String sql = INSERT_PREFIX + filter + INSERT_SUFFIX;
        Map<Long, NotificationDto> created = new LinkedHashMap<>();
        jdbcTemplate.query(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql);
            ps.setString(1, draft.type().name());
            ps.setString(2, draft.severity().name());
            ps.setString(3, draft.title());
            ps.setString(4, draft.body());
            ps.setString(5, draft.link());
            ps.setString(6, draft.referenceType());
            if (draft.referenceId() == null) {
                ps.setNull(7, Types.BIGINT);
            } else {
                ps.setLong(7, draft.referenceId());
            }
            ps.setObject(8, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            binder.bind(ps, FIRST_FILTER_INDEX, connection);
            return ps;
        }, rs -> {
            long userId = rs.getLong("user_id");
            created.put(userId, new NotificationDto(rs.getLong("id"), draft.type(), draft.severity(), draft.title(),
                    draft.body(), draft.link(), draft.referenceType(), draft.referenceId(), false, createdAt));
        });
        if (!created.isEmpty()) {
            realtimePublisher.toEachUser(Destinations.QUEUE_NOTIFICATIONS, created);
        }
        return created;
    }

    private static Long[] distinctIds(Collection<Long> ids) {
        if (ids == null) {
            return new Long[0];
        }
        return ids.stream().filter(Objects::nonNull).distinct().toArray(Long[]::new);
    }

    private static String[] tenantRoleNames(Set<Role> roles) {
        Set<Role> source = roles == null ? Role.tenantRoles() : roles;
        return source.stream().filter(Objects::nonNull).filter(Role::isTenantRole).map(Enum::name).distinct()
                .toArray(String[]::new);
    }
}
