# AGENTS.md

本文件是 `gateway` 项目的 AI 编码入口。AI 修改本项目代码、路由或 Nacos 配置前，必须先阅读本文件，再按任务风险阅读 `README.md` 和 `docs/ai-coding` 下的规范。

## 项目定位

- 项目名称：`gateway`
- 项目类型：Spring Cloud Gateway 网关
- 技术栈：Java 17、Spring Boot、Spring Cloud Gateway、OpenAPI、Nacos、Gradle
- 同级服务：`../user`、`../message` 和其它后端服务
- 核心风险：路由误配、请求头透传丢失、跨域放开、文档/监控入口暴露、Nacos 配置漂移、网关错误承担业务鉴权

## 修改前阅读顺序

任何代码或配置修改前必须先阅读：

1. `README.md`：确认当前网关职责、本地地址、路由和验证方式。
2. `docs/ai-coding/README.md`：确认 AI 编码入口和网关必读结论。
3. `docs/ai-coding/AI_CODING_GUIDE.md`：确认执行步骤、禁止事项和验证命令。
4. `docs/ai-coding/AI_DIRECTORY_STRUCTURE_GUIDE.md`：确认 Spring Cloud Gateway 目录、资源、文档和跨项目边界。
5. `docs/ai-coding/AI_COMMENT_STYLE_GUIDE.md`：确认注释规范、自解释优先、禁止注释掉死代码和排版要求。
6. `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md`：确认网关 Route、Predicate、Filter、Nacos 配置和禁止业务鉴权回流规则。
7. `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md`：确认风险分级、Definition of Done 和交付门禁。
8. `docs/ai-coding/VERSIONING_SPEC.md`：确认 `group = 'com'`、`version = '1.0.0'`、补丁递增和消费者同步规则。
9. `docs/ai-coding/GATEWAY_CODING_SPEC.md`：确认网关服务边界、技术基线、认证转发和测试要求。
10. `docs/ai-coding/NACOS_CONFIG_SPEC.md`：修改远程配置前必须阅读。
11. `docs/ai-coding/SECURITY_CODING_SPEC.md`：涉及路由暴露、请求头、跨域、日志、代理转发、监控入口或文档入口时必须阅读。

## 项目边界

- 网关只负责路由、跨域、限流、OpenAPI 文档转发和请求转发。
- 新增路由、过滤器、Nacos 配置处理或 OpenAPI 文档转发能力时，必须优先沿用 `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md` 中的 Gateway Route、Predicate、Filter Chain、Adapter、Configuration Properties 等网关适用模式。
- 网关必须原样透传 `Authorization: Bearer <jwt>` 等必要请求头，由 `user` 和业务服务处理认证授权。
- 网关不实现业务级鉴权、字段级授权、租户隔离、用户身份解析和数据权限。
- 路由配置以 Nacos 远程 `gateway-spring.yaml` 为准，仓库不保留本地配置副本。
- 新增可通过网关访问的 Java 微服务或 OpenAPI 文档入口时，必须同时评估 Nacos 远程 `gateway-spring.yaml` 的业务路由和 `springdoc.swagger-ui.urls` 聚合项；服务未能通过对应网关文档路径验证前，不加入 Swagger UI 下拉。

## AI 工程门禁

- 新增路由、改跨域、改请求头、改文档入口、改监控入口、改 Nacos 配置默认高风险。
- 新增或修改功能前，必须按 `AI_AUTOMATION_WORKFLOW.md` 整理需求说明、验收标准和开发手册。
- 完成后必须按 `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md` 做风险分级、Definition of Done、测试证据、安全检查、风险和回滚说明。
- 修改 Nacos 配置时，必须读回权威配置源再报告结果，不能只根据本地文件或发布命令判断成功。

## 多智能体协作规则

- 子智能体可以并行分析 README、Nacos 规则、路由匹配、后端服务端口、前端请求和错误日志。
- 不允许多个 worker 同时修改同一 Nacos 配置、同一路由规则、跨域配置或网关安全入口。
- 最终路由边界、安全影响和配置读回结论必须由主智能体统一判断。

## 验证命令

基础验证：

```bash
./gradlew clean test bootJar --no-daemon
bash scripts/check-secrets.sh
```

涉及路由或 Nacos 时，还必须用 curl 或等价方式验证网关实际监听端口、目标服务直连地址和 Nacos 配置读回结果。

## 禁止事项

- 禁止在网关恢复旧 Redis token 鉴权、业务权限校验或用户身份解析。
- 禁止为单个接口随意放开 `*` 跨域、凭证跨域、监控入口、文档入口或裸 IP 访问。
- 禁止把 Nacos 地址、账号密码、内网敏感地址、本机路径和临时调试配置写入仓库。
- 禁止 AI 触碰真实密钥/凭证（疑似密钥只能告警，由项目负责人处理）；配置中心结构性调整（dataId 拆分/合并、import 顺序、`${}` 引用、Nacos 接入地址/内网地址、namespace/group）允许 AI 自主完成，但必须保值不改值，不得擅自变更生产业务配置的实际取值。
- 禁止在网关吞掉认证头、租户头、traceId 或其它后端依赖的安全上下文。
