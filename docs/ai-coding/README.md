# Gateway AI 编码规范入口

本目录是 `gateway` 项目的 AI 编码入口。AI 修改网关代码或 Nacos 配置前，先读本目录，再读取 Nacos 远程配置确认当前路由。

## 快速阅读

1. 先读 `AI_CODING_GUIDE.md`，确认执行步骤和禁止事项。
2. 再读 `GATEWAY_CODING_SPEC.md`，确认网关服务边界、技术基线、认证转发和测试要求。
3. 修改 Nacos 配置时读 `NACOS_CONFIG_SPEC.md`，直接读取和更新 Nacos 远程 `gateway-spring.yaml`。
4. 涉及后端认证、统一响应、JWT、权限、多租户等公共能力时，对照同级 `user` 和 `utils` 项目，不在网关重复实现。
5. 修改完成后执行 `./gradlew clean test bootJar --no-daemon`。

## 必读结论

- 网关只负责路由、跨域、限流、Knife4j 网关文档聚合和请求转发。
- 网关不再做旧 Redis `token` 鉴权，不再调用 `user/authUserSystem`，也不解析 `ApiResponse`。
- 网关不做任何 token、header、用户、权限、Actuator 自定义鉴权过滤；安全认证由后端服务和运维侧访问控制处理。
- 新后端认证使用 `Authorization: Bearer <jwt>`，网关必须原样透传请求头，由 `user` 和业务服务的 Spring Security 处理。
- 路由配置只保存在 Nacos；仓库不保留 `gateway-spring.yaml` 本地副本。
- 当前远程配置只有 `user` 项目路由；新增微服务时让 AI 在 Nacos 远程配置里追加一条网关路由。
- Spring Cloud Gateway 4 已不使用 Ribbon；不要恢复旧 `RewriteRoundRobinRule`。
- Springfox 和自写 `/swagger-resources` 聚合已移除；OpenAPI 聚合走官方 Knife4j Gateway Starter，入口是 `/doc.html`。
- 日志配置来自 Nacos 远程 `logging.yml`，仓库不保留本地 `logback-spring.xml`。

## AI 注释要求

- AI 新增或修改 Java 代码时，必须按“每行注释”标准补充中文注释。
- 包声明、import、空行、单独的大括号可以不写无意义注释。
- 字段、常量、Bean、方法、判断条件、返回值、lambda、流式处理步骤和异常分支必须逐行说明用途。
- 不允许只写类注释或方法注释后省略关键代码行注释。

## 目录结构

```text
docs/
  ai-coding/
    README.md
    AI_CODING_GUIDE.md
    GATEWAY_CODING_SPEC.md
    NACOS_CONFIG_SPEC.md
```
