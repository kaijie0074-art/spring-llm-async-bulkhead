package com.resumeai.stability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.resumeai.stability.config.TestLlmConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S2 熔断：fake LLM 切 429 → 连续失败 → 熔断打开 → 请求拿到 AI_DEGRADED 且耗时骤降（不再真调）；
 * 恢复后熔断闭合。验证"供应商限流不再传导为全站慢"。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ai.mode=fixed",
        "test.llm.delay-ms=50",
        "ai.pool.core=2", "ai.pool.max=4", "ai.pool.queue-capacity=20",
})

@Import(TestLlmConfig.class)
class CircuitBreakerTest {

    @Autowired TestRestTemplate rest;
    @Autowired com.resumeai.stability.llm.FakeLlmServer llm;

    @Test
    void 限流风暴熔断与恢复() {
        // 1) 正常态可达
        var ok = rest.postForEntity("/api/resume/optimize?resumeId=cb-warm", null, String.class);
        assertTrue(ok.getStatusCode().is2xxSuccessful() || ok.getStatusCode().value() == 503);

        // 2) 制造 429 风暴
        rest.getForObject(llm.baseUrl() + "/__ctl/fail429", String.class);
        try {
            long slowestFail = 0;
            for (int i = 0; i < 8; i++) {
                long t0 = System.currentTimeMillis();
                var resp = rest.postForEntity("/api/resume/optimize?resumeId=cb-" + i, null, String.class);
                long cost = System.currentTimeMillis() - t0;
                slowestFail = Math.max(slowestFail, cost);
            }
            // 3) 连续失败后熔断打开：后续请求快速降级（不再等 LLM 真响应）
            long t0 = System.currentTimeMillis();
            var degraded = rest.postForEntity("/api/resume/optimize?resumeId=cb-open", null, String.class);
            long cost = System.currentTimeMillis() - t0;
            assertEquals(503, degraded.getStatusCode().value());
            assertTrue(degraded.getBody().contains("AI_DEGRADED") || degraded.getBody().contains("AI_BUSY"),
                    degraded.getBody());
            assertTrue(cost < 2000, "熔断打开后应快速降级，耗时 " + cost + "ms");
        } finally {
            // 4) 恢复
            rest.getForObject(llm.baseUrl() + "/__ctl/ok", String.class);
        }
        // 5) 熔断半开→闭合：等待 open 等待期(2s)后探测恢复
        try {
            Thread.sleep(3100);
            // 半开探测：第一发可能仍是降级（等待期/探测窗），第二发必须在恢复后转 CLOSED
            var probe = rest.postForEntity("/api/resume/optimize?resumeId=cb-probe", null, String.class);
            assertTrue(probe.getStatusCode().value() == 200 || probe.getStatusCode().value() == 503,
                    "probe=" + probe.getBody());
            Thread.sleep(1200);
            var recovered = rest.postForEntity("/api/resume/optimize?resumeId=cb-recover", null, String.class);
            assertEquals(200, recovered.getStatusCode().value(), "body=" + recovered.getBody());
        } catch (InterruptedException e) {
            fail("恢复等待被中断");
        }
    }
}
