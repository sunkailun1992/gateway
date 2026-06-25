# Nacos 配置中心规范（Fleet 统一）

> 本文件前半是**全 fleet 统一**的 Nacos 配置中心分层与拆分规范（各服务仓库内容一致）；后半「网关专属」是 `gateway` 服务的路由与 `gateway-spring.yaml` 约定。
> Fleet 成员：`ai`、`ai-agent`、`user`、`message`、`gateway`（公共能力由 `utils` 提供）。

## 1. 机制：只用官方主流方式，不自创

- **统一用 Spring Cloud Alibaba 的 `spring.config.import` 导入远程配置**（SCA 2025.x 官方文档主推的多配置导入方式）。
  - 写法：`- "optional:nacos:{dataId}?group={group}&refreshEnabled=true"`。
  - **不使用** `bootstrap.yml` + `spring.cloud.nacos.config.shared-configs/extension-configs` 经典写法，全 fleet 不混用两套机制。
- **dataId 命名**：
  - 服务自身配置：`{spring.application.name}.yaml`（业务）与 `{spring.application.name}-spring.yaml`（Spring 框架/环境）。
  - 共享 / 横切模块：**显式命名 dataId**（如 `redis.yaml`、`aliyun.yaml`、`a2a.yaml`）——显式命名 import 就是官方示例写法，合规。
- **一个 `@ConfigurationProperties` 前缀树只存在于唯一一个 dataId**。禁止把同一前缀树拆到多个 dataId、靠加载顺序“合并”——这是非主流技巧，全 fleet 禁止。
- **共享的“值”用标准 `${占位符}` 引用**（Spring 原生占位符）。禁止裸 IP / 裸密钥散落到各服务文件；基础设施地址一律引用 `reuse-configuration.yaml` 的 `custom.*`。

## 2. 分层：每个 dataId 归属唯一、可解释

| 层 | dataId | group | 内容 |
|---|---|---|---|
| **L0 本地引导** | 各仓库 `src/main/resources/application.yml` | — | 连 Nacos 前必需的最小集：`server.port`、`spring.application.name`、`custom.nacos-*`、`spring.cloud.nacos`、`spring.config.import`。**不放任何业务/密钥**。5 服务此段逐字相同，只差 `port` + `name` + import 列表 |
| **L1 共享基础设施** | `logging.yml` `reuse-configuration.yaml` `traffic-governance.yaml` `redis.yaml` `rabbitmq.yaml` `elasticsearch.yaml` `seata.yaml` `zipkin.yaml` `admin.yaml` `dubbo.yaml` `xxl-job.yaml` `mybatis-plus.yaml` `security-auth.yaml` `swagger.yaml` | DEFAULT_GROUP | fleet 公共基础设施 / 框架配置 |
| **L2 共享横切域** | `aliyun.yaml`（aliyun 账号+OSS+SMS+钉钉+直播+email）、`a2a.yaml`（A2A 共享值） | DEFAULT_GROUP | 多服务共享的第三方/领域配置 |
| **L3 服务业务** | `{svc}.yaml` | DEFAULT_GROUP | 本服务业务键 + 本服务**私有**的 `@ConfigurationProperties` 树（如 `ai` 的 `wechat`、`aliyun.oss` bucket） |
| **L4 服务框架/环境** | `{svc}-spring.yaml` | DEFAULT_GROUP | datasource、profile、discovery、`spring.ai` model、gateway 路由等 Spring/环境配置 |

> 当前 `gateway-spring.yaml` 已归到 `DEFAULT_GROUP`，本地 `spring.config.import` 必须与 Nacos 中的 group 保持一致。

## 3. 各 dataId 内容边界

