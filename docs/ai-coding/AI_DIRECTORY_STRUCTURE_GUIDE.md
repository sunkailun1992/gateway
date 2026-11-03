# AI 目录管理规范

本规范约束 AI 在 `gateway` 网关服务中新增、移动、拆分和命名目录的方式。目录管理必须基于当前 Java 17、Spring Boot、Spring Cloud Gateway、Gradle 和 Nacos 网关项目结构。

## 核心依据

- Gradle Java SourceSet：生产源码放 `src/main/java`，生产资源放 `src/main/resources`，测试源码放 `src/test/java`，测试资源放 `src/test/resources`。
- Spring Boot / Spring Cloud 包结构：主应用类位于根包下，网关配置和扩展位于根包子包内。
- Spring Cloud Gateway 约定：路由、Predicate、Filter、跨域和文档聚合属于网关层，不承载业务鉴权。
- GitHub / AI 规范：CI 放 `.github/workflows/`，AI 规范放 `docs/ai-coding/`，根目录只保留 `AGENTS.md` 作为入口。

## 当前标准目录

```text
.
├── AGENTS.md
├── README.md
├── build.gradle
├── settings.gradle
├── gradle/
├── src/
├── docs/ai-coding/
├── scripts/
└── .github/
```

生产代码根包：

```text
src/main/java/com/kellen
```

当前网关代码目录：

| 目录 | 职责 |
| --- | --- |
| `com/kellen/config` | Gateway、OpenAPI、Nacos、跨域、限流、文档聚合等网关配置。 |
| `src/main/resources` | `application.yml` 等启动所需资源。 |
| `docs/ai-coding` | 网关 AI 编程规范、Nacos 配置规范和安全规则。 |

## 目录规则

- 新增路由、过滤器、Predicate、跨域或 OpenAPI 文档转发能力优先放在 `com/kellen/config` 或清晰命名的网关子包中。
- 网关不得新增业务域目录，例如 `auth`、`user`、`message` 业务实现目录；认证授权应由后端服务处理，网关只透传必要请求头。
- Nacos 权威配置不落本地副本；需要说明配置时写进 `NACOS_CONFIG_SPEC.md` 或 README，不提交真实远程配置和密钥。
- 新增测试必须放 `src/test/java/com/kellen`，测试资源放 `src/test/resources`。
- 当前按网关技术职责组织包（config/filter/predicate 等，package-by-layer）；当路由、限流、跨域、文档聚合等网关能力在多个目录中持续膨胀，且改动总是跨多个目录联动时，才评估按网关能力特性分包（package-by-feature）。演进必须有真实维护痛点，不为小规模代码强行切换，并同步 Spring 组件扫描、网关配置、测试和文档。
- AI 规范统一放 `docs/ai-coding/`；根目录不再新增 `AI_*.md`、`*_SPEC.md` 或临时分析文档。
- 当前仓库不得嵌套 `user`、`message`、`report`、`utils`、`admin-web`、`ai` 等同级项目副本；跨项目修改必须切换到真实同级仓库。
- 构建产物、IDE 文件、本机模块文件和系统文件不得提交，例如 `build/`、`.gradle/`、`.idea/`、`*.iml`、`.DS_Store`。

## 变更流程

1. 先判断文件属于网关源码、测试、资源、文档、脚本、CI 还是工具配置。
2. 查找现有同类目录，优先复用，不新增平行体系。
3. 移动 Java 文件时同步 `package`、import、测试、README 和 AI 规范引用。
4. 涉及 Nacos、路由、跨域、文档入口或请求头透传时，同时更新 `GATEWAY_CODING_SPEC.md` 或 `NACOS_CONFIG_SPEC.md`。
5. 执行 `git diff --check`，涉及 Java 目录或 package 变化时执行 `./gradlew clean test bootJar --no-daemon` 或说明无法执行的原因。

## 检查清单

- 是否符合 Java / Spring Boot / Gradle / Spring Cloud Gateway 主流目录约定？
- 是否保持网关只做路由、跨域、限流、文档聚合和请求转发？
- 是否避免把业务鉴权、用户服务或消息服务实现放入网关？
- 是否没有嵌套同级项目副本？
- 是否没有移动或替换已有 Nacos 地址、内网地址、密钥或生产配置？
