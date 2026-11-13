package com.telerad.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayLanUrlTest {
    @Test
    void acceptsOnlyAPlainOrigin() {
        assertThat(GatewayService.lanUrlFrom("{\"lanUrl\":\"https://h1.edge.example.et:8443\"}"))
                .isEqualTo("https://h1.edge.example.et:8443");
        assertThat(GatewayService.lanUrlFrom("{\"lanUrl\":\"https://h1.edge.example.et/\"}"))
                .isEqualTo("https://h1.edge.example.et");
        assertThat(GatewayService.lanUrlFrom("{\"lanUrl\":\"javascript:alert(1)\"}")).isNull();
        assertThat(GatewayService.lanUrlFrom("{\"lanUrl\":\"https://h1/x?y=1\"}")).isNull();
        assertThat(GatewayService.lanUrlFrom("{\"lanUrl\":\"https://h1/path\"}")).isNull();
        assertThat(GatewayService.lanUrlFrom("{\"lanUrl\":\"\"}")).isNull();
        assertThat(GatewayService.lanUrlFrom("{}")).isNull();
        assertThat(GatewayService.lanUrlFrom("not json")).isNull();
    }
}
