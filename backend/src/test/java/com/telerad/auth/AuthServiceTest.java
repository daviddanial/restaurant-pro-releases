package com.telerad.auth;

import com.telerad.audit.AuditService;
import com.telerad.common.Role;
import com.telerad.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import java.util.Optional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    @Mock UserAccountRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock AuditService auditService;
    @InjectMocks AuthService authService;

    private Tenant tenant;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        tenant = Tenant.builder()
                .id(UUID.randomUUID())
                .code("DEMO")
                .name("Demo Hospital")
                .build();
        user = UserAccount.builder()
                .id(UUID.randomUUID())
                .email("staff@demohosp.local")
                .fullName("Staff")
                .role(Role.HOSPITAL_USER)
                .passwordHash("$2a$hash")
                .tenant(tenant)
                .active(true)
                .build();
    }

    @Test
    void login_success_returnsTokenAndAudits() {
        when(userRepository.findByEmailIgnoreCase("staff@demohosp.local")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", user.getPasswordHash())).thenReturn(true);
        when(jwtService.createToken(user)).thenReturn("jwt-token");

        AuthResponse response = authService.login(new LoginRequest("staff@demohosp.local", "password"), "127.0.0.1");

        assertThat(response.accessToken()).isEqualTo("jwt-token");
        assertThat(response.email()).isEqualTo("staff@demohosp.local");
        assertThat(response.role()).isEqualTo(Role.HOSPITAL_USER);
        assertThat(response.tenantId()).isEqualTo(tenant.getId());
        assertThat(response.tenantCode()).isEqualTo("DEMO");
        assertThat(response.tenantName()).isEqualTo("Demo Hospital");

        verify(auditService).record(eq(tenant.getId()), eq(user.getId()), eq("LOGIN"),
                eq("User"), eq(user.getId().toString()), eq(user.getEmail()), eq("127.0.0.1"));
    }

    @Test
    void login_unknownEmail_throwsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("x@y.z", "pw"), "1.1.1.1"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void login_wrongPassword_throwsUnauthorized() {
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "wrong"), null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getReason()).isEqualTo("Invalid credentials"));
        verify(jwtService, never()).createToken(any());
    }

    @Test
    void login_inactiveUser_throwsUnauthorized() {
        user.setActive(false);
        when(userRepository.findByEmailIgnoreCase(user.getEmail())).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.login(new LoginRequest(user.getEmail(), "password"), null))
                .isInstanceOf(ResponseStatusException.class);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void login_adminWithoutTenant_nullTenantFields() {
        UserAccount admin = UserAccount.builder()
                .id(UUID.randomUUID())
                .email("admin@telerad.local")
                .fullName("Admin")
                .role(Role.ADMIN)
                .passwordHash("hash")
                .active(true)
                .build();
        when(userRepository.findByEmailIgnoreCase(admin.getEmail())).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        when(jwtService.createToken(admin)).thenReturn("tok");

        AuthResponse response = authService.login(new LoginRequest(admin.getEmail(), "password"), "ip");
        assertThat(response.tenantId()).isNull();
        assertThat(response.tenantCode()).isNull();
        assertThat(response.tenantName()).isNull();

        ArgumentCaptor<UUID> tenantCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(auditService).record(tenantCaptor.capture(), eq(admin.getId()), eq("LOGIN"),
                any(), any(), any(), any());
        assertThat(tenantCaptor.getValue()).isNull();
    }
}
