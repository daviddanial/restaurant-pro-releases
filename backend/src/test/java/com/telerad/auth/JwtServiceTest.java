package com.telerad.auth;

import com.telerad.common.Role;
import com.telerad.config.TeleradProperties;
import com.telerad.tenant.Tenant;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class JwtServiceTest {
    private JwtService jwtService;
    private TeleradProperties properties;

    @BeforeEach
    void setUp() {
        properties = new TeleradProperties();
        properties.getJwt().setSecret("unit-test-secret-key-at-least-32-bytes!!");
        properties.getJwt().setAccessTokenMinutes(60);
        jwtService = new JwtService(properties);
    }

    @Test
    void createAndParseToken_withTenantClaims() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder().id(tenantId).code("DEMO").name("Demo Hospital").build();
        UserAccount user = UserAccount.builder()
                .id(userId)
                .email("rad@telerad.local")
                .fullName("Dr Rad")
                .role(Role.RADIOLOGIST)
                .passwordHash("hash")
                .tenant(tenant)
                .active(true)
                .build();

        String token = jwtService.createToken(user);
        assertThat(token).isNotBlank();

        Claims claims = jwtService.parse(token);
        assertThat(jwtService.userId(claims)).isEqualTo(userId);
        assertThat(jwtService.role(claims)).isEqualTo(Role.RADIOLOGIST);
        assertThat(jwtService.tenantId(claims)).isEqualTo(tenantId);
        assertThat(claims.get("email", String.class)).isEqualTo("rad@telerad.local");
        assertThat(claims.get("name", String.class)).isEqualTo("Dr Rad");
        assertThat(claims.get("tenantCode", String.class)).isEqualTo("DEMO");
    }

    @Test
    void createToken_withoutTenant_omitsTenantClaims() {
        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .email("admin@telerad.local")
                .fullName("Admin")
                .role(Role.ADMIN)
                .passwordHash("hash")
                .active(true)
                .build();

        Claims claims = jwtService.parse(jwtService.createToken(user));
        assertThat(jwtService.tenantId(claims)).isNull();
        assertThat(jwtService.role(claims)).isEqualTo(Role.ADMIN);
    }

    @Test
    void padsShortSecret() {
        TeleradProperties shortSecret = new TeleradProperties();
        shortSecret.getJwt().setSecret("short");
        shortSecret.getJwt().setAccessTokenMinutes(30);
        JwtService svc = new JwtService(shortSecret);

        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .email("a@b.co")
                .fullName("A")
                .role(Role.ADMIN)
                .passwordHash("h")
                .active(true)
                .build();
        Claims claims = svc.parse(svc.createToken(user));
        assertThat(claims.get("email", String.class)).isEqualTo("a@b.co");
    }

    @Test
    void rejectsTamperedToken() {
        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .email("a@b.co")
                .fullName("A")
                .role(Role.ADMIN)
                .passwordHash("h")
                .active(true)
                .build();
        String token = jwtService.createToken(user);
        String tampered = token.substring(0, token.length() - 4) + "xxxx";
        assertThatThrownBy(() -> jwtService.parse(tampered))
                .isInstanceOfAny(SignatureException.class, io.jsonwebtoken.JwtException.class);
    }

    @Test
    void rejectsExpiredToken() {
        properties.getJwt().setAccessTokenMinutes(0);
        // zero minutes → expires immediately; sleep briefly to ensure clock advances
        JwtService expiredSvc = new JwtService(properties);
        UserAccount user = UserAccount.builder()
                .id(UUID.randomUUID())
                .email("a@b.co")
                .fullName("A")
                .role(Role.ADMIN)
                .passwordHash("h")
                .active(true)
                .build();
        String token = expiredSvc.createToken(user);
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThatThrownBy(() -> expiredSvc.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
