# Gateway 编码规范

## 技术基线

- Java 17
- Spring Boot 3.2.4
- Spring Cloud 2023.0.1
- Spring Cloud Alibaba 2023.0.1.0
- Spring Cloud Gateway 4
- Gradle Wrapper 8.5

## 服务边界

`gateway` 是入口网关，只承担基础流量职责：

- 从 Nacos 读取网关路由。
- 使用 Nacos Discovery 做服务发现。
- 通过 `lb://service-name` 转发到后端服务。
- 处理跨域预检。
- 使用 `RequestRateLimiter` 做网关级限流。
- 使用 Knife4j Gateway Starter 聚合 OpenAPI3 文档。
- 从 Nacos 远程 `logging.yml` 读取日志配置。

## 认证边界

后端升级后，认证主线是 `Authorization: Bearer <jwt>`：

- 登录入口在 `user` 服务 `/auth/sessions`。
- 当前用户资源入口在 `user` 服务 `/auth/current/resources`。
- 统一响应由 `utils` 的 `ApiResponse<T>` 提供。
- Spring Security、JWT 解析、用户上下文、多租户上下文由 `utils` 和业务服务处理。

网关规则：

- 原样透传 `Authorization`、`dataSource`、租户相关请求头。
- 不读取历史 `token` 请求头作为登录态。
- 不查询 Redis 中的用户对象或 API 权限列表。
- 不调用 `user/authUserSystem` 做二次鉴权。
- 不按 `ApiResponse.code` 判断请求能否放行。
- 不新增 token、header、用户、权限或 Actuator 自定义鉴权过滤器。

## 注释规范

- AI 新增或修改 Java、YAML、Nacos 配置、脚本、测试和示例等编程内容时必须按“每行注释”标准处理，每一行新增或修改内容都要说明该行内容的用途、功能、业务含义或网关职责。
- 包声明、import、空行、单独的大括号和 Markdown 普通说明段落不需要写注释。
- 类、字段、常量、方法、Bean、判断条件、返回值、lambda 和流式处理步骤必须逐行写中文注释。
- 注释要说明这一行在网关里的职责或约束，避免无意义复述代码语法。
- 修改已有类时，至少补齐被修改逻辑周围的关键行注释。

## 路由规范

- Nacos 路由配置只保存在远程配置中心，不在仓库保留本地 YAML 副本。
- 公共限流放到 `spring.cloud.gateway.default-filters`，避免每条路由重复配置。
- 当前配置 `user` 和 `message` 服务路由。
- 新增微服务时，让 AI 读取远程 `gateway-spring.yaml` 后追加一条路由并整体发布回 Nacos。
- 常规业务服务使用 `/{service}/** + StripPrefix=1`。
- `user` 服务同时支持旧 `/user/**` 前缀和新 `/auth/**` 直达。
- 旧 `/user/**` 使用 `RewritePath=/user/(?<segment>.*), /${segment}`，保证 `/user/auth/sessions` 仍可转到后端 `/auth/sessions`。
- 新 `/auth/**` 不剥离前缀，保证 `/auth/sessions` 直达后端 `/auth/sessions`。
- `message` 服务使用 `/message/**` 前缀，并通过 `RewritePath=/message/(?<segment>.*), /${segment}` 转发到后端真实路径。

## 日志规范

- 仓库不保留 `src/main/resources/logback-spring.xml`。
- 日志配置统一维护在 Nacos 远程 `logging.yml`。
- 不接入 SLS、Loghub 或阿里云日志 appender。
- 不在日志配置中写入 accessKey、secretKey 等凭证。

## OpenAPI 规范

- 本项目不再使用 springfox。
- 后端服务使用 OpenAPI3 `/v3/api-docs`。
- 网关不再维护自写 `/swagger-resources` Controller。
- 网关文档聚合使用官方 `knife4j-gateway-spring-boot-starter`。
- 统一访问入口是 `/doc.html`。
- 当前 `user` 服务文档地址配置为 `/user/v3/api-docs?group=default`。
- 当前 `message` 服务文档地址配置为 `/message/v3/api-docs?group=default`。
- 新增微服务时，同步在 Nacos 远程 `gateway-spring.yaml` 维护 `spring.cloud.gateway.routes` 和 `knife4j.gateway.routes`。

## 测试规范

- 新增或调整代码后执行 `./gradlew clean test bootJar --no-daemon`。
- 测试包名必须能扫描到 `com.kellen.GatewayApplication`。
- 如测试读取本机 Nacos 配置，需确认 Nacos 已启动并说明依赖。
- 普通单元测试不要默认依赖真实 Redis、Nacos、业务服务；需要外部配置时优先 mock 或使用测试配置。
