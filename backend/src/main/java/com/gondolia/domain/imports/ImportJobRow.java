package com.gondolia.domain.imports;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Fila de una importación (SPEC §16.2). {@code raw} conserva el valor original por encabezado, {@code data} los
 * valores normalizados y editables desde la UI, y {@code messages} los avisos por campo
 * ({@code [{field,level,message}]}). {@code productId} y {@code lotId} se completan al aplicarla.
 */
@Entity
@Table(name = "import_job_rows")
@Getter
@Setter
@NoArgsConstructor
public class ImportJobRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;

    /** Número de fila en el archivo (1 = primera fila de datos, sin contar el encabezado). */
    private int rowNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode raw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode data;

    @Enumerated(EnumType.STRING)
    private ImportRowStatus status = ImportRowStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private ImportRowAction action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode messages;

    private Long productId;

    private Long lotId;
}
