package com.gondolia.pos;

import com.gondolia.domain.tenant.Branch;
import com.gondolia.domain.tenant.BranchRepository;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Nombres de sucursales y usuarios para las respuestas del POS (cada fila por sucursal lleva
 * {@code branchId} + {@code branchName}, SPEC §6).
 */
@Component
@RequiredArgsConstructor
public class PosDirectory {

    private final BranchRepository branchRepository;
    private final UserRepository userRepository;

    public Map<Long, String> branchNames(Long tenantId) {
        return branchRepository.findByTenantIdOrderByNameAsc(tenantId).stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName, (a, b) -> a, HashMap::new));
    }

    public Map<Long, String> userNames(Collection<Long> userIds) {
        Set<Long> ids = userIds.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<User> users = userRepository.findAllById(ids);
        Map<Long, String> names = new HashMap<>();
        for (User user : users) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    public static String nameOf(Map<Long, String> names, Long id) {
        return id == null ? null : names.get(id);
    }
}
