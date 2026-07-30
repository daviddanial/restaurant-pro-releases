package com.telerad.auth;

import com.telerad.common.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures seed users authenticate with password "password" even if the Flyway hash differs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordSeedFixer implements ApplicationRunner {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String hash = passwordEncoder.encode("password");
        userRepository.findAll().forEach(u -> {
            if (!passwordEncoder.matches("password", u.getPasswordHash())) {
                u.setPasswordHash(hash);
                log.info("Reset demo password for {}", u.getEmail());
            }
        });
    }
}
