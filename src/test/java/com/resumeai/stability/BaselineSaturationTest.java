package com.resumeai.stability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 基线饱和复现（自动化版线程池耗尽）：小 Tomcat 线程池（8）+ baseline 同步模式 +
 * delay 500ms → 16 路并发 AI 占满 8 线程，登录被迫排队，max 耗时显著高于正常。
 *
 * 这条测试的存在意义：把"全站雪崩"从口头描述变成可断言的失败（见回归记录）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.tomcat.threads.max=8",
                "ai.mode=baseline",
                "ai.llm.delay-ms=500",
                "ai.llm.port=0",
        })
@TestPropertySource(properties = {
        "server.tomcat.threads.max=8",
        "ai.mode=baseline",
        "ai.llm.delay-ms=500",
        "ai.llm.port=0",
})
class BaselineSaturationTest {

    @Autowired TestRestTemplate rest;

    @Test
    void 同步AI请求把登录挤到排队() throws Exception {
        int aiConcurrency = 16;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(aiConcurrency + 8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
            for (int i = 0; i < aiConcurrency; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    long t0 = System.currentTimeMillis();
                    try {
                        rest.postForEntity("/api/resume/optimize?resumeId=base-" + idx, null, String.class);
                    } catch (Exception ignored) { }
                    return System.currentTimeMillis() - t0;
                }));
            }
            // AI 慢路径已占满 8 个 Tomcat 线程；此刻登录只能等
            Thread.sleep(100);
            long t0 = System.currentTimeMillis();
            var resp = rest.getForEntity("/api/login", String.class);
            long loginCost = System.currentTimeMillis() - t0;

            assertEquals(200, resp.getStatusCode().value());
            // 登录本体 20ms；排队必须显著放大（≥2 个 AI 周期的等待）
            assertTrue(loginCost >= 400,
                    "登录耗时 " + loginCost + "ms —— 未观测到排队，基线饱和未复现（测试环境异常）");

            for (var f : futures) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }
}
