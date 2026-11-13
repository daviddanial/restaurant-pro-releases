package com.telerad.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayServerCertTest {
    @TempDir Path dir;

    private GatewayProperties props(List<String> hostnames) {
        GatewayProperties p = new GatewayProperties();
        p.setCaDir(dir.toString());
        p.setServerKeystore(dir.resolve("server.p12").toString());
        p.setServerHostnames(hostnames);
        return p;
    }

    private Set<String> sans() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(dir.resolve("server.p12"))) {
            ks.load(in, "telerad".toCharArray());
        }
        Set<String> out = new HashSet<>();
        for (List<?> san : ((X509Certificate) ks.getCertificate("server")).getSubjectAlternativeNames()) {
            out.add(String.valueOf(san.get(1)));
        }
        return out;
    }

    @Test
    void serverCertificateNamesThePublicHostOfTheMtlsPort() throws Exception {
        new GatewayCaService(props(List.of())).init();
        assertThat(sans()).containsExactlyInAnyOrder("localhost", "telerad-gateway-api", "127.0.0.1");
        long first = Files.getLastModifiedTime(dir.resolve("server.p12")).toMillis();

        // Deployed: hospital servers connect by the public name/IP, so the certificate is re-issued.
        new GatewayCaService(props(List.of("Telerad.Example.ET", "196.189.236.143"))).init();
        assertThat(sans()).contains("telerad.example.et", "196.189.236.143", "localhost");

        // Same names again: kept as is.
        Thread.sleep(20);
        long second = Files.getLastModifiedTime(dir.resolve("server.p12")).toMillis();
        new GatewayCaService(props(List.of("telerad.example.et", "196.189.236.143"))).init();
        assertThat(Files.getLastModifiedTime(dir.resolve("server.p12")).toMillis()).isEqualTo(second);
        assertThat(second).isGreaterThanOrEqualTo(first);
    }
}
