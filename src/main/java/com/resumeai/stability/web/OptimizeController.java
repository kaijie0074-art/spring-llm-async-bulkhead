package com.resumeai.stability.web;

import com.resumeai.stability.config.AiProperties;
import com.resumeai.stability.service.BaselineOptimizeService;
import com.resumeai.stability.service.FixedOptimizeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.util.concurrent.TimeoutException;

/**
 * POST /api/resume/optimize —— 按 ai.mode 分发：
 *   baseline：同步阻塞（复现线程池耗尽）
 *   fixed   ：DeferredResult 立即释放 Tomcat 线程，AI 在独立池执行（隔离）
 */
@RestController
@RequestMapping("/api/resume")
public class OptimizeController {

    private static final Logger log = LoggerFactory.getLogger(OptimizeController.class);

    private final BaselineOptimizeService baseline;
    private final FixedOptimizeService fixed;
    private final AiProperties props;

    @Autowired
    public OptimizeController(BaselineOptimizeService baseline, FixedOptimizeService fixed, AiProperties props) {
        this.baseline = baseline;
        this.fixed = fixed;
        this.props = props;
    }

    @PostMapping("/optimize")
    public Object optimize(@RequestParam(defaultValue = "r-1") String resumeId) {
        if (props.getMode() == AiProperties.Mode.BASELINE) {
            return baseline.optimize(resumeId); // 占住 Tomcat 线程 8s+
        }
        return deferred(resumeId);
    }

    private DeferredResult<ResponseEntity<String>> deferred(String resumeId) {
        final Long timeoutMs = (long) props.getLlm().getDeferredResultTimeoutMs();
        DeferredResult<ResponseEntity<String>> dr = new DeferredResult<>(timeoutMs);

        // 研究 Q3：deferred 超时只结束用户等待。SimpleClientHttpRequestFactory 的阻塞
        // socket read 不可被线程中断 cancel 释放，池线程必钉到 read 超时——这里 cancel(true)
        // 是尽力而为（至少让 exceptionally/thenAccept 短路、结果不再写回），资源上界仍由 read 决定。
        final var future = fixed.optimizeAsync(resumeId);

        // 评审意见 #1：onTimeout/onCompletion 必须清理，防线程/回调泄漏
        dr.onTimeout(() -> {
            log.warn("[fixed] deferred timeout resumeId={} thread={} cancelled={}",
                    resumeId, Thread.currentThread().getName(), future.cancel(true));
            dr.setErrorResult(ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body("{\"code\":\"AI_TIMEOUT\",\"msg\":\"排队超时，请稍后重试\"}"));
        });
        dr.onCompletion(() -> log.debug("[fixed] deferred completed resumeId={}", resumeId));

        future.thenAccept(outcome -> {
            if (dr.isSetOrExpired()) {
                return; // 用户侧已超时：不再写回（线程仍占用至 read 超时，见上）
            }
            HttpHeaders headers = new HttpHeaders();
            headers.set("Content-Type", "application/json");
            if (outcome.retryAfterSeconds() != null) {
                headers.set("Retry-After", outcome.retryAfterSeconds()); // 评审#3：503 带 Retry-After
            }
            dr.setResult(ResponseEntity.status(outcome.httpStatus()).headers(headers).body(outcome.body()));
        });
        return dr;
    }

    @GetMapping("/mode")
    public String mode() {
        return "{\"mode\":\"" + props.getMode() + "\"}";
    }
}
