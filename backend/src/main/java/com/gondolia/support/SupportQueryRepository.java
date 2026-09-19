package com.gondolia.support;

import com.gondolia.common.PageResponse;
import com.gondolia.domain.support.MessageSenderType;
import com.gondolia.domain.support.TicketCategory;
import com.gondolia.domain.support.TicketChannel;
import com.gondolia.domain.support.TicketPriority;
import com.gondolia.domain.support.TicketStatus;
import com.gondolia.domain.tenant.BusinessType;
import com.gondolia.domain.tenant.TenantPlan;
import com.gondolia.domain.user.Role;
import com.gondolia.support.dto.AttachmentDto;
import com.gondolia.support.dto.MessageDto;
import com.gondolia.support.dto.SupportStatsDto;
import com.gondolia.support.dto.TicketSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecturas de la bandeja de soporte con SQL (SPEC §5.2: la analítica va por {@code JdbcTemplate}).
 * <p>
 * Resuelve en una sola consulta lo que la conversación necesita fila a fila: nombre del comercio y de las personas,
 * vista previa del último mensaje y <b>mensajes sin leer según quién mira</b> (el comercio cuenta los del agente
 * posteriores a {@code customer_last_read_at}; el agente, los del cliente posteriores a {@code agent_last_read_at}).
 */
@Repository
@RequiredArgsConstructor
public class SupportQueryRepository {

    /** Estados en los que un ticket sigue necesitando atención. */
    public static final List<TicketStatus> ACTIVE_STATUSES =
            List.of(TicketStatus.OPEN, TicketStatus.IN_PROGRESS, TicketStatus.WAITING_CUSTOMER);

    static final int PREVIEW_LENGTH = 140;
    static final String PREVIEW_IMAGE = "Imagen adjunta";
    /** Ventana del promedio de primera respuesta del tablero. */
    static final int FIRST_RESPONSE_WINDOW_DAYS = 30;

    /**
     * Letras con tilde y su versión sin tilde (mismo orden) para buscar "sin distinguir mayúsculas ni acentos"
     * (api-e §5): el texto buscado pasa por {@link #plain} y las columnas por {@code translate(...)} en SQL, con la
     * misma tabla. Van las mayúsculas también para no depender de que {@code lower()} de la base las conozca.
     */
    static final String ACCENTED = "áàâäãéèêëíìîïóòôöõúùûüñçÁÀÂÄÃÉÈÊËÍÌÎÏÓÒÔÖÕÚÙÛÜÑÇ";
    static final String UNACCENTED = "aaaaaeeeeiiiiooooouuuuncAAAAAEEEEIIIIOOOOOUUUUNC";
    /** Columnas por las que busca la bandeja: asunto, comercio y persona que abrió el ticket. */
    private static final List<String> SEARCHABLE = List.of("t.subject", "ten.name", "cu.full_name");

    /** Desde qué lado se mira la conversación (define qué mensajes cuentan como no leídos). */
    public enum Viewer {
        CUSTOMER("customer_last_read_at", MessageSenderType.AGENT),
        AGENT("agent_last_read_at", MessageSenderType.CUSTOMER);

        private final String readColumn;
        private final MessageSenderType unreadFrom;

        Viewer(String readColumn, MessageSenderType unreadFrom) {
            this.readColumn = readColumn;
            this.unreadFrom = unreadFrom;
        }
    }

    /** Filtros de la bandeja del agente (SPEC §6.8). */
    public record AgentFilter(TicketStatusFilter status, AssignedFilter assigned, String q) {
    }

    private static final String SUMMARY_SELECT = """
            select t.id, t.tenant_id, ten.name as tenant_name, ten.plan as tenant_plan,
                   ten.business_type as tenant_business_type, t.subject, t.category, t.priority, t.status, t.channel,
                   t.created_by, cu.full_name as created_by_name, cu.role as created_by_role,
                   t.assigned_to, au.full_name as assigned_to_name, t.last_message_at, t.created_at, t.resolved_at,
                   t.rating, lm.body as last_body, lm.attachment_id as last_attachment_id,
                   (select count(*) from support_messages m
                     where m.ticket_id = t.id and m.sender_type = :unreadFrom
                       and (t.%s is null or m.created_at > t.%s)) as unread_count
            from support_tickets t
            join tenants ten on ten.id = t.tenant_id
            left join users cu on cu.id = t.created_by
            left join users au on au.id = t.assigned_to
            left join lateral (
                select m.body, m.attachment_id from support_messages m
                where m.ticket_id = t.id order by m.created_at desc, m.id desc limit 1
            ) lm on true
            """;

    private static final String MESSAGES_SQL = """
            select m.id, m.ticket_id, m.sender_id, u.full_name as sender_name, m.sender_type, m.body, m.created_at,
                   a.id as attachment_id, a.content_type, a.original_name, a.size_bytes
            from support_messages m
            left join users u on u.id = m.sender_id
            left join attachments a on a.id = m.attachment_id
            where m.ticket_id = :ticketId
            order by m.created_at, m.id
            """;

