package com.gondolia.pos.dto;

import java.time.Instant;

/**
 * Caja del POS con su sucursal y, si lo hay, el turno abierto (SPEC §15.2).
 */
public record PosRegisterDto(Long id, Long branchId, String branchName, String name, boolean active,
                             Instant createdAt, OpenSessionRef openSession) {

    /** Turno abierto de la caja ({@code null} si está libre). */
    public record OpenSessionRef(Long id, Long openedById, String openedByName, Instant openedAt, boolean mine) {
    }
}
