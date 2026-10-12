package com.resumeai.stability.llm;

import com.resumeai.stability.config.AiProperties;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置 Fake LLM 服务：让应用自包含（CI 无外部依赖即可复现线程池耗尽）。
 *
 * 端点：
 *   POST /v1/chat/completions —— sleep(ai.llm.delay-ms) 后返回固定 JSON（模拟 5-30s 的 LLM 推理）
 *   GET  /__ctl/fail429       —— 切换为返回 429（压测熔断路径）
 *   GET  /__ctl/ok            —— 恢复正常
 */
@Component
public class FakeLlmServer {

    private static final Logger log = LoggerFactory.getLogger(FakeLlmServer.class);

    private final AiProperties props;
    private HttpServer server;
    private final AtomicBoolean failMode = new AtomicBoolean(false);
    private volatile int boundPort;

    @Autowired
    public FakeLlmServer(AiProperties props) {
        this.props = props;
        start();
    }

    private void start() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", props.getLlm().getPort()), 50);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // fake 服务不构成瓶颈
            server.createContext("/v1/chat/completions", exchange -> {
                if (failMode.get()) {
                    byte[] body = "{\"error\":\"rate_limited\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(429, body.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(body); }
                    return;
                }
                try {
                    Thread.sleep(props.getLlm().getDelayMs()); // 模拟慢推理
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = ("{\"choices\":[{\"message\":{\"content\":\"optimized\"}}],\"delay\":"
                        + props.getLlm().getDelayMs() + "}").getBytes(StandardCharsets.UTF_8);
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
            log.info("FakeLlmServer started on 127.0.0.1:{} delay={}ms failMode=false", boundPort, props.getLlm().getDelayMs());
        } catch (Exception e) {
            throw new IllegalStateException("FakeLlmServer 启动失败", e);
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
