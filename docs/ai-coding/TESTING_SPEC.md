# 测试分层规范

## 定位

`gateway` 是 Spring Cloud Gateway 服务。测试重点是真实路由、断言、过滤器、请求头透传、跨域和 OpenAPI 文档转发，不测试业务服务内部逻辑。

## 主流分层

- 单元测试：Predicate、Filter、配置转换、小工具，使用 JUnit 5 + AssertJ。
- Gateway 集成测试：使用 `@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)` + `WebTestClient` 发真实 HTTP 请求。
- 路由转发测试：使用 stub backend、mock server 或测试服务，验证路径改写、请求头透传和响应转发。
- E2E 测试：真实 Nacos、真实后端服务、真实网关链路放到独立 integration profile 或测试环境。

## assertThat 规则

`assertThat` 是断言工具，可以继续使用。判断测试是否充分，看被断言的数据是否来自真实网关链路，而不是看是否用了 `assertThat`。

## 网关测试要求

- 新增或修改路由时，必须有真实 HTTP 级别测试覆盖匹配路径、目标 URI、Path Rewrite 和转发结果。
- 修改请求头、认证头、租户头、版本号、流量泳道等透传逻辑时，必须用 `WebTestClient` 验证真实 header。
- 修改 Swagger/OpenAPI 聚合时，必须验证网关文档路径返回可读结果。
- 网关不得在测试中补业务鉴权逻辑；业务鉴权测试归后端服务。

## 外部环境

- 普通 CI 不依赖真实 Nacos 或真实后端服务。
- 完整网关 E2E 使用独立测试环境或 integration profile。

## 测试稳定性

- 测试结果只能由输入决定，不依赖真实时间、线程调度、连接复用、端口、执行顺序或本机遗留状态。需要时间的逻辑注入 `Clock`；等待异步结果用有总时限的条件等待，不用固定 `Thread.sleep` 赌时序。
- 本地 HTTP 桩服务每个测试独占随机端口，并在 `@AfterEach` 停止。同一测试对同一被测实例请求多次时，桩响应设 `Connection: close`，不依赖 JDK `HttpServer` 的 keep-alive 连接回收时序。更复杂的桩可评估 MockWebServer 或 WireMock，新增依赖按依赖变更处理。
- 连接池、事件循环、静态单例、系统属性等进程级共享状态，不能成为测试之间的隐含依赖；用到时写明隔离方式，不依赖测试类或测试方法的执行顺序。
- 新增或修改涉及网络、并发、超时、流式的测试，合并前本机连续三轮运行该测试类全部通过：`./gradlew test --rerun --tests '<测试类全名>'`。
- 偶发失败按缺陷处理：先查明原因再修，不用自动重试、放宽断言、`@Disabled` 或删除测试换取通过。确需临时禁用时，在注解里写明原因与恢复条件，并在交付说明中列出。
- 本机 macOS 通过不代表 CI 的 Linux 通过；只在一个平台验证过的结论要写明平台。

## 必跑命令

```bash
./gradlew clean test
```
