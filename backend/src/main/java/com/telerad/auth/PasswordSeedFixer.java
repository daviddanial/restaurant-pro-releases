package com.telerad.auth;

import com.telerad.common.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local demo only: ensures seed users authenticate with password "password" even if the Flyway hash
 * differs. It resets EVERY account whose password is not "password", so it must never run on a real
 * deployment; it is off unless {@code telerad.demo.reset-passwords=true}.
 */
@Component
@ConditionalOnProperty(name = "telerad.demo.reset-passwords", havingValue = "true")
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
