package com.telerad.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigCorsTest {
    private static CorsConfiguration config(String extra) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        return SecurityConfig.corsConfigurationSource(SecurityConfig.allowedOriginPatterns(extra))
                .getCorsConfiguration(req);
    }

    @Test
    void deployedSiteFromSettingIsAllowed() {
        CorsConfiguration c = config(" https://telerad.example.org , ");
        assertThat(c.checkOrigin("https://telerad.example.org")).isEqualTo("https://telerad.example.org");
        assertThat(c.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    }

    @Test
    void otherSitesAreRefused() {
        CorsConfiguration c = config("https://telerad.example.org");
        assertThat(c.checkOrigin("https://evil.example.org")).isNull();
        assertThat(config("").checkOrigin("https://telerad.example.org")).isNull();
    }
}
