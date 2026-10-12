package com.resumeai.stability.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * AI 独立线程池（S1 层）——容量推导（评审意见 #2 要求写明）：
 *
 *   core=8, max=16, queue=50, 拒绝策略=AbortPolicy（快速失败 503）
 *   - 单次 LLM 占用上限 = readTimeout 12s（S0 保证）
 *   - 最坏在途 = max 16 线程 × 12s = 192s·并发；队列 50 × 等待上界（DeferredResult 15s 封顶）
 *   - 与 Tomcat 200 线程完全隔离：AI 洪峰只打满这个 16+50 的小池子，
 *     登录/列表等业务接口的 200 线程毫发无损——这是"雪崩→隔离"的核心算术
 *   - 拒绝策略必须 AbortPolicy 快速失败：CallerRuns 会让 Tomcat 线程替班（隔离失效），
 *     DiscardPolicy 静默丢弃（用户永远等不到响应）
 */
@Configuration
public class AiExecutorConfig {

    public static final String AI_EXECUTOR = "aiExecutor";

    @Bean(name = AI_EXECUTOR, destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor aiExecutor(AiProperties props, MeterRegistry registry) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(props.getPool().getCore());
        ex.setMaxPoolSize(props.getPool().getMax());
        ex.setQueueCapacity(props.getPool().getQueueCapacity());
        ex.setThreadNamePrefix("ai-llm-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();

        // 评审意见 #6：Gauge 注册时机——在 Bean 初始化后绑定到已初始化的 executor，
        // 弱引用持有对象，避免 MeterRegistry 强引用导致 Bean 无法回收
        ThreadPoolExecutor tp = ex.getThreadPoolExecutor();
        Gauge.builder("ai.executor.active", tp, ThreadPoolExecutor::getActiveCount)
             .description("AI 线程池活跃线程数").register(registry);
        Gauge.builder("ai.executor.queue.size", tp, t -> t.getQueue().size())
             .description("AI 线程池排队任务数").register(registry);
        Gauge.builder("ai.executor.pool.size", tp, ThreadPoolExecutor::getPoolSize)
             .description("AI 线程池当前线程数").register(registry);
        return ex;
    }

    @Bean(name = "tomcatUnaffectedMarker")
    public Executor tomcatUnaffectedMarker() {
        return Runnable::run; // 文档性 Bean：Tomcat 线程池由 Boot 自管，与 aiExecutor 隔离
    }
}
