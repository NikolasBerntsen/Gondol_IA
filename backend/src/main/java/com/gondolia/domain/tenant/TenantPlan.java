package com.gondolia.domain.tenant;

import java.math.BigDecimal;

/**
 * Plan de suscripción. El precio es mensual, en ARS y por cada sucursal activa.
 */
public enum TenantPlan {
    FREEMIUM(0, 1),
    BASICO(25_000, 3),
    PROFESIONAL(55_000, 10);

    private final BigDecimal monthlyPricePerBranch;
    private final int maxBranches;

    TenantPlan(long monthlyPricePerBranch, int maxBranches) {
        this.monthlyPricePerBranch = BigDecimal.valueOf(monthlyPricePerBranch);
        this.maxBranches = maxBranches;
    }

    /** Precio mensual en ARS por sucursal activa. */
    public BigDecimal monthlyPricePerBranch() {
        return monthlyPricePerBranch;
    }

    /** Cantidad máxima de sucursales activas. */
    public int maxBranches() {
        return maxBranches;
    }

    /** Abono mensual para una cantidad de sucursales activas. */
    public BigDecimal monthlyFee(long activeBranches) {
        return monthlyPricePerBranch.multiply(BigDecimal.valueOf(Math.max(activeBranches, 0)));
    }

    public boolean isPaid() {
        return monthlyPricePerBranch.signum() > 0;
    }
}
