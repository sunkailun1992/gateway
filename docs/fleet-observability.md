# 监控采集与回滚

## 需求与边界

本批版本为 `1.1.1`。使用 Micrometer Prometheus registry 提供指标，OpenTelemetry API 1.59.0 仅消费统一启动入口注入的 Java agent；应用不建立 SDK，也不依赖重型 utils。Java agent 的指标与日志 exporter 保持关闭（`OTEL_METRICS_EXPORTER=none`、`OTEL_LOGS_EXPORTER=none`），日志继续走共享 ES 配置。生产 classpath 不引入测试 SDK。

指标固定维度为 `application=gateway`、`version`、`environment=github-dev`；部署环境可通过 `observability.environment` 覆盖。版本优先使用显式 `spring.application.version`，以便原生 IDEA Spring Boot Run/Debug 与 OTel 的服务版本一致；未指定时使用 Jar 中可选的 `BuildProperties`，两者缺失或空白时标为 `unknown`。IDE 编译没有生成 build-info 也必须能够启动和采集，不要求先运行 Gradle，不创建虚假的构建信息。业务会话 ID、患者、租户、用户、trace ID、URL、prompt 和正文不得作为标签。

此修复只调整指标装配的可选依赖及版本选择。验收使用真实 Spring 上下文验证无 `BuildProperties` 启动、显式版本优先、Jar 版本回退、空白版本和 `unknown` 分支；不涉及路由、管理鉴权、Nacos 或 Compose。

## 内部管理入口

运行配置来自共享 `actuator.yaml`，不把环境值搬回 base application.yml。只在专用内网或回环地址启用独立监听：`management.server.port`（计划采集端口 `17100`，实际值以目标部署核实）、`management.server.address`；允许范围为 `management.endpoints.web.exposure.include=health,prometheus`。采集器通过 `actuatorReq` 请求头发送私有 `security.actuator.token`，不能把真实令牌写入 Git、命令示例或日志。无令牌、错误令牌、空白配置和未解析配置均拒绝采集。

部署前必须核实绑定地址、主/管理两个实际监听端口、Prometheus 到管理地址的可达性和防火墙边界。不能用公网网关业务路由暴露采集接口。应分别验证无令牌 401、错误令牌 401、正确令牌 200 和输出包含 JVM 指标。不得为采集设置 exposure=*。

gateway 的公网自身及后端 Actuator 路径继续 404，有效令牌也不能放开。过滤器使用服务器实际监听端口区分管理流量，不使用 Host 或转发头。管理端口只有规范的 GET health（含探针）和受令牌保护的 GET Prometheus；即使远程误配 exposure=*，env、configprops、heapdump、shutdown 等仍 404。自定义 base-path/path-mapping 需同步采集配置，路径编码与后端前缀不能绕过公网阻断。

## 验收与回滚

源码验证执行 `./gradlew clean test bootJar --no-daemon` 与 `STRICT_SECRET_SCAN=1 bash scripts/check-secrets.sh`。测试使用合成令牌与本机临时监听，不连接真实 Nacos、模型供应商或监控服务器；端到端采集、真实 Dubbo/HTTP trace、ES 关联和生产资源影响仍需部署后独立验收。

本批没有发布制品、推送、部署或修改远程 Nacos。回滚先停用采集目标与 Java agent 启动参数，再回退服务制品及其配套配置；关闭监控不能放宽现有管理令牌或公网阻断。历史 Spring Boot Admin/Zipkin 已退役，当前 profiles 与配置分类不再导入这两项，不恢复旧配置补采集。
