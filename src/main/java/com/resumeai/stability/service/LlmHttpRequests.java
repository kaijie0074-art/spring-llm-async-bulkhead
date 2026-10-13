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
        // max_tokens>0 时携带（延迟/成本控制旋钮，研究 Q7：输出长度是延迟的主导项）
        String body = "{\"model\":\"" + props.getLlm().getModel()
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"请优化简历 " + resumeId + "\"}]"
                + (props.getLlm().getMaxTokens() > 0
                    ? ",\"max_tokens\":" + props.getLlm().getMaxTokens()
                    : "")
                + "}";
        return new HttpEntity<>(body, headers);
    }

    static String chatCompletionsUrl(AiProperties props) {
        String base = props.getLlm().getBaseUrl().replaceAll("/+$", "");
        // base 已含版本段（如智谱 /api/paas/v4、/api/coding/paas/v4）时直接拼接；
        // 无版本段（如 DeepSeek https://api.deepseek.com）按 OpenAI 惯例补 /v1
        if (base.matches(".*(/v\\d+|/api)$") || base.endsWith("/chat/completions")) {
            return base.endsWith("/chat/completions") ? base : base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }
}
