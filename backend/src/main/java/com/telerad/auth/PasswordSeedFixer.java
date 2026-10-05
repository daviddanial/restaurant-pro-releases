package com.telerad.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * DISABLED for production deployment.
 *
 * This runner used to force every user's password back to the demo value
 * ("password") on each application start, which silently defeats any password
 * rotation performed in the database. Kept as a compile-safe no-op so existing
 * wiring and history stay intact; administer accounts via /api/admin/users or
 * a direct DB update instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PasswordSeedFixer implements ApplicationRunner {
    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        log.info("PasswordSeedFixer disabled — demo password reset skipped.");
    }
}
