# 问题报告 RST-001：同步 LLM 调用打满 Tomcat 线程池 → 全站雪崩

- 编号：RST-001
- 报告人：杨凯杰（工程师）
- 日期：2026-10-10
- 严重级：P0（全站不可用）
- 状态：已修复（PR #1 合并于 2026-10-13）

## 现象

晚间高峰（宿舍群转发 → 30 人同时点"AI 优化简历"）后，**整个小程序全功能不可用**——包括与 AI 毫无关系的登录、列表、支付。用户在群里骂，创始人微信问"服务器怎么了"。此时无任何日志/监控可查（放大器 0：零可观测）。

## 机制推演（算术）

- Spring Boot 内嵌 Tomcat 默认 `server.tomcat.threads.max=200`，accept-count=100
- 单次 LLM 调用 P50≈8s / P95≈25s（长简历+推理高峰）
- 线程占用时长 ≈ 响应时长 → **200 ÷ 15s ≈ 13 QPS 即饱和**
- 晚间高峰 + 社群裂变 → 瞬时十几并发是日常
- 饱和后：新请求排队 → 队列满 → 拒连；Nginx 60s 超时先于后端放弃 → 小程序 502

三个放大器：

| # | 放大器 | 机制 |
|---|---|---|
| 1 | 无超时 RestTemplate | `SimpleClientHttpRequestFactory` 默认 0=无限等待，LLM 供应商抖动时线程被无限期钉死，耗尽不可逆 |
| 2 | 429 裸重试风暴 | 供应商限流 → 代码裸重试 ×3 → 请求量翻倍 → 更持久的 429 → 更多线程堆积 |
| 3 | 会话型长占用 | 模拟面试多轮对话，用户挂 10 分钟每轮占线程，会话型流量对线程池占用是 CRUD 的 30 倍量级 |

## 复现

三层复现（docs/reproductions/README.md）：
1. **自动化测试**：`BaselineSaturationTest`——Tomcat 8 线程 + delay 500ms + 16 路并发 → 断言登录被迫排队（耗时 ≥2 个 AI 周期）
2. **本机压测**：`loadtest/run.py` baseline 模式（16 并发 optimize + 4 并发 login，delay 4s）→ 登录 P99 从毫秒级飙到 10s+ 级
3. **修复对照**：同参数 fixed 模式 → 登录 P99 保持毫秒级，AI 溢出部分 503 AI_BUSY 快速失败

## 根因

1. AI 慢调用与普通业务共享 Tomcat 200 线程（无隔离）
2. HTTP 客户端无超时（占用无上界）
3. 无熔断：供应商 429 直接传导为线程堆积
4. 无快速失败语义：排队用户只会等到超时，体验最差且占资源最久

## 验收标准（DoD）

- [x] S0：connect 3s / read 12s 超时（TimeoutTest：504 在 ~1s 返回而非无限等）
- [x] S1：独立有界线程池（8/16/50/Abort）+ DeferredResult + 503 AI_BUSY + Retry-After（IsolationIntegrationTest）
- [x] S2：CircuitBreaker（429 率超阈 → OPEN → 快速降级 → 半开恢复；CircuitBreakerTest）
- [x] 隔离效果量化：压测登录 P99 前后对比（evidence/loadtest-*.md）
- [x] 饱和复现自动化：BaselineSaturationTest 把"雪崩"变成可断言的失败
