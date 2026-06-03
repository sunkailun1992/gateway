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
- 当前只保留 `user` 服务；新增微服务时让 AI 追加对应路由。
- Knife4j 聚合配置和网关路由一起维护在远程 `gateway-spring.yaml`。
- 新增微服务时，同步追加 `knife4j.gateway.routes` 文档路由。

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

## 当前 Knife4j 聚合约定

网关使用官方 `knife4j-gateway-spring-boot-starter`，当前为手动聚合模式：

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
```

访问入口：

```text
http://网关地址/doc.html
```

## 发布后验证

```bash
curl -sS "http://<NACOS_ADDR>/nacos/v1/cs/configs?dataId=gateway-spring.yaml&group=test&tenant=<NACOS_NAMESPACE>"
./gradlew clean test bootJar --no-daemon
```
