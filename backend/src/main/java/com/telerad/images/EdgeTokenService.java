package com.telerad.images;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.telerad.gateway.GatewayProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Short-lived image passes for the hospital server (KB note 13 §4). The cloud decides who may
 * open which study and signs a pass (JWT, RS256); the hospital server checks it with the public
 * key and never needs a list of users. The key pair lives next to the gateway CA.
 */
@Service
@Slf4j
public class EdgeTokenService {
    static final String ISSUER = "telerad";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder B64URL = Base64.getUrlEncoder().withoutPadding();

    private final GatewayProperties gatewayProps;
    private final Duration ttl;
    private PrivateKey privateKey;
    private String publicKeyPem;

    public EdgeTokenService(GatewayProperties gatewayProps,
                            @Value("${telerad.images.edge-token-minutes:60}") long ttlMinutes) {
        this.gatewayProps = gatewayProps;
        this.ttl = Duration.ofMinutes(Math.max(1, ttlMinutes));
    }

    public record Pass(String token, Instant expiresAt) {}

    @PostConstruct
    void init() {
        try {
            Path dir = Path.of(gatewayProps.getCaDir()).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path keyPath = dir.resolve("edge-token.key");
            Path pubPath = dir.resolve("edge-token.pub");
            if (!Files.exists(keyPath) || !Files.exists(pubPath)) {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair kp = gen.generateKeyPair();
                Files.writeString(keyPath, pem("PRIVATE KEY", kp.getPrivate().getEncoded()), StandardCharsets.UTF_8);
                try {
                    Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
                } catch (UnsupportedOperationException ignored) {
                    // non-POSIX file system
                }
                Files.writeString(pubPath, pem("PUBLIC KEY", kp.getPublic().getEncoded()), StandardCharsets.UTF_8);
                log.info("Generated image-pass signing key under {}", dir);
            }
            KeyFactory kf = KeyFactory.getInstance("RSA");
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(der(Files.readString(keyPath))));
            publicKeyPem = Files.readString(pubPath);
            kf.generatePublic(new X509EncodedKeySpec(der(publicKeyPem))); // fail fast on a bad file
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialise image-pass key: " + e.getMessage(), e);
        }
    }

    /** Public key the hospital server verifies passes with (PEM, SubjectPublicKeyInfo). */
    public String publicKeyPem() {
        return publicKeyPem;
    }

    /** A pass for one user to read the given studies from one hospital server. */
    public Pass issue(UUID gatewayId, UUID userId, UUID tenantId, List<String> studyUids) {
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", gatewayId.toString());
        claims.put("sub", userId.toString());
        claims.put("tid", tenantId.toString());
        claims.put("studies", studyUids);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", exp.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        return new Pass(sign(claims), exp);
    }

    String sign(Map<String, Object> claims) {
        try {
            String header = B64URL.encodeToString(
                    MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
            String payload = B64URL.encodeToString(MAPPER.writeValueAsBytes(claims));
            String input = header + "." + payload;
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(privateKey);
            sig.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + B64URL.encodeToString(sig.sign());
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign image pass", e);
        }
    }

    private static String pem(String type, byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + b64 + "\n-----END " + type + "-----\n";
    }

    private static byte[] der(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
