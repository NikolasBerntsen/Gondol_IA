package com.gondolia.pos;

import com.gondolia.common.PageResponse;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.common.Timestamps;
import com.gondolia.domain.pos.CashMovementType;
import com.gondolia.domain.pos.PaymentMethod;
import com.gondolia.domain.pos.PosCashMovement;
import com.gondolia.domain.pos.PosCashMovementRepository;
import com.gondolia.domain.pos.PosPayment;
import com.gondolia.domain.pos.PosPaymentRepository;
import com.gondolia.domain.pos.PosRegister;
import com.gondolia.domain.pos.PosRegisterRepository;
import com.gondolia.domain.pos.PosSale;
import com.gondolia.domain.pos.PosSaleItem;
import com.gondolia.domain.pos.PosSaleItemRepository;
import com.gondolia.domain.pos.PosSaleRepository;
import com.gondolia.domain.pos.PosSaleStatus;
import com.gondolia.domain.pos.PosSession;
import com.gondolia.domain.pos.PosSessionRepository;
import com.gondolia.domain.pos.PosSessionStatus;
import com.gondolia.pos.dto.CashMovementRequest;
import com.gondolia.pos.dto.CloseSessionRequest;
import com.gondolia.pos.dto.OpenSessionRequest;
import com.gondolia.pos.dto.PosCashMovementDto;
import com.gondolia.pos.dto.PosSessionReportDto;
import com.gondolia.pos.dto.PosSessionSummaryDto;
import com.gondolia.pos.dto.PosTopProductDto;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Predicate;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turnos de caja del POS GondolIA (SPEC §15.2): apertura, ingresos y retiros de efectivo, cierre con arqueo y
 * reporte Z.
 * <p>
 * Arqueo: {@code expectedCash = openingCash + Σ pagos CASH − Σ vuelto + CASH_IN − CASH_OUT − efectivo neto de
 * ventas anuladas}. Al cerrar, el valor calculado y la diferencia quedan guardados en el turno; el reporte de un
 * turno cerrado muestra ese arqueo aunque después se anule una venta.
 * <p>
 * Un turno sin ventas también se cierra (el cajero abrió la caja por error o no vendió nada): el cierre libera la
 * caja igual que cualquier otro y queda marcado con {@code closedWithoutSales}.
 */
@Service
@RequiredArgsConstructor
public class PosSessionService {

    private static final String NOT_FOUND = "No encontramos ese turno de caja.";

    private final PosSessionRepository sessionRepository;
    private final PosRegisterRepository registerRepository;
    private final PosSaleRepository saleRepository;
    private final PosSaleItemRepository saleItemRepository;
    private final PosPaymentRepository paymentRepository;
    private final PosCashMovementRepository cashMovementRepository;
    private final PosRegisterService registerService;
    private final BranchAccessService branchAccess;
    private final PosDirectory directory;
    private final EntityManager entityManager;
    private final Clock clock;

    // ------------------------------------------------------------------ turno actual

    /** Turno abierto del usuario, o {@code null} si no tiene ninguno. */
    @Transactional(readOnly = true)
    public PosSessionReportDto current(AuthUser user) {
        Optional<PosSession> session = sessionRepository.findByOpenedByAndStatus(user.id(), PosSessionStatus.OPEN);
        return session.map(value -> report(user, value)).orElse(null);
    }

    // ------------------------------------------------------------------ apertura

