# 开源调研引用

> 调研日期：2026-10-11。上游项目作为机制与选型依据。

| 项目/文档 | 链接 | 机制一句话 | 本项目借鉴点 | 采用与否 |
|---|---|---|---|---|
| Resilience4j | https://github.com/resilience4j/resilience4j | Bulkhead（Semaphore/ThreadPool 两种）+ CircuitBreaker + RateLimiter | S2 直接采用 spring-boot3 starter；**SemaphoreBulkhead vs ThreadPoolBulkhead 的取舍**：本项目已有独立 ThreadPoolTaskExecutor（S1），再叠 ThreadPoolBulkhead 会形成两层池化——选择编程式 CircuitBreaker + 自有池，避免双重池化 | CircuitBreaker 直接采用；Bulkhead 以自有有界池等价实现 |
| Spring Boot Task Execution 文档 | https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.task-execution-and-scheduling | 无 Executor Bean 时自动配置 ThreadPoolTaskExecutor | 理解默认行为后**刻意不依赖默认 executor**：AI 池必须显式命名（aiExecutor）+ 显式容量 + AbortPolicy | 设计输入 |
| Spring Framework DeferredResult | https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/context/request/async/DeferredResult.html | 容器线程立即释放，结果由任一线程补齐 | S1 的"释放 Tomcat 线程"机制本体；onTimeout/onCompletion 清理语义（评审#1） | 直接采用 |
| Tomcat threads.max 默认值 | StackOverflow #25399400 + Spring Boot 文档 | `server.tomcat.threads.max` 默认 200 | 饱和算术的分母（13 QPS 推导）；测试用 @TestPropertySource 把它调小到 8 复现饱和 | 背景依据 |
| 微信小程序 onChunkReceived | https://developers.weixin.qq.com/miniprogram/dev/api/network/request/RequestTask.onChunkReceived.html | `enableChunked:true` + `onChunkReceived` 分块接收（基础库 2.20.1+），ArrayBuffer 载荷 | S4 流式路线的服务端前置认知：**wx.request 原生无 SSE**，需手工解析分块 + UTF-8 跨 chunk 累积缓冲解码；wx.uploadFile 不支持 chunked（上传与流式必须分离） | 引用记录（流式属 v1.1 路线，本仓库实现到 503 快速失败为止） |
| Netflix Hystrix | https://github.com/Netflix/Hystrix（维护停止） | 线程池隔离+熔断的先行者 | 历史参照：Hystrix 停维后 Resilience4j 是 Spring 生态标准继任 | 未采用（停维） |
| wrk / ab | https://httpd.apache.org/docs/2.4/programs/ab.html | 高性能压测工具 | 压测方法论（并发×持续×分位数）参照；本仓库用 Python 标准库实现同原理（loadtest/run.py），CI 零依赖可跑 | 方法借鉴，工具自实现 |

## 结论

修复的三个层次各有上游依据：**超时**（S0）是 HTTP 客户端常识但最常被漏配；**隔离**（S1）的"独立有界池 + 快速失败"是 Bulkhead 模式的线程池形态；**熔断**（S2）直接复用 Resilience4j 状态机（OPEN 快速失败、HALF_OPEN 限并发探测、自动恢复）。三者组合后，AI 供应商的任何故障形态（慢/限流/宕机）都只消耗自己的小池子，主站毫发无损。
