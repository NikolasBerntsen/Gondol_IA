package com.gondolia.security;

import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import java.security.Principal;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Usuario autenticado (HTTP y STOMP). {@link #getName()} devuelve el id para el ruteo {@code /user/**} de STOMP.
 */
public record AuthUser(Long id, String email, String fullName, Role role, Long tenantId) implements Principal {

    public static AuthUser from(User user) {
        return new AuthUser(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), user.getTenantId());
    }

    @Override
    public String getName() {
        return String.valueOf(id);
    }

    public boolean isTenantUser() {
        return role != null && role.isTenantRole();
    }

    public boolean hasRole(Role expected) {
        return role == expected;
    }

    public List<GrantedAuthority> authorities() {
        return List.of(new SimpleGrantedAuthority(role.authority()));
    }
}
