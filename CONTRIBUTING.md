# 贡献指南

## 跑测试

```bash
mvn test   # 4 个集成测试（会启动完整应用 + 内置 FakeLlmServer，约 1 分钟）
```

## 调参数

`AiExecutorConfig` 容量注释是调参依据——改 8/16/50 前先读注释再改，且必须同步更新 IsolationIntegrationTest 的并发参数。

## 双模式纪律

- `ai.mode=baseline` 的代码路径**不许顺手优化**——它是缺陷的忠实复现载体（BaselineSaturationTest 依赖它）
- fixed 路径的异常处理约定见 design.md D5（不做 catch-rethrow；新异常类型进 unwrap 分类）

## 分支与提交

- `main` 受保护；`fix/<编号>-<slug>` / `feat/<slug>`
- commit：`feat|fix|test|docs: 中文摘要`
