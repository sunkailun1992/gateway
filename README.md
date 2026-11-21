# gateway

`gateway` 是基于 Spring Cloud Gateway 的入口网关服务，面向 Java 21 / Spring Boot 4 / Spring Cloud 2025.1.x。承担路由转发、跨域预检、网关限流与 Nacos 配置加载；第5阶段起也是 BFF：浏览器经 Keycloak 登录、会话存 Redis，转发时把会话里的访问令牌换成 Bearer 头。

## 当前定位

- 服务名：`gateway`
- 默认端口：`7100`
- Java 版本：`21`
- Spring Boot：`4.0.9`
- Spring Cloud：`2025.1.4`
- Spring Cloud Alibaba：`2025.1.0.0`
- Nacos Client：`3.3.0`
- Gradle Wrapper：`9.9.1`
- 配置中心：Nacos SDK `<NACOS_SDK_ADDR>`，当前应用端口 `8848`；控制台端口 `18080`
- Nacos namespace ID：`github-dev`

## 服务边界

- 从 Nacos 读取远程网关路由配置。
- 通过 Nacos Discovery 发现后端服务。
- 使用 `lb://service-name` 转发到后端服务。
- 处理浏览器跨域预检请求。
- 使用 `RequestRateLimiter` 做网关级限流。
- 从 Nacos 远程 `logging.yml` 读取日志配置。
- BFF：浏览器经 Keycloak（`github-dev` 域，授权码加 PKCE）登录，会话存 Spring Session（Redis）；转发时 `TokenRelay` 把会话里的访问令牌换成 `Authorization: Bearer`，并去掉 `Cookie` 头，访问令牌到期用刷新令牌续期。
- 脚本与工具直接带 Bearer 访问令牌：网关校验签发方、受众（`github-api`、`github-mcp`，以及 ai 经令牌交换调 ai-agent 用的 `github-ai-agent`：AgentCard 登记的调用地址是网关）、有效期与 RS256 后原样转发；各服务仍只认自己的受众。
- 只拒绝未认证的请求（页面跳转去登录，API 与 XHR 返回 401 JSON）；细粒度授权、租户与数据权限仍在各服务。

## 禁止事项

- 网关不做业务权限、租户或数据范围判断，也不解析业务上下文；认证只用 Spring Security 的标准 OAuth2 组件（登录、资源服务器、TokenRelay）。独立管理监听端口仅为 Prometheus 执行运维令牌校验。
- 不恢复旧 `TokenFilter`、`UserRpc`、`RedisUtils` token 鉴权。
- 不解析、组装或判断业务 `ApiResponse`。
- 不查询 Redis 用户对象或接口权限列表；登录态只存在 Spring Session 的会话里（含 Keycloak 的访问令牌与刷新令牌），不另建令牌存储。
- 不调用 `user/authUserSystem` 做二次鉴权。
- 不恢复 Ribbon 负载均衡规则。
- 不保留本地 `docs/nacos/gateway-spring.yaml`。
- 不保留本地 `src/main/resources/logback-spring.xml`。
- 不接入 SLS、Loghub 或阿里云日志 appender。
- 不复制同级 `../utils` 的工具类源码到本项目。
- 不恢复自写 `/swagger-resources` 聚合 Controller；OpenAPI UI 聚合只使用 Springdoc 官方 Swagger UI，并由 Nacos 配置服务列表。

## 当前路由

网关在路由前固定拦截自身及后端转发路径中的 Actuator 管理端点，返回 404；只保留根路径 `/actuator/health` 与 liveness/readiness 探针。这一层只按路径拦截，登录与令牌由安全链处理，业务鉴权仍由后端负责。独立管理端口只允许探活和带有效 `actuatorReq` 的 Prometheus GET，其余管理入口固定 404，详见 [监控采集与回滚](docs/fleet-observability.md)。Swagger UI / OpenAPI 在 `prod` profile 默认关闭，其它 profile 默认开启，可通过个人 Nacos `gateway.exposure.api-docs-enabled` 显式控制。此过滤不替代来源白名单、CORS 或后端授权。

当前远程 Nacos `gateway-spring.yaml`（模板在 `../utils/ops/github-dev/nacos/DEFAULT_GROUP/gateway-spring.yaml`）的默认过滤器与路由：

