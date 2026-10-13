# 真实 LLM 压测实跑记录 · 2026-10-13

## 环境与修复

- 端点：`https://open.bigmodel.cn/api/coding/paas/v4`（智谱 GLM Coding Plan，OpenAI 兼容），模型 glm-4-flash
- **实测发现并修复真实缺陷**：`LlmHttpRequests.chatCompletionsUrl` 硬编码追加 `/v1/chat/completions`，对智谱端点（base 已含 `/v4`）拼出 `/v4/v1/chat/completions` 404——README 声称"默认智谱"但默认配置真实调用必失败（此前 evidence 为本地假服务口径，未暴露）。已修复为版本段感知拼接（DeepSeek 无版本段仍补 `/v1`），`mvn test` 4 项全绿（FakeLlmServer 约定不受影响）。
- 单请求冒烟：真实调用 200，耗时 25.99s——真实依赖为"极慢依赖"。

## 压测对比（同参数：optimize 并发 220 / login 并发 4 / 持续 20s）

| 指标 | baseline（真实 LLM） | fixed（真实 LLM） | 改善 |
|---|---|---|---|
| /api/login P99 | **998 ms** | **75 ms** | **13.3×** |
| /api/login P50 | 105 ms | 41 ms | 2.6× |
| /api/login 吞吐 | 503 次（全 200） | 1881 次（全 200） | 3.7× |
| /api/resume/optimize | 5248 次：200×71 / 500×5177，P99 20.9s | 151929 次：503×151907 / 504×21，P99 59ms | 从"排队等死"到毫秒级快速失败 |

## 解读

1. **baseline 雪崩形态（真实依赖）**：220 并发 × 26s/调用的真实慢依赖占满 Tomcat 线程，login P99 被拖至 998ms（无压测干扰时 29ms）；optimize 98.9% 以 500 告终且 P99 达 20.9s（排队等死）。
2. **fixed 保护（真实依赖）**：有界池+熔断打开后，AI 溢出流量 22ms 快速失败（503+Retry-After），login P99 75ms、吞吐 3.7×——隔离在真实 API 依赖下成立。
3. 与旧 evidence（本地假 LLM、4289ms→366ms）口径不同：本次为真实 API 依赖（含端点限流 429 传导），数据文件 `loadtest-baseline-real-llm-20261013.md` / `loadtest-fixed-real-llm-20261013.md`。
4. 冒烟单请求 fixed 504（12s 超时）：设计取舍——AI 请求允许失败，登录路径必须活着。

## 离线回归

`mvn test` 4 项（隔离/饱和复现/超时/熔断）全绿。
