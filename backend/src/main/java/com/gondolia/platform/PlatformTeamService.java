package com.gondolia.platform;

import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ConflictException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.error.NotFoundException;
import com.gondolia.common.util.Emails;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.platform.dto.PlatformUserDto;
import com.gondolia.platform.dto.PlatformUserRequests;
import com.gondolia.realtime.SessionTerminationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Equipo interno de GondolIA (SPEC §6.6): dueños y agentes de soporte. Un dueño no puede desactivarse a sí mismo
 * ni cambiar el rol de una cuenta ya creada.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlatformTeamService {

    static final String MSG_NOT_FOUND = "La cuenta no existe";
    static final String CODE_EMAIL_TAKEN = "EMAIL_TAKEN";
    static final String CODE_SELF_DEACTIVATION = "SELF_DEACTIVATION";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionTerminationService sessionTermination;

    /** Dueños y soporte, ordenados por nombre. */
    public List<PlatformUserDto> list() {
        return userRepository.findByRoleInOrderByFullNameAsc(Role.platformRoles()).stream()
                .map(PlatformTeamService::toDto)
                .toList();
    }

    /** Alta de un integrante del equipo (rol de plataforma). */
    @Transactional
    public PlatformUserDto create(PlatformUserRequests.Create request) {
        if (!request.role().isPlatformRole()) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "El equipo de GondolIA solo tiene dueños y agentes de soporte");
        }
        String email = Emails.normalize(request.email());
        if (email == null) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR, "Cargá el email de la cuenta");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException(CODE_EMAIL_TAKEN, "Ya hay una cuenta con el email «" + email + "»");
        }
        User user = new User();
        user.setTenantId(null);
        user.setEmail(email);
        user.setFullName(request.fullName().strip());
        user.setRole(request.role());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setActive(true);
        userRepository.saveAndFlush(user);
        log.info("Alta en el equipo de GondolIA: {} ({})", user.getEmail(), user.getRole());
        return toDto(user);
    }

    /** Cambia el nombre y el alta/baja de una cuenta del equipo. */
    @Transactional
    public PlatformUserDto update(Long userId, PlatformUserRequests.Update request, Long actorUserId) {
        User user = require(userId);
        boolean active = Boolean.TRUE.equals(request.active());
        if (!active && user.getId().equals(actorUserId)) {
            throw new ConflictException(CODE_SELF_DEACTIVATION, "No podés desactivar tu propia cuenta");
        }
        user.setFullName(request.fullName().strip());
        boolean deactivating = user.isActive() && !active;
        user.setActive(active);
        if (deactivating) {
            user.incrementTokenVersion();
        }
        userRepository.saveAndFlush(user);
        if (deactivating) {
            sessionTermination.forceLogoutUser(user.getId(), ErrorCodes.USER_DISABLED,
                    "Tu cuenta fue desactivada. Comunicate con GondolIA.");
        }
        return toDto(user);
    }

    /** Define una contraseña nueva: obliga a cambiarla al entrar y cierra las sesiones abiertas de esa cuenta. */
    @Transactional
    public PlatformUserDto resetPassword(Long userId, PlatformUserRequests.ResetPassword request) {
        User user = require(userId);
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(true);
        user.incrementTokenVersion();
        userRepository.saveAndFlush(user);
        sessionTermination.forceLogoutUser(user.getId(), "PASSWORD_RESET",
                "Un dueño de GondolIA restableció tu contraseña. Volvé a iniciar sesión.");
        log.info("Contraseña restablecida para la cuenta {} del equipo", user.getEmail());
        return toDto(user);
    }

    private User require(Long userId) {
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);
        if (user == null || !user.getRole().isPlatformRole()) {
            throw new NotFoundException(MSG_NOT_FOUND);
        }
        return user;
    }

    private static PlatformUserDto toDto(User user) {
        return new PlatformUserDto(user.getId(), user.getFullName(), user.getEmail(), user.getRole(), user.isActive(),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
