# AGENTS.md

本文件是 `gateway` 项目的 AI 编码入口。AI 修改本项目代码、路由或 Nacos 配置前，必须先阅读本文件，再按任务风险阅读 `README.md` 和 `docs/ai-coding` 下的规范。

## Nacos 配置注释要求

- 新增或修改 Nacos 配置、模板、引导配置及渲染/发布工具前，必须阅读 [Nacos 注释与归类规范](docs/ai-coding/NACOS_COMMENT_SPEC.md)。
- 每个 dataId 写明职责、环境归属、消费者；每个可调参数写明用途、必要单位、格式、空值及开关影响。YAML 使用 `#` 注释，渲染结果必须保留说明。
- 注释整理保持键、类型、值、占位符和列表/import 顺序不变；发布前读回权威配置并备份，使用旧版本比对/CAS，发布后逐项读回。真实凭据不得进入 Git 或注释。
- 只处理明确授权的仓库及环境；Yudao 项目排除，不随本规范同步修改。

## Git 分支、完整交付与清理

- 长期分支只保留 `main`（正式主干），禁止 force push 和删除，不再创建 `test`、`preprod`。新任务 fetch 后从最新 `origin/main` 的确定 SHA 建独立短分支，续作可复用已核实归属的本功能分支，保留其他会话与无关 dirty。
- 完整功能包含必要代码、测试、文档、migration、公共契约和依赖。候选按最新 main 加完整功能及必需跨仓配套验证，不逐提交拼装常规交付。
- 完整功能合入 main、远端 refs 和包含关系核实后，清理本次已结束的本地及远端短分支。未合入、用途不明或被其他 worktree 占用的分支不能删；`main` 永久排除在短分支清理外。详情见 `docs/ai-coding/BRANCHING_SPEC.md`。
- Git 分支不自动切换 profile 或触发部署。现有 APP_ENV=dev、github-dev 与服务根 config/application-dev.properties 模式保持；base application.yml 不改写，未来环境映射和部署另行配置与验收。
- 按风险执行编译、测试、安全专项检查及必要独立验证；Review 是补充，不新增人工代码审批。只按已有用户授权执行提交、推送、合并、tag、制品和部署；纯规范不升业务版本或重复发布 Maven 制品。
- 维护者本人，以及维护者本机用维护者账号运行的 Claude / Codex 会话，可以直接合并到 `main`：合并前本机验证命令全过，合并后看 CI，红了马上修。本仓库 push 目前不会自动触发 CI，推送 `main` 后手动执行 `gh workflow run CI --repo sunkailun1992/gateway --ref main`，确认新 run 的 `headSha` 等于推送的提交再读结论。
- 云端会话（`CLAUDE_CODE_REMOTE=true`，或会话里没有本机配置）只推会话分支，由维护者或本机会话合并。其他人的代码走 PR：CI 全绿、维护者审核批准、评审对话解决后再合并。代理不修改 ruleset 与仓库权限，除非用户当场明确要求。

## 项目定位

- 项目名称：`gateway`
- 项目类型：Spring Cloud Gateway 网关
- 技术栈：Java 21、Spring Boot、Spring Cloud Gateway、OpenAPI、Nacos、Gradle、`com:rpc-api`
- 同级服务：`../user`、`../message`、`../ai`、`../report` 和其它后端服务
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
8. `docs/ai-coding/BRANCHING_SPEC.md`：确认分支命名、短分支生命周期、release/hotfix、tag 和清理规则。
9. `docs/ai-coding/ENVIRONMENT_CONFIG_SPEC.md`：确认环境、Nacos namespace、Java profile 和前端边界。
10. `docs/ai-coding/VERSIONING_SPEC.md`：确认 `group = 'com'`、`version = '1.0.0'`、补丁递增和消费者同步规则。
11. `docs/ai-coding/RPC_API_CODING_SPEC.md`：确认网关与 Dubbo RPC 契约、`../rpc-api` 和 `../utils` 的边界。
12. `docs/ai-coding/TESTING_SPEC.md`：确认网关 SpringBootTest、WebTestClient、stub backend 和 E2E 测试边界。
13. `docs/ai-coding/GATEWAY_CODING_SPEC.md`：确认网关服务边界、技术基线、认证转发和测试要求。
14. `docs/ai-coding/NACOS_CONFIG_SPEC.md`：修改远程配置前必须阅读。
15. `docs/ai-coding/SECURITY_CODING_SPEC.md`：涉及路由暴露、请求头、跨域、日志、代理转发、监控入口或文档入口时必须阅读。

## 项目边界

- 网关只负责路由、跨域、限流、OpenAPI 文档转发和请求转发。
- 新增路由、过滤器、Nacos 配置处理或 OpenAPI 文档转发能力时，必须优先沿用 `docs/ai-coding/AI_DESIGN_PATTERN_GUIDE.md` 中的 Gateway Route、Predicate、Filter Chain、Adapter、Configuration Properties 等网关适用模式。
- 网关必须原样透传 `Authorization: Bearer <jwt>` 等必要请求头，由 `user` 和业务服务处理认证授权。
- 网关不实现业务级鉴权、字段级授权、租户隔离、用户身份解析和数据权限。
- 网关不实现 Dubbo provider/consumer 业务编排；跨服务 RPC 契约统一在同级 `../rpc-api`，网关只在需要外部 HTTP 暴露时维护路由。
- 网关不维护业务库 DDL；如果后端 PostgreSQL 业务库因 Seata AT 提示 `undo_log table not exist` 启动失败，按目标库手动执行 `../utils/src/main/resources/db/postgresql/common-infra-schema.sql`，不要在网关仓库新增 SQL 副本。
- 路由配置以 Nacos 远程 `gateway-spring.yaml` 为准，仓库不保留本地配置副本。
- 新增可通过网关访问的 Java 微服务或 OpenAPI 文档入口时，必须同时评估 Nacos 远程 `gateway-spring.yaml` 的业务路由和 `springdoc.swagger-ui.urls` 聚合项；服务未能通过对应网关文档路径验证前，不加入 Swagger UI 下拉。

## AI 工程门禁

- 新增路由、改跨域、改请求头、改文档入口、改监控入口、改 Nacos 配置默认高风险。
- 新增或修改功能前，必须按 `AI_AUTOMATION_WORKFLOW.md` 整理需求说明、验收标准和开发手册。
- 完成后必须按 `docs/ai-coding/AI_ENGINEERING_GUARDRAILS.md` 做风险分级、Definition of Done、测试证据、安全检查、风险和回滚说明。
- 修改 Nacos 配置时，必须读回权威配置源再报告结果，不能只根据本地文件或发布命令判断成功。
- 测试分层按 `docs/ai-coding/TESTING_SPEC.md` 执行；路由和 header 透传必须用 SpringBootTest + WebTestClient 验证真实网关链路。
- 测试必须稳定可重复，按 `docs/ai-coding/TESTING_SPEC.md`「测试稳定性」：不依赖时序、连接复用或执行顺序；偶发失败按缺陷处理，不用重试、跳过或删除测试掩盖。

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
- 禁止在网关新增业务表、DDL、Entity、Mapper 或 Repository；需要持久化时回到对应业务服务，并按该服务表设计规范处理。
