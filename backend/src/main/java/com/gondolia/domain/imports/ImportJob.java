package com.gondolia.domain.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.gondolia.domain.common.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Importación masiva de productos y stock inicial desde Excel o CSV (SPEC §16). Campos JSONB:
 * {@code sheetNames} (hojas del archivo), {@code headers} (encabezados detectados), {@code columnMapping}
 * ({@code {fieldKey: header}}), {@code options} ({@code {updateExisting,createCategories,...}}) y {@code result}
 * ({@code {productsCreated,productsUpdated,...}}).
 */
@Entity
@Table(name = "import_jobs")
@Getter
@Setter
@NoArgsConstructor
public class ImportJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    @Enumerated(EnumType.STRING)
    private ImportType type = ImportType.PRODUCTS;

    @Enumerated(EnumType.STRING)
    private ImportStatus status = ImportStatus.UPLOADED;

    private String fileName;

    @Enumerated(EnumType.STRING)
    private ImportFileFormat fileFormat;

    private String sheetName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode sheetNames;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode headers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode columnMapping;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode options;

    private int totalRows;

    private int validRows;

    private int warningRows;

    private int errorRows;

    private int skippedRows;

    /** Filas ya procesadas al aplicar (barra de progreso). */
    private int processedRows;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode result;

    private String errorMessage;

    private Long createdBy;

    /** Se completa al insertar si no se asignó (el seeder puede fijar importaciones históricas). */
    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    private Instant appliedAt;

    @PrePersist
    void onCreate() {
        Instant now = Timestamps.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }
}
