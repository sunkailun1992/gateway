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

## 必跑命令

```bash
./gradlew clean test
```
