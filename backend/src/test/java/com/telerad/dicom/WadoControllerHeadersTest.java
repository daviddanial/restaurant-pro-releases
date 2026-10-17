package com.telerad.dicom;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WadoControllerHeadersTest {
    @Test
    void acceptsGzip() {
        assertThat(WadoController.acceptsGzip("gzip, deflate, br, zstd")).isTrue();
        assertThat(WadoController.acceptsGzip("br;q=1.0, gzip;q=0.5")).isTrue();
        assertThat(WadoController.acceptsGzip("*")).isTrue();
        assertThat(WadoController.acceptsGzip("gzip;q=0")).isFalse();
        assertThat(WadoController.acceptsGzip("identity")).isFalse();
        assertThat(WadoController.acceptsGzip(null)).isFalse();
    }

    @Test
    void etagMatches() {
        assertThat(WadoController.etagMatches("\"1.2.3.gz\"", "\"1.2.3.gz\"")).isTrue();
        assertThat(WadoController.etagMatches("W/\"1.2.3\", \"x\"", "\"1.2.3\"")).isTrue();
        assertThat(WadoController.etagMatches("*", "\"1.2.3\"")).isTrue();
        assertThat(WadoController.etagMatches("\"1.2.3\"", "\"1.2.3.gz\"")).isFalse();
        assertThat(WadoController.etagMatches(null, "\"1.2.3\"")).isFalse();
    }
}
