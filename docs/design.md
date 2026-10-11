# 设计（design.md）

## 双模式架构

一个配置项 `ai.mode=baseline|fixed` 切换两套服务实现，**单代码库同时承载"缺陷态"与"修复态"**——复现不需要 checkout 历史版本，CI 与压测脚本对两种行为都能直接验证。

```
POST /api/resume/optimize
  ├─ baseline → BaselineOptimizeService：Tomcat 工作线程内同步 RestTemplate（无超时）
  └─ fixed    → DeferredResult（容器线程立即释放）
                 → FixedOptimizeService.optimizeAsync
                     → aiExecutor（8/16/50/Abort 独立池）
                         → CircuitBreaker.executeSupplier
                             → RestTemplate（connect 3s / read 12s）
                 溢出→503 AI_BUSY+Retry-After；OPEN→503 AI_DEGRADED；读超时→504
```

## 关键决策

### D1：为什么用自有 ThreadPoolTaskExecutor 而不是 Resilience4j ThreadPoolBulkhead
两者同构（池+上限+拒绝）。已有 Spring 原生 executor 的情况下再叠 Bulkhead 会形成**两层池化**——容量推导与排障都翻倍。选 Resilience4j 只取其 CircuitBreaker（状态机复杂度不值得自造），池化用 Spring 原生（评审确认项）。

### D2：拒绝策略必须 AbortPolicy
- CallerRuns：Tomcat 线程替班执行 → **隔离失效**（雪崩回归）
- Discard：静默丢弃 → 用户永远等不到响应（最差体验）
- Abort：快速 503 + Retry-After → 用户立刻知道"稍后再试"，资源零占用

### D3：熔断用编程式而非注解 AOP
注解代理在测试里行为不透明（代理穿透/自调用失效的坑）。编程式 CircuitBreakerRegistry 在 @PostConstruct 构建，状态机行为完全可控可断言。

### D4：getState() 短路是熔断器的经典死锁（过程缺陷留档）
Resilience4j 的 OPEN→HALF_OPEN 转换由 `tryAcquirePermission()` 惰性触发。若在调用前用 `getState()==OPEN` 短路返回降级，**永远不会走到 permission 获取**，熔断器卡死在 OPEN。正确做法：捕获 `CallNotPermittedException`（executeSupplier 内部已做权限判断）。

### D5：异常必须按 cause 链递归解包
CompletableFuture 包 CompletionException、熔断包 CallNotPermitted、HTTP 客户端包 ResourceAccessException——固定层数的 `getCause()` 判断在任一环变化后失明（本项目实测：超时被误判 502 而非 504、熔断记录不到失败）。`unwrap(type, t)` 沿链最多解 5 层。

### D6：容量推导（写在 AiExecutorConfig 注释，评审#2 要求）
core=8 / max=16 / queue=50：最坏在途 = 16×12s(read-timeout 上界)；队列等待 ≤ DeferredResult 15s 封顶；溢出 Abort→503。Tomcat 200 线程与该池完全隔离——AI 任何洪峰最多消耗 16 线程+50 排队，主站无损。

## 拒绝的替代方案

| 方案 | 拒绝理由 |
|---|---|
| WebFlux 全面响应式改写 | 单体存量代码改造成本极高；DeferredResult+独立池已解决隔离，收益不成比例 |
| 每请求虚拟线程（Loom） | Java 21 可用，但"无界并发直打慢供应商"只是把排队换了个地方——快速失败语义与熔断仍不可少；作为 v1.1 的池实现替换项保留 |
| 消息队列削峰（S3 架构期） | 根治方案但引入基础设施；本仓库演示"S0+S1+S2 一周内可落地"的最小解，S3 路线在问题报告标注 |
| Netflix Hystrix | 停止维护；Spring 生态标准继任是 Resilience4j |

## 线上闭环

1. `/actuator/metrics/ai.executor.*` 接 Prometheus（Gauge 已注册）
2. 503 AI_BUSY 率 >5% 持续 5min → 告警（扩容或降级文案）
3. 熔断 OPEN 事件 → 告警（供应商健康）
