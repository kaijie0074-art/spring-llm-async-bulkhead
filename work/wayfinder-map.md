# wayfinder-map：spring-llm-async-bulkhead

| # | 里程碑 | 判据 | 完成 | 证据 |
|---|---|---|---|---|
| 1 | 饱和算术与放大器梳理 | 13 QPS 推导成立 | ✅ 10-10 | 问题报告 RST-001 |
| 2 | 开源调研 | S0/S1/S2 各有上游依据 | ✅ 10-11 | open-source-references.md |
| 3 | 双模式实现 | baseline/fixed 单库切换 | ✅ 10-12 | AiProperties + 两 Service |
| 4 | 测试全绿 | 隔离/饱和/超时/熔断 | ✅ 10-12 | mvn test 4/4 |
| 5 | 实压数据 | 220 并发前后对比 | ✅ 10-13 | evidence/ 两份报告 |
| 6 | 评审+验收 | 6+1 意见闭环 | ✅ 10-13 | docs/reviews/ |
| 7 | 自主深潜研究 | 7 问实验链撞到模型侧瓶颈 | ✅ 10-14 | docs/research/deep-dive-20261014.md + evidence/ |

## 过程缺陷台账

1. 异常 catch-rethrow 包装 → 熔断失明/超时误判 → unwrap 递归解包
2. getState() 短路 → 熔断死锁 OPEN → CallNotPermittedException 语义
3. 压测被 HTTP_PROXY 劫持 → opener 禁代理
4. 子 shell cwd 丢失 → 绝对路径编排

## 剩余风险（v1.1）

- 迟到结果计数 Counter
- 熔断参数按供应商 SLA 校准（✅ 10-14 研究落地：ai.breaker.* 可配 + 推荐值）
- S3 队列削峰 / S4 chunked 流式（架构期；研究 Q3 已证 cancel 不能释放阻塞读，流式是绕开
  单流 16.4 tok/s 速率、改善用户感知的另一条路，但总时长不变——见 deep-dive Q2/Q7）
