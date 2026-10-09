package com.telerad.gateway;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Adds an HTTPS connector with client-auth=want for gateway mTLS (default :8443).
 * Main API remains on HTTP :8080 for portal/UI; edge agent uses the mTLS port.
 */
@Configuration
@RequiredArgsConstructor
@Slf4j
public class GatewayMtlsConnectorConfig {
    private final GatewayProperties props;
    private final GatewayCaService gatewayCaService; // ensure CA + keystores exist first

    @Bean
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> gatewayMtlsConnector() {
        return factory -> {
            if (!props.isMtlsEnabled()) {
                log.info("Gateway mTLS connector disabled");
                return;
            }
            // Touch CA service so @PostConstruct has run
            gatewayCaService.caCertificate();
            Path ks = Path.of(props.getServerKeystore()).toAbsolutePath().normalize();
            Path trust = Path.of(props.getCaDir()).toAbsolutePath().normalize().resolve("truststore.p12");
            if (!Files.exists(ks) || !Files.exists(trust)) {
                log.warn("Gateway mTLS keystore/truststore missing ({} / {}); connector not added", ks, trust);
                return;
            }
            factory.addAdditionalTomcatConnectors(httpsConnector(ks, trust));
            log.info("Gateway mTLS connector on port {}", props.getMtlsPort());
        };
    }

    private Connector httpsConnector(Path keystore, Path truststore) {
        Connector connector = new Connector(Http11NioProtocol.class.getName());
        connector.setScheme("https");
        connector.setSecure(true);
        connector.setPort(props.getMtlsPort());

        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setHostName("_default_");
        sslHostConfig.setCertificateVerification("optional");
        sslHostConfig.setTruststoreFile(truststore.toString());
        sslHostConfig.setTruststorePassword(props.getServerKeystorePassword());
        sslHostConfig.setTruststoreType("PKCS12");

        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(sslHostConfig, SSLHostConfigCertificate.Type.RSA);
        cert.setCertificateKeystoreFile(keystore.toString());
        cert.setCertificateKeystorePassword(props.getServerKeystorePassword());
        cert.setCertificateKeystoreType("PKCS12");
        sslHostConfig.addCertificate(cert);

        protocol.addSslHostConfig(sslHostConfig);
        return connector;
    }
}
