# AI 设计模式规范

本规范约束 AI 在 `gateway` 项目中选择、引入和调整设计模式的方式。网关是 Spring Cloud Gateway 项目，模式必须服务路由、过滤器、配置和安全边界，不能把业务认证或业务权限重新写回网关。

## 1. 总原则

- 先识别当前改动是 Java 网关代码、Spring Cloud Gateway 配置、Nacos YAML、脚本还是测试。
- 优先沿用 Spring Cloud Gateway 官方模型：Route、Predicate、Filter、GlobalFilter、配置属性。
- 设计模式必须服务网关边界：路由、跨域、请求头透传、限流、Knife4j 聚合、Nacos 配置读写和失败关闭。
- 不允许用模式把用户认证、角色权限、租户隔离、数据权限或业务响应解析写进网关。
- 简单配置优先保留声明式 YAML，不为它硬套 Java 抽象。

## 2. 标准参考

- Spring Cloud Gateway 官方 Route Predicate Factory、GatewayFilter Factory、GlobalFilter 模型。
- Spring Boot 配置属性和 Bean 生命周期。
- GoF 设计模式只作为命名语言，网关优先使用框架原生扩展点。
- SOLID 原则用于判断过滤器职责、配置读取和依赖方向。

## 3. 本项目推荐模式

### Chain of Responsibility / Filter Chain

适用网关过滤器。

- 请求处理优先按 Gateway Filter Chain 组织。
- 每个过滤器只做一个职责，例如请求头透传、日志摘要、限流或文档聚合辅助。
- 不在过滤器里调用业务数据库或解析业务权限。

### Strategy

适用多种路由、限流、文档聚合或请求头处理策略。

- 确实存在多个可替换策略时才引入接口。
- 单一路由配置优先保留 YAML，不写 Java 策略。
- 策略不能吞掉 `Authorization`、租户头或 traceId。

### Adapter

适用 Nacos 配置、Knife4j 聚合、后端服务路由和第三方网关扩展。

- 外部配置格式和框架 API 差异封装在适配层。
- 上层逻辑只关注网关职责，不接触业务服务内部实现。

### Configuration Properties

适用可配置的网关行为。

- Java 配置优先使用 Spring Boot 配置属性绑定。
- Nacos 远程配置是路由权威来源；仓库不保留本地 `gateway-spring.yaml` 副本。
- 配置缺失或读取失败时默认失败关闭。

### Facade

适用把多个框架对象包装成小而稳定的网关能力。

- 只为网关内部使用，不对业务服务暴露。
- 不把业务认证、业务路由决策和后端权限包装进 Facade。

## 4. 谨慎或禁止使用

- 恢复旧 `TokenFilter`、`UserRpc`、Redis token 鉴权或业务权限过滤器。
- Service Locator 和反射式路由插件。
- 巨型 GatewayManager，把路由、跨域、文档、Nacos、监控都塞进一个类。
- 在网关维护业务权限表、接口权限列表或用户登录态。
- 用 Java 代码替代清晰的 Spring Cloud Gateway YAML 配置，除非确有框架扩展需求。

## 5. 检查清单

- 是否仍然只做路由、跨域、限流、文档聚合和请求转发？
- 是否原样透传 `Authorization`、租户头、traceId 等后端依赖头？
- 是否没有引入业务鉴权、字段级授权、租户隔离或数据权限逻辑？
- 是否优先使用 Gateway 原生 Route/Predicate/Filter 扩展点？
- 是否避免在仓库新增 Nacos 路由配置副本？
- 是否执行 `./gradlew clean test bootJar --no-daemon` 或说明未验证原因？
