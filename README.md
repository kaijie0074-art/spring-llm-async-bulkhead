# spring-llm-async-bulkhead

**同步 LLM 调用 → Tomcat 线程池耗尽 → 全站雪崩**：单代码库双模式（`ai.mode=baseline|fixed`）的可复现修复。220 并发实压：登录 P99 从 **4289ms → 366ms**（11.7×），AI 溢出流量从"排队等死"变为 **78ms 快速失败 + Retry-After**。

> 直连真实 LLM API（OpenAI 兼容协议，默认智谱，`LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL` 环境变量配置）；集成测试用测试源集内的可控本地端点（延迟可配、可切 429）；上游项目（Resilience4j/Spring/微信小程序文档）仅作机制引用，见 [docs/open-source-references.md](docs/open-source-references.md)。

## 快速开始

要求：JDK 21、Maven 3.8+。

```bash
mvn test                 # 4 集成测试：隔离/饱和复现/超时/熔断全周期
mvn -DskipTests package
# 复现雪崩（baseline，需真实 LLM 凭证）
export LLM_API_KEY=your-key   # 可选 LLM_BASE_URL/LLM_MODEL
java -jar target/*.jar --ai.mode=baseline
python3 loadtest/run.py --optimize 220 --login 4 --duration 25 --label baseline
# 验证修复（fixed，同参数）
java -jar target/*.jar --ai.mode=fixed
python3 loadtest/run.py --optimize 220 --login 4 --duration 25 --label fixed

## 修复分层（S0→S2，改动成本递增）

| 层 | 手段 | 关键类 | 测试 |
|---|---|---|---|
| S0 半天 | HTTP 超时 connect 3s / read 12s | RestTemplateConfig | TimeoutTest（504 ~1s） |
| S1 1-2天 | 独立有界池 8/16/50/Abort + DeferredResult + 503 AI_BUSY+Retry-After | AiExecutorConfig / OptimizeController | IsolationIntegrationTest |
| S2 2-3天 | CircuitBreaker（429/5xx/超时→OPEN 快速降级→HALF_OPEN 限探测→恢复） | FixedOptimizeService | CircuitBreakerTest |
| 复现 | Tomcat 压 8 线程自动复现饱和 | — | BaselineSaturationTest |

## 两个留档的 Resilience4j 翻车点（design.md D4/D5）

1. **异常包装致熔断失明**：catch-rethrow 让 recordException 看不见类型——熔断永不开、超时误判 502。解法：cause 链递归解包 + 不重包。
2. **getState() 短路死锁**：OPEN→HALF_OPEN 由 tryAcquirePermission 惰性触发，短路后熔断器永远打不开恢复门。解法：捕获 CallNotPermittedException。

## 文档路径

- [问题报告 RST-001](docs/issue-reports/RST-001-threadpool-exhaustion.md)（含 13 QPS 饱和算术与三个放大器）
- [复现材料](docs/reproductions/README.md) / [设计](docs/design.md)（容量推导、拒绝 CallerRuns 的理由）
- [开源调研引用](docs/open-source-references.md) / [修复引用](docs/repair-references.md)
- [回归记录](docs/regression-records/线程池隔离-回归记录-20261013.md)（220 并发前后对比）
- [评审意见](docs/reviews/R1-评审意见-20261013.md) / [采纳记录](docs/reviews/采纳记录.md) / [人工评审](docs/reviews/人工评审-代码走查-20261013.md)
- [负责人验收](docs/acceptance.md) / [覆盖场景说明](docs/test-coverage.md)
- [CHANGELOG](CHANGELOG.md) / [任务地图](work/wayfinder-map.md)

## License

MIT。
