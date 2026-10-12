package com.resumeai.stability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * S0 超时：read-timeout 600ms + delay 3000ms → 504 AI_TIMEOUT 在 ~1s 内返回，
 * 而不是无限等待（v0.1.0 的"线程被无限钉死"形态）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ai.mode=fixed",
        "ai.llm.delay-ms=3000",
        "ai.llm.read-timeout-ms=600",
        "ai.llm.deferred-result-timeout-ms=8000",
        "ai.llm.port=0",
})
class TimeoutTest {

    @Autowired TestRestTemplate rest;

    @Test
    void 读超时快速失败而非无限等待() {
        long t0 = System.currentTimeMillis();
        var resp = rest.postForEntity("/api/resume/optimize?resumeId=to-1", null, String.class);
        long cost = System.currentTimeMillis() - t0;
        assertEquals(504, resp.getStatusCode().value(), resp.getBody());
        assertTrue(cost < 3000, "超时应在 read-timeout+缓冲 内返回，实际 " + cost + "ms");
    }
}