```text
default-filters（按顺序作用于全部路由）：
  RequestRateLimiter            Redis 令牌桶限流
  TokenRelay                    会话请求：把会话里的访问令牌换成 Bearer 头（Bearer 请求原样）
  RemoveRequestHeader=Cookie    网关会话 Cookie 不进下游
routes（按 order 匹配，专属路径在 /api/** 兜底前）：
  /auth/**                                         → user
  /user/**                                         → user（StripPrefix=1，文档等兼容前缀）
  /message/**                                      → message（StripPrefix=1）
  /report/**                                       → report（StripPrefix=1）
  /api/mcp/**、/.well-known/oauth-protected-resource/** → mcp
  /api/agents/**                                   → ai-agent
  /api/ai-memory/**                                → ai-memory
  /api/rag/**                                      → rag
  /api/**                                          → ai
```

`/oauth2/**`（登录入口）、`/login/oauth2/code/**`（Keycloak 回调）与 `/logout` 由网关自己处理，不转发；user 的旧登录、OAuth 与发现接口已在第5阶段删除。

## OpenAPI 文档

网关内置 Springdoc 官方 Swagger UI，聚合入口：

```text
http://网关地址/swagger-ui/index.html
```

聚合服务列表放在 Nacos 远程 `gateway-spring.yaml` 的 `springdoc.swagger-ui.urls`，后端服务仍保留标准 OpenAPI3 原始文档，网关负责把服务路径转发到对应服务：

```text
http://网关地址/user/v3/api-docs
http://网关地址/message/v3/api-docs
http://网关地址/report/v3/api-docs
```

新增微服务时，在 Nacos 远程 `gateway-spring.yaml` 同步增加业务路由和 `springdoc.swagger-ui.urls` 条目，不再维护本地聚合配置。

## Nacos 配置

核心路由配置只保存在 Nacos 远程配置中心：

```text
dataId: gateway-spring.yaml
group: DEFAULT_GROUP
namespace: <NACOS_NAMESPACE>
```

网关加载 `reuse-configuration.yaml` 作为基础设施地址统一入口。`gateway-spring.yaml` 中 Redis、Nacos Discovery 等地址只引用公共变量，不直接写裸 IP：

```yaml
spring:
  data:
    redis:
      host: ${custom.infra-host}
  cloud:
    nacos:
      discovery:
        server-addr: ${custom.infra-nacos-addr}
        namespace: ${custom.namespace}
        group: ${custom.nacos-group}
```

读取远程配置：

```bash
curl -sS "http://<NACOS_ADDR>/nacos/v3/client/cs/config?dataId=gateway-spring.yaml&groupName=DEFAULT_GROUP&namespaceId=<NACOS_NAMESPACE>"
```

新增微服务时，先读取远程完整内容，再追加对应业务路由；需要进入 Swagger UI 聚合时，同步追加 `springdoc.swagger-ui.urls`，再通过 Nacos 3.x Admin API 整体发布回 Nacos。不要在仓库里新增本地 `gateway-spring.yaml` 副本。

## 构建验证

```bash
./gradlew clean test bootJar --no-daemon
```

启动本地网关：

在 gateway 仓库根目录启动。默认 profile 为 `dev`，Spring Boot 自动读取本机 `config/application-dev.properties` 的五个 `GITHUB_NACOS_*` 连接键；该文件权限为 `0600`，已忽略，不提交真实地址或密码。无需修改 `application.yml` 或预先 export，OS 环境变量仍优先。IDE working directory 也设为本仓库根目录。按当前开发环境约定，三个 profile 均连接个人 `github-dev`；`test`/`prod` 不读取 dev 私有文件，仍使用相同的 Nacos dev 连接变量与既有默认值，不回落到 localhost。

```bash
./gradlew bootRun
```

或使用构建产物启动：

```bash
java -jar build/libs/gateway.jar
```

### dev 本机灰度入口

IDEA 使用 `dev` 启动时，网关只监听本机回环地址。普通本机请求不必携带灰度授权：网关按共享的
`local-canary.tag` 签一个短时路由授权，转给已注册的灰度实例；`Authorization`、来源租户及其它业务头原样交给后端校验。
共享配置未就绪、签名密钥或标签不合法、缺少映射或灰度实例未启动时直接返回不可用，不回落稳定实例。

默认本机路由只在 `dev` 且未同时启用 `test` / `prod` 时生效，并要求监听端、对端和 `Host` 都是回环地址，
浏览器 `Origin` 如存在也必须是回环来源；带 `Forwarded` / `X-Forwarded-*` 头的请求不签发默认授权。
显式传入的灰度授权仍按原规则校验，错误授权不会被默认授权替换。`test`、`prod` 的路由行为不变。

