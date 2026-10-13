package com.resumeai.stability.service;

import com.resumeai.stability.config.AiProperties;
import org.junit.jupiter.api.Test;



import static org.junit.jupiter.api.Assertions.*;

/**
 * 研究 2026-10-14 引入的两个可配旋钮的契约：
 * 1) ai.breaker.* 覆盖能真实落到 CircuitBreakerConfig（离线扫描的前提）；
 * 2) ai.llm.max-tokens>0 时请求体带 max_tokens，=0 时保持原样（不破坏既有行为）。
 */
class ResearchKnobsTest {

    @Test
    void 熔断参数覆盖生效() {
        AiProperties props = new AiProperties();
        AiProperties.Breaker bp = props.getBreaker();
        bp.setFailureRateThreshold(80f);
        bp.setSlidingWindowSize(20);
        bp.setMinimumNumberOfCalls(10);
        bp.setWaitDurationInOpenStateMs(30000);
        bp.setPermittedNumberOfCallsInHalfOpenState(5);

        FixedOptimizeService svc = new FixedOptimizeService(null, null, props);
        svc.initBreaker();
        var cfg = svc.getCircuitBreaker().getCircuitBreakerConfig();
        assertEquals(80f, cfg.getFailureRateThreshold());
        assertEquals(20, cfg.getSlidingWindowSize());
        assertEquals(10, cfg.getMinimumNumberOfCalls());
        assertEquals(30000L, cfg.getWaitIntervalFunctionInOpenState().apply(1));
        assertEquals(5, cfg.getPermittedNumberOfCallsInHalfOpenState());
    }

    @Test
    void 默认熔断参数与历史硬编码一致() {
        FixedOptimizeService svc = new FixedOptimizeService(null, null, new AiProperties());
        svc.initBreaker();
        var cfg = svc.getCircuitBreaker().getCircuitBreakerConfig();
        assertEquals(50f, cfg.getFailureRateThreshold());
        assertEquals(8, cfg.getSlidingWindowSize());
        assertEquals(4, cfg.getMinimumNumberOfCalls());
        assertEquals(2000L, cfg.getWaitIntervalFunctionInOpenState().apply(1));
        assertEquals(2, cfg.getPermittedNumberOfCallsInHalfOpenState());
    }

    @Test
    void maxTokens为零不带字段_为正带字段() {
        AiProperties props = new AiProperties();
        String body0 = LlmHttpRequests.chatCompletion(props, "r-1").getBody();
        assertFalse(body0.contains("max_tokens"), body0);

        props.getLlm().setMaxTokens(128);
        String body128 = LlmHttpRequests.chatCompletion(props, "r-1").getBody();
        assertTrue(body128.contains("\"max_tokens\":128"), body128);
    }
}
