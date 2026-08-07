package com.telerad.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class LoginRequestTest {
    @Test
    void trimsEmailAndPassword() {
        LoginRequest req = new LoginRequest("  rad@telerad.local  ", "  password  ");
        assertThat(req.email()).isEqualTo("rad@telerad.local");
        assertThat(req.password()).isEqualTo("password");
    }

    @Test
    void allowsNullFieldsThroughConstructor() {
        LoginRequest req = new LoginRequest(null, null);
        assertThat(req.email()).isNull();
        assertThat(req.password()).isNull();
    }
}