- **`reuse-configuration.yaml`**：**只放 `custom.*` 公共变量**（基础设施地址 + Nacos 凭据变量，如 `custom.infra-*`、`custom.nacos-username/password/context-path`）。**不放任何业务块或第三方密钥块**。
- **`traffic-governance.yaml`**：统一放灰度发布请求头名、默认 `X-Release-Version`、默认 `X-Traffic-Lane`、实例 `release.version`/`traffic.lane`/`canary.tag`/`traffic.weight` 元数据，以及 Nacos Discovery 元数据。**权重是治理配置和实例元数据，不由公网前端随意决定**；公网前端默认只带发布版本和泳道，灰度 tag/权重必须由受控配置显式开启。
- **`logging/traffic-governance/redis/rabbitmq/elasticsearch/seata/zipkin/admin/dubbo/xxl-job/mybatis-plus/security-auth`**：各对应一组 utils 自动配置 / Spring 体系前缀，整组留在各自 dataId。
- **`swagger.yaml`**：`swagger.enable` + `swagger.name=${spring.application.name}`；要自定义显示名的服务在自己的 `{svc}.yaml` 覆盖一行。
- **`aliyun.yaml`**：整棵 `aliyun`（account key + `oss` + `sms` + `dingding` + `liveStreaming`）+ 顶层 `email`。绑定方为 utils 的 `CommonAliyunProperties(prefix="aliyun")`。**凡使用任一 aliyun 能力、或可能触发 utils `@RequestRequired` 钉钉告警的服务都要 import**。
- **`a2a.yaml`**：A2A 共享值，统一放 `custom.a2a-*`（协议版本、context-path、tenant、provider-org、agent 名契约）+ Nacos 凭据引用。`ai`（消费端 `ai.agent-registry.*`）与 `ai-agent`（生产端 `ai.agent.registry.*`）各自的块**引用** `${custom.a2a-*}`，agent 名两端共用同一变量，保证契约一致。
- **`{svc}.yaml`**：本服务业务键 + 本服务私有 `@ConfigurationProperties` 树。`ai` 私有的 `wechat`（微信小程序，绑定 `CommonWechatProperties(prefix="wechat")`）与 `aliyun.oss` bucket 等收在这里，**不放共享层**。
- **`{svc}-spring.yaml`**：datasource、`spring.profiles`、discovery override、`spring.ai.*` model、gateway 路由。

## 4. import 顺序（所有服务照此排）

```
logging → reuse-configuration → traffic-governance → security-auth(仅鉴权服务) → swagger
→ {svc} → {svc}-spring
→ mybatis-plus → redis → rabbitmq → elasticsearch → seata → zipkin → admin → dubbo → xxl-job
→ aliyun(用到 aliyun/钉钉告警的服务) → a2a(A2A 参与方)
```

后导入的同名键覆盖先导入的；服务私有 `{svc}` / `{svc}-spring` 排在共享基础设施之后，便于在本服务做最终覆盖。

## 5. 各服务 import 清单（按角色裁剪）

| dataId | ai | ai-agent | user | message | gateway |
|---|:--:|:--:|:--:|:--:|:--:|
| logging | ✅ | ✅ | ✅ | ✅ | ✅ |
| reuse-configuration | ✅ | ✅ | ✅ | ✅ | ✅ |
| traffic-governance | ✅ | ✅ | ✅ | ✅ | ✅ |
| security-auth | ❌(自有微信鉴权) | ❌ | ✅ | ✅ | ❌ |
| swagger | ✅ | ✅ | ✅ | ✅ | ✅ |
| {svc} / {svc}-spring | ✅ | ✅ | ✅ | ✅ | ✅ |
| mybatis-plus | ✅ | ❌ | ✅ | ✅ | ❌ |
| redis | ✅ | ❌ | ✅ | ✅ | ✅ |
| rabbitmq | ✅ | ❌ | ✅ | ✅ | ❌ |
| elasticsearch | ✅ | ❌ | ✅ | ✅ | ❌ |
| seata | ✅ | ❌ | ✅ | ✅ | ❌ |
| zipkin | ✅ | ❌ | ✅ | ✅ | ✅ |
| admin | ✅ | ❌ | ✅ | ✅ | ✅ |
| dubbo | ✅ | ✅ | ✅ | ✅ | ❌ |
| xxl-job | ✅ | ❌ | ✅ | ✅ | ❌ |
| aliyun | ✅(OSS) | ⬜验证@RequestRequired | ✅ | ✅ | ⬜验证@RequestRequired |
| a2a | ✅(消费) | ✅(生产) | ❌ | ❌ | ❌ |

> `ai` 故意不导入 `security-auth.yaml`：用自有微信 Bearer 鉴权，fleet JWT 保持关闭。
> `aliyun` 对 `ai-agent`/`gateway` 标“验证”：取决于该服务是否使用 `@RequestRequired`（utils AOP 触发钉钉告警）或任何 aliyun 能力；用到才 import。

## 6. 改配置铁律

