package com.resumeai.stability;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.resumeai.stability.config.TestLlmConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心隔离测试（fixed 模式）：30 并发 AI 慢请求 + 30 并发登录同时打。
 * 断言：登录完全不受 AI 洪峰影响（max < 3s，CI 慢机器容差），AI 每个请求要么成功要么快速失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ai.mode=fixed",
        "test.llm.delay-ms=300",
        "ai.pool.core=4", "ai.pool.max=8", "ai.pool.queue-capacity=10",
})

@Import(TestLlmConfig.class)
class IsolationIntegrationTest {

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;

    @Test
    void 登录在AI洪峰下保持毫秒级() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(70);
        try {
            List<Future<long[]>> logins = new ArrayList<>();
            List<Future<int[]>> optimizes = new ArrayList<>();

            for (int i = 0; i < 30; i++) {
                final int idx = i;
                optimizes.add(pool.submit((Callable<int[]>) () -> {
                    long t0 = System.currentTimeMillis();
                    var resp = rest.postForEntity(
                            "/api/resume/optimize?resumeId=t-" + idx, null, String.class);
                    return new int[]{resp.getStatusCode().value(), (int) (System.currentTimeMillis() - t0)};
                }));
            }
            for (int i = 0; i < 30; i++) {
                logins.add(pool.submit((Callable<long[]>) () -> {
                    long t0 = System.currentTimeMillis();
                    var resp = rest.getForEntity("/api/login", String.class);
                    return new long[]{resp.getStatusCode().value(), System.currentTimeMillis() - t0};
                }));
            }

            long loginMax = 0;
            for (Future<long[]> f : logins) {
                long[] r = f.get(30, TimeUnit.SECONDS);
                assertEquals(200, r[0], "登录必须全部成功");
                loginMax = Math.max(loginMax, r[1]);
            }
            // 登录 20ms 业务耗时 + 调度抖动；3s 容差覆盖 CI 慢机器（评审意见 #5）
            assertTrue(loginMax < 3000, "登录 max=" + loginMax + "ms —— AI 洪峰泄漏到了主站线程池");

            for (Future<int[]> f : optimizes) {
                int[] r = f.get(40, TimeUnit.SECONDS);
                int status = r[0];
                assertTrue(status == 200 || status == 503 || status == 504,
                        "AI 请求只允许 成功/排队拒绝(AI_BUSY)/超时，got=" + status);
                // 快速失败语义：拒绝必须"快"（排队上限内），不允许 30s 级挂死
                assertTrue(r[1] < 20000, "AI 请求耗时 " + r[1] + "ms 超过 DeferredResult 上界");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
