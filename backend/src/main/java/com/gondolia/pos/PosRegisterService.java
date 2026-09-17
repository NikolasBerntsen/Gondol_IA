package com.gondolia.pos;

import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.domain.pos.PosRegister;
import com.gondolia.domain.pos.PosRegisterRepository;
import com.gondolia.domain.pos.PosSession;
import com.gondolia.domain.pos.PosSessionRepository;
import com.gondolia.domain.pos.PosSessionStatus;
import com.gondolia.pos.dto.PosRegisterDto;
import com.gondolia.pos.dto.PosRegisterRequest;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.CurrentUser;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cajas del POS GondolIA (SPEC §15.2). Las lista cualquier usuario del POS dentro de su alcance de sucursales; el
 * alta y la edición son del administrador.
 */
@Service
@RequiredArgsConstructor
public class PosRegisterService {

    private static final String NOT_FOUND = "No encontramos esa caja.";

    private final PosRegisterRepository registerRepository;
    private final PosSessionRepository sessionRepository;
    private final BranchAccessService branchAccess;
    private final PosDirectory directory;

    /** Cajas del alcance de sucursales, con el turno abierto de cada una si lo hay. */
    @Transactional(readOnly = true)
    public List<PosRegisterDto> list(Long tenantId, boolean includeInactive) {
        List<Long> branchIds = branchAccess.scopeBranchIds();
        if (branchIds.isEmpty()) {
            return List.of();
        }
        List<PosRegister> registers = includeInactive
                ? registerRepository.findByTenantIdAndBranchIdInOrderByBranchIdAscNameAsc(tenantId, branchIds)
                : registerRepository.findByTenantIdAndBranchIdInAndActiveTrueOrderByBranchIdAscNameAsc(tenantId,
                        branchIds);
        return toDtos(tenantId, registers);
    }

    @Transactional(readOnly = true)
    public PosRegisterDto get(Long tenantId, Long registerId) {
        PosRegister register = require(tenantId, registerId);
        branchAccess.assertAccess(register.getBranchId());
        return toDtos(tenantId, List.of(register)).getFirst();
    }

    @Transactional
    public PosRegisterDto create(Long tenantId, PosRegisterRequest request) {
        Long branchId = branchAccess.requireSingleBranch(request.branchId());
        String name = normalizeName(request.name());
        if (registerRepository.existsByBranchIdAndNameIgnoreCase(branchId, name)) {
            throw new ConflictException(PosErrorCodes.REGISTER_NAME_TAKEN,
                    "Ya hay una caja con ese nombre en la sucursal. Elegí otro.");
        }
        PosRegister register = new PosRegister();
        register.setTenantId(tenantId);
        register.setBranchId(branchId);
        register.setName(name);
        register.setActive(request.active() == null || request.active());
        registerRepository.save(register);
        return toDtos(tenantId, List.of(register)).getFirst();
    }

    @Transactional
    public PosRegisterDto update(Long tenantId, Long registerId, PosRegisterRequest request) {
        PosRegister register = require(tenantId, registerId);
        branchAccess.assertAccess(register.getBranchId());
        Optional<PosSession> openSession = sessionRepository.findByRegisterIdAndStatus(register.getId(),
                PosSessionStatus.OPEN);

        Long branchId = request.branchId() == null ? register.getBranchId() : request.branchId();
        if (!Objects.equals(branchId, register.getBranchId())) {
            if (openSession.isPresent()) {
                throw new ConflictException(PosErrorCodes.REGISTER_IN_USE,
                        "La caja tiene un turno abierto: cerralo antes de moverla de sucursal.");
            }
            branchAccess.assertAccess(branchId);
        }
        String name = normalizeName(request.name());
        if (registerRepository.existsByBranchIdAndNameIgnoreCaseAndIdNot(branchId, name, register.getId())) {
            throw new ConflictException(PosErrorCodes.REGISTER_NAME_TAKEN,
                    "Ya hay una caja con ese nombre en la sucursal. Elegí otro.");
        }
        boolean active = request.active() == null ? register.isActive() : request.active();
        if (!active && openSession.isPresent()) {
            throw new ConflictException(PosErrorCodes.REGISTER_IN_USE,
                    "La caja tiene un turno abierto: cerralo antes de desactivarla.");
        }
        register.setBranchId(branchId);
        register.setName(name);
        register.setActive(active);
        registerRepository.save(register);
        return toDtos(tenantId, List.of(register)).getFirst();
    }

    /** Caja del tenant o 404 (nunca revela cajas de otro comercio). */
    public PosRegister require(Long tenantId, Long registerId) {
        return registerRepository.findByIdAndTenantId(registerId, tenantId)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
    }

    private String normalizeName(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private List<PosRegisterDto> toDtos(Long tenantId, List<PosRegister> registers) {
        Map<Long, String> branchNames = directory.branchNames(tenantId);
        Set<Long> userIds = new HashSet<>();
        List<PosSession> openSessions = new ArrayList<>();
        for (PosRegister register : registers) {
            sessionRepository.findByRegisterIdAndStatus(register.getId(), PosSessionStatus.OPEN)
                    .ifPresent(session -> {
                        openSessions.add(session);
                        userIds.add(session.getOpenedBy());
                    });
        }
        Map<Long, String> userNames = directory.userNames(userIds);
        Long currentUserId = CurrentUser.optional().map(user -> user.id()).orElse(null);
        List<PosRegisterDto> dtos = new ArrayList<>(registers.size());
        for (PosRegister register : registers) {
            PosSession open = openSessions.stream()
                    .filter(session -> Objects.equals(session.getRegisterId(), register.getId()))
                    .findFirst().orElse(null);
            PosRegisterDto.OpenSessionRef ref = open == null ? null : new PosRegisterDto.OpenSessionRef(
                    open.getId(), open.getOpenedBy(), PosDirectory.nameOf(userNames, open.getOpenedBy()),
                    open.getOpenedAt(), Objects.equals(open.getOpenedBy(), currentUserId));
            dtos.add(new PosRegisterDto(register.getId(), register.getBranchId(),
                    branchNames.get(register.getBranchId()), register.getName(), register.isActive(),
                    register.getCreatedAt(), ref));
        }
        return dtos;
    }
}
