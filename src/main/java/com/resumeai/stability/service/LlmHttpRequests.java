package com.resumeai.stability.service;

import com.resumeai.stability.config.AiProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 真实 LLM API（OpenAI 兼容协议）请求构造。
 *
 * 生产：ai.llm.base-url 指向供应商（默认智谱，可切 DeepSeek 等），
 * 凭证 ai.llm.api-key（建议经环境变量 LLM_API_KEY 注入）；
 * 指向本机测试端点（127.0.0.1/localhost）时不要求凭证。
 */
final class LlmHttpRequests {

    private LlmHttpRequests() {}

    static void ensureConfigured(AiProperties props) {
        String baseUrl = props.getLlm().getBaseUrl();
        boolean local = baseUrl.contains("127.0.0.1") || baseUrl.contains("localhost");
        if (!local && (props.getLlm().getApiKey() == null || props.getLlm().getApiKey().isBlank())) {
            throw new IllegalStateException(
                    "真实 LLM API 未配置凭证：请设置环境变量 LLM_API_KEY（可选 LLM_BASE_URL / LLM_MODEL），"
                    + "或配置 ai.llm.api-key。当前 base-url=" + baseUrl);
        }
    }

    static HttpEntity<String> chatCompletion(AiProperties props, String resumeId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (props.getLlm().getApiKey() != null && !props.getLlm().getApiKey().isBlank()) {
            headers.setBearerAuth(props.getLlm().getApiKey());
        }
        String body = "{\"model\":\"" + props.getLlm().getModel()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"请优化简历 " + resumeId + "\"}]}";
        return new HttpEntity<>(body, headers);
    }

    static String chatCompletionsUrl(AiProperties props) {
        return props.getLlm().getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
    }
}
