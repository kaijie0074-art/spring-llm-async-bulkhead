package com.resumeai.stability.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 双模式配置——一个属性切换基线/加固，单代码库可同时复现两种行为：
 *
 *   ai.mode=baseline  v0.1.0：Controller 线程内同步阻塞调 LLM，无超时配置
 *   ai.mode=fixed     v1.0.0：S0 超时 + S1 独立有界线程池 + S2 熔断降级
 */
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    public enum Mode { BASELINE, FIXED }

    /** 默认 baseline：README 的"复现"路径就是默认启动路径。 */
    private Mode mode = Mode.BASELINE;
    private final Llm llm = new Llm();
    private final Pool pool = new Pool();

    public static class Llm {
        /** FakeLlmServer 端口；0=随机端口（测试并发安全）。 */
        private int port = 18081;
        /** 单次 LLM 延迟（ms）：默认 8000 对应真实 P50~P95 区间（问题报告 RST-001 算术）。 */
        private long delayMs = 8000;
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 12000;
        private int deferredResultTimeoutMs = 15000;

        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
        public int getDeferredResultTimeoutMs() { return deferredResultTimeoutMs; }
        public void setDeferredResultTimeoutMs(int v) { this.deferredResultTimeoutMs = v; }
    }

    public static class Pool {
        private int core = 8;
        private int max = 16;
        private int queueCapacity = 50;

        public int getCore() { return core; }
        public void setCore(int core) { this.core = core; }
        public int getMax() { return max; }
        public int getMax2() { return max; }
        public void setMax(int max) { this.max = max; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Llm getLlm() { return llm; }
    public Pool getPool() { return pool; }
}