    private static final String STATS_SQL = """
            select
              count(*) filter (where status = 'OPEN') as open_count,
              count(*) filter (where status = 'IN_PROGRESS') as in_progress_count,
              count(*) filter (where status = 'WAITING_CUSTOMER') as waiting_count,
              count(*) filter (where assigned_to is null
                                 and status in ('OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER')) as unassigned_count,
              count(*) filter (where assigned_to = :agentId
                                 and status in ('OPEN', 'IN_PROGRESS', 'WAITING_CUSTOMER')) as mine_count,
              count(*) filter (where resolved_at >= :dayStart and resolved_at < :dayEnd) as resolved_today,
              avg(extract(epoch from (first_response_at - created_at)) / 60.0)
                filter (where first_response_at is not null and created_at >= :since) as avg_first_response
            from support_tickets
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    // ------------------------------------------------------------------ comercio

    /** Tickets del comercio, del más reciente al más viejo. */
    @Transactional(readOnly = true)
    public List<TicketSummary> listForTenant(Long tenantId, TicketStatusFilter status) {
        MapSqlParameterSource params = viewerParams(Viewer.CUSTOMER).addValue("tenantId", tenantId);
        StringBuilder sql = new StringBuilder(select(Viewer.CUSTOMER)).append(" where t.tenant_id = :tenantId");
        appendStatus(sql, params, status);
        sql.append(" order by t.last_message_at desc, t.id desc");
        return jdbc.query(sql.toString(), params, summaryMapper());
    }

    // ------------------------------------------------------------------ agente

    /** Bandeja del agente, paginada y filtrada por estado, asignación y texto libre. */
    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> listForAgent(AgentFilter filter, Long agentId, int page, int size) {
        MapSqlParameterSource params = viewerParams(Viewer.AGENT).addValue("agentId", agentId);
        StringBuilder where = new StringBuilder(" where true");
        appendStatus(where, params, filter.status());
        switch (filter.assigned() == null ? AssignedFilter.ALL : filter.assigned()) {
            case ME -> where.append(" and t.assigned_to = :agentId");
            case UNASSIGNED -> where.append(" and t.assigned_to is null");
            case ALL -> {
                // sin filtro de asignación
            }
        }
        String search = normalizeSearch(filter.q());
        if (search != null) {
            where.append(SEARCHABLE.stream()
                    .map(column -> "lower(translate(coalesce(" + column + ", ''), :accented, :unaccented))"
                            + " like :q escape '\\'")
                    .collect(Collectors.joining(" or ", " and (", ")")));
            params.addValue("q", search)
                    .addValue("accented", ACCENTED)
                    .addValue("unaccented", UNACCENTED);
        }

        Long total = jdbc.queryForObject("""
                select count(*) from support_tickets t
                join tenants ten on ten.id = t.tenant_id
                left join users cu on cu.id = t.created_by
                """ + where, params, Long.class);
        long totalElements = total == null ? 0 : total;
        if (totalElements == 0) {
            return PageResponse.empty(page, size);
        }
        params.addValue("limit", size).addValue("offset", (long) page * size);
        List<TicketSummary> content = jdbc.query(
                select(Viewer.AGENT) + where + " order by t.last_message_at desc, t.id desc limit :limit offset :offset",
                params, summaryMapper());
        return PageResponse.of(content, page, size, totalElements);
    }

    /** Tablero de la bandeja: pendientes por estado, sin asignar, míos, resueltos hoy y primera respuesta promedio. */
    @Transactional(readOnly = true)
    public SupportStatsDto stats(Long agentId) {
        LocalDate today = LocalDate.now(clock);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("agentId", agentId)
                .addValue("dayStart", offset(today.atStartOfDay(clock.getZone()).toInstant()))
                .addValue("dayEnd", offset(today.plusDays(1).atStartOfDay(clock.getZone()).toInstant()))
                .addValue("since", offset(clock.instant().minus(java.time.Duration.ofDays(FIRST_RESPONSE_WINDOW_DAYS))));
        return jdbc.queryForObject(STATS_SQL, params, (rs, row) -> new SupportStatsDto(
                rs.getLong("open_count"),
                rs.getLong("in_progress_count"),
                rs.getLong("waiting_count"),
                rs.getLong("unassigned_count"),
                rs.getLong("mine_count"),
                rs.getLong("resolved_today"),
                roundMinutes(rs.getBigDecimal("avg_first_response"))));
    }

    // ------------------------------------------------------------------ un ticket

    /** Resumen de un ticket desde el punto de vista indicado. */
    @Transactional(readOnly = true)
    public Optional<TicketSummary> findSummary(Long ticketId, Viewer viewer) {
        MapSqlParameterSource params = viewerParams(viewer).addValue("ticketId", ticketId);
        List<TicketSummary> rows = jdbc.query(select(viewer) + " where t.id = :ticketId", params, summaryMapper());
        return rows.stream().findFirst();
    }

    /** Conversación completa, del mensaje más viejo al más nuevo. */
    @Transactional(readOnly = true)
    public List<MessageDto> messages(Long ticketId) {
        return jdbc.query(MESSAGES_SQL, new MapSqlParameterSource("ticketId", ticketId), messageMapper());
    }

    /** Un mensaje ya persistido, con el nombre de quien lo envió y su adjunto. */
    @Transactional(readOnly = true)
    public Optional<MessageDto> findMessage(Long messageId) {
        List<MessageDto> rows = jdbc.query("""
                select m.id, m.ticket_id, m.sender_id, u.full_name as sender_name, m.sender_type, m.body, m.created_at,
                       a.id as attachment_id, a.content_type, a.original_name, a.size_bytes
                from support_messages m
                left join users u on u.id = m.sender_id
                left join attachments a on a.id = m.attachment_id
                where m.id = :messageId
                """, new MapSqlParameterSource("messageId", messageId), messageMapper());
        return rows.stream().findFirst();
    }

    // ------------------------------------------------------------------ internos

    private static String select(Viewer viewer) {
        return SUMMARY_SELECT.formatted(viewer.readColumn, viewer.readColumn);
    }

    private static MapSqlParameterSource viewerParams(Viewer viewer) {
        return new MapSqlParameterSource("unreadFrom", viewer.unreadFrom.name());
    }

    private static void appendStatus(StringBuilder sql, MapSqlParameterSource params, TicketStatusFilter filter) {
        List<TicketStatus> statuses = (filter == null ? TicketStatusFilter.ALL : filter).statuses();
        if (statuses == null) {
            return;
        }
        sql.append(" and t.status in (:statuses)");
        params.addValue("statuses", statuses.stream().map(Enum::name).toList());
    }

    /**
     * Patrón {@code LIKE} del texto libre: sin tildes y en minúsculas (igual que las columnas, ver {@link #ACCENTED}) y
     * con la barra invertida, {@code %} y {@code _} escapados para que se busquen literales. {@code null} si no hay texto.
     */
    static String normalizeSearch(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String clean = plain(q.strip()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + clean + "%";
    }

    /** Texto sin tildes y en minúsculas, igual que {@code lower(translate(columna, ACCENTED, UNACCENTED))}. */
    static String plain(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char character : text.toCharArray()) {
            int index = ACCENTED.indexOf(character);
            out.append(index < 0 ? character : UNACCENTED.charAt(index));
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    static String preview(String body, boolean hasAttachment) {
        if (body != null && !body.isBlank()) {
            String clean = body.strip().replaceAll("\\s+", " ");
            return clean.length() <= PREVIEW_LENGTH ? clean : clean.substring(0, PREVIEW_LENGTH - 1) + "…";
        }
        return hasAttachment ? PREVIEW_IMAGE : null;
    }

    private static RowMapper<TicketSummary> summaryMapper() {
        return (rs, row) -> {
            long attachmentId = rs.getLong("last_attachment_id");
            boolean hasAttachment = !rs.wasNull();
            return new TicketSummary(
                    rs.getLong("id"),
                    rs.getLong("tenant_id"),
                    rs.getString("tenant_name"),
                    enumValue(rs.getString("tenant_plan"), TenantPlan.class),
                    enumValue(rs.getString("tenant_business_type"), BusinessType.class),
                    rs.getString("subject"),
                    enumValue(rs.getString("category"), TicketCategory.class),
                    enumValue(rs.getString("priority"), TicketPriority.class),
                    enumValue(rs.getString("status"), TicketStatus.class),
                    enumValue(rs.getString("channel"), TicketChannel.class),
                    nullableLong(rs, "created_by"),
                    rs.getString("created_by_name"),
                    enumValue(rs.getString("created_by_role"), Role.class),
                    nullableLong(rs, "assigned_to"),
                    rs.getString("assigned_to_name"),
                    instant(rs, "last_message_at"),
                    preview(rs.getString("last_body"), hasAttachment && attachmentId > 0),
                    rs.getInt("unread_count"),
                    instant(rs, "created_at"),
                    instant(rs, "resolved_at"),
                    nullableInt(rs, "rating"));
        };
    }

    private static RowMapper<MessageDto> messageMapper() {
        return (rs, row) -> {
            Long attachmentId = nullableLong(rs, "attachment_id");
            AttachmentDto attachment = attachmentId == null ? null : new AttachmentDto(attachmentId,
                    "/api/attachments/" + attachmentId, rs.getString("content_type"), rs.getString("original_name"),
                    rs.getLong("size_bytes"));
            return new MessageDto(
                    rs.getLong("id"),
                    rs.getLong("ticket_id"),
                    nullableLong(rs, "sender_id"),
                    rs.getString("sender_name"),
                    enumValue(rs.getString("sender_type"), MessageSenderType.class),
                    rs.getString("body"),
                    attachment,
                    instant(rs, "created_at"));
        };
    }

    private static <E extends Enum<E>> E enumValue(String value, Class<E> type) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offset(Instant instant) {
        return OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    /** Promedio en minutos con un decimal, o {@code null} si todavía no hubo primeras respuestas. */
    static Double roundMinutes(java.math.BigDecimal value) {
        return value == null ? null : value.setScale(1, java.math.RoundingMode.HALF_UP).doubleValue();
    }
}
