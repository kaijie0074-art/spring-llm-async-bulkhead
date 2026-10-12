package com.resumeai.stability.service;

import com.resumeai.stability.config.AiProperties;
import com.resumeai.stability.llm.FakeLlmServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * v0.1.0 基线实现：在 Tomcat 工作线程内同步阻塞调 LLM。
 *
 * 这段代码故意保持"最常见写法"：Controller → Service 直接 RestTemplate 同步调用。
 * ai.mode=baseline 时激活。算术（问题报告 RST-001）：
 *   200 线程 ÷ 15s/次 ≈ 13 QPS 即饱和；饱和后登录/列表全部排队 → 全站雪崩。
 */
@Service
public class BaselineOptimizeService {

    private static final Logger log = LoggerFactory.getLogger(BaselineOptimizeService.class);

    private final RestTemplate restTemplate;
    private final FakeLlmServer llm;
    private final AiProperties props;

    @Autowired
    public BaselineOptimizeService(RestTemplate llmRestTemplate, FakeLlmServer llm, AiProperties props) {
        this.restTemplate = llmRestTemplate;
        this.llm = llm;
        this.props = props;
    }

    /** 直接占用调用线程（=Tomcat 工作线程）达 delay 时长。 */
    public String optimize(String resumeId) {
        long t0 = System.currentTimeMillis();
        log.info("[baseline] optimize start resumeId={} thread={}", resumeId, Thread.currentThread().getName());
        String body = restTemplate.postForObject(llm.baseUrl() + "/v1/chat/completions",
                "{\"resume_id\":\"" + resumeId + "\"}", String.class);
        log.info("[baseline] optimize done resumeId={} cost={}ms", resumeId, System.currentTimeMillis() - t0);
        return body;
    }
}
