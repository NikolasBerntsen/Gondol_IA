package com.gondolia.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.imports.ImportRowAction;
import com.gondolia.domain.imports.ImportRowStatus;
import com.gondolia.imports.dto.ImportDtos;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Acceso directo a {@code import_job_rows} para lo que no cubre el repositorio JPA del núcleo: inserción masiva,
 * actualización en bloque del resultado de la validación, búsqueda con texto libre y acciones masivas.
 */
@Component
@RequiredArgsConstructor
public class ImportRowStore {

    /** Fila tal como se guarda y se devuelve. */
    public record StoredRow(Long id, int rowNumber, Map<String, String> raw, Map<String, String> data,
                            ImportRowStatus status, ImportRowAction action, List<ImportDtos.RowMessageDto> messages,
                            Long productId, Long lotId) {
    }

    /** Valores a persistir tras validar una fila. */
    public record RowUpdate(Long id, Map<String, String> data, ImportRowStatus status, ImportRowAction action,
                            List<ImportDtos.RowMessageDto> messages) {
    }

    private static final int BATCH_SIZE = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private final RowMapper<StoredRow> rowMapper = (ResultSet rs, int index) -> new StoredRow(
            rs.getLong("id"),
            rs.getInt("row_number"),
            readMap(rs.getString("raw")),
            readMap(rs.getString("data")),
            ImportRowStatus.valueOf(rs.getString("status")),
            rs.getString("action") == null ? null : ImportRowAction.valueOf(rs.getString("action")),
            readMessages(rs.getString("messages")),
            (Long) rs.getObject("product_id"),
            (Long) rs.getObject("lot_id"));

    // -----------------------------------------------------------------------
    // Escritura
    // -----------------------------------------------------------------------

    /** Inserta las filas crudas del archivo (una por fila de datos, en el orden del archivo). */
    public void insertRaw(Long jobId, List<String> headers, List<List<String>> rows) {
        List<String> payloads = new ArrayList<>(rows.size());
        for (List<String> cells : rows) {
            Map<String, String> raw = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                raw.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
            }
            payloads.add(write(raw));
        }
        for (int start = 0; start < payloads.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, payloads.size());
            List<String> chunk = payloads.subList(start, end);
            int offset = start;
            jdbc.batchUpdate("""
                    insert into import_job_rows (job_id, row_number, raw, status)
                    values (?, ?, ?::jsonb, 'PENDING')
                    """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int index) throws SQLException {
                    ps.setLong(1, jobId);
                    ps.setInt(2, offset + index + 1);
                    ps.setString(3, chunk.get(index));
                }

