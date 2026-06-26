# Gateway 编码规范

## 技术基线

- Java 17
- Spring Boot 4.0.4
- Spring Cloud 2025.1.1
- Spring Cloud Alibaba 2025.1.0.0
- Nacos Client 3.2.2
- Spring Cloud Gateway 4
- Gradle Wrapper 8.5

## 服务边界

`gateway` 是入口网关，只承担基础流量职责：

- 从 Nacos 读取网关路由。
- 使用 Nacos Discovery 做服务发现。
- 通过 `lb://service-name` 转发到后端服务。
- 处理跨域预检。
- 使用 `RequestRateLimiter` 做网关级限流。
- 使用 OpenAPI Starter 聚合 OpenAPI3 文档。
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

- 注释的目的是让人和下一个 AI 看懂这段代码 AI 为什么这样写、在网关里承担什么职责、守住哪条安全边界，不采用机械“每行尾部注释”，能从代码一眼看懂的语法不必注释。
- 优先用方法级 JavaDoc、配置块前置注释解释路由、跨域、请求头透传、限流等改动的网关职责和约束。
- 禁止重复语法的注释、过时注释、凑数空话，以及只把方法名翻译一遍的无信息注释；写错网关职责或安全边界结论的注释宁可不写。
- 完整注释规范见 `AI_CODING_GUIDE.md` 的“注释要求”小节。

## 路由规范

- Nacos 路由配置只保存在远程配置中心，不在仓库保留本地 YAML 副本。
- 公共限流放到 `spring.cloud.gateway.server.webflux.default-filters`，避免每条路由重复配置。
- 当前配置 `user`、`message`、`ai` 和 `report` 服务路由。
- 新增微服务时，让 AI 读取远程 `gateway-spring.yaml` 后追加一条路由并整体发布回 Nacos。
- 常规业务服务使用 `/{service}/**` 网关前缀，并按当前远端配置选择稳定的路径改写方式；现有 `message` 和 `report` 使用 `RewritePath`。
- `user` 服务同时支持旧 `/user/**` 前缀和新 `/auth/**` 直达。
- 旧 `/user/**` 使用 `RewritePath=/user/(?<segment>.*), /${segment}`，保证 `/user/auth/sessions` 仍可转到后端 `/auth/sessions`。
- 新 `/auth/**` 不剥离前缀，保证 `/auth/sessions` 直达后端 `/auth/sessions`。
- `message` 服务使用 `/message/**` 前缀，并通过 `RewritePath=/message/(?<segment>.*), /${segment}` 转发到后端真实路径。
- `ai` 服务当前保留 `/api/**` 直达路由，不改写路径。
- `report` 服务使用 `/report/**` 前缀，并通过 `RewritePath=/report/(?<segment>.*), /${segment}` 转发到后端真实路径。

## 日志规范

- 仓库不保留 `src/main/resources/logback-spring.xml`。
- 日志配置统一维护在 Nacos 远程 `logging.yml`。
- 不接入 SLS、Loghub 或阿里云日志 appender。
- 不在日志配置中写入 accessKey、secretKey 等凭证。

## 路径与本机环境规范

- README、AI 规范、YAML、properties、脚本、测试、示例和 Java 代码中不得写入个人电脑绝对路径、下载目录、IDE 路径、JDK 安装路径或本机仓库完整路径。
- 需要描述同级仓库时，使用 `../user`、`../message`、`../report`、`../utils` 这类相对路径，不使用开发者机器上的完整目录。
- 需要描述可变安装目录、日志目录、临时目录或 JDK 路径时，使用环境变量、Nacos 配置、`~` 用户目录、`${user.home}`、`${java.io.tmpdir}` 或 `<PLACEHOLDER>` 占位符。
- 网关路由和基础设施地址优先使用 Nacos 公共配置变量；本地私有路径不得提交到仓库。
- 提交前必须使用 `rg` 搜索本机用户名、用户目录、仓库根目录和系统盘路径关键字，检查是否残留本机路径。

## OpenAPI 规范

- 本项目不再使用 springfox。
- 后端服务使用 OpenAPI3 `/v3/api-docs`。
- 网关不再维护自写 `/swagger-resources` Controller。
- 网关使用 Springdoc 官方 Swagger UI 做文档聚合，聚合服务列表只放 Nacos `gateway-spring.yaml`，不放本地配置副本。
- Swagger UI 统一访问入口是 `/swagger-ui/index.html`。
- 各服务原始 OpenAPI 入口仍是 `/v3/api-docs`。
- 当前 `user` 服务文档地址配置为 `/user/v3/api-docs`。
- 当前 `message` 服务文档地址配置为 `/message/v3/api-docs`。
- 当前 `report` 服务文档地址配置为 `/report/v3/api-docs`。
- 新增微服务时，同步在 Nacos 远程 `gateway-spring.yaml` 维护 `spring.cloud.gateway.server.webflux.routes` 和 `springdoc.swagger-ui.urls`，保证服务前缀能转发 `/v3/api-docs` 且 Swagger UI 下拉项可用。

## 测试规范

- 新增或调整代码后执行 `./gradlew clean test bootJar --no-daemon`。
- 测试包名必须能扫描到 `com.kellen.GatewayApplication`。
- 如测试读取本机 Nacos 配置，需确认 Nacos 已启动并说明依赖。
- 普通单元测试不要默认依赖真实 Redis、Nacos、业务服务；需要外部配置时优先 mock 或使用测试配置。
