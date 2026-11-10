# 监控采集与回滚

## 需求与边界

本批版本为 `1.1.6`，精确消费 `com:fleet-observability:0.1.2`。使用 Micrometer Prometheus registry 提供指标，OpenTelemetry API 1.59.0 仅消费统一启动入口注入的 Java agent；应用不建立 SDK，也不依赖重型 utils。Java agent 的指标与日志 exporter 保持关闭（`OTEL_METRICS_EXPORTER=none`、`OTEL_LOGS_EXPORTER=none`），日志继续走共享 ES 配置。生产 classpath 不引入测试 SDK。

指标固定维度为 `application=gateway`、`version`、`environment=github-dev`；部署环境可通过 `observability.environment` 覆盖。版本优先使用显式 `spring.application.version`，以便原生 IDEA Spring Boot Run/Debug 与 OTel 的服务版本一致；未指定时使用 Jar 中可选的 `BuildProperties`，两者缺失或空白时标为 `unknown`。IDE 编译没有生成 build-info 也必须能够启动和采集，不要求先运行 Gradle，不创建虚假的构建信息。业务会话 ID、患者、租户、用户、trace ID、URL、prompt 和正文不得作为标签。

指标装配使用真实 Spring 上下文验证无 `BuildProperties` 启动、显式版本优先、Jar 版本回退、空白版本和 `unknown` 分支。

共享 ECS 配置与轻量库必须成套更新：`logging.structured.json.context.include=false` 关闭 Boot 默认自由 MDC/KVP 合流，固定成员 SPI 仅输出白名单，同名 trace/span 键只编码一次。未知字段、自由正文和原始异常仍关闭；普通错误只形成安全类名摘要及 `error_type`。固定 `gateway.jar` 的 `bootJar` 显式绑定 `applicationVersion` 输入，并在构建后读回真实 Manifest；原生 IDEA Run/Debug 继续使用既有绿叶入口。

## 内部管理入口

运行配置来自共享 `actuator.yaml`，不把环境值搬回 base application.yml。只在专用内网或回环地址启用独立监听：`management.server.port`（计划采集端口 `17100`，实际值以目标部署核实）、`management.server.address`；允许范围为 `management.endpoints.web.exposure.include=health,prometheus`。采集器通过 `actuatorReq` 请求头发送私有 `security.actuator.token`，不能把真实令牌写入 Git、命令示例或日志。无令牌、错误令牌、空白配置和未解析配置均拒绝采集。

部署前必须核实绑定地址、主/管理两个实际监听端口、Prometheus 到管理地址的可达性和防火墙边界。不能用公网网关业务路由暴露采集接口。应分别验证无令牌 401、错误令牌 401、正确令牌 200 和输出包含 JVM 指标。不得为采集设置 exposure=*。

gateway 的公网自身及后端 Actuator 路径继续 404，有效令牌也不能放开。过滤器使用服务器实际监听端口区分管理流量，不使用 Host 或转发头。管理端口只有规范的 GET health（含探针）和受令牌保护的 GET Prometheus；即使远程误配 exposure=*，env、configprops、heapdump、shutdown 等仍 404。自定义 base-path/path-mapping 需同步采集配置，路径编码与后端前缀不能绕过公网阻断。

## 验收与回滚

源码验证执行 `./gradlew clean test bootJar --no-daemon` 与 `STRICT_SECRET_SCAN=1 bash scripts/check-secrets.sh`。测试使用合成令牌与本机临时监听，不连接真实 Nacos、模型供应商或监控服务器；端到端采集、真实 Dubbo/HTTP trace、ES 关联和生产资源影响仍需部署后独立验收。

源码测试、实际 Jar Manifest/轻量依赖、远端发布和运行态采集分别验收。回滚先停用采集目标与 Java agent 启动参数，再成套回退服务制品及日志配置；关闭监控不能放宽现有管理令牌或公网阻断。历史 Spring Boot Admin/Zipkin 已退役，当前 profiles 与配置分类不再导入这两项，不恢复旧配置补采集。

