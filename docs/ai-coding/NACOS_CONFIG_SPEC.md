# Gateway Nacos 配置规范

## 配置文件

当前网关核心路由配置在 Nacos：

```text
dataId: gateway-spring.yaml
group: test
namespace: cfbf4c42-5ebb-4566-a095-30a568556a85
```

仓库不保留本地 `gateway-spring.yaml` 副本。每次修改都以 Nacos 远程配置为准：先读取远程完整内容，修改后整体发布回 Nacos。

## 整理规则

- 使用 `spring.data.redis`，不要使用 Boot 2 旧键 `spring.redis`。
- 使用 `spring.cloud.gateway.default-filters` 配置公共 `RequestRateLimiter`。
- 每条路由只保留自己的 `id`、`uri`、`predicates` 和必要的路径改写。
- 服务转发使用 `lb://服务名`。
- 不在 Nacos 路由里写业务权限规则。
- 不在 Nacos 路由里写旧 token 鉴权规则。
- 当前只保留 `user` 服务；新增微服务时让 AI 追加对应路由。

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

- `/auth/login` 直接转发到 `user` 服务 `/auth/login`。
- `/auth/resources` 直接转发到 `user` 服务 `/auth/resources`。
- `/user/auth/login` 兼容旧外部前缀，转发到 `user` 服务 `/auth/login`。
- `/user/v3/api-docs` 转发到 `user` 服务 `/v3/api-docs`，用于文档聚合。

## 发布后验证

```bash
curl -sS "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=gateway-spring.yaml&group=test&tenant=cfbf4c42-5ebb-4566-a095-30a568556a85"
./gradlew clean test bootJar --no-daemon
```
