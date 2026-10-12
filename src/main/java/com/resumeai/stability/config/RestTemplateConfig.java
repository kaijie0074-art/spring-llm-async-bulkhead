package com.resumeai.stability.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * HTTP 客户端配置（S0 层）。
 *
 * baseline 模式：不设置任何超时——SimpleClientHttpRequestFactory 默认 0 = 无限等待，
 * 这正是 v0.1.0 的缺陷（问题报告 RST-001 放大器 1：LLM 供应商抖动时线程被无限期钉死）。
 * fixed 模式：connect 3s / read 12s——最坏情况线程占用有上界（12s 而非 ∞）。
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate llmRestTemplate(RestTemplateBuilder builder, AiProperties props) {
        RestTemplateBuilder b = builder;
        if (props.getMode() == AiProperties.Mode.FIXED) {
            b = b.setConnectTimeout(Duration.ofMillis(props.getLlm().getConnectTimeoutMs()))
                 .setReadTimeout(Duration.ofMillis(props.getLlm().getReadTimeoutMs()));
        }
        return b.build();
    }
}
