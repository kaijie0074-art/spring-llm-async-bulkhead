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
    private final Breaker breaker = new Breaker();


    public static class Llm {
        /** 真实 LLM API（OpenAI 兼容协议）地址。 */
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        /** API 凭证（生产经环境变量 LLM_API_KEY 注入；指向本机测试端点时可空）。 */
        private String apiKey = "";
        private String model = "glm-4-flash";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 12000;
        private int deferredResultTimeoutMs = 15000;
        /** >0 时请求体带 max_tokens（延迟/成本控制旋钮）；0=不限制（供应商默认）。 */
        private int maxTokens = 0;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { this.baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { this.apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { this.model = v; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
        public int getDeferredResultTimeoutMs() { return deferredResultTimeoutMs; }
        public void setDeferredResultTimeoutMs(int v) { this.deferredResultTimeoutMs = v; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int v) { this.maxTokens = v; }
    }

    public static class Pool {
        private int core = 8;
        private int max = 16;
        private int queueCapacity = 50;

        public int getCore() { return core; }
        public void setCore(int core) { this.core = core; }
        public int getMax() { return max; }
        public void setMax(int max) { this.max = max; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int v) { this.queueCapacity = v; }
    }

    /** S2 熔断参数——2026-10-14 研究后改为可配（对不同延迟量级的依赖，半开探测节奏必须可调）。 */
    public static class Breaker {
        private float failureRateThreshold = 50f;
        private int slidingWindowSize = 8;
        private int minimumNumberOfCalls = 4;
        /** OPEN 态冷却等待（ms）。对 P50≈5-30s 的 LLM 依赖，过短会反复探测烧配额。 */
        private int waitDurationInOpenStateMs = 2000;
        private int permittedNumberOfCallsInHalfOpenState = 2;

        public float getFailureRateThreshold() { return failureRateThreshold; }
        public void setFailureRateThreshold(float v) { this.failureRateThreshold = v; }
        public int getSlidingWindowSize() { return slidingWindowSize; }
        public void setSlidingWindowSize(int v) { this.slidingWindowSize = v; }
        public int getMinimumNumberOfCalls() { return minimumNumberOfCalls; }
        public void setMinimumNumberOfCalls(int v) { this.minimumNumberOfCalls = v; }
        public int getWaitDurationInOpenStateMs() { return waitDurationInOpenStateMs; }
        public void setWaitDurationInOpenStateMs(int v) { this.waitDurationInOpenStateMs = v; }
        public int getPermittedNumberOfCallsInHalfOpenState() { return permittedNumberOfCallsInHalfOpenState; }
        public void setPermittedNumberOfCallsInHalfOpenState(int v) { this.permittedNumberOfCallsInHalfOpenState = v; }
    }

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public Llm getLlm() { return llm; }
    public Pool getPool() { return pool; }
    public Breaker getBreaker() { return breaker; }
}
