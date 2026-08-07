package com.telerad.auth;

import com.telerad.common.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class UserPrincipalTest {
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authorities_prefixRole() {
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "a@b.co", "A", Role.RADIOLOGIST, null, null);
        assertThat(p.authorities()).extracting(a -> a.getAuthority())
                .containsExactly("ROLE_RADIOLOGIST");
    }

    @Test
    void current_returnsPrincipalFromSecurityContext() {
        UserPrincipal p = new UserPrincipal(UUID.randomUUID(), "a@b.co", "A", Role.ADMIN, null, null);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(p, null, p.authorities()));
        assertThat(UserPrincipal.current()).isSameAs(p);
    }

    @Test
    void current_throwsWhenUnauthenticated() {
        assertThatThrownBy(UserPrincipal::current)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No authenticated user");
    }
}
