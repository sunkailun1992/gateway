# Gateway AI 编码执行指南

## 使用方式

当用户要求修改网关或网关配置时，AI 应按以下顺序工作：

1. 阅读当前项目代码，确认改动属于网关边界。
2. 阅读 `AI_AUTOMATION_WORKFLOW.md`，先整理需求说明、验收标准、开发手册、测试说明和交付说明。
3. 阅读 `AI_ENGINEERING_GUARDRAILS.md`，确认风险等级、必须测试、安全门禁、Review 门禁和交付说明。
4. 阅读 `AI_DIRECTORY_STRUCTURE_GUIDE.md`，确认 Spring Cloud Gateway 目录、资源、文档和跨项目边界。
5. 阅读 `AI_DESIGN_PATTERN_GUIDE.md`，确认网关 Route、Predicate、Filter、Nacos 配置和禁止业务鉴权回流规则。
6. 阅读 `GATEWAY_CODING_SPEC.md`，确认不能把业务认证、业务权限或公共工具重新写回网关。
7. 修改 Nacos 配置前阅读 `NACOS_CONFIG_SPEC.md`，先读取远程 `gateway-spring.yaml`，再直接发布更新到 Nacos。
8. 涉及 `user` 登录、资源、权限或 JWT 时，读取同级 `user` 项目真实 Controller、Service 和 `utils` 安全配置，不凭旧路径推断。
9. 涉及公共能力时，优先复用同级 `utils` 项目，不在网关新增公共工具包。
10. 涉及 Redis、Nacos Discovery、Spring Admin、Zipkin 等基础设施地址时，优先引用 `reuse-configuration.yaml` 公共变量，不在业务配置中新增裸 IP。
11. 新增或修改 README、AI 规范、配置、脚本、测试、示例和代码时，禁止写入个人电脑绝对路径；目录关系使用相对路径，外部安装位置使用环境变量或 `<PLACEHOLDER>` 占位符。
12. 修改完成后执行编译、测试和打包验证，并按 `AI_ENGINEERING_GUARDRAILS.md` 输出风险、回滚和未验证项。

## 多智能体协作规则

- 可以使用多个子智能体并行协作，但子智能体默认只能执行需求分析和项目学习，不直接修改代码。
- 网关排查适合拆分为前端请求、网关路由、Nacos 配置、后端服务注册和日志分析多个 explorer；主智能体必须最后汇总证据并判断根因。
- 代码 Review 可以按安全风险、路由逻辑、配置漂移、测试缺口和可维护性拆分给多个 reviewer；结论必须由主智能体统一收口。
- 测试回归和日志分析可以并行：一个 agent 跑 `bootJar` 或测试，一个 agent 分析失败日志，一个 agent 查最近配置改动；最终修复仍由主智能体或一个明确 worker 收口。
- 大功能可以拆给多个 worker 独立实现，但必须先划清写入边界，例如 worker A 只改 Nacos 路由、worker B 只改 OpenAPI 文档转发、worker C 只补测试。
- 如果多个 worker 需要修改同一份 `gateway-spring.yaml`、同一个过滤器、同一个公共配置或同一段路由规则，不允许并行写入，必须改为主智能体串行处理。
- 子智能体输出应包含读取范围、关键发现、风险点和建议，不应直接给出未经主智能体验证的最终结论。

## 安全编码规则

安全细则单独维护在 `SECURITY_CODING_SPEC.md`。新增或修改路由、跨域、请求头透传、日志、OpenAPI、监控入口、代理转发时，必须先阅读该文件并按检查清单验证。

## 注释要求

AI 新增或修改 Java、YAML、Nacos 配置、脚本、测试和示例等编程内容时，必须遵守 `AI_COMMENT_STYLE_GUIDE.md`。

核心原则：

- 修改注释前先识别文件类型和框架上下文；规范未覆盖时，先查官方或主流规范并补充到注释规范文件。
- 优先让代码自解释，能用路由名、过滤器名、常量和小方法表达的意图，不用注释补救。
- 注释解释长期维护需要知道的路由职责、跨域、请求头透传、限流、OpenAPI 文档转发、Nacos 配置和失败策略。
- 禁止逐行翻译式注释，禁止用注释保留旧路由、旧过滤器、调试代码或整块废弃实现。
- 注释必须保持缩进、对齐、换行和段落美观一致；不能为了补说明把 Java、YAML 或脚本弄乱。

## 设计模式要求

AI 新增或重构 Java、YAML、Nacos 配置、脚本、测试和示例结构前，必须遵守 `AI_DESIGN_PATTERN_GUIDE.md`。

核心原则：

- 网关优先使用 Spring Cloud Gateway 原生 Route、Predicate、Filter 和 GlobalFilter 扩展点。
- 请求处理适合 Filter Chain；Nacos、OpenAPI 和后端服务差异可用 Adapter 包装。
- 简单路由优先保留声明式 YAML，不为配置硬套 Java 抽象。
- 设计模式不得把业务认证、业务权限、租户隔离、数据权限或业务响应解析写回网关。

## 禁止事项

- 不要恢复旧 `TokenFilter`、`UserRpc`、`RedisUtils` token 鉴权。
- 不要新增任何 token、header、用户、权限或 Actuator 自定义鉴权过滤器。
- 不要在网关解析、组装或判断业务 `ApiResponse`。
- 不要在网关维护用户权限表、接口权限列表或 Redis 登录态。
- 不要恢复 Ribbon 负载均衡规则。
- 不要在仓库新增 `docs/nacos/gateway-spring.yaml`；网关路由配置只保留在 Nacos 远程配置中心。
- 不要恢复 SLS/Loghub 日志依赖或 appender。
- 不要让测试默认依赖未说明的外部服务；确实需要 Nacos 时要在结果中说明。
- 不要在 `gateway-spring.yaml` 中新增散落的基础设施裸 IP；除本地 `application.yml` 连接 Nacos 的启动入口外，基础设施地址统一从公共配置读取。
- 不要在仓库文件中写入个人电脑绝对路径、下载目录、IDE 路径、JDK 安装路径或本机仓库完整路径；本地私有路径放到环境变量、用户级 Gradle 配置、IDE 运行配置或未提交的本地配置中。

## 推荐修改顺序

1. Nacos 远程 `gateway-spring.yaml`
2. `src/main/resources/application.yml`
3. `src/main/java/com/kellen/config/*`
4. `src/test/java/*`
5. `Dockerfile` 或构建脚本

## 验证命令

```bash
./gradlew clean test bootJar --no-daemon
bash scripts/check-secrets.sh
```

## Nacos 读取命令

```bash
curl -sS "http://<NACOS_ADDR>/nacos/v1/cs/configs?dataId=gateway-spring.yaml&group=test&tenant=<NACOS_NAMESPACE>"
```

## Nacos 发布命令

```bash
curl -sS -X POST "http://<NACOS_ADDR>/nacos/v1/cs/configs" \
  --data-urlencode "dataId=gateway-spring.yaml" \
  --data-urlencode "group=test" \
  --data-urlencode "tenant=<NACOS_NAMESPACE>" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=<整理后的完整 YAML 内容>"
```
