package com.gondolia.domain.announcement;

import com.gondolia.domain.common.Severity;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.tenant.BusinessType;
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
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Aviso general o recall publicado por los dueños de GondolIA. Los lotes de un recall están en
 * {@link AnnouncementRecallLot}.
 */
@Entity
@Table(name = "announcements")
@Getter
@Setter
@NoArgsConstructor
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AnnouncementKind kind;

    @Enumerated(EnumType.STRING)
    private Severity severity = Severity.INFO;

    @Enumerated(EnumType.STRING)
    private AnnouncementStatus status = AnnouncementStatus.PUBLISHED;

    private String title;

    private String body;

    /** CSV de {@link BusinessType}; NULL = todos los rubros. */
    private String targetBusinessTypes;

    private String recallProductName;

    private String recallBrand;

    private String recallBarcode;

    private boolean recallAllLots = false;

    private LocalDate recallExpiryFrom;

    private LocalDate recallExpiryTo;

    private String recallReason;

    private String recallInstructions;

    /** Solo el agregado: nunca se expone qué tenants coinciden. */
    private int affectedTenantsCount = 0;

    private int recipientsCount = 0;

    private Long createdBy;

    private Instant publishedAt;

    @Column(updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }

    public boolean isRecall() {
        return kind == AnnouncementKind.RECALL;
    }

    /** Rubros destinatarios; conjunto vacío = todos. */
    public Set<BusinessType> targetBusinessTypeSet() {
        if (targetBusinessTypes == null || targetBusinessTypes.isBlank()) {
            return EnumSet.noneOf(BusinessType.class);
        }
        return Arrays.stream(targetBusinessTypes.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .map(BusinessType::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(BusinessType.class)));
    }

    /** Guarda los rubros como CSV; {@code null} o vacío = todos. */
    public void assignTargetBusinessTypes(Collection<BusinessType> types) {
        this.targetBusinessTypes = types == null || types.isEmpty()
                ? null
                : types.stream().distinct().sorted().map(Enum::name).collect(Collectors.joining(","));
    }
}