    @Transactional
    public PosSessionReportDto open(AuthUser user, OpenSessionRequest request) {
        PosRegister register = registerService.require(user.tenantId(), request.registerId());
        branchAccess.assertAccess(register.getBranchId());
        if (!register.isActive()) {
            throw new ConflictException(PosErrorCodes.REGISTER_INACTIVE,
                    "Esa caja está desactivada. Elegí otra o pedile al administrador que la active.");
        }
        if (sessionRepository.existsByOpenedByAndStatus(user.id(), PosSessionStatus.OPEN)) {
            throw new ConflictException(ErrorCodes.SESSION_ALREADY_OPEN,
                    "Ya tenés un turno abierto. Cerralo antes de abrir otro.");
        }
        if (sessionRepository.existsByRegisterIdAndStatus(register.getId(), PosSessionStatus.OPEN)) {
            throw new ConflictException(ErrorCodes.REGISTER_BUSY,
                    "Esa caja ya tiene un turno abierto. Elegí otra caja.");
        }

        PosSession session = new PosSession();
        session.setTenantId(user.tenantId());
        session.setBranchId(register.getBranchId());
        session.setRegisterId(register.getId());
        session.setStatus(PosSessionStatus.OPEN);
        session.setOpenedBy(user.id());
        session.setOpeningCash(PosMoney.orZero(request.openingCash()));
        session.setOpenedAt(Timestamps.now());
        sessionRepository.save(session);
        return report(user, session);
    }

    // ------------------------------------------------------------------ efectivo

    @Transactional
    public PosSessionReportDto addCashMovement(AuthUser user, Long sessionId, CashMovementRequest request) {
        PosSession session = requireOpen(user, sessionId);
        PosCashMovement movement = new PosCashMovement();
        movement.setTenantId(user.tenantId());
        movement.setSessionId(session.getId());
        movement.setType(request.type());
        movement.setAmount(PosMoney.scale(request.amount()));
        movement.setReason(request.reason().strip());
        movement.setUserId(user.id());
        movement.setCreatedAt(Timestamps.now());
        cashMovementRepository.save(movement);
        return report(user, session);
    }

    // ------------------------------------------------------------------ cierre

    /**
     * Cierra el turno con arqueo y libera la caja. No exige ventas: un turno sin ninguna venta vigente (o con todas
     * anuladas) se cierra igual y queda marcado como cerrado sin ventas; el aviso previo lo muestra el mostrador.
     */
    @Transactional
    public PosSessionReportDto close(AuthUser user, Long sessionId, CloseSessionRequest request) {
        PosSession session = requireOpen(user, sessionId);
        Arqueo arqueo = arqueo(session);
        BigDecimal counted = PosMoney.orZero(request.countedCash());
        session.setExpectedCash(arqueo.expectedCash());
        session.setCountedCash(counted);
        session.setCashDifference(PosMoney.scale(counted.subtract(arqueo.expectedCash())));
        session.setClosedWithoutSales(arqueo.salesCount() == 0);
        session.setStatus(PosSessionStatus.CLOSED);
        session.setClosedBy(user.id());
        session.setClosedAt(Timestamps.now());
        session.setClosingNote(request.note() == null || request.note().isBlank() ? null : request.note().strip());
        sessionRepository.save(session);
        return report(user, session);
    }

    // ------------------------------------------------------------------ consultas

