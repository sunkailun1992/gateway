# Gateway AI 编码执行指南

## 使用方式

当用户要求修改网关或网关配置时，AI 应按以下顺序工作：

1. 阅读当前项目代码，确认改动属于网关边界。
2. 阅读 `GATEWAY_CODING_SPEC.md`，确认不能把业务认证、业务权限或公共工具重新写回网关。
3. 修改 Nacos 配置前阅读 `NACOS_CONFIG_SPEC.md`，先读取远程 `gateway-spring.yaml`，再直接发布更新到 Nacos。
4. 涉及 `user` 登录、资源、权限或 JWT 时，读取同级 `user` 项目真实 Controller、Service 和 `utils` 安全配置，不凭旧路径推断。
5. 涉及公共能力时，优先复用同级 `utils` 项目，不在网关新增公共工具包。
6. 修改完成后执行编译、测试和打包验证。

## 注释要求

- AI 新增或修改 Java 代码时，按“每行注释”执行。
- 包声明、import、空行和单独的大括号不用强行注释。
- 类、字段、常量、配置 Bean、方法、条件判断、返回值、lambda、流式处理每一步都要写中文注释。
- 注释必须说明业务目的或网关职责，不能写“设置变量”“返回结果”这类无信息量注释。
- 调整已有代码时，补齐被修改代码附近的关键行注释，避免新旧风格混杂导致后续 AI 误读。

## 禁止事项

- 不要恢复旧 `TokenFilter`、`UserRpc`、`RedisUtils` token 鉴权。
- 不要新增任何 token、header、用户、权限或 Actuator 自定义鉴权过滤器。
- 不要在网关解析、组装或判断业务 `ApiResponse`。
- 不要在网关维护用户权限表、接口权限列表或 Redis 登录态。
- 不要恢复 Ribbon 负载均衡规则。
- 不要在仓库新增 `docs/nacos/gateway-spring.yaml`；网关路由配置只保留在 Nacos 远程配置中心。
- 不要恢复 SLS/Loghub 日志依赖或 appender。
- 不要让测试默认依赖未说明的外部服务；确实需要 Nacos 时要在结果中说明。

## 推荐修改顺序

1. Nacos 远程 `gateway-spring.yaml`
2. `src/main/resources/bootstrap.yml` 或 `application.yml`
3. `src/main/java/com/kellen/config/*`
4. `src/test/java/*`
5. `Dockerfile` 或构建脚本

## 验证命令

```bash
./gradlew clean test bootJar --no-daemon
```

## Nacos 读取命令

```bash
curl -sS "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=gateway-spring.yaml&group=test&tenant=cfbf4c42-5ebb-4566-a095-30a568556a85"
```

## Nacos 发布命令

```bash
curl -sS -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=gateway-spring.yaml" \
  --data-urlencode "group=test" \
  --data-urlencode "tenant=cfbf4c42-5ebb-4566-a095-30a568556a85" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=<整理后的完整 YAML 内容>"
```
