package com.gondolia.movements;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Utilidades compartidas por los servicios del módulo: alcance de sucursales, nombres para mostrar y
 * conversión de rangos de fechas de negocio a instantes.
 */
@Service
@RequiredArgsConstructor
public class MovementSupport {

    private final BranchAccessService branchAccessService;
    private final UserRepository userRepository;
    private final Clock clock;

    /** Sucursales del alcance actual ({@code X-Branch-Id}); nunca vacío para un usuario de comercio. */
    public List<Long> scope() {
        List<Long> branchIds = branchAccessService.scopeBranchIds();
        if (branchIds.isEmpty()) {
            throw new BadRequestException(ErrorCodes.BRANCH_REQUIRED,
                    "Todavía no tenés ninguna sucursal asignada. Pedile al administrador que te asigne una.");
        }
        return branchIds;
    }

    /** Alcance limitado a una sucursal concreta (filtro opcional de la pantalla); valida el acceso. */
    public List<Long> scope(Long branchId) {
        if (branchId == null) {
            return scope();
        }
        branchAccessService.assertAccess(branchId);
        return List.of(branchId);
    }

    /** Nombres de las sucursales del comercio actual (id → nombre). */
    @Transactional(readOnly = true)
    public Map<Long, String> branchNames() {
        return branchAccessService.branchNames(CurrentUser.tenantId());
    }

    /** Nombre de una sucursal o {@code "—"} si no se puede resolver. */
    public String branchName(Map<Long, String> names, Long branchId) {
        String name = branchId == null ? null : names.get(branchId);
        return name == null ? "—" : name;
    }

    /** Nombres completos de los usuarios indicados (id → nombre); ignora los nulos. */
    @Transactional(readOnly = true)
    public Map<Long, String> userNames(Collection<Long> userIds) {
        Set<Long> ids = new LinkedHashSet<>();
        for (Long id : userIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        for (User user : userRepository.findAllById(ids)) {
            names.put(user.getId(), user.getFullName());
        }
        return names;
    }

    /**
     * Comienzo del día {@code from} en hora de negocio, o null. Se devuelve como {@link OffsetDateTime}
     * porque es un parámetro de JDBC: el driver de PostgreSQL no sabe qué tipo SQL usar para un
     * {@link Instant} suelto ("Can't infer the SQL type…") y la consulta termina en 500.
     */
    public OffsetDateTime startOfDay(LocalDate from) {
        return from == null ? null : atStartOfDay(from);
    }

    /** Fin (exclusivo) del día {@code to} en hora de negocio, o null; ver {@link #startOfDay}. */
    public OffsetDateTime endOfDay(LocalDate to) {
        return to == null ? null : atStartOfDay(to.plusDays(1));
    }

    private OffsetDateTime atStartOfDay(LocalDate date) {
        return OffsetDateTime.ofInstant(date.atStartOfDay(clock.getZone()).toInstant(), ZoneOffset.UTC);
    }

    /** Valida que {@code from} no sea posterior a {@code to}. */
    public void validateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "La fecha «desde» no puede ser posterior a la fecha «hasta»");
        }
    }

    /** Hoy en hora de negocio. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Texto de búsqueda listo para {@code ilike} ({@code %texto%}) o null si está vacío. */
    public String like(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.strip();
        return trimmed.isEmpty() ? null : "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }
}