## 本地访问注意

本机网关默认使用 `7100`；前端与 Nacos 是独立端口。Nacos 控制台的 `18080` 只用于管理，应用连接使用 `8848`，gRPC 使用 `9848`。

```text
http://localhost:7100/actuator/health
http://localhost:7100/message/v3/api-docs
http://localhost:7100/report/v3/api-docs
```

## AI 编码入口

AI 修改本项目时必须先阅读：

1. `AGENTS.md`
2. `docs/ai-coding/README.md`
3. `docs/ai-coding/AI_CODING_GUIDE.md`
4. `docs/ai-coding/GATEWAY_CODING_SPEC.md`
5. `docs/ai-coding/NACOS_CONFIG_SPEC.md`

AI 新增或修改代码时，注释要让人看懂这段代码 AI 为什么这样写、在网关里承担什么职责，不采用机械逐行注释，也不要写过时、凑数或误导的注释；完整规则见 `docs/ai-coding/AI_CODING_GUIDE.md` 的“注释要求”小节。

## 显式 CORS 策略

网关的 `CorsFilter` 统一使用 Spring `CorsWebFilter` / `DefaultCorsProcessor`。`gateway.cors.allowed-origins` 必须逐项列出完整 HTTP(S) origin；默认空列表拒绝跨域，`*`、origin pattern、`null`、路径或嵌入凭据均不接受。无 Origin 与同源请求继续路由，不影响后端业务鉴权或 Authorization 透传。不能仅依赖 `spring.cloud.gateway.*.globalcors` 约束这一入口。

转发响应通过 Gateway 的 `HttpHeadersFilter.Type.RESPONSE` 扩展点移除下游的 `Access-Control-*`，再与网关响应合并，避免登录、租户查询等接口产生重复或冲突的 CORS 标头。后端服务仍可保留自己的直连跨域策略；网关关闭凭证时，下游也不能重新开启。非 CORS 响应标头、响应体及请求认证头保持透传。

可覆盖 `gateway.cors.allowed-methods`、`allowed-headers`、`exposed-headers`、`allow-credentials`、`max-age`。默认方法为 GET/HEAD/POST/PUT/DELETE/PATCH/OPTIONS；默认请求头保留 Authorization、Content-Type、Accept 与现有安全 trace/traffic 头，不允许任意身份头。凭证默认只对显式 origin 开启，可设 `allow-credentials=false`；不支持通配符。默认预检缓存1800秒，允许0–86400秒。非法配置失败关闭，拒绝跨域请求；策略每请求读取当前环境以支持刷新。

`CorsPolicyHttpIntegrationTest` 通过真实随机端口和 stub 后端覆盖 allowlist、未知/null origin、凭证/通配符、方法与请求头限制、同源及普通路由。本轮版本仍为1.0.7。

## HTTP安全日志与全链观测

当前服务版本为 `1.1.7`，精确消费轻量 `com:fleet-observability:0.1.2`，由公共模块为 WebFlux 请求生成安全终态事件。共享日志配置必须同时设置 `logging.structured.json.context.include=false`，由固定安全成员 SPI 合并业务 KVP 与合法 trace/span 身份，避免 MDC 同名键导致 ECS 整条编码失败。未知 MDC、原始日志正文、异常正文和堆栈仍不输出；普通错误仅保留安全 logger/异常类名摘要。HTTP 正文只按路由与类型白名单投影，流式内容默认省略。

该模块仅依赖 JSON、SLF4J 和 OTel API；构建不引入完整 `utils` 或业务存储能力。CI 先独立发布轻量子模块到 Maven Local，再编译网关。固定部署包名仍为 `gateway.jar`，`applicationVersion` 纳入 `bootJar` 输入，版本变化必须重新生成 Manifest。目标环境日志、Trace、HTTP/SSE透传与原生IDE启动需要独立验收。

统一 IDEA／服务器 Jar 启动入口为 gateway 成套配置 `gateway.observability.client-span-enabled=true` 和 `otel.instrumentation.reactor-netty-1.0.enabled=false`，修复固定 agent 2.33.1 在 Gateway 分段响应体取消时遗漏出站 CLIENT 终态的问题。应用开关默认关闭；精确 alias 只停用 Reactor Netty 客户端模块，公共 Instrumenter 与 Reactor Context bridge 抑制底层重复 CLIENT，保留 Netty／WebFlux 自动 SERVER。不得改用 `otel.instrumentation.reactor-netty.enabled=false` 或 `otel.instrumentation.netty.enabled=false`，也不得只应用其中一个开关。日常继续使用原生 Spring Boot 绿叶 Run/Debug，受管入口拒绝 VM、环境变量或业务参数覆盖这组配置。

