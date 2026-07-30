package com.telerad;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableAsync
@EnableScheduling
public class TeleradApplication {
    public static void main(String[] args) {
        // Prefer our classpath ImageReaderFactory (JDK jpeg) over dcm4che's jpeg-cv defaults.
        System.setProperty(
                "org.dcm4che3.imageio.codec.ImageReaderFactory",
                "org/dcm4che3/imageio/codec/ImageReaderFactory.properties");
        SpringApplication.run(TeleradApplication.class, args);
    }
}
