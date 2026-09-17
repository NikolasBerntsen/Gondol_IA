package com.gondolia.domain.user;

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

/**
 * Sucursal asignada a un usuario. Solo aplica a {@code TENANT_EMPLOYEE} y {@code TENANT_CASHIER}: jefe y
 * administrador acceden a todas.
 */
@Entity
@Table(name = "user_branches")
@Getter
@Setter
@NoArgsConstructor
public class UserBranch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;

    private Long branchId;

    @Column(updatable = false)
    private Instant createdAt;

    public UserBranch(Long userId, Long branchId) {
        this.userId = userId;
        this.branchId = branchId;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Timestamps.now();
        }
    }
}
