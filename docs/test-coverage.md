# 覆盖场景说明（test-coverage.md）

4 个集成测试覆盖四个正交场景。

## 隔离（S1 核心）

| 场景 | 用例 | 断言 |
|---|---|---|
| AI 洪峰下登录无感 | IsolationIntegrationTest | 30 并发 AI+30 登录：登录 100% 成功且 max<3s |
| AI 快速失败语义 | 同上 | 每个请求 ∈ {200, 503, 504} 且 <20s（无挂死） |

## 饱和复现（基线缺陷固化）

| 场景 | 用例 | 断言 |
|---|---|---|
| 同步 AI 占满线程池殃及登录 | BaselineSaturationTest | 登录耗时 ≥400ms（2 个 AI 周期下界，容差依据评审#5） |

## 超时（S0）

| 场景 | 用例 | 断言 |
|---|---|---|
| 读超时快速失败 | TimeoutTest | 504 + <3s（对比 v0.1.0 无限等待） |

## 熔断（S2 全周期）

| 场景 | 用例 | 断言 |
|---|---|---|
| 429 风暴→OPEN | CircuitBreakerTest | 503 AI_DEGRADED 且 <2s（不再真调） |
| 恢复→HALF_OPEN→CLOSED | 同上 | 探测容忍 + 第二发必 200 |

## 压测（非 CI，本机证据）

| 场景 | 命令 | 证据 |
|---|---|---|
| 220 并发 baseline 雪崩 | loadtest/run.py | evidence/loadtest-baseline.md（login P99 4289ms） |
| 220 并发 fixed 隔离 | 同参数 | evidence/loadtest-fixed.md（login P99 366ms） |

## 明确未覆盖（诚实边界）

- 排队任务的迟到结果计数（当前仅日志，v1.1 加 Counter）
- 熔断窗口参数的供应商 SLA 校准（演示值）
- S3 队列削峰 / S4 小程序 chunked 流式（架构期路线，问题报告标注）
- 真实 LLM 长尾延迟分布（测试端点为常数 delay；长尾风险由超时上界覆盖，评审#7 备案；真实 API 实压需配置 LLM_API_KEY）