CLIENT 在真实发送前建立并注入 W3C 身份；POST 的请求头可能由入站 body 回调写出，因此通过官方 `HttpClientCustomizer` 与 `Connection.addHandlerLast`，只在真正 `HttpRequest` 的同步 write 范围恢复该请求的不可变 Context。私有 Reactor key 按请求隔离，handler 写头后自移除，取消前未写头时由框架在终止／池释放时清理；同名未知或其他请求的 handler 一律失败关闭，不读写 agent 内部 Channel 属性。已本地完成／缓存短路的 `alreadyRouted` 请求不建立 CLIENT。span 覆盖响应体写回；正常完成、主动取消和实际上游错误分别记录 success、cancelled、error，HTTP 400–599 也保持 error；只有收到真实上游响应头才记录 CLIENT HTTP 状态码，写头前取消保持未知。正文流的取消单独监听，每次逻辑请求只结束一次；不以外层 Mono 正常结束推定 SSE 已正常完成，不补造旧 trace 的缺失 parent。应用仍不建立 SDK，属性不读取 URL、目标地址、认证头、正文或异常消息。细节、官方选择器依据及回滚合同见 [监控采集与回滚](docs/fleet-observability.md)。

隔离的真实 agent 回归显式使用已有的固定制品，不自动下载，不连接 Nacos、模型或监控后台：

```bash
FLEET_TEST_AGENT=/secure/opentelemetry-javaagent-2.33.1.jar \
FLEET_TEST_PRIVACY=/secure/fleet-privacy-extension-0.1.1.jar \
./gradlew test --tests com.kellen.config.GatewayClientTracingAgentTest --no-daemon
```

本仓 CI 显式从固定公开源码构建隐私扩展、校验固定官方 agent 与两个产物的摘要，再通过上述入口运行全部四个子 JVM 用例；测试本身不下载制品、不读取私有运行配置。未提供路径的本机或外部 CI 明确跳过。用例使用两个真实回环 WebFlux 上下文和已启用的完整 HTTP 日志链，覆盖带 JSON body 的 POST 正常、活动取消、上游中断，以及同池六次复用、十二次并发、写头前取消清理后的正常请求；核对请求／响应字节、认证头透传、实际发送头及 exporter 父子 span。单次 GET 通过不能替代这一合同。测试跳过、隔离验证通过与目标环境整链验收分别报告。

## macOS 原生 DNS 运行依赖

运行制品显式携带 `netty-resolver-dns-native-macos` 的 `osx-aarch_64` classifier，版本由同一 Spring Boot/Netty BOM 管理；现有 Intel classifier 保留。该依赖不按构建宿主筛选，因此 Linux CI 打出的 Jar 也能在 Apple Silicon 上加载对应原生库。Linux 继续使用其正常平台实现，不调整 DNS 服务器、查询或回退策略。构建后应核对实际运行图和 Jar 中版本一致，并在对应架构的全新 JVM 验证原生库可用性；打包成功不代替网络、服务健康或业务验收。

本次精确对齐 `com:fleet-observability:0.1.2`，接入 Servlet ERROR 分派再次拒绝时的唯一 HTTP 终态修复；认证、响应和日志安全字段合同保持。源码与测试通过后，目标进程和追踪/日志联结仍须单独读回验收。

## BFF 登录与令牌转发（1.3.0）

第5阶段（设计见 kellen-workspace 的 `docs/superpowers/specs/2026-11-20-phase5-keycloak-design.md`），实现在 `com.kellen.config.security`，按请求的凭证分两条过滤链：

