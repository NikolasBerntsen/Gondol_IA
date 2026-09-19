package com.gondolia.catalog;

import com.gondolia.catalog.dto.ProductMovementDto;
import com.gondolia.domain.inventory.Lot;
import com.gondolia.domain.inventory.LotRepository;
import com.gondolia.domain.inventory.StockMovement;
import com.gondolia.domain.inventory.StockMovementRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Últimos movimientos de un producto en el alcance de sucursales, para la ficha del producto (SPEC §3.3: "ficha con
 * lotes y movimientos del producto", jefe + administrador + empleado). Es solo lectura y respeta el alcance: un
 * empleado nunca ve movimientos de sucursales que no tiene asignadas.
 * <p>
 * El historial de ventas y de movimientos es del jefe y del administrador (SPEC §3.3): para el resto de los roles
 * (el empleado) la ficha muestra el movimiento de stock —tipo, cantidad, lote, sucursal, quién y cuándo— pero sin los
 * datos de la venta ({@code unitPrice}, {@code discountPct}, {@code totalAmount}) ni la referencia del comprobante
 * ({@code batchRef}), que llevan al historial que no puede abrir.
 */
@Service
@RequiredArgsConstructor
public class ProductMovementService {

    /** Tope de filas que devuelve la ficha. */
    public static final int MAX_LIMIT = 50;

    private final StockMovementRepository movementRepository;
    private final LotRepository lotRepository;
    private final UserRepository userRepository;
    private final BranchAccessService branchAccessService;
    private final ProductService productService;

    @Transactional(readOnly = true)
    public List<ProductMovementDto> recent(Long productId, int limit) {
        Long tenantId = CurrentUser.tenantId();
        productService.require(tenantId, productId);
        List<Long> branchIds = branchAccessService.scopeBranchIds();
        if (branchIds.isEmpty()) {
            return List.of();
        }
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        Specification<StockMovement> filter = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), tenantId));
            predicates.add(cb.equal(root.get("productId"), productId));
            predicates.add(root.get("branchId").in(branchIds));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        List<StockMovement> movements = movementRepository
                .findAll(filter, PageRequest.of(0, size, Sort.by(Sort.Direction.DESC, "occurredAt", "id")))
                .getContent();

        Map<Long, String> branchNames = branchAccessService.branchNames(tenantId);
        Map<Long, String> lotNumbers = lotNumbers(tenantId, movements);
        Map<Long, String> userNames = userNames(tenantId, movements);
        boolean salesDetail = seesSalesHistory(CurrentUser.role());

        return movements.stream()
                .map(movement -> new ProductMovementDto(
                        movement.getId(),
                        movement.getBranchId(),
                        branchNames.get(movement.getBranchId()),
                        movement.getLotId(),
                        movement.getLotId() == null ? null : lotNumbers.get(movement.getLotId()),
                        movement.getType(),
                        movement.getQuantity(),
                        salesDetail ? movement.getUnitPrice() : null,
                        salesDetail ? movement.getDiscountPct() : null,
                        salesDetail ? movement.getTotalAmount() : null,
                        movement.getSource(),
                        salesDetail ? movement.getBatchRef() : null,
                        movement.getReason(),
                        movement.getUserId() == null ? null : userNames.get(movement.getUserId()),
                        movement.getOccurredAt()))
                .toList();
    }

    /**
     * {@code true} si el rol ve el historial de ventas y de movimientos (SPEC §3.3: jefe y administrador, el mismo
     * grupo que {@code Roles.TENANT_DASHBOARD}).
     */
    static boolean seesSalesHistory(Role role) {
        return role == Role.TENANT_ADMIN || role == Role.TENANT_BOSS;
    }

    private Map<Long, String> lotNumbers(Long tenantId, List<StockMovement> movements) {
        List<Long> ids = movements.stream().map(StockMovement::getLotId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return lotRepository.findByTenantIdAndIdIn(tenantId, ids).stream()
                .filter(lot -> lot.getLotNumber() != null)
                .collect(Collectors.toMap(Lot::getId, Lot::getLotNumber, (a, b) -> a));
    }

    private Map<Long, String> userNames(Long tenantId, List<StockMovement> movements) {
        List<Long> ids = movements.stream().map(StockMovement::getUserId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userRepository.findByTenantIdOrderByFullNameAsc(tenantId).stream()
                .filter(user -> ids.contains(user.getId()))
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
    }
}
