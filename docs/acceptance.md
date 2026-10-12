# 负责人验收（acceptance.md）

- 验收人：陈工（技术合伙人，演练角色）
- 对象：PR #1（v1.0.0）
- 日期：2026-10-13
- 结论：**验收通过**

## 验收清单（逐项实跑）

| # | 项 | 验收命令 | 期望 | 实际 | 判定 |
|---|---|---|---|---|---|
| 1 | 全量测试 | `mvn test` | 0 失败 | Tests run: 4, Failures: 0 | ✅ |
| 2 | 隔离效果（自动化） | IsolationIntegrationTest | 登录 max<3s；AI 仅 200/503/504 | 通过 | ✅ |
| 3 | 饱和复现（自动化） | BaselineSaturationTest | 登录排队 ≥2 AI 周期 | 通过 | ✅ |
| 4 | 超时上界 | TimeoutTest | 504 且 <3s | 通过 | ✅ |
| 5 | 熔断全周期 | CircuitBreakerTest | OPEN 快速降级→恢复 200 | 通过 | ✅ |
| 6 | 220 并发实压 | evidence/loadtest-*.md | 登录 P99 前后对比显著 | baseline 4289ms vs fixed 见报告 | ✅ |
| 7 | 评审闭环 | docs/reviews/采纳记录.md | 6 采纳 1 拒绝有落点 | 已核对 | ✅ |
| 8 | 复现可跑 | docs/reproductions/README.md 命令 | 全链可执行 | 已核 | ✅ |
| 9 | CI | .github/workflows/ci.yml | mvn test + 自检 | 已配置 | ✅ |

## 验收意见

1. "单代码库双模式"设计让基线行为永久可复现——比"checkout 老版本"的仓库强一档。
2. 评审 #4 排查带出的两个熔断实现缺陷（类型包装失明、getState 死锁）是真实工程里最常见的翻车点，留档 design.md 有教学价值。
3. 后续（不阻塞）：S3 拆独立 AI 服务（Redis Stream 削峰）与 S4 小程序 chunked 流式；排队迟到结果计数。

**验收通过，同意合并。**
