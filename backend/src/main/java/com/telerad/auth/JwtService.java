package com.telerad.auth;

import com.telerad.common.Role;
import com.telerad.config.TeleradProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {
    private final SecretKey key;
    private final long accessTokenMinutes;

    public JwtService(TeleradProperties properties) {
        byte[] secret = properties.getJwt().getSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(secret, 0, padded, 0, secret.length);
            secret = padded;
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTokenMinutes = properties.getJwt().getAccessTokenMinutes();
    }

    public String createToken(UserAccount user) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(accessTokenMinutes * 60);
        var builder = Jwts.builder()
                .subject(user.getId().toString())
                .claim("typ", "user")
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .claim("name", user.getFullName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp));
        if (user.getTenant() != null) {
            builder.claim("tenantId", user.getTenant().getId().toString());
            builder.claim("tenantCode", user.getTenant().getCode());
        }
        return builder.signWith(key).compact();
    }

    /** Share session JWT — expires at share.expiresAt (decision 7B). */
    public String createShareToken(
            UUID shareId,
            UUID tenantId,
            UUID patientId,
            int codeVersion,
            boolean includeReports,
            boolean allowDicomDownload,
            Instant expiresAt
    ) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(shareId.toString())
                .claim("typ", "share")
                .claim("tenantId", tenantId.toString())
                .claim("patientId", patientId.toString())
                .claim("codeVersion", codeVersion)
                .claim("includeReports", includeReports)
                .claim("allowDicomDownload", allowDicomDownload)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isShareToken(Claims claims) {
        return "share".equals(claims.get("typ", String.class));
    }

    public UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    public Role role(Claims claims) {
        return Role.valueOf(claims.get("role", String.class));
    }

    public UUID tenantId(Claims claims) {
        String tid = claims.get("tenantId", String.class);
        return tid == null ? null : UUID.fromString(tid);
    }
}
