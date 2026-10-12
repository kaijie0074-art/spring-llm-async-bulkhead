package com.resumeai.stability.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import com.resumeai.stability.config.AiProperties;
import com.resumeai.stability.llm.FakeLlmServer;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * v1.0.0 加固实现（S0+S1+S2）：
 *
 * S0 超时：RestTemplateConfig 在 fixed 模式注入 connect 3s / read 12s
 * S1 隔离：LLM 调用提交到独立有界线程池（aiExecutor 8/16/50/Abort）
 *          队列满 → RejectedExecutionException → 503 AI_BUSY + Retry-After（评审#3）
 * S2 熔断：Resilience4j CircuitBreaker 包 LLM 调用；429/5xx/超时记失败，
 *          failureRate≥50%（窗口≥8 次）→ OPEN 直接降级 AI_DEGRADED，半开恢复
 */
@Service
public class FixedOptimizeService {

    private static final Logger log = LoggerFactory.getLogger(FixedOptimizeService.class);

    private final RestTemplate restTemplate;
    private final FakeLlmServer llm;
    private final ThreadPoolTaskExecutor aiExecutor;
    private final AiProperties props;
    private CircuitBreaker circuitBreaker;

    @Autowired
    public FixedOptimizeService(RestTemplate llmRestTemplate, FakeLlmServer llm,
                                @Qualifier("aiExecutor") ThreadPoolTaskExecutor aiExecutor,
                                AiProperties props) {
        this.restTemplate = llmRestTemplate;
        this.llm = llm;
        this.aiExecutor = aiExecutor;
        this.props = props;
    }

    @PostConstruct
    void initBreaker() {
        // 编程式构建（不用注解 AOP）：行为在测试里完全可控、无代理坑
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50f)
                .slidingWindowSize(8)
                .minimumNumberOfCalls(4)
                .waitDurationInOpenState(Duration.ofSeconds(2))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(this::isLlmFailure)
                .build();
        this.circuitBreaker = CircuitBreakerRegistry.of(config).circuitBreaker("llm");
    }

    private boolean isLlmFailure(Throwable t) {
        if (t instanceof RestClientResponseException rx) return rx.getStatusCode().value() >= 500 || rx.getStatusCode().value() == 429;
        return t instanceof ResourceAccessException; // 超时/连接失败
    }

    /** 异步执行：提交到 aiExecutor；返回带超时与降级语义的 Future 结果。 */
    public CompletableFuture<OptimizeOutcome> optimizeAsync(String resumeId) {
        try {
            return CompletableFuture.supplyAsync(() -> invokeWithBreaker(resumeId), aiExecutor)
                    // 评审意见 #1：异常路径统一按 cause 链递归解包分类转译（超时/拒绝/失败）
                    .exceptionally(t -> {
                        if (unwrap(RejectedExecutionException.class, t) != null) {
                            return OptimizeOutcome.busy();
                        }
                        if (unwrap(ResourceAccessException.class, t) != null) {
                            return OptimizeOutcome.timeout();
                        }
                        return OptimizeOutcome.failed(t.getMessage());
                    });
        } catch (RejectedExecutionException e) {
            // supplyAsync 直接拒绝（池满且队列满）
            return CompletableFuture.completedFuture(OptimizeOutcome.busy());
        }
    }

    private OptimizeOutcome invokeWithBreaker(String resumeId) {
        // 评审意见 #4：OPEN 态的快速失败与 HALF_OPEN 的并发探测限制（permitted=2）
        // 都交给 Resilience4j 状态机：executeSupplier 内部走 tryAcquirePermission，
        // OPEN 时抛 CallNotPermittedException（等待期到后自动转 HALF_OPEN）——
        // 不要用 getState() 短路：getState 不触发状态转换，会让熔断器卡死在 OPEN
        try {
            String body = circuitBreaker.executeSupplier(() ->
                    restTemplate.postForObject(llm.baseUrl() + "/v1/chat/completions",
                            "{\"resume_id\":\"" + resumeId + "\"}", String.class));
            return OptimizeOutcome.ok(body);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("[fixed] breaker OPEN → degraded resumeId={}", resumeId);
            return OptimizeOutcome.degraded();
        }
        // 其余 RuntimeException 原样冒泡：breaker 已记录失败类型，exceptionally 统一转译
    }

    /** 沿 cause 链递归找目标类型（CompletableFuture 会包 CompletionException，最多解 5 层）。 */
    static <T extends Throwable> T unwrap(Class<T> type, Throwable t) {
        int depth = 0;
        while (t != null && depth++ < 5) {
            if (type.isInstance(t)) return type.cast(t);
            t = t.getCause();
        }
        return null;
    }

    /** 统一出参：三种非成功态都带可读语义与 HTTP 映射。 */
    public record OptimizeOutcome(String kind, int httpStatus, String body, String retryAfterSeconds) {
        static OptimizeOutcome ok(String body) { return new OptimizeOutcome("OK", 200, body, null); }
        static OptimizeOutcome busy() { return new OptimizeOutcome("AI_BUSY", 503,
                "{\"code\":\"AI_BUSY\",\"msg\":\"排队人数过多，请稍后再试\"}", "2"); }
        static OptimizeOutcome degraded() { return new OptimizeOutcome("AI_DEGRADED", 503,
                "{\"code\":\"AI_DEGRADED\",\"msg\":\"AI 服务繁忙，已自动降级，请稍后重试\"}", "5"); }
        static OptimizeOutcome timeout() { return new OptimizeOutcome("AI_TIMEOUT", 504,
                "{\"code\":\"AI_TIMEOUT\",\"msg\":\"AI 响应超时，请稍后重试\"}", "3"); }
        static OptimizeOutcome failed(String msg) { return new OptimizeOutcome("AI_FAILED", 502,
                "{\"code\":\"AI_FAILED\",\"msg\":\"" + (msg == null ? "unknown" : msg) + "\"}", null); }
    }
}
