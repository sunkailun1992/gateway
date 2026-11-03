# RPC API 协作规范

## 契约归属

- 跨服务 Dubbo RPC 接口、DTO、枚举和值对象统一维护在同级 `../rpc-api`。
- 网关不实现 provider，不直接编写 consumer 调用逻辑，不复制接口和 DTO。
- `utils` 只提供 Dubbo 上下文透传、公共配置、公共工具和中间件适配，不维护业务 RPC 契约。

## 当前服务角色

- `gateway` 主要通过 HTTP 路由把外部请求转发给后端服务。
- 网关可以依赖 `rpc-api` 保持 fleet 依赖统一，但不得在网关承担业务 RPC 编排、鉴权或数据查询。
- 新增 RPC 服务后，网关只在需要外部 HTTP 暴露时配置对应业务路由和 OpenAPI 聚合。

## 依赖和 CI

- Gradle 依赖使用 `implementation "com:rpc-api:${rpcApiVersion}"`。
- 本地联调前先在 `../rpc-api` 执行 `./gradlew publishToMavenLocal`。
- CI 必须先 checkout `sunkailun1992/rpc-api` 并 `publishToMavenLocal`，再编译本服务。

## 上下文

- 网关只负责透传必要请求头，不伪造登录用户、租户、数据源、版本号或流量泳道。
- Dubbo 上下文由业务服务和 `utils` 过滤器处理，不在网关直接拼装 Dubbo attachment。