1. **读回验证**：改前读回 Nacos 权威配置，改后整体发布并再次读回校验；不靠本地文件或发布命令判断成功。
2. **保值不改值**：本规范是布局搬迁，**逐键保留原值**。`reuse-configuration` 的既有 `custom.*` 只增不改不删；utils `@Value` 消费的 `mysql/mysql-gray` 等绝不动。
3. **AI 自主边界**：配置中心的**结构性调整允许 AI 自主完成**——新增/拆分/合并 dataId、调整 import 与顺序、改 `${}` 引用、调整 Nacos 接入地址 / namespace / group。但**禁止触碰真实密钥/凭证**（access-key/secret、app-secret、API key、DB 密码、token、license 等，疑似密钥只告警、由负责人处理），且布局搬迁必须**保值不改值**，不得擅自变更生产业务配置的实际取值。真实密钥/地址只在 Nacos，仓库与 Nacos 模板只放占位符。
4. **加法优先迁移**：先建新 dataId（与旧块双份共存，零影响）→ 各服务切 import + 改引用 → 逐个重启读回验证 → **最后一步**才从 `reuse-configuration` 删旧块。
5. **新增微服务**：复制 `application.yml` 模板，仅改 `server.port` + `spring.application.name` + 按角色裁剪 import 列表，禁止重写引导段结构。若新服务需要通过网关访问或进入 Swagger UI 聚合，必须同步读取并整体更新 Nacos `gateway-spring.yaml`（`DEFAULT_GROUP`）：补 `spring.cloud.gateway.server.webflux.routes` 业务路由和 `springdoc.swagger-ui.urls` 聚合项；发布后读回并验证对应网关文档路径（例如 `/<service>/v3/api-docs`）、`/v3/api-docs/swagger-config` 与 `/swagger-ui/index.html`。

---

# 网关专属：路由与 gateway-spring.yaml

## 配置文件

当前网关核心路由配置在 Nacos：

```text
dataId: gateway-spring.yaml
group: DEFAULT_GROUP
namespace: <NACOS_NAMESPACE>
```

仓库不保留本地 `gateway-spring.yaml` 副本。每次修改都以 Nacos 远程配置为准：先读取远程完整内容，修改后整体发布回 Nacos。

## 整理规则

- 使用 `spring.data.redis`，不要使用 Boot 2 旧键 `spring.redis`。
- Redis、Nacos Discovery、Spring Admin、Zipkin 等基础设施地址优先引用 `reuse-configuration.yaml` 的公共变量，不要在 `gateway-spring.yaml` 中新增裸 IP。
- 使用 `spring.cloud.gateway.server.webflux.default-filters` 配置公共 `RequestRateLimiter`。
- Spring Cloud Gateway 5 的路由前缀是 `spring.cloud.gateway.server.webflux.routes`；旧 `spring.cloud.gateway.routes` 不再作为有效配置使用，整理远程配置时必须替换成新前缀。
- 每条路由只保留自己的 `id`、`uri`、`predicates` 和必要的路径改写。
- 服务转发使用 `lb://服务名`。
- 不在 Nacos 路由里写业务权限规则。
- 不在 Nacos 路由里写旧 token 鉴权规则。
- 当前保留 `user` 和 `message` 服务；新增微服务时让 AI 同步评估业务路由和 Swagger UI 聚合项。
- OpenAPI 原始文档通过普通网关路由转发；Swagger UI 下拉列表只维护在 Nacos `gateway-spring.yaml` 的 `springdoc.swagger-ui.urls`。
- 新增微服务时，必须保证对应前缀能转发 `/v3/api-docs`，需要聚合时还要同步补 `springdoc.swagger-ui.urls` 并验证 `/v3/api-docs/swagger-config`。

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

网关使用 Springdoc 官方 Swagger UI 做文档聚合；聚合服务列表只维护在 Nacos 远程 `gateway-spring.yaml` 的 `springdoc.swagger-ui.urls`，仓库不保留本地聚合配置副本。Swagger UI 入口：

```text
http://网关地址/swagger-ui/index.html
```

各服务 OpenAPI 原始文档仍通过普通路由访问：

```text
http://网关地址/user/v3/api-docs
http://网关地址/message/v3/api-docs
http://网关地址/ai/v3/api-docs
```

## 发布后验证

```bash
curl -sS "http://<NACOS_ADDR>/nacos/v3/client/cs/config?dataId=gateway-spring.yaml&groupName=DEFAULT_GROUP&namespaceId=<NACOS_NAMESPACE>"
./gradlew clean test bootJar --no-daemon
```
