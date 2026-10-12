# 修复引用（问题 → 实现 → 测试对照）

| 问题编号 | 故障层 | 修复模块 | commit 主题 | 测试 |
|---|---|---|---|---|
| RST-001-a | 无超时（放大器1） | `RestTemplateConfig`（fixed 模式 connect 3s / read 12s；baseline 保持默认 0=无限，供复现） | fix: S0 全局超时 | `TimeoutTest`（504 ~1s 返回） |
| RST-001-b | 线程池不隔离 | `AiExecutorConfig`（独立有界池 8/16/50/Abort + 容量推导注释）+ `OptimizeController.deferred`（DeferredResult 释放容器线程 + onTimeout/onCompletion 清理） | feat: S1 独立有界池与异步化 | `IsolationIntegrationTest` |
| RST-001-c | 排队无上界、无快速失败语义 | 池满 → AbortPolicy → 503 AI_BUSY + **Retry-After 头**（评审#3） | feat: 快速失败与Retry-After | Isolation 断言"200/503/504 且 <20s" |
| RST-001-d | 429 传导（放大器2） | `FixedOptimizeService` CircuitBreaker（failureRate 50%/窗口 8/最少 4 次；OPEN 快速降级 AI_DEGRADED；HALF_OPEN 限 2 探测） | feat: S2 熔断降级 | `CircuitBreakerTest` |
| RST-001-e | 异常类型被包装致熔断失明 | `unwrap`（cause 链递归解包）+ invokeWithBreaker 不做 catch-rethrow | fix: 异常解包分类 | TimeoutTest 504 正确分类（曾 502） |
| RST-001-f | 熔断卡死 OPEN | 移除 `getState()` 短路，改 `CallNotPermittedException` 语义（getState 不触发 OPEN→HALF_OPEN 转换） | fix: 熔断状态机语义 | CircuitBreakerTest 恢复段 |
| RST-001-g | 无观测 | `PoolMetricsBinder`（ai.executor.active/queue/pool Gauge）+ actuator 暴露 | feat: 池指标 | Isolation 场景日志可观察 |
| RST-001-h | 饱和不可复现 | `BaselineSaturationTest`（Tomcat 压 8 线程自动复现排队）+ `loadtest/run.py`（220 并发实压） | test: 雪崩可复现 | 两份 evidence 压测报告 |

## PR 索引

- PR #1（fix/async-bulkhead → main）：承载 S0+S1+S2 全部修复；评审见 docs/reviews/。
