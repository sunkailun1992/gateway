# Gateway Nacos 配置规范

## 配置文件

当前网关核心路由配置在 Nacos：

```text
dataId: gateway-spring.yaml
group: test
namespace: <NACOS_NAMESPACE>
```

仓库不保留本地 `gateway-spring.yaml` 副本。每次修改都以 Nacos 远程配置为准：先读取远程完整内容，修改后整体发布回 Nacos。

## 整理规则

- 使用 `spring.data.redis`，不要使用 Boot 2 旧键 `spring.redis`。
- Redis、Nacos Discovery、Spring Admin、Zipkin 等基础设施地址优先引用 `reuse-configuration.yaml` 的公共变量，不要在 `gateway-spring.yaml` 中新增裸 IP。
- 使用 `spring.cloud.gateway.default-filters` 配置公共 `RequestRateLimiter`。
- 每条路由只保留自己的 `id`、`uri`、`predicates` 和必要的路径改写。
- 服务转发使用 `lb://服务名`。
- 不在 Nacos 路由里写业务权限规则。
- 不在 Nacos 路由里写旧 token 鉴权规则。
- 当前保留 `user` 和 `message` 服务；新增微服务时让 AI 追加对应路由。
- OpenAPI 原始文档通过普通网关路由转发，不维护单独的文档 UI 聚合配置。
- 新增微服务时，只需要保证对应前缀能转发 `/v3/api-docs`。

## 当前 user 路由约定

`user` 后端升级后认证入口是 `/auth/**`：

```yaml
- id: user
  uri: lb://user
  predicates:
    - Path=/user/**,/auth/**
  filters:
    - RewritePath=/user/(?<segment>.*), /${segment}
```

含义：

- `/auth/sessions` 直接转发到 `user` 服务 `/auth/sessions`。
- `/auth/current/resources` 直接转发到 `user` 服务 `/auth/current/resources`。
- `/user/auth/sessions` 兼容外部 user 前缀，转发到 `user` 服务 `/auth/sessions`。
- `/user/v3/api-docs` 转发到 `user` 服务 `/v3/api-docs`，用于读取 OpenAPI 原始文档。

## 当前 message 路由约定

`message` 服务统一使用 `/message/**` 网关前缀：

```yaml
- id: message
  uri: lb://message
  predicates:
    - Path=/message/**
  filters:
    - RewritePath=/message/(?<segment>.*), /${segment}
```

含义：

- `/message/**` 转发到 `message` 服务对应真实路径。
- `/message/v3/api-docs` 转发到 `message` 服务 `/v3/api-docs`，用于读取 OpenAPI 原始文档。

## 当前 AI 路由约定

AI 服务需要同时保留小程序现有真实路径和统一网关前缀：

```yaml
- id: ai-direct
  uri: lb://ai
  predicates:
    - Path=/api/ai/**,/api/v1/files/**,/api/files/**,/api/v1/auth/**,/api/auth/**
- id: ai-prefix
  uri: lb://ai
  predicates:
    - Path=/ai/**
  filters:
    - RewritePath=^/ai/(?<segment>.*), /${segment}
```

含义：

- `/api/ai/**` 转发到 AI 对话和问卷接口，不改写路径。
- `/api/v1/files/**` 和 `/api/files/**` 转发到 AI 文件服务，不改写路径。
- `/api/v1/auth/**` 和 `/api/auth/**` 转发到 AI 微信小程序登录接口，不改写路径。
- `/ai/**` 用作统一前缀，`/ai/v3/api-docs` 转发到 `/v3/api-docs`。

## 当前 OpenAPI 文档转发约定

网关不引入文档聚合 starter，也不维护独立聚合配置。通过普通路由访问各服务 OpenAPI 原始文档：

```text
http://网关地址/user/v3/api-docs
http://网关地址/message/v3/api-docs
http://网关地址/ai/v3/api-docs
```

## 发布后验证

```bash
curl -sS "http://<NACOS_ADDR>/nacos/v1/cs/configs?dataId=gateway-spring.yaml&group=test&tenant=<NACOS_NAMESPACE>"
./gradlew clean test bootJar --no-daemon
```
