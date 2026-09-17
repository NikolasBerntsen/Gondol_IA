package com.gondolia.bootstrap;

import com.gondolia.common.util.Emails;
import com.gondolia.config.AppProperties;
import com.gondolia.domain.user.Role;
import com.gondolia.domain.user.User;
import com.gondolia.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea el dueño inicial de la plataforma ({@code APP_BOOTSTRAP_OWNER_EMAIL} / {@code APP_BOOTSTRAP_OWNER_PASSWORD})
 * si todavía no existe ningún {@code PLATFORM_OWNER}.
 */
@Slf4j
@Order(10)
@Component
@RequiredArgsConstructor
public class BootstrapRunner implements ApplicationRunner {

    static final String OWNER_FULL_NAME = "Dueño GondolIA";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.PLATFORM_OWNER)) {
            return;
        }
        String email = Emails.normalize(properties.bootstrap().ownerEmail());
        String password = properties.bootstrap().ownerPassword();
        if (email == null || password == null || password.isBlank()) {
            log.warn("No hay dueño de plataforma y faltan APP_BOOTSTRAP_OWNER_EMAIL/APP_BOOTSTRAP_OWNER_PASSWORD");
            return;
        }
        if (userRepository.existsByEmail(email)) {
            log.warn("No se creó el dueño inicial: el email {} ya pertenece a otro usuario", email);
            return;
        }

        User owner = new User();
        owner.setEmail(email);
        owner.setFullName(OWNER_FULL_NAME);
        owner.setRole(Role.PLATFORM_OWNER);
        owner.setTenantId(null);
        owner.setPasswordHash(passwordEncoder.encode(password));
        owner.setActive(true);
        userRepository.save(owner);
        log.info("Dueño inicial de GondolIA creado: {}", email);
    }
}
