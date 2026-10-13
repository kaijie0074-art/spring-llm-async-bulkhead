package com.resumeai.stability.llm;

import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 测试用 LLM 端点（仅测试源集；生产直连真实 OpenAI 兼容 API）。
 *
 * 端点：
 *   POST /v1/chat/completions —— sleep(delayMs) 后返回固定 JSON（复现慢推理）
 *   GET  /__ctl/fail429       —— 切换为返回 429（压测熔断路径）
 *   GET  /__ctl/ok            —— 恢复正常
 */
public class FakeLlmServer {

    private final int port;
    private final long delayMs;
    private HttpServer server;
    private final AtomicBoolean failMode = new AtomicBoolean(false);
    private volatile int boundPort;

    public FakeLlmServer(int port, long delayMs) {
        this.port = port;
        this.delayMs = delayMs;
        start();
    }

    private void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 50);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // 测试端点不构成瓶颈
            server.createContext("/v1/chat/completions", exchange -> {
                if (failMode.get()) {
                    byte[] body = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(429, body.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                    return;
                }
                try {
                    Thread.sleep(delayMs); // 复现慢推理
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"optimized\"}}],\"delay\":"
                        + delayMs + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
            });
            server.createContext("/__ctl/fail429", exchange -> {
                failMode.set(true);
                respond(exchange, "fail429 on");
            });
            server.createContext("/__ctl/ok", exchange -> {
                failMode.set(false);
                respond(exchange, "ok");
            });
            server.start();
            boundPort = server.getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException("测试 LLM 端点启动失败", e);
        }
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String msg) throws java.io.IOException {
        byte[] body = msg.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + boundPort;
    }

    @PreDestroy
    public void stop() {
        if (server != null) server.stop(0);
    }
}
