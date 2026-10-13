# 复现材料

## 快速复现（三种粒度）

### 1. 自动化测试（CI 可跑，~30s）

```bash
mvn test
```

- `BaselineSaturationTest`——Tomcat 线程压到 8 + baseline 模式 + 16 路并发 AI → **断言登录被迫排队 ≥2 个 AI 周期**（雪崩的自动化形态）
- `IsolationIntegrationTest`——fixed 模式 30 并发 AI + 30 并发登录 → 登录 max <3s、AI 只允许 200/503/504
- `TimeoutTest`——read-timeout 600ms + delay 3000ms → 504 在 ~1s 返回（对比 v0.1.0 的无限等待）
- `CircuitBreakerTest`——429 风暴 → 熔断 OPEN 快速降级（<2s）→ 恢复后闭合

### 2. 本机压测（真实 200 线程算术，~2 分钟）

```bash
mvn -DskipTests package
# baseline：220 并发 AI（>200 线程）→ 全站排队（真实 LLM API，需凭证）
export LLM_API_KEY=your-key
java -jar target/*.jar --ai.mode=baseline &
python3 loadtest/run.py --optimize 220 --login 4 --duration 25 --label baseline --out evidence/loadtest-baseline.md
# fixed：同参数
java -jar target/*.jar --ai.mode=fixed &
python3 loadtest/run.py --optimize 220 --login 4 --duration 25 --label fixed --out evidence/loadtest-fixed.md
```

### 3. 实跑数据（2026-10-13，evidence/）

**baseline（AI 220 并发 > Tomcat 200 线程 → 饱和）**

| 接口 | P50 | P95 | P99 | 状态码 |
|---|---|---|---|---|
| /api/login | 107ms | **4024ms** | **4289ms** | 200×99 |
| /api/resume/optimize | 4164ms | 7681ms | 8764ms | 200×1236 |

登录从毫秒级劣化到 4.3 秒（40×），且随持续时间的堆积会继续恶化直至拒连——与问题报告的故障时序一致。

**fixed（同参数，S0+S1+S2 全开）**

见 evidence/loadtest-fixed.md：登录 P99 保持毫秒级；AI 溢出流量拿到 503 AI_BUSY（带 Retry-After）快速失败。

## 已知边界

- 测试端点（src/test 内 FakeLlmServer）的 delay 是常数（非真实 LLM 的长尾分布）；P99 长尾形态由 TimeoutTest 的超时上界保证；生产直连真实 API（LLM_API_KEY），长尾形态以真实压测为准
- 压测脚本为 Python 标准库线程模型（~几百 QPS 上限），对 200 线程饱和复现足够；更大规模用 wrk/ab（见 open-source-references）
- wx.request 流式（enableChunked/onChunkReceived）属 S4 路线，本仓库止于 503 快速失败；小程序端约束已记入 open-source-references.md
