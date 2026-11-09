package com.telerad.config;

import com.telerad.auth.JwtAuthFilter;
import com.telerad.gateway.GatewayAuthFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {
    private final JwtAuthFilter jwtAuthFilter;
    private final GatewayAuthFilter gatewayAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/gateway/enroll").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/public/shares/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/public/shares/*/unlock").permitAll()
                        .requestMatchers("/api/public/shares/me/**").hasRole("SHARE")
                        .requestMatchers("/api/gateway/**").hasRole("GATEWAY")
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(gatewayAuthFilter, JwtAuthFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${telerad.cors.origins:}") String extraOrigins) {
        return corsConfigurationSource(allowedOriginPatterns(extraOrigins));
    }

    /**
     * Local dev origins plus the deployed site(s) from {@code telerad.cors.origins}
     * (env {@code TELERAD_CORS_ORIGINS}, comma-separated, e.g. {@code https://telerad.example.org}).
     * Behind a TLS proxy the browser's Origin (https) never matches what the API sees, so the
     * public site must be listed or every login is refused with "Invalid CORS request".
     */
    static List<String> allowedOriginPatterns(String extraOrigins) {
        List<String> origins = new ArrayList<>(List.of("http://localhost:*", "http://127.0.0.1:*"));
        if (extraOrigins != null) {
            for (String origin : extraOrigins.split(",")) {
                String trimmed = origin.trim();
                if (!trimmed.isEmpty() && !origins.contains(trimmed)) origins.add(trimmed);
            }
        }
        return origins;
    }

    static CorsConfigurationSource corsConfigurationSource(List<String> originPatterns) {
        CorsConfiguration config = new CorsConfiguration();
        // Patterns without credentials — allowCredentials(true)+wildcard is invalid CORS
        config.setAllowedOriginPatterns(originPatterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
