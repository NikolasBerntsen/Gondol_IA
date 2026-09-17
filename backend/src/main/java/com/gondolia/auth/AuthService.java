package com.gondolia.auth;

import com.gondolia.auth.dto.ChangePasswordRequest;
import com.gondolia.auth.dto.LoginRequest;
import com.gondolia.auth.dto.LoginResponse;
import com.gondolia.auth.dto.MeDto;
import com.gondolia.auth.dto.TokenResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.common.util.Emails;
import com.gondolia.domain.tenant.Tenant;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantSettings;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.domain.tenant.TenantModule;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.AuthUser;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.IssuedToken;
import com.gondolia.security.JwtService;
import com.gondolia.security.UserAccessValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AuthService {

    public static final String INVALID_CURRENT_PASSWORD = "INVALID_CURRENT_PASSWORD";
    public static final String SAME_PASSWORD = "SAME_PASSWORD";

    static final String MSG_BAD_CREDENTIALS = "El email o la contraseña son incorrectos";

    /** Límite de BCrypt. */
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final BranchAccessService branchAccessService;
    private final ModuleService moduleService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final UserAccessValidator userAccessValidator;
    private final Clock clock;

    /** Hash de referencia para que un email inexistente tarde lo mismo que una contraseña incorrecta. */
    private final String timingGuardHash;

    public AuthService(UserRepository userRepository, TenantRepository tenantRepository,
                       TenantSettingsRepository tenantSettingsRepository, BranchAccessService branchAccessService,
                       ModuleService moduleService, PasswordEncoder passwordEncoder, JwtService jwtService,
                       UserAccessValidator userAccessValidator, Clock clock) {
        this.userRepository = userRepository;
        this.tenantRepository = tenantRepository;
        this.tenantSettingsRepository = tenantSettingsRepository;
        this.branchAccessService = branchAccessService;
        this.moduleService = moduleService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.userAccessValidator = userAccessValidator;
        this.clock = clock;
        this.timingGuardHash = passwordEncoder.encode("gondolia-timing-guard");
    }

    /**
     * Valida credenciales y estado de la cuenta (usuario activo, comercio habilitado), registra el último ingreso y
     * emite un JWT.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        String password = request.password();
        String email = Emails.normalize(request.email());
        User user = email == null ? null : userRepository.findByEmail(email).orElse(null);

        if (user == null || exceedsBcryptLimit(password)) {
            passwordEncoder.matches("gondolia-timing-guard-mismatch", timingGuardHash);
            throw badCredentials();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw badCredentials();
        }

        userAccessValidator.checkAccess(user);
        user.setLastLoginAt(clock.instant());
        IssuedToken token = jwtService.issueToken(user);
        log.debug("Inicio de sesión del usuario {}", user.getId());
        return new LoginResponse(token.token(), token.expiresAt(), toMe(user));
    }

    @Transactional(readOnly = true)
    public MeDto me(Long userId) {
        return toMe(loadUser(userId));
    }

    /**
     * Cambia la contraseña, incrementa {@code token_version} (invalida todas las sesiones) y devuelve un token nuevo
     * para la sesión actual.
     */
    @Transactional
    public TokenResponse changePassword(Long userId, ChangePasswordRequest request) {
        User user = loadUser(userId);
        if (exceedsBcryptLimit(request.currentPassword())
                || !passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new BadRequestException(INVALID_CURRENT_PASSWORD, "La contraseña actual no es correcta");
        }
        if (exceedsBcryptLimit(request.newPassword())) {
            throw new BadRequestException(ErrorCodes.VALIDATION_ERROR,
                    "La nueva contraseña es demasiado larga (máximo 72 bytes)");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new BadRequestException(SAME_PASSWORD, "La nueva contraseña tiene que ser distinta de la actual");
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setMustChangePassword(false);
        user.incrementTokenVersion();
        userRepository.saveAndFlush(user);

        IssuedToken token = jwtService.issueToken(user);
        log.info("Usuario {} cambió su contraseña; sesiones anteriores invalidadas", user.getId());
        return new TokenResponse(token.token(), token.expiresAt());
    }

    /** {@link MeDto} del usuario, con el comercio y las sucursales accesibles para usuarios de tenant. */
    @Transactional(readOnly = true)
    public MeDto toMe(User user) {
        MeDto.TenantInfo tenantInfo = null;
        List<BranchAccessService.BranchRef> branches = List.of();
        if (user.getRole().isTenantRole() && user.getTenantId() != null) {
            tenantInfo = tenantRepository.findById(user.getTenantId()).map(this::toTenantInfo).orElse(null);
            branches = branchAccessService.accessibleBranches(AuthUser.from(user));
        }
        return new MeDto(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.isMustChangePassword(), tenantInfo, branches);
    }

    /** Comercio con sus módulos habilitados y el máximo efectivo de sucursales (SPEC §14.2). */
    private MeDto.TenantInfo toTenantInfo(Tenant tenant) {
        TenantSettings settings = tenantSettingsRepository.findById(tenant.getId())
                .orElseGet(() -> TenantSettings.defaultsFor(tenant.getId()));
        Set<TenantModule> modules = moduleService.enabledModules(tenant.getId());
        int maxBranches = ModuleService.effectiveMaxBranches(tenant.getPlan(),
                modules.contains(TenantModule.MULTI_BRANCH));
        return new MeDto.TenantInfo(tenant.getId(), tenant.getName(), tenant.getPlan(), tenant.getBusinessType(),
                settings.getCurrency(), settings.getStockRotation(), List.copyOf(modules), maxBranches);
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.UNAUTHORIZED,
                        UserAccessValidator.MSG_SESSION_INVALID));
    }

    private static boolean exceedsBcryptLimit(String password) {
        return password == null || password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES;
    }

    private static ApiException badCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, ErrorCodes.BAD_CREDENTIALS, MSG_BAD_CREDENTIALS);
    }
}
