package com.gondolia.domain.tenant;

import com.gondolia.domain.common.Timestamps;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * Sucursal (local) de un tenant. Stock, lotes, ventas, alertas, IA y recalls son por sucursal; la API key del POS
 * también (solo se guardan su SHA-256 y su prefijo).
 */
@Entity
@Table(name = "branches")
@Getter
@Setter
@NoArgsConstructor
public class Branch {

    /** Nombre de la sucursal que se crea junto con el tenant. */
    public static final String DEFAULT_NAME = "Sucursal Principal";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long tenantId;

    private String name;

    private String code;

    private String address;

    private String city;

    private String province;

    private String phone;

    private boolean active = true;

    private String posApiKeyHash;

    private String posApiKeyPrefix;

    private Instant posApiKeyCreatedAt;

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

    public boolean hasPosApiKey() {
        return posApiKeyHash != null;
    }
}
