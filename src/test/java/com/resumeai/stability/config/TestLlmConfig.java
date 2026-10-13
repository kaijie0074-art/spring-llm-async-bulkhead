package com.resumeai.stability.config;

import com.resumeai.stability.llm.FakeLlmServer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 测试 LLM 端点装配（仅测试源集）：起一个本地可控端点（慢推理/429 切换），
 * 并把 ai.llm.base-url 运行时覆写指向它——服务层每次调用都从 props 读
 * base-url，因此无需静态端口。属性 test.llm.delay-ms 控制端点延迟。
 */
@TestConfiguration
public class TestLlmConfig {

    @Bean(destroyMethod = "stop")
    FakeLlmServer fakeLlmServer(AiProperties props,
                                @Value("${test.llm.delay-ms:300}") long delayMs) {
        FakeLlmServer server = new FakeLlmServer(0, delayMs);
        props.getLlm().setBaseUrl(server.baseUrl());
        return server;
    }
}
