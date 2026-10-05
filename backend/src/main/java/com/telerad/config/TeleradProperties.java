package com.telerad.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "telerad")
public class TeleradProperties {
    private Jwt jwt = new Jwt();
    private S3 s3 = new S3();
    private Scp scp = new Scp();
    private Rabbit rabbit = new Rabbit();
    private Notify notify = new Notify();
    private Sla sla = new Sla();

    @Data
    public static class Jwt {
        private String secret;
        private long accessTokenMinutes = 480;
    }

    @Data
    public static class S3 {
        private String endpoint;
        private String accessKey;
        private String secretKey;
        private String bucket;
        private String region = "us-east-1";
    }

    @Data
    public static class Scp {
        private boolean enabled;
        private String aeTitle = "TELERAD_SCP";
        private int port = 11112;
        private String bindAddress = "0.0.0.0";
    }

    @Data
    public static class Rabbit {
        private String ingestQueue = "study.ingest";
        private String exchange = "telerad.exchange";
    }

    @Data
    public static class Notify {
        private boolean emailEnabled = true;
        private boolean webhookEnabled = true;
        private String fallbackEmail = "ops@telerad.local";
        /** Optional platform-wide webhook when tenant has none. */
        private String defaultWebhookUrl = "";
        /** Shared secret for HMAC-SHA256 signing of outbound webhooks (EHR integration). */
        private String ehrWebhookSecret = "";
    }

    @Data
    public static class Sla {
        private boolean enabled = true;
        private int statMinutes = 60;
        private int urgentMinutes = 240;
        private int routineMinutes = 1440;
        private long checkIntervalMs = 60000;
    }
}
