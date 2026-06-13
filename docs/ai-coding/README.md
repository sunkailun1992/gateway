# Gateway AI 编码规范入口

本目录是 `gateway` 项目的 AI 编码入口。AI 修改网关代码或 Nacos 配置前，先读本目录，再读取 Nacos 远程配置确认当前路由。

## 快速阅读

1. 先读 `AI_CODING_GUIDE.md`，确认执行步骤和禁止事项。
2. 再读 `AI_AUTOMATION_WORKFLOW.md`，按需求说明、验收标准、开发手册、测试说明和交付说明组织自动化开发。
3. 再读 `AI_ENGINEERING_GUARDRAILS.md`，确认风险分级、Definition of Done、测试门禁、安全门禁和交付说明。
4. 再读 `GATEWAY_CODING_SPEC.md`，确认网关服务边界、技术基线、认证转发和测试要求。
5. 涉及路由暴露、请求头透传、跨域、日志、代理转发、监控入口或文档入口时，读 `SECURITY_CODING_SPEC.md`。
6. 修改 Nacos 配置时读 `NACOS_CONFIG_SPEC.md`，直接读取和更新 Nacos 远程 `gateway-spring.yaml`。
7. 涉及后端认证、统一响应、JWT、权限、多租户等公共能力时，对照同级 `user` 和 `utils` 项目，不在网关重复实现。
8. 修改完成后执行 `./gradlew clean test bootJar --no-daemon`。

## 必读结论

- 网关只负责路由、跨域、限流、Knife4j 网关文档聚合和请求转发。
- 网关不再做旧 Redis `token` 鉴权，不再调用 `user/authUserSystem`，也不解析 `ApiResponse`。
- 网关不做任何 token、header、用户、权限、Actuator 自定义鉴权过滤；安全认证由后端服务和运维侧访问控制处理。
- 安全规则独立维护在 `SECURITY_CODING_SPEC.md`，新增路由或改网关配置时必须同步检查接口鉴权透传、数据脱敏、水平越权路径绕过、文件遍历、退出清理 token 边界、XSS 跨站脚本、SQL 注入参数透传、文件上传校验、CSRF、SSRF、限流资源消耗、安全响应头、供应链、配置安全、异常失败关闭、安全日志告警、开放重定向和运维入口访问控制。
- 新后端认证使用 `Authorization: Bearer <jwt>`，网关必须原样透传请求头，由 `user` 和业务服务的 Spring Security 处理。
- 路由配置只保存在 Nacos；仓库不保留 `gateway-spring.yaml` 本地副本。
- 当前远程配置已有 `user` 和 `message` 项目路由；新增微服务时让 AI 在 Nacos 远程配置里追加一条网关路由。
- Spring Cloud Gateway 4 已不使用 Ribbon；不要恢复旧 `RewriteRoundRobinRule`。
- Springfox 和自写 `/swagger-resources` 聚合已移除；OpenAPI 聚合走官方 Knife4j Gateway Starter，入口是 `/doc.html`。
- 日志配置来自 Nacos 远程 `logging.yml`，仓库不保留本地 `logback-spring.xml`。
- AI 新增或修改 README、AI 规范、配置、脚本、测试、示例和代码时，禁止写入个人电脑绝对路径、本机下载目录、本机 JDK 路径或本机仓库完整路径；需要表达目录关系时使用相对路径、环境变量或 `<PLACEHOLDER>` 占位符。
- AI 开始修改网关或配置前必须按 `AI_AUTOMATION_WORKFLOW.md` 先整理需求说明、验收标准和开发手册；小改动可以简化输出，但检查项不能跳过。
- AI 完成网关或配置修改后必须按 `AI_ENGINEERING_GUARDRAILS.md` 做风险分级、Definition of Done、测试证据、安全检查、风险和回滚说明。

## AI 注释要求

- 注释要让接手的人和下一个 AI 看懂这段代码 AI 为什么这样写、在网关里承担什么职责，不采用机械“每行尾部注释”，能从代码一眼看懂的语法不必注释。
- 禁止重复语法、过时、凑数，或误导网关职责/安全边界的注释。
- 完整规则见 `AI_CODING_GUIDE.md` 的“注释要求”小节。

## 目录结构

```text
docs/
  ai-coding/
    README.md
    AI_CODING_GUIDE.md
    AI_AUTOMATION_WORKFLOW.md
    AI_ENGINEERING_GUARDRAILS.md
    GATEWAY_CODING_SPEC.md
    SECURITY_CODING_SPEC.md
    NACOS_CONFIG_SPEC.md
```
