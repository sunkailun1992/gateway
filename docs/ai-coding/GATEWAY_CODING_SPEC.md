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
- 对 `/actuator` 入口做额外保护。
- 聚合 OpenAPI 资源列表。
- 从 Nacos 远程 `logging.yml` 读取日志配置。

## 认证边界

后端升级后，认证主线是 `Authorization: Bearer <jwt>`：

- 登录入口在 `user` 服务 `/auth/login`。
- 当前用户资源入口在 `user` 服务 `/auth/resources`。
- 统一响应由 `utils` 的 `ApiResponse<T>` 提供。
- Spring Security、JWT 解析、用户上下文、多租户上下文由 `utils` 和业务服务处理。

网关规则：

- 原样透传 `Authorization`、`dataSource`、租户相关请求头。
- 不读取历史 `token` 请求头作为登录态。
- 不查询 Redis 中的用户对象或 API 权限列表。
- 不调用 `user/authUserSystem` 做二次鉴权。
- 不按 `ApiResponse.code` 判断请求能否放行。

## 路由规范

- Nacos 路由配置只保存在远程配置中心，不在仓库保留本地 YAML 副本。
- 公共限流放到 `spring.cloud.gateway.default-filters`，避免每条路由重复配置。
- 当前只配置 `user` 服务路由。
- 新增微服务时，让 AI 读取远程 `gateway-spring.yaml` 后追加一条路由并整体发布回 Nacos。
- 常规业务服务使用 `/{service}/** + StripPrefix=1`。
- `user` 服务同时支持旧 `/user/**` 前缀和新 `/auth/**` 直达。
- 旧 `/user/**` 使用 `RewritePath=/user/(?<segment>.*), /${segment}`，保证 `/user/auth/login` 仍可转到后端 `/auth/login`。
- 新 `/auth/**` 不剥离前缀，保证 `/auth/login` 直达后端 `/auth/login`。

## 日志规范

- 仓库不保留 `src/main/resources/logback-spring.xml`。
- 日志配置统一维护在 Nacos 远程 `logging.yml`。
- 不接入 SLS、Loghub 或阿里云日志 appender。
- 不在日志配置中写入 accessKey、secretKey 等凭证。

## OpenAPI 规范

- 本项目不再使用 springfox。
- 后端服务使用 OpenAPI3 `/v3/api-docs`。
- 网关 `/swagger-resources` 只基于 Gateway routes 生成资源列表，不读取业务 Controller。

## 测试规范

- 新增或调整代码后执行 `./gradlew clean test bootJar --no-daemon`。
- 测试包名必须能扫描到 `com.kellen.GatewayApplication`。
- 如测试读取本机 Nacos 配置，需确认 Nacos 已启动并说明依赖。
- 普通单元测试不要默认依赖真实 Redis、Nacos、业务服务；需要外部配置时优先 mock 或使用测试配置。
