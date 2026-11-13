# gateway

`gateway` 是基于 Spring Cloud Gateway 的入口网关服务，面向 Java 17 / Spring Boot 4 / Spring Cloud 2025.1.x。当前只承担路由转发、跨域预检、网关限流和 Nacos 配置加载职责。

## 当前定位

- 服务名：`gateway`
- 默认端口：`7100`
- Java 版本：`17`
- Spring Boot：`4.0.4`
- Spring Cloud：`2025.1.1`
- Spring Cloud Alibaba：`2025.1.0.0`
- Nacos Client：`3.2.2`
- Gradle Wrapper：`8.14.3`
- 配置中心：Nacos SDK `<NACOS_SDK_ADDR>`，当前应用端口 `8848`；控制台端口 `18080`
- Nacos namespace ID：`github-dev`

## 服务边界

- 从 Nacos 读取远程网关路由配置。
- 通过 Nacos Discovery 发现后端服务。
- 使用 `lb://service-name` 转发到后端服务。
- 处理浏览器跨域预检请求。
- 使用 `RequestRateLimiter` 做网关级限流。
- 从 Nacos 远程 `logging.yml` 读取日志配置。

## 禁止事项

- 网关不做业务 token、用户或权限鉴权；独立管理监听端口仅为 Prometheus 执行运维令牌校验。
- 不恢复旧 `TokenFilter`、`UserRpc`、`RedisUtils` token 鉴权。
- 不解析、组装或判断业务 `ApiResponse`。
- 不查询 Redis 用户对象、接口权限列表或登录态。
- 不调用 `user/authUserSystem` 做二次鉴权。
- 不恢复 Ribbon 负载均衡规则。
- 不保留本地 `docs/nacos/gateway-spring.yaml`。
- 不保留本地 `src/main/resources/logback-spring.xml`。
- 不接入 SLS、Loghub 或阿里云日志 appender。
- 不复制同级 `../utils` 的工具类源码到本项目。
- 不恢复自写 `/swagger-resources` 聚合 Controller；OpenAPI UI 聚合只使用 Springdoc 官方 Swagger UI，并由 Nacos 配置服务列表。

## 当前路由

网关在路由前固定拦截自身及后端转发路径中的 Actuator 管理端点，返回 404；只保留根路径 `/actuator/health` 与 liveness/readiness 探针。公网规则不读取身份、权限或管理令牌，业务鉴权仍由后端负责。独立管理端口只允许探活和带有效 `actuatorReq` 的 Prometheus GET，其余管理入口固定 404，详见 [监控采集与回滚](docs/fleet-observability.md)。Swagger UI / OpenAPI 在 `prod` profile 默认关闭，其它 profile 默认开启，可通过个人 Nacos `gateway.exposure.api-docs-enabled` 显式控制。此过滤不替代来源白名单、CORS 或后端授权。

当前远程 Nacos `gateway-spring.yaml` 维护 `user`、`message`、`ai` 和 `report` 服务路由：

```yaml
- id: user
  uri: lb://user
  predicates:
    - Path=/user/**,/auth/**
  filters:
    - RewritePath=/user/(?<segment>.*), /${segment}
- id: message
  uri: lb://message
  predicates:
    - Path=/message/**
  filters:
    - RewritePath=/message/(?<segment>.*), /${segment}
- id: ai
  uri: lb://ai
  predicates:
    - Path=/api/**
- id: report
  uri: lb://report
  predicates:
    - Path=/report/**
  filters:
    - RewritePath=/report/(?<segment>.*), /${segment}
```

路由含义：

- `/auth/sessions` 直接转发到 `user` 服务 `/auth/sessions`。
- `/auth/current/resources` 直接转发到 `user` 服务 `/auth/current/resources`。
- `/user/auth/sessions` 兼容外部 user 前缀，转发到 `user` 服务 `/auth/sessions`。
- `/user/v3/api-docs` 转发到 `user` 服务 `/v3/api-docs`，用于文档聚合。
- `/message/**` 转发到 `message` 服务对应路径，例如 `/message/v3/api-docs` 转发到 `/v3/api-docs`。
- `/api/**` 保持现有路径不变，直接转发到 `ai` 服务。
- `/report/**` 转发到 `report` 服务对应路径，例如 `/report/v3/api-docs` 转发到 `/v3/api-docs`。

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
