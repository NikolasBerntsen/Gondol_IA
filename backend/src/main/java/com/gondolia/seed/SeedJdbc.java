package com.gondolia.seed;

import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Escritura en bloque para el seeder: {@code INSERT ... RETURNING id} para las tablas chicas, ids reservados de las
 * secuencias y {@code COPY ... FROM STDIN} (vía {@link JdbcTemplate}, en la misma transacción) para las grandes
 * (lotes, movimientos y ventas del POS): cientos de miles de filas en segundos.
 * <p>
 * El driver de PostgreSQL está en el {@code pom.xml} con alcance {@code runtime}, así que la API de {@code COPY}
 * ({@code PGConnection.getCopyAPI().copyIn(sql, reader)}) se invoca por reflexión.
 */
final class SeedJdbc {

    /** Tamaño del texto CSV que se manda en cada {@code COPY}. */
    private static final int COPY_CHUNK_CHARS = 4 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private long copiedRows;

    SeedJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    long copiedRows() {
        return copiedRows;
    }

    /** {@code INSERT ... RETURNING id}. */
    long insert(String sql, Object... args) {
        Long id = jdbc.queryForObject(sql + " returning id", Long.class, convert(args));
        if (id == null) {
            throw new IllegalStateException("El insert no devolvió id: " + sql);
        }
        return id;
    }

    void update(String sql, Object... args) {
        jdbc.update(sql, convert(args));
    }

    /** Reserva {@code count} ids de la secuencia de {@code table.id}, en orden creciente. */
    long[] nextIds(String table, int count) {
        if (count <= 0) {
            return new long[0];
        }
        List<Long> ids = jdbc.queryForList(
                "select nextval(pg_get_serial_sequence(?, 'id')) from generate_series(1, ?)", Long.class, table,
                count);
        long[] result = new long[ids.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = ids.get(i);
        }
        java.util.Arrays.sort(result);
        return result;
    }

    /** Filas para {@code COPY table (columns) FROM STDIN (FORMAT csv)}. */
    Copy copy(String table, String columns) {
        return new Copy(table, columns);
    }

    final class Copy implements AutoCloseable {
        private final String sql;
        private final StringBuilder buffer = new StringBuilder();

        private Copy(String table, String columns) {
            this.sql = "COPY " + table + " (" + columns + ") FROM STDIN WITH (FORMAT csv)";
        }

        void row(Object... values) {
            for (int i = 0; i < values.length; i++) {
                if (i > 0) {
                    buffer.append(',');
                }
                appendValue(values[i]);
            }
            buffer.append('\n');
            copiedRows++;
            if (buffer.length() >= COPY_CHUNK_CHARS) {
                flush();
            }
        }

        private void appendValue(Object value) {
            if (value == null) {
                return;
            }
            String text;
            if (value instanceof BigDecimal decimal) {
                text = decimal.toPlainString();
            } else if (value instanceof Boolean bool) {
                text = bool ? "t" : "f";
            } else if (value instanceof Enum<?> constant) {
                text = constant.name();
            } else {
                text = value.toString();
            }
            buffer.append('"').append(text.replace("\"", "\"\"")).append('"');
        }

        void flush() {
            if (buffer.isEmpty()) {
                return;
            }
            String data = buffer.toString();
            buffer.setLength(0);
            jdbc.execute((ConnectionCallback<Object>) connection -> {
                try {
                    Class<?> pgConnection = Class.forName("org.postgresql.PGConnection");
                    Object pg = connection.unwrap(pgConnection);
                    Object copyApi = pgConnection.getMethod("getCopyAPI").invoke(pg);
                    Method copyIn = copyApi.getClass().getMethod("copyIn", String.class, Reader.class);
                    return copyIn.invoke(copyApi, sql, new StringReader(data));
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof java.sql.SQLException sqlException) {
                        throw sqlException;
                    }
                    throw new IllegalStateException("Falló el COPY: " + sql, cause);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException("El driver no expone la API de COPY de PostgreSQL", e);
                }
            });
        }

        @Override
        public void close() {
            flush();
        }
    }

    private static Object[] convert(Object[] args) {
        Object[] converted = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof Instant instant) {
                converted[i] = instant.atOffset(ZoneOffset.UTC);
            } else if (arg instanceof Enum<?> constant) {
                converted[i] = constant.name();
            } else {
                converted[i] = arg;
            }
        }
        return converted;
    }
}