- Bearer 链：带 `Authorization: Bearer` 的请求按 Keycloak 访问令牌校验，无状态、不建会话、不要 CSRF，通过后原样转发。
- 会话链：其余请求。需要登录的页面跳到 `/oauth2/authorization/keycloak` → Keycloak 登录页 → 回调 `/login/oauth2/code/keycloak`（Keycloak 已登记 7100 与 8000 两个回调）；登录时换新会话 ID。
- 会话 Cookie `__Host-SESSION`：Secure、HttpOnly、SameSite=Lax、Path=/（本机 localhost 属安全上下文）。会话在 Spring Session（Redis 命名空间 `fleet:gateway:session`），已授权客户端（访问令牌、刷新令牌）也存在会话里，网关重启不丢登录。
- CSRF：会话请求的写操作要带 `X-XSRF-TOKEN`，值取自前端可读的 `XSRF-TOKEN` Cookie；Bearer 请求不需要。Swagger UI 开了 `springdoc.swagger-ui.csrf.enabled`，会自动带上。
- 注销：`POST /logout`（带 CSRF 头）作废网关会话（连同会话里的令牌），再跳到 Keycloak 的注销端点结束 SSO 会话（带 `id_token_hint` 与 `post_logout_redirect_uri`，回到发起注销的入口）。XHR 请求（`X-Requested-With: XMLHttpRequest`）返回 202 加 `Location`，由前端自己导航；fetch 跟随跨源跳转会被 CORS 拦下。
- 错误：未登录的页面跳转去登录；API 与 XHR 返回 401 JSON（`A0220`，`WWW-Authenticate: Bearer`）；CSRF 不对或拒绝访问返回 403 JSON（`A0301`）。
- 启动依赖：登录注册用 `issuer-uri`，网关启动时向 Keycloak 取发现文档（RP 发起注销要用其中的 `end_session_endpoint`）；Keycloak 不可达时网关起不来，先确认 NAS 上的 Keycloak 在运行。
- Nacos：`gateway-spring.yaml` 新增 Keycloak 登录注册（客户端密钥是运行时占位符 `GITHUB_KEYCLOAK_GATEWAY_CLIENT_SECRET`）、资源服务器（只收 RS256，受众 `github-api`、`github-mcp`、`github-ai-agent`）、`TokenRelay` 与去 `Cookie` 的默认过滤器、会话命名空间与 Swagger UI 的 CSRF；user 路由只留 `/auth/**`。三套 profile 不再导入 `security-jwt.yaml`。
- 测试：`GatewaySecurityConfigTest` 用 MockWebServer 模拟 Keycloak（发现文档、令牌端点、JWKS）与下游服务，走真实登录（PKCE）、令牌转发、到期并发续期、Bearer 校验（受众、算法）、CSRF 与注销；其余集成测试改为带假 Keycloak 签的令牌。追踪探针的两个最小应用不装安全与会话的自动配置。
- 令牌端点超时（1.3.1）：登录回调用授权码换令牌、TokenRelay 用刷新令牌续期，都经带超时的 WebClient 访问 Keycloak 令牌端点——连接 5 秒、响应 10 秒（`gateway.oauth2.token-endpoint.connect-timeout`、`response-timeout`，与 utils 出站取令牌一致）。Spring Security 缺省的 WebClient 不限响应时间，Keycloak 卡住时登录与续期会一直挂着、占住连接；现在超时即失败，续期失败的请求不转发。`GatewaySecurityConfigTest` 用不回包的令牌端点核对两条路径都在超时内放弃。
- Nacos 账号不带默认值（1.3.2）：去掉 dev、test、prod 三套配置里 Nacos 账号与口令的默认值（原为 `nacos`，即 Nacos 管理员账号）：`custom.nacos-username`、`custom.nacos-password` 只引用 `GITHUB_NACOS_USERNAME`、`GITHUB_NACOS_PASSWORD`，缺值时启动即失败，不再静默以管理员登录（CWE-1392 默认凭据）；值由私有环境提供（IDEA 的 env 文件、Jar 的 application.properties 或容器同名环境变量）。
- 续期失败的出口（1.3.3）：TokenRelay 取不到可用访问令牌时，刷新令牌被 Keycloak 拒绝（空闲或最长期限到期、用户被停用或删除）就作废会话，单页应用的 XHR/API 请求回 401 JSON（A0230 登录已过期）由前端跳登录，整页导航回登录入口；令牌端点不可用（连接失败、超时、连接被断开）回 503 JSON（C0001）并保留会话，Keycloak 恢复后照常续期；Bearer 请求取不到签发方公钥时回 401 JSON（A0220）。三种情况都只记一行警告、不打堆栈（此前为 500 加堆栈，XHR 之后还会被转成跨源 302，前端只见网络错误）。依据 IETF《OAuth 2.0 for Browser-Based Apps》的 BFF 一节：会话令牌失效时 BFF 对 API 请求回 401，由前端发起登录。
