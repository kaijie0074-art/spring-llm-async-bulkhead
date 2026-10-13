# CHANGELOG

## [1.0.0] - 2026-10-13

合并 PR #1（fix/async-bulkhead → main），负责人验收通过。

### 修复（相对 v0.1.0-baseline）

- **S0 超时**：RestTemplate connect 3s / read 12s（baseline 保留默认无限时供复现）
- **S1 隔离**：独立有界线程池 8/16/50/AbortPolicy + DeferredResult（容器线程立即释放）+ onTimeout/onCompletion 清理 + 503 AI_BUSY 带 Retry-After
- **S2 熔断**：Resilience4j CircuitBreaker（50%/窗口 8/最少 4）——OPEN 快速降级 AI_DEGRADED、HALF_OPEN 限 2 探测、自动恢复
- **观测**：ai.executor.active/queue/pool Gauge + actuator
- **可复现**：BaselineSaturationTest 自动化饱和断言；loadtest/run.py 220 并发实压（登录 P99 4289ms→366ms）

### 评审与验收

- 6 条意见采纳、1 条拒绝备案（FakeLlm 长尾随机化）
- 4 个过程实测缺陷留档（异常包装失明、getState 死锁、代理劫持、子 shell cwd）
- 4 测试全绿；CI 上线

## [0.1.0-baseline] - 2026-10-09

初始实现：Controller → Service 同步 RestTemplate 调 LLM，无超时、无隔离、无熔断。

**已知缺陷**（RST-001）：13 QPS 即饱和全站；无超时线程被无限钉死；429 裸重试风暴。
