package com.gondolia.domain.pos;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Numeración correlativa de tickets por sucursal (SPEC §15.2). La fila se bloquea con {@code SELECT ... FOR UPDATE}
 * ({@code PosBranchCounterRepository.lockByBranchId}) al emitir cada venta, así dos cajas de la misma sucursal nunca
 * repiten número.
 */
@Entity
@Table(name = "pos_branch_counters")
@Getter
@Setter
@NoArgsConstructor
public class PosBranchCounter {

    /** Formato del código de ticket: {@code String.format(TICKET_CODE_FORMAT, branchId, number)}. */
    public static final String TICKET_CODE_FORMAT = "%04d-%08d";

    @Id
    @Column(name = "branch_id")
    private Long branchId;

    private long lastNumber;

    public PosBranchCounter(Long branchId) {
        this.branchId = branchId;
        this.lastNumber = 0;
    }

    /** Avanza el contador y devuelve el número recién asignado. */
    public long next() {
        lastNumber++;
        return lastNumber;
    }

    /** Código de ticket para un número de esta sucursal ({@code 0003-00000127}). */
    public static String ticketCode(Long branchId, long number) {
        return String.format(TICKET_CODE_FORMAT, branchId, number);
    }
}
