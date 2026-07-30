package com.telerad.dicom;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.imageio.codec.ImageReaderFactory;
import org.dcm4che3.imageio.plugins.rle.RLEImageReaderSpi;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.spi.IIORegistry;
import java.io.InputStream;

/**
 * Configures dcm4che ImageIO codecs for TeleRad without OpenCV natives:
 * <ul>
 *   <li>JPEG Baseline/Extended → JDK ImageIO ({@code jpeg})</li>
 *   <li>RLE Lossless → {@code dcm4che-imageio-rle} (explicit SPI registration)</li>
 * </ul>
 */
@Component
@Slf4j
public class DicomImageIoConfigurer {

    @PostConstruct
    void configure() {
        registerRleReader();
        configureReaderFactory();
    }

    private void registerRleReader() {
        try {
            IIORegistry registry = IIORegistry.getDefaultInstance();
            registry.registerServiceProvider(new RLEImageReaderSpi());
            boolean available = ImageIO.getImageReadersByFormatName("rle").hasNext();
            log.info("Registered dcm4che RLE ImageReader (available={})", available);
        } catch (Exception e) {
            log.error("Failed to register RLE ImageReader: {}", e.getMessage(), e);
        }
    }

    private void configureReaderFactory() {
        try {
            ImageReaderFactory factory = new ImageReaderFactory();
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream("org/dcm4che3/imageio/codec/ImageReaderFactory.properties")) {
                if (in == null) {
                    throw new IllegalStateException("Missing ImageReaderFactory.properties on classpath");
                }
                factory.load(in);
            }
            ImageReaderFactory.setDefault(factory);
            log.info("Configured dcm4che ImageReaderFactory (JDK JPEG + RLE)");
        } catch (Exception e) {
            log.error("Failed to configure DICOM ImageIO readers: {}", e.getMessage(), e);
        }
    }
}