                @Override
                public int getBatchSize() {
                    return chunk.size();
                }
            });
        }
    }

    /** Guarda el resultado de validar un conjunto de filas. */
    public void applyValidation(List<RowUpdate> updates) {
        for (int start = 0; start < updates.size(); start += BATCH_SIZE) {
            List<RowUpdate> chunk = updates.subList(start, Math.min(start + BATCH_SIZE, updates.size()));
            jdbc.batchUpdate("""
                    update import_job_rows
                       set data = ?::jsonb, status = ?, action = ?, messages = ?::jsonb
                     where id = ?
                    """, chunk, chunk.size(), (PreparedStatement ps, RowUpdate update) -> {
                ps.setString(1, write(update.data()));
                ps.setString(2, update.status().name());
                if (update.action() == null) {
                    ps.setNull(3, Types.VARCHAR);
                } else {
                    ps.setString(3, update.action().name());
                }
                ps.setString(4, write(update.messages()));
                ps.setLong(5, update.id());
            });
        }
    }

    /** Marca el resultado de aplicar una fila. */
    public void markApplied(Long rowId, ImportRowStatus status, Long productId, Long lotId,
                            List<ImportDtos.RowMessageDto> messages) {
        jdbc.update("""
                update import_job_rows set status = ?, product_id = ?, lot_id = ?, messages = ?::jsonb where id = ?
                """, status.name(), productId, lotId, write(messages), rowId);
    }

    // -----------------------------------------------------------------------
    // Lectura
    // -----------------------------------------------------------------------

    public List<StoredRow> allRows(Long jobId) {
        return jdbc.query("select * from import_job_rows where job_id = ? order by row_number", rowMapper, jobId);
    }

    /** Bloque de filas a aplicar, en el orden del archivo. */
    public List<StoredRow> applicableRows(Long jobId, int afterRowNumber, int limit) {
        return jdbc.query("""
                select * from import_job_rows
                 where job_id = ? and row_number > ? and status in ('VALID', 'WARNING')
                 order by row_number
                 limit ?
                """, rowMapper, jobId, afterRowNumber, limit);
    }

    /** Filas con error o advertencia, para el CSV de errores. */
    public List<StoredRow> rowsWithMessages(Long jobId) {
        return jdbc.query("""
                select * from import_job_rows
                 where job_id = ? and status in ('ERROR', 'WARNING', 'FAILED')
                 order by case status when 'ERROR' then 0 when 'FAILED' then 1 else 2 end, row_number
                """, rowMapper, jobId);
    }

    public java.util.Optional<StoredRow> findRow(Long jobId, Long rowId) {
        return jdbc.query("select * from import_job_rows where job_id = ? and id = ?", rowMapper, jobId, rowId)
                .stream().findFirst();
    }

    /** Página de filas con filtro por estado y búsqueda en los valores de la fila. */
    public List<StoredRow> page(Long jobId, ImportRowStatus status, String query, int page, int size) {
        StringBuilder sql = new StringBuilder("select * from import_job_rows where job_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(jobId);
        appendFilters(sql, params, status, query);
        sql.append(" order by row_number limit ? offset ?");
        params.add(size);
        params.add((long) page * size);
        return jdbc.query(sql.toString(), rowMapper, params.toArray());
    }

    public long count(Long jobId, ImportRowStatus status, String query) {
        StringBuilder sql = new StringBuilder("select count(*) from import_job_rows where job_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(jobId);
        appendFilters(sql, params, status, query);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, params.toArray());
        return total == null ? 0 : total;
    }

    private void appendFilters(StringBuilder sql, List<Object> params, ImportRowStatus status, String query) {
        if (status != null) {
            sql.append(" and status = ?");
            params.add(status.name());
        }
        if (query != null && !query.isBlank()) {
            sql.append("""
                     and (cast(row_number as text) like ?
                          or lower(coalesce(data::text, '')) like ?
                          or lower(raw::text) like ?)
                    """);
            String like = "%" + query.trim().toLowerCase() + "%";
            params.add("%" + query.trim() + "%");
            params.add(like);
            params.add(like);
        }
    }

    public Map<ImportRowStatus, Integer> countsByStatus(Long jobId) {
        Map<ImportRowStatus, Integer> counts = new EnumMap<>(ImportRowStatus.class);
        for (Map<String, Object> row : jdbc.queryForList(
                "select status, count(*) as total from import_job_rows where job_id = ? group by status", jobId)) {
            counts.put(ImportRowStatus.valueOf(String.valueOf(row.get("status"))),
                    ((Number) row.get("total")).intValue());
        }
        return counts;
    }

    // -----------------------------------------------------------------------
    // Acciones masivas
    // -----------------------------------------------------------------------

    /** Ids de las filas alcanzadas por una acción masiva (selección explícita o todo el filtro). */
    public List<Long> resolveIds(Long jobId, List<Long> rowIds, ImportRowStatus filterStatus) {
        if (rowIds != null && !rowIds.isEmpty()) {
            List<Long> distinct = rowIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
            if (distinct.isEmpty()) {
                return List.of();
            }
            String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
            List<Object> params = new ArrayList<>();
            params.add(jobId);
            params.addAll(distinct);
            return jdbc.queryForList("select id from import_job_rows where job_id = ? and id in (" + placeholders
                    + ") order by row_number", Long.class, params.toArray());
        }
        if (filterStatus != null) {
            return jdbc.queryForList("select id from import_job_rows where job_id = ? and status = ? order by row_number",
                    Long.class, jobId, filterStatus.name());
        }
        return jdbc.queryForList("select id from import_job_rows where job_id = ? order by row_number", Long.class,
                jobId);
    }

    /** Marca filas como omitidas sin revalidarlas. */
    public int skip(List<Long> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        int[] updated = jdbc.batchUpdate("update import_job_rows set status = 'SKIPPED', action = 'SKIP' where id = ?",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        ps.setLong(1, ids.get(index));
                    }

                    @Override
                    public int getBatchSize() {
                        return ids.size();
                    }
                });
        return java.util.Arrays.stream(updated).map(count -> Math.max(count, 0)).sum();
    }

    public void deleteRows(Long jobId) {
        jdbc.update("delete from import_job_rows where job_id = ?", jobId);
    }

    // -----------------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------------

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                    "No pudimos guardar los datos de la importación.");
        }
    }

    public JsonNode toNode(Object value) {
        return objectMapper.valueToTree(value);
    }

    public <T> T fromNode(JsonNode node, Class<T> type) {
        return node == null || node.isNull() ? null : objectMapper.convertValue(node, type);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<String, String> result = new LinkedHashMap<>();
            raw.forEach((key, value) -> result.put(key, value == null ? "" : String.valueOf(value)));
            return result;
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private List<ImportDtos.RowMessageDto> readMessages(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, ImportDtos.RowMessageDto.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
