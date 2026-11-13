package com.telerad.images;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.telerad.gateway.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EdgeTokenServiceTest {
    @TempDir Path dir;

    private EdgeTokenService service() {
        GatewayProperties props = new GatewayProperties();
        props.setCaDir(dir.toString());
        EdgeTokenService s = new EdgeTokenService(props, 60);
        s.init();
        return s;
    }

    @Test
    void passIsSignedForOneGatewayUserAndStudy() throws Exception {
        EdgeTokenService s = service();
        UUID gw = UUID.randomUUID(), user = UUID.randomUUID(), tenant = UUID.randomUUID();
        var pass = s.issue(gw, user, tenant, List.of("1.2.3"));

        String[] parts = pass.token().split("\\.");
        assertThat(parts).hasSize(3);
        Base64.Decoder dec = Base64.getUrlDecoder();
        JsonNode header = new ObjectMapper().readTree(dec.decode(parts[0]));
        JsonNode claims = new ObjectMapper().readTree(dec.decode(parts[1]));
        assertThat(header.get("alg").asText()).isEqualTo("RS256");
        assertThat(claims.get("aud").asText()).isEqualTo(gw.toString());
        assertThat(claims.get("sub").asText()).isEqualTo(user.toString());
        assertThat(claims.get("studies").get(0).asText()).isEqualTo("1.2.3");
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);

        String pem = s.publicKeyPem().replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
        Signature v = Signature.getInstance("SHA256withRSA");
        v.initVerify(pub);
        v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(v.verify(dec.decode(parts[2]))).isTrue();
    }

    @Test
    void keyIsKeptAcrossRestarts() {
        String first = service().publicKeyPem();
        String second = service().publicKeyPem();
        assertThat(second).isEqualTo(first);
        assertThat(Files.exists(dir.resolve("edge-token.key"))).isTrue();
    }
}
