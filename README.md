# gateway

`gateway` 是基于 Spring Cloud Gateway 4 的入口网关服务，面向 Java 17 / Spring Boot 3.2.4 / Spring Cloud 2023.0.1。当前只承担路由转发、跨域预检、网关限流、Knife4j 网关文档聚合和 Nacos 配置加载职责。

## 当前定位

- 服务名：`gateway`
- 默认端口：`8888`
- Java 版本：`17`
- Spring Boot：`3.2.4`
- Spring Cloud：`2023.0.1`
- Spring Cloud Alibaba：`2023.0.1.0`
- Gradle Wrapper：`8.5`
- 配置中心：Nacos `<NACOS_ADDR>`
- Nacos namespace：`<NACOS_NAMESPACE>`

## 服务边界

- 从 Nacos 读取远程网关路由配置。
- 通过 Nacos Discovery 发现后端服务。
- 使用 `lb://service-name` 转发到后端服务。
- 处理浏览器跨域预检请求。
- 使用 `RequestRateLimiter` 做网关级限流。
- 通过 Knife4j Gateway Starter 聚合 OpenAPI3 文档。
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
- 不恢复自写 `/swagger-resources` 聚合 Controller。

## 当前路由

当前远程 Nacos `gateway-spring.yaml` 维护 `user` 和 `message` 服务路由：

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
```

路由含义：

- `/auth/sessions` 直接转发到 `user` 服务 `/auth/sessions`。
- `/auth/current/resources` 直接转发到 `user` 服务 `/auth/current/resources`。
- `/user/auth/sessions` 兼容外部 user 前缀，转发到 `user` 服务 `/auth/sessions`。
- `/user/v3/api-docs` 转发到 `user` 服务 `/v3/api-docs`，用于文档聚合。
- `/message/**` 转发到 `message` 服务对应路径，例如 `/message/v3/api-docs` 转发到 `/v3/api-docs`。

## Knife4j 聚合

网关使用官方 `knife4j-gateway-spring-boot-starter` 聚合微服务文档，访问地址：

```text
http://网关地址/doc.html
```

当前采用手动聚合模式：

```yaml
knife4j:
  gateway:
    enabled: true
    strategy: manual
    tags-sorter: order
    operations-sorter: order
    routes:
      - name: 用户服务
        service-name: user
        url: /user/v3/api-docs?group=default
        context-path: /user
        order: 1
      - name: 消息服务
        service-name: message
        url: /message/v3/api-docs?group=default
        context-path: /message
        order: 2
```

新增微服务时，在 Nacos 远程 `gateway-spring.yaml` 的 `spring.cloud.gateway.routes` 增加业务路由，同时在 `knife4j.gateway.routes` 增加对应文档路由。

## Nacos 配置

核心路由配置只保存在 Nacos 远程配置中心：

```text
dataId: gateway-spring.yaml
group: test
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
curl -sS "http://<NACOS_ADDR>/nacos/v1/cs/configs?dataId=gateway-spring.yaml&group=test&tenant=<NACOS_NAMESPACE>"
```

新增微服务时，先读取远程完整内容，再追加对应路由并整体发布回 Nacos。不要在仓库里新增本地 `gateway-spring.yaml` 副本。

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
http://192.168.101.141:8888/message/doc.html
```

## AI 编码入口

AI 修改本项目时必须先阅读：

1. `docs/ai-coding/README.md`
2. `docs/ai-coding/AI_CODING_GUIDE.md`
3. `docs/ai-coding/GATEWAY_CODING_SPEC.md`
4. `docs/ai-coding/NACOS_CONFIG_SPEC.md`

AI 新增或修改 Java 代码时，必须按项目要求补充中文逐行注释；包声明、import、空行和单独的大括号不需要写无意义注释。