## 流式 HTTP 客户端 span

固定 Java agent 2.33.1 的 Reactor Netty 客户端自动采集在 Spring Cloud Gateway 分段响应体取消时可能传播出 CLIENT span 身份，却不导出该 span 的终态。`GatewayClientTracingFilter` 使用两个官方 Gateway 扩展点：order=-2 的 GlobalFilter 包住 NettyWriteResponseFilter 的完整 body 写回；order=MAX_VALUE-1 的发送边界在负载均衡目标解析后、真正 NettyRoutingFilter 发包前创建真实 CLIENT。请求头扩展点只注入这个已存在 span 的 W3C 身份。不会向已发生的旧 trace 追加、补造或重写 span。

统一启动入口只对 gateway 成套设置 `gateway.observability.client-span-enabled=true` 和 `otel.instrumentation.reactor-netty-1.0.enabled=false`；应用开关默认关闭，其余服务不增加这些键。不能用 `otel.instrumentation.reactor-netty.enabled=false` 或 `otel.instrumentation.netty.enabled=false` 替换精确客户端选择器，它们会影响入站 SERVER 或传播桥。该行为以官方 2.33.1 [客户端模块选择器](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.33.1/instrumentation/reactor/reactor-netty/reactor-netty-1.0/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/reactornetty/v1_0/ReactorNettyInstrumentationModule.java) 和 [WebFlux 入站模块选择器](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.33.1/instrumentation/spring/spring-webflux/spring-webflux-5.0/javaagent/src/main/java/io/opentelemetry/javaagent/instrumentation/spring/webflux/v5_0/server/reactornetty/ReactorNettyInstrumentationModule.java) 的不同 alias 为依据。

公共 `opentelemetry-instrumentation-api:2.25.0` 与 `opentelemetry-reactor-3.1:2.25.0-alpha` 对齐既有 OTel API 1.59.0，分别提供 HTTP 语义抑制与公开 Reactor Context bridge；下层 Netty 重复 CLIENT 被抑制，自动 SERVER 保留。应用仍不建立 SDK、exporter 或全局 Reactor hook。Scope 只用于同步组装，异步订阅通过官方 bridge 传递，不把 Scope 跨线程持有。

真实响应体的取消单独监听，不能只依赖外层 Mono 终态，因为 Gateway 在客户端断连后也可能正常完成写回 Mono。正常完成记录 success；主动取消记录 cancelled；真正上游异常及 HTTP 400–599 记录 error。每个逻辑请求生命周期只结束一次，同一 exchange 的重复 header 处理复用该 span；真实 Retry 路由行为需另外验收。异常仅记录类名，不记录异常正文或事件。属性 getter 不读取 URL、地址、认证头或正文，即使配置 header capture 也得不到这些内容。没有真正 HTTP 发送时不创建 CLIENT；已有上层 HTTP Instrumenter 上下文按 shouldStart 合同抑制，不结束其他调用者的 span。

`GatewayClientTracingFilterTest` 覆盖终态、重入抑制、4xx/5xx、正文取消和真实错误优先级。`GatewayClientTracingAgentTest` 用两个真实 Spring WebFlux 随机端口上下文、真实 Gateway、固定 agent 和 exporter 边界捕获，验证正常/活动取消/上游中断均为一条 trace、两个自动 SERVER 与唯一网关 CLIENT，实际发送头、已导出 CLIENT 与两侧 parentSpanId 一致。普通 CI 没有私有 agent 制品时显式跳过这三项；本机提供 `FLEET_TEST_AGENT`、`FLEET_TEST_PRIVACY` 的本机文件路径后运行测试，不使用真实 Nacos、模型或监控后台。

回滚时关闭应用开关并恢复统一入口原有客户端自动采集选择器，或成套回退原制品与原运行配置；不得仅停用自动客户端模块而仍使用旧应用。默认关闭的源码测试、隔离真实 agent 验证和目标九服务的实际整链验收分别记录，不能互相替代。
