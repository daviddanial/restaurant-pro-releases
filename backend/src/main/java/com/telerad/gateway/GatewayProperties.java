package com.telerad.gateway;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "telerad.gateway")
public class GatewayProperties {
    /** Allow Bearer access-token auth (local/dev only). Pilot requires mTLS. */
    private boolean bearerDev = true;

    private String caDir = "certs/gateway-ca";
    private String caCertFile = "ca.crt";
    private String caKeyFile = "ca.key";
    private int certValidityDays = 90;
    private int renewAfterDays = 60;
    private int enrollmentTokenHours = 24;

    private int heartbeatIntervalSeconds = 300;
    private int heartbeatMissSeconds = 900;

    /** Default ingest-audit retention days (minimum 365). */
    private int ingestAuditRetentionDays = 365;

    private int mtlsPort = 8443;
    private boolean mtlsEnabled = true;
    private String serverKeystore = "certs/gateway-ca/server.p12";
    private String serverKeystorePassword = "telerad";
    /**
     * Public host names / IPs hospital servers use to reach the mTLS port (env
     * TELERAD_GATEWAY_SERVER_HOSTNAMES, comma-separated). They go into the server certificate's
     * subject alternative names; the certificate is re-issued when one is missing.
     */
    private java.util.List<String> serverHostnames = new java.util.ArrayList<>();
}
