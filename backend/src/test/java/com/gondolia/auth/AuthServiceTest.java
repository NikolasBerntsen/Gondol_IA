package com.gondolia.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gondolia.auth.dto.ChangePasswordRequest;
import com.gondolia.auth.dto.LoginRequest;
import com.gondolia.auth.dto.LoginResponse;
import com.gondolia.auth.dto.MeDto;
import com.gondolia.auth.dto.TokenResponse;
import com.gondolia.common.error.ApiException;
import com.gondolia.common.error.BadRequestException;
import com.gondolia.common.error.ErrorCodes;
import com.gondolia.domain.tenant.TenantRepository;
import com.gondolia.domain.tenant.TenantSettingsRepository;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import com.gondolia.modules.ModuleService;
import com.gondolia.security.BranchAccessService;
import com.gondolia.security.IssuedToken;
import com.gondolia.security.JwtService;
import com.gondolia.security.UserAccessValidator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Login, cambio de contraseña y armado de {@code /me} sin base de datos: el repositorio y los servicios de apoyo van
 * mockeados y el hash es un BCrypt real (con el coste mínimo) para que las comparaciones sean las de producción.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-20T17:00:00Z");
    private static final String PASSWORD = "Gondolia2026!";
    /** 80 bytes en UTF-8: por encima del límite de BCrypt (72). */
    private static final String DEMASIADO_LARGA = "á".repeat(40);

    @Mock
    private UserRepository userRepository;
    @Mock
    private TenantRepository tenantRepository;
    @Mock
    private TenantSettingsRepository tenantSettingsRepository;
    @Mock
    private BranchAccessService branchAccessService;
    @Mock
    private ModuleService moduleService;
    @Mock
    private JwtService jwtService;
    @Mock
    private UserAccessValidator userAccessValidator;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private AuthService service;
    private User user;

    @BeforeEach
    void setUp() {
        service = new AuthService(userRepository, tenantRepository, tenantSettingsRepository, branchAccessService,
                moduleService, passwordEncoder, jwtService, userAccessValidator, clock);
        user = platformOwner();
        when(jwtService.issueToken(any(User.class)))
                .thenReturn(new IssuedToken("jwt-nuevo", NOW.plusSeconds(43_200)));
    }

    private User platformOwner() {
        User owner = new User();
        owner.setId(1L);
        owner.setEmail("dueno@gondolia.app");
        owner.setFullName("Dueño");
        owner.setRole(Role.PLATFORM_OWNER);
        owner.setPasswordHash(passwordEncoder.encode(PASSWORD));
        return owner;
    }

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        void devuelveTokenYUsuarioConLasCredencialesCorrectas() {
            when(userRepository.findByEmail("dueno@gondolia.app")).thenReturn(Optional.of(user));

            LoginResponse response = service.login(new LoginRequest("dueno@gondolia.app", PASSWORD));

            assertThat(response.token()).isEqualTo("jwt-nuevo");
            assertThat(response.expiresAt()).isEqualTo(NOW.plusSeconds(43_200));
            assertThat(response.user().email()).isEqualTo("dueno@gondolia.app");
            assertThat(response.user().role()).isEqualTo(Role.PLATFORM_OWNER);
            assertThat(user.getLastLoginAt()).isEqualTo(NOW);
            verify(userAccessValidator).checkAccess(user);
        }

        @Test
        void normalizaElEmailAntesDeBuscarlo() {
            when(userRepository.findByEmail("dueno@gondolia.app")).thenReturn(Optional.of(user));

            assertThat(service.login(new LoginRequest("  DUENO@Gondolia.App  ", PASSWORD)).token())
                    .isEqualTo("jwt-nuevo");
        }

        @Test
        void emailInexistenteEsBadCredentials() {
            when(userRepository.findByEmail("nadie@gondolia.app")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.login(new LoginRequest("nadie@gondolia.app", PASSWORD)))
                    .isInstanceOf(ApiException.class)
                    .satisfies(error -> {
                        ApiException api = (ApiException) error;
                        assertThat(api.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(api.getCode()).isEqualTo(ErrorCodes.BAD_CREDENTIALS);
                    });
            verifyNoInteractions(jwtService);
        }

        /** El mensaje no distingue email de contraseña: no se filtra qué cuentas existen. */
        @Test
        void contrasenaIncorrectaDaElMismoErrorQueUnEmailInexistente() {
            when(userRepository.findByEmail("dueno@gondolia.app")).thenReturn(Optional.of(user));
            when(userRepository.findByEmail("nadie@gondolia.app")).thenReturn(Optional.empty());

            String conPasswordMala = messageOf(() -> service.login(new LoginRequest("dueno@gondolia.app", "otra")));
            String conEmailInexistente =
                    messageOf(() -> service.login(new LoginRequest("nadie@gondolia.app", PASSWORD)));

            assertThat(conPasswordMala).isEqualTo(conEmailInexistente).isEqualTo(AuthService.MSG_BAD_CREDENTIALS);
            assertThat(user.getLastLoginAt()).isNull();
        }

        /** Más de 72 bytes: BCrypt los truncaría, así que se corta antes de comparar. */
        @Test
        void contrasenaMasLargaQueElLimiteDeBcryptSeRechaza() {
            when(userRepository.findByEmail("dueno@gondolia.app")).thenReturn(Optional.of(user));

            assertThat(DEMASIADO_LARGA.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
            assertThatThrownBy(() -> service.login(new LoginRequest("dueno@gondolia.app", DEMASIADO_LARGA)))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(AuthService.MSG_BAD_CREDENTIALS);
        }

        @Test
        void emailVacioNiSiquieraConsultaElRepositorio() {
            assertThatThrownBy(() -> service.login(new LoginRequest("   ", PASSWORD)))
                    .isInstanceOf(ApiException.class);
            verify(userRepository, never()).findByEmail(any());
        }

        /** Un usuario o un comercio deshabilitado lo corta el validador, después de verificar la contraseña. */
        @Test
        void unUsuarioSinAccesoNoRecibeToken() {
            when(userRepository.findByEmail("dueno@gondolia.app")).thenReturn(Optional.of(user));
            ApiException disabled = new ApiException(HttpStatus.UNAUTHORIZED, "USER_DISABLED",
                    UserAccessValidator.MSG_USER_DISABLED);
            when(userAccessValidator.checkAccess(user)).thenThrow(disabled);

            assertThatThrownBy(() -> service.login(new LoginRequest("dueno@gondolia.app", PASSWORD)))
                    .isSameAs(disabled);
            verifyNoInteractions(jwtService);
        }
    }

    @Nested
    @DisplayName("me")
    class Me {

        @Test
        void devuelveElUsuarioDeLaSesion() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));

            MeDto me = service.me(1L);

            assertThat(me.id()).isEqualTo(1L);
            assertThat(me.tenant()).isNull();
            assertThat(me.branches()).isEmpty();
        }

        @Test
        void siElUsuarioYaNoExisteLaSesionNoVale() {
            when(userRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.me(99L))
                    .isInstanceOf(ApiException.class)
                    .hasMessage(UserAccessValidator.MSG_SESSION_INVALID);
        }

        @Test
        void unUsuarioDeComercioSinComercioCargadoNoRompe() {
            User employee = new User();
            employee.setId(20L);
            employee.setEmail("empleado@prueba.com");
            employee.setFullName("Empleado");
            employee.setRole(Role.TENANT_EMPLOYEE);
            employee.setTenantId(5L);
            employee.setPasswordHash(passwordEncoder.encode(PASSWORD));
            when(userRepository.findById(20L)).thenReturn(Optional.of(employee));
            when(tenantRepository.findById(5L)).thenReturn(Optional.empty());
            when(branchAccessService.accessibleBranches(any())).thenReturn(List.of());

            MeDto me = service.me(20L);

            assertThat(me.tenant()).isNull();
            assertThat(me.role()).isEqualTo(Role.TENANT_EMPLOYEE);
        }
    }

    @Nested
    @DisplayName("changePassword")
    class ChangePassword {

        @BeforeEach
        void findUser() {
            when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        }

        @Test
        void cambiaLaContrasenaEInvalidaLasSesionesAbiertas() {
            String hashAnterior = user.getPasswordHash();
            user.setMustChangePassword(true);

            TokenResponse response = service.changePassword(1L, new ChangePasswordRequest(PASSWORD, "NuevaClave2026!"));

            assertThat(response.token()).isEqualTo("jwt-nuevo");
            assertThat(user.getPasswordHash()).isNotEqualTo(hashAnterior);
            assertThat(passwordEncoder.matches("NuevaClave2026!", user.getPasswordHash())).isTrue();
            assertThat(user.isMustChangePassword()).isFalse();
            assertThat(user.getTokenVersion()).isEqualTo(1);
            verify(userRepository).saveAndFlush(user);
        }

        @Test
        void conLaContrasenaActualEquivocadaNoCambiaNada() {
            assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest("otra", "NuevaClave2026!")))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(e -> assertThat(((BadRequestException) e).getCode())
                            .isEqualTo(AuthService.INVALID_CURRENT_PASSWORD));
            assertThat(user.getTokenVersion()).isZero();
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        void laContrasenaNuevaTieneQueSerDistinta() {
            assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest(PASSWORD, PASSWORD)))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(e -> assertThat(((BadRequestException) e).getCode())
                            .isEqualTo(AuthService.SAME_PASSWORD));
            verify(userRepository, never()).saveAndFlush(any());
        }

        @Test
        void laContrasenaNuevaNoPuedeSuperarElLimiteDeBcrypt() {
            assertThatThrownBy(() -> service.changePassword(1L, new ChangePasswordRequest(PASSWORD, DEMASIADO_LARGA)))
                    .isInstanceOf(BadRequestException.class)
                    .satisfies(e -> assertThat(((BadRequestException) e).getCode())
                            .isEqualTo(ErrorCodes.VALIDATION_ERROR));
        }
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
            throw new AssertionError("se esperaba un error de credenciales");
        } catch (ApiException e) {
            return e.getMessage();
        }
    }
}
