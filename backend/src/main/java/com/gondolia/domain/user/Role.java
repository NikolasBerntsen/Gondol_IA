package com.gondolia.domain.user;

import java.util.EnumSet;
import java.util.Set;

public enum Role {
    PLATFORM_OWNER(Scope.PLATFORM),
    SUPPORT_AGENT(Scope.PLATFORM),
    TENANT_BOSS(Scope.TENANT),
    TENANT_ADMIN(Scope.TENANT),
    TENANT_EMPLOYEE(Scope.TENANT);

    private enum Scope { PLATFORM, TENANT }

    private final Scope scope;

    Role(Scope scope) {
        this.scope = scope;
    }

    /** Rol de un usuario de comercio ({@code tenant_id} obligatorio). */
    public boolean isTenantRole() {
        return scope == Scope.TENANT;
    }

    /** Rol interno de GondolIA ({@code tenant_id} NULL). */
    public boolean isPlatformRole() {
        return scope == Scope.PLATFORM;
    }

    /**
     * {@code true} si accede a todas las sucursales activas del tenant (jefe y administrador); el empleado solo a
     * las asignadas en {@code user_branches}.
     */
    public boolean accessesAllBranches() {
        return this == TENANT_ADMIN || this == TENANT_BOSS;
    }

    /** Authority de Spring Security ({@code ROLE_<nombre>}). */
    public String authority() {
        return "ROLE_" + name();
    }

    public static Set<Role> tenantRoles() {
        return EnumSet.of(TENANT_BOSS, TENANT_ADMIN, TENANT_EMPLOYEE);
    }

    public static Set<Role> platformRoles() {
        return EnumSet.of(PLATFORM_OWNER, SUPPORT_AGENT);
    }
}