    /**
     * Turnos del alcance de sucursales. El administrador ve todos (o solo los suyos con {@code mine}); el empleado y
     * el cajero, siempre los propios.
     */
    @Transactional(readOnly = true)
    public PageResponse<PosSessionSummaryDto> list(AuthUser user, PosSessionStatus status, LocalDate from,
                                                   LocalDate to, boolean mine, int page, int size) {
        List<Long> branchIds = branchAccess.scopeBranchIds();
        if (branchIds.isEmpty()) {
            return PageResponse.empty(page, size);
        }
        boolean onlyMine = mine || !PosAccess.isAdmin(user);
        Specification<PosSession> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("tenantId"), user.tenantId()));
            predicates.add(root.get("branchId").in(branchIds));
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (onlyMine) {
                predicates.add(cb.equal(root.get("openedBy"), user.id()));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("openedAt"), startOfDay(from)));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("openedAt"), startOfDay(to.plusDays(1))));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Page<PosSession> sessions = sessionRepository.findAll(spec,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "openedAt", "id")));
        Map<Long, String> branchNames = directory.branchNames(user.tenantId());
        Map<Long, String> registerNames = registerNames(user.tenantId());
        Set<Long> userIds = new HashSet<>();
        sessions.forEach(session -> {
            userIds.add(session.getOpenedBy());
            userIds.add(session.getClosedBy());
        });
        Map<Long, String> userNames = directory.userNames(userIds);
        return PageResponse.of(sessions, session -> new PosSessionSummaryDto(session.getId(), session.getBranchId(),
                branchNames.get(session.getBranchId()), session.getRegisterId(),
                registerNames.get(session.getRegisterId()), session.getStatus(), session.getOpenedBy(),
                PosDirectory.nameOf(userNames, session.getOpenedBy()),
                PosDirectory.nameOf(userNames, session.getClosedBy()), session.getOpenedAt(), session.getClosedAt(),
                PosMoney.orZero(session.getOpeningCash()), session.getExpectedCash(), session.getCountedCash(),
                session.getCashDifference(), session.getSalesCount(), PosMoney.orZero(session.getSalesTotal()),
                session.getVoidedCount(), PosMoney.orZero(session.getVoidedTotal()),
                PosAccess.owns(user, session), session.isClosedWithoutSales()));
    }

    /** Reporte Z de un turno. */
    @Transactional(readOnly = true)
    public PosSessionReportDto get(AuthUser user, Long sessionId) {
        return report(user, require(user, sessionId));
    }

    // ------------------------------------------------------------------ acceso

    /** Turno del tenant, dentro del alcance de sucursales y visible para el usuario. */
    public PosSession require(AuthUser user, Long sessionId) {
        PosSession session = sessionRepository.findByIdAndTenantId(sessionId, user.tenantId())
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        branchAccess.assertAccess(session.getBranchId());
        PosAccess.assertCanView(user, session);
        return session;
    }

    /** Turno abierto que el usuario puede operar (propio, o cualquiera si es administrador). */
    public PosSession requireOpen(AuthUser user, Long sessionId) {
        PosSession session = sessionRepository.findByIdAndTenantId(sessionId, user.tenantId())
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        branchAccess.assertAccess(session.getBranchId());
        PosAccess.assertCanOperate(user, session);
        if (!session.isOpen()) {
            throw new ConflictException(PosErrorCodes.SESSION_NOT_OPEN,
                    "El turno de caja está cerrado. Abrí uno nuevo para seguir cobrando.");
        }
        return session;
    }

    /** Bloquea la fila del turno para actualizar sus contadores sin perder ventas concurrentes. */
    public PosSession lock(Long sessionId) {
        return entityManager.find(PosSession.class, sessionId, LockModeType.PESSIMISTIC_WRITE);
    }

    // ------------------------------------------------------------------ arqueo y reporte

    /** Totales de efectivo y de ventas de un turno (SPEC §15.2). */
    public record Arqueo(BigDecimal cashPayments, BigDecimal changeGiven, BigDecimal cashIn, BigDecimal cashOut,
                         BigDecimal voidedNetCash, BigDecimal expectedCash,
                         Map<PaymentMethod, BigDecimal> totalsByMethod, int salesCount, BigDecimal salesTotal,
                         int units, int voidedCount, BigDecimal voidedTotal, List<PosTopProductDto> topProducts) {
    }

    /**
     * Recalcula el arqueo del turno con las ventas y movimientos vigentes.
     * {@code expectedCash = openingCash + Σ pagos CASH − Σ vuelto + CASH_IN − CASH_OUT − efectivo neto anulado}.
     */
    public Arqueo arqueo(PosSession session) {
        List<PosSale> sales = saleRepository.findBySessionIdOrderByNumberAsc(session.getId());
        List<Long> saleIds = sales.stream().map(PosSale::getId).toList();
        Map<Long, List<PosPayment>> paymentsBySale = new HashMap<>();
        if (!saleIds.isEmpty()) {
            for (PosPayment payment : paymentRepository.findBySaleIdInOrderBySaleIdAscIdAsc(saleIds)) {
                paymentsBySale.computeIfAbsent(payment.getSaleId(), key -> new ArrayList<>()).add(payment);
            }
        }

        Map<PaymentMethod, BigDecimal> totalsByMethod = new EnumMap<>(PaymentMethod.class);
        for (PaymentMethod method : PaymentMethod.values()) {
            totalsByMethod.put(method, PosMoney.ZERO);
        }
        BigDecimal cashPayments = PosMoney.ZERO;
        BigDecimal changeAll = PosMoney.ZERO;
        BigDecimal changeCompleted = PosMoney.ZERO;
        BigDecimal voidedNetCash = PosMoney.ZERO;
        BigDecimal salesTotal = PosMoney.ZERO;
        BigDecimal voidedTotal = PosMoney.ZERO;
        int salesCount = 0;
        int voidedCount = 0;
        int units = 0;
        List<Long> completedIds = new ArrayList<>();

        for (PosSale sale : sales) {
            List<PosPayment> payments = paymentsBySale.getOrDefault(sale.getId(), List.of());
            BigDecimal saleCash = PosMoney.ZERO;
            for (PosPayment payment : payments) {
                if (payment.getMethod() == PaymentMethod.CASH) {
                    saleCash = saleCash.add(PosMoney.orZero(payment.getAmount()));
                }
            }
            BigDecimal change = PosMoney.orZero(sale.getChangeAmount());
            cashPayments = cashPayments.add(saleCash);
            changeAll = changeAll.add(change);
            if (sale.getStatus() == PosSaleStatus.VOIDED) {
                voidedCount++;
                voidedTotal = voidedTotal.add(PosMoney.orZero(sale.getTotal()));
                voidedNetCash = voidedNetCash.add(saleCash.subtract(change));
            } else {
                salesCount++;
                salesTotal = salesTotal.add(PosMoney.orZero(sale.getTotal()));
                units += sale.getUnits();
                changeCompleted = changeCompleted.add(change);
                completedIds.add(sale.getId());
                for (PosPayment payment : payments) {
                    totalsByMethod.merge(payment.getMethod(), PosMoney.orZero(payment.getAmount()), BigDecimal::add);
                }
            }
        }

        BigDecimal cashIn = PosMoney.orZero(cashMovementRepository.sumByType(session.getId(),
                CashMovementType.CASH_IN));
        BigDecimal cashOut = PosMoney.orZero(cashMovementRepository.sumByType(session.getId(),
                CashMovementType.CASH_OUT));
        BigDecimal expected = PosMoney.orZero(session.getOpeningCash())
                .add(cashPayments)
                .subtract(changeAll)
                .add(cashIn)
                .subtract(cashOut)
                .subtract(voidedNetCash);

        return new Arqueo(PosMoney.scale(cashPayments), PosMoney.scale(changeCompleted), cashIn, cashOut,
                PosMoney.scale(voidedNetCash), PosMoney.scale(expected), totalsByMethod, salesCount,
                PosMoney.scale(salesTotal), units, voidedCount, PosMoney.scale(voidedTotal),
                topProducts(completedIds));
    }

    /** Reporte completo del turno (con el arqueo guardado si ya está cerrado). */
    public PosSessionReportDto report(AuthUser user, PosSession session) {
        Arqueo arqueo = arqueo(session);
        Map<Long, String> branchNames = directory.branchNames(user.tenantId());
        Map<Long, String> registerNames = registerNames(user.tenantId());
        List<PosCashMovement> movements =
                cashMovementRepository.findBySessionIdOrderByCreatedAtAscIdAsc(session.getId());
        Set<Long> userIds = new HashSet<>();
        userIds.add(session.getOpenedBy());
        userIds.add(session.getClosedBy());
        movements.forEach(movement -> userIds.add(movement.getUserId()));
        Map<Long, String> userNames = directory.userNames(userIds);

        boolean closed = session.getStatus() == PosSessionStatus.CLOSED;
        BigDecimal expected = closed && session.getExpectedCash() != null
                ? PosMoney.scale(session.getExpectedCash())
                : arqueo.expectedCash();
        BigDecimal counted = session.getCountedCash() == null ? null : PosMoney.scale(session.getCountedCash());
        BigDecimal difference = counted == null ? null : PosMoney.scale(counted.subtract(expected));

        List<PosCashMovementDto> movementDtos = movements.stream()
                .map(movement -> new PosCashMovementDto(movement.getId(), movement.getType(),
                        PosMoney.orZero(movement.getAmount()), movement.getReason(),
                        PosDirectory.nameOf(userNames, movement.getUserId()), movement.getCreatedAt()))
                .toList();

        return new PosSessionReportDto(session.getId(), session.getBranchId(),
                branchNames.get(session.getBranchId()), session.getRegisterId(),
                registerNames.get(session.getRegisterId()), session.getStatus(), session.getOpenedBy(),
                PosDirectory.nameOf(userNames, session.getOpenedBy()),
                PosDirectory.nameOf(userNames, session.getClosedBy()), session.getOpenedAt(), session.getClosedAt(),
                PosMoney.orZero(session.getOpeningCash()), arqueo.totalsByMethod(), arqueo.cashIn(), arqueo.cashOut(),
                arqueo.changeGiven(), expected, counted, difference, arqueo.salesCount(), arqueo.salesTotal(),
                arqueo.units(), arqueo.voidedCount(), arqueo.voidedTotal(), arqueo.topProducts(), movementDtos,
                PosAccess.owns(user, session), session.getClosingNote(), session.isClosedWithoutSales());
    }

    /** Sincroniza los contadores del turno después de una venta o de una anulación. */
    public void applySale(PosSession session, BigDecimal total, boolean voided) {
        BigDecimal amount = PosMoney.orZero(total);
        if (voided) {
            session.setSalesCount(Math.max(0, session.getSalesCount() - 1));
            session.setSalesTotal(PosMoney.scale(PosMoney.orZero(session.getSalesTotal()).subtract(amount)));
            session.setVoidedCount(session.getVoidedCount() + 1);
            session.setVoidedTotal(PosMoney.scale(PosMoney.orZero(session.getVoidedTotal()).add(amount)));
        } else {
            session.setSalesCount(session.getSalesCount() + 1);
            session.setSalesTotal(PosMoney.scale(PosMoney.orZero(session.getSalesTotal()).add(amount)));
        }
    }

    private List<PosTopProductDto> topProducts(List<Long> saleIds) {
        if (saleIds.isEmpty()) {
            return List.of();
        }
        Map<String, PosTopProductDto> byProduct = new LinkedHashMap<>();
        for (PosSaleItem item : saleItemRepository.findBySaleIdInOrderBySaleIdAscIdAsc(saleIds)) {
            String key = item.getProductId() == null ? "n:" + item.getProductName() : "p:" + item.getProductId();
            PosTopProductDto current = byProduct.get(key);
            if (current == null) {
                byProduct.put(key, new PosTopProductDto(item.getProductId(), item.getProductName(),
                        item.getQuantity(), PosMoney.orZero(item.getLineTotal())));
            } else {
                byProduct.put(key, new PosTopProductDto(current.productId(), current.productName(),
                        current.units() + item.getQuantity(),
                        PosMoney.scale(current.total().add(PosMoney.orZero(item.getLineTotal())))));
            }
        }
        return byProduct.values().stream()
                .sorted(Comparator.comparingInt(PosTopProductDto::units).reversed()
                        .thenComparing(PosTopProductDto::productName))
                .limit(5)
                .toList();
    }

    private Map<Long, String> registerNames(Long tenantId) {
        Map<Long, String> names = new HashMap<>();
        for (PosRegister register : registerRepository.findByTenantIdOrderByBranchIdAscNameAsc(tenantId)) {
            names.put(register.getId(), register.getName());
        }
        return names;
    }

    private Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }
}
