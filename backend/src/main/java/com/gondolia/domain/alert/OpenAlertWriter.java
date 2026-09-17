package com.gondolia.domain.alert;

import com.gondolia.domain.common.Timestamps;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Apertura idempotente de alertas: inserta en {@code OPEN} salvo que ya exista una alerta {@code OPEN} o
 * {@code ACKNOWLEDGED} del tenant con la misma {@code dedupe_key}. Usa {@code INSERT ... ON CONFLICT DO NOTHING} sobre
 * el índice único parcial {@code uq_alerts_open_dedupe}, así dos transacciones concurrentes nunca fallan por la clave
 * duplicada (la operación de negocio que abre la alerta no se revierte).
 */
@Repository
@RequiredArgsConstructor
public class OpenAlertWriter {

    static final int MAX_TITLE_LENGTH = 200;
    static final int MAX_DEDUPE_KEY_LENGTH = 150;

    private static final String INSERT_SQL = """
            insert into alerts (tenant_id, branch_id, type, severity, status, product_id, lot_id, announcement_id,
                                title, message, dedupe_key, created_at, updated_at)
            values (?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?)
            on conflict (tenant_id, dedupe_key) where status in ('OPEN', 'ACKNOWLEDGED') do nothing
            returning id
            """;

    private static final int[] ARG_TYPES = {
            Types.BIGINT, Types.BIGINT, Types.VARCHAR, Types.VARCHAR, Types.BIGINT, Types.BIGINT, Types.BIGINT,
            Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.TIMESTAMP_WITH_TIMEZONE, Types.TIMESTAMP_WITH_TIMEZONE
    };

    private final JdbcTemplate jdbcTemplate;

    /**
     * Abre la alerta con los datos de {@code alert} (se ignoran {@code id}, {@code status} y las fechas). Obligatorios:
     * {@code tenantId}, {@code type}, {@code severity}, {@code title} y {@code dedupeKey}.
     *
     * @return id de la alerta creada, o vacío si ya había una abierta con esa clave
     */
    public Optional<Long> openIfAbsent(Alert alert) {
        Objects.requireNonNull(alert.getTenantId(), "tenantId");
        Objects.requireNonNull(alert.getType(), "type");
        Objects.requireNonNull(alert.getSeverity(), "severity");
        Objects.requireNonNull(alert.getTitle(), "title");
        Objects.requireNonNull(alert.getDedupeKey(), "dedupeKey");
        if (alert.getDedupeKey().length() > MAX_DEDUPE_KEY_LENGTH) {
            throw new IllegalArgumentException("dedupeKey supera " + MAX_DEDUPE_KEY_LENGTH + " caracteres");
        }
        OffsetDateTime now = OffsetDateTime.ofInstant(Timestamps.now(), ZoneOffset.UTC);
        Object[] args = {
                alert.getTenantId(), alert.getBranchId(), alert.getType().name(), alert.getSeverity().name(),
                alert.getProductId(), alert.getLotId(), alert.getAnnouncementId(), abbreviate(alert.getTitle()),
                alert.getMessage(), alert.getDedupeKey(), now, now
        };
        List<Long> ids = jdbcTemplate.query(INSERT_SQL, args, ARG_TYPES, (rs, rowNum) -> rs.getLong(1));
        return ids.stream().findFirst();
    }

    private static String abbreviate(String title) {
        String value = title.strip();
        return value.length() <= MAX_TITLE_LENGTH ? value : value.substring(0, MAX_TITLE_LENGTH - 1) + "…";
    }
}
