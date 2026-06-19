# AI 注释规范

本规范约束 AI 在 `gateway` 项目中新增或修改注释的方式。项目是 Spring Cloud Gateway 网关，注释必须服务于路由、跨域、请求头透传、OpenAPI 文档转发、Nacos 配置和安全边界维护。

## 0. AI 执行流程

- 修改注释前先识别文件类型和上下文，例如 Java/Spring Cloud Gateway、Nacos YAML、Spring Boot YAML、Gradle、Shell 或 Markdown。
- 优先阅读 `AGENTS.md`、`docs/ai-coding/AI_CODING_GUIDE.md`、本文件、`GATEWAY_CODING_SPEC.md`、`NACOS_CONFIG_SPEC.md`、`SECURITY_CODING_SPEC.md`。
- 本规范未覆盖的文件类型，先查官方或主流规范，补充规范来源和网关落地规则后再改代码。
- 不为了统一风格批量重排 Nacos 配置、生产地址、密钥所在行或远程配置快照。

## 1. 总原则

- 自解释优先：能用清晰路由名、过滤器名、常量和小方法表达的意图，先重构代码，不用注释补救。
- 注释只解释代码看不出的内容：路由职责、请求头透传、跨域边界、限流策略、文档聚合、Nacos 配置来源和失败策略。
- 不给 package、import、普通注解、简单赋值、普通 getter/setter 或显而易见的链式调用逐行加注释。
- 禁止逐行翻译式注释，例如“设置路由 ID”“返回结果”“调用方法”。
- 禁止用注释保留废弃过滤器、旧路由、调试 main 或整块旧代码；历史版本交给 Git。
- 注释必须随代码同步更新，过时注释必须删除或修正。

## 2. Java 和网关注释

- 配置类、过滤器、路由装配、OpenAPI 文档转发和 Nacos 读取/发布逻辑应使用类级或方法级 Javadoc 说明职责和边界。
- 路由、跨域、请求头透传、监控入口和文档入口注释必须说明为什么这样做，以及不能绕过什么保护。
- 涉及 `Authorization`、租户头、traceId 或其它安全上下文时，注释要说明透传要求，不要让网关承担业务鉴权。
- 实现注释只写在关键逻辑块上方，不解释普通 Spring Gateway API 调用。

## 3. YAML、Gradle、Shell 和 Markdown 注释

- Nacos YAML 注释解释远程配置来源、路由含义、环境差异和误改风险，不解释 YAML 语法。
- Spring Boot YAML 注释解释启动入口、Nacos 地址和本地覆盖方式，不批量重排生产配置。
- 发现 Nacos 地址、账号密码、内网地址或其它敏感配置时，只报告文件行号和风险，不自动替换、删除或移动。
- Gradle 注释解释插件、依赖、任务和版本选择的项目原因，不解释 DSL 语法。
- Shell 注释解释安全边界、错误处理、密钥脱敏和退出码，不解释普通命令。

## 4. 格式和美观度

- 维持当前文件缩进、空行、换行宽度和段落风格，不在同一文件混用多套注释风格。
- 行尾注释只用于短枚举、短单位或既有对齐风格；造成列宽混乱或超长行时改为块上方注释。
- 不为了“看起来整齐”改动密钥、生产地址、Nacos 地址、数据库凭据或 token 所在行。
- 提交前从 diff 视觉检查一次：注释应让网关边界更容易扫读，而不是更乱。

## 5. 检查清单

- 注释是否解释了网关职责或安全边界，而不是语法？
- 是否可以用更好的路由名、过滤器名、常量或方法替代注释？
- 是否存在注释掉的旧路由、旧过滤器、调试代码或整块废弃实现？
- 是否泄露密钥、内网地址、Nacos 凭据或生产配置？
- 缩进、换行、对齐和段落是否与当前文件风格一致？

## 6. 参考依据

- [Google Java Style Guide - Javadoc](https://google.github.io/styleguide/javaguide.html#s7-javadoc)
- [Oracle JDK Documentation Comment Specification](https://docs.oracle.com/en/java/javase/21/docs/specs/javadoc/doc-comment-spec.html)
- [YAML 1.2.2 Specification - Comments](https://yaml.org/spec/1.2.2/#comments)
- [Spring Cloud Gateway Reference](https://docs.spring.io/spring-cloud-gateway/reference/)
- [Spring Boot Externalized Configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html)
- [Gradle Build Language Reference](https://docs.gradle.org/current/dsl/)
- [Google Shell Style Guide](https://google.github.io/styleguide/shellguide.html)
- Robert C. Martin《Clean Code》第 4 章 Comments：注释是次优手段，优先让代码自解释；注释掉的代码应删除。
