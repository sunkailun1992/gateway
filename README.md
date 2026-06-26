# gateway

`gateway` 是基于 Spring Cloud Gateway 的入口网关服务，面向 Java 17 / Spring Boot 4 / Spring Cloud 2025.1.x。当前只承担路由转发、跨域预检、网关限流和 Nacos 配置加载职责。

## 当前定位

- 服务名：`gateway`
- 默认端口：`8888`
- Java 版本：`17`
- Spring Boot：`4.0.4`
- Spring Cloud：`2025.1.1`
- Spring Cloud Alibaba：`2025.1.0.0`
- Nacos Client：`3.2.2`
- Gradle Wrapper：`8.5`
- 配置中心：Nacos `<NACOS_ADDR>`
- Nacos namespace：`<NACOS_NAMESPACE>`

## 服务边界

- 从 Nacos 读取远程网关路由配置。
- 通过 Nacos Discovery 发现后端服务。
- 使用 `lb://service-name` 转发到后端服务。
- 处理浏览器跨域预检请求。
- 使用 `RequestRateLimiter` 做网关级限流。
- 从 Nacos 远程 `logging.yml` 读取日志配置。

## 禁止事项

- 不在网关做 token、header、用户、权限或 Actuator 自定义鉴权过滤。
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
- `/api/**` 保持 AI 小程序现有路径不变，直接转发到 `ai` 服务。
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

```bash
./gradlew bootRun
```

或使用构建产物启动：

```bash
java -jar build/libs/gateway-1.0.0.jar
```

## 本地访问注意

如果本机 `127.0.0.1:8888` 被 SSH 隧道或其他进程占用，使用本机局域网 IP 访问网关，例如：

```text
http://192.168.101.141:8888/auth/sessions
http://192.168.101.141:8888/message/v3/api-docs
http://192.168.101.141:8888/report/v3/api-docs
```

## AI 编码入口

AI 修改本项目时必须先阅读：

1. `AGENTS.md`
2. `docs/ai-coding/README.md`
3. `docs/ai-coding/AI_CODING_GUIDE.md`
4. `docs/ai-coding/GATEWAY_CODING_SPEC.md`
5. `docs/ai-coding/NACOS_CONFIG_SPEC.md`

AI 新增或修改代码时，注释要让人看懂这段代码 AI 为什么这样写、在网关里承担什么职责，不采用机械逐行注释，也不要写过时、凑数或误导的注释；完整规则见 `docs/ai-coding/AI_CODING_GUIDE.md` 的“注释要求”小节。
