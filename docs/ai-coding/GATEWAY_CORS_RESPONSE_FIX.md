# 网关 CORS 响应标头修复

## 需求与边界

浏览器经网关调用租户列表、登录等接口时，网关与 Servlet 后端分别输出 CORS 标头，转发合并后出现重复的 `Access-Control-Allow-Origin`，浏览器因此拒绝读取 HTTP 200 响应。网关统一决定对外 CORS 策略，后端保留自己的直连策略。

本次只修改网关响应标头合并，不修改登录参数、认证授权、业务响应、租户、数据库、路由、Nacos 白名单或公司项目，不增加依赖。

## 验收标准

- 允许来源的实际 POST/GET 响应中，Origin、Credentials、Expose-Headers 仅由网关输出一份。
- 后端的重复、冲突或大小写不同的 CORS 标头不能覆盖网关策略。
- 网关关闭凭证时，后端不能重新开启；无 Origin 与同源请求不继承后端的跨域标头。
- 未列出的来源、方法和请求头仍在转发前拒绝，预检仍不调用后端。
- 路径、Authorization、非 CORS 响应标头与响应体保持透传。

## 开发入口

- `CorsFilter` 使用 Spring Cloud Gateway 的 `HttpHeadersFilter.Type.RESPONSE` 扩展点，在下游标头合并前移除 `Access-Control-*`，保留原有 Spring `CorsWebFilter` 的白名单处理。
- `StubBackend` 可附带响应标头，并记录合成 Authorization，以真实随机端口的 HTTP 链路重现双层 CORS。
- `CorsPolicyHttpIntegrationTest` 覆盖重复、冲突、关闭凭证、同源和无 Origin 等反例；既有路由与安全测试继续回归。

## 测试与发布

先执行新增回归，确认旧实现失败，再验证修复；随后执行完整测试、JAR 构建、密钥检查与真实网关/后端验证。真实登录只核对业务结果，不记录密码、Token 或完整请求体。

全量回归中的三个旧 Nacos 断言仍要求已经弃用的独立 test/prod namespace 与缺省地址失败行为；在未修改的 origin/main 导出中也能重现相同失败。本次仅将测试和启动文档对齐用户已确定的统一 `github-dev` 连接约定，不修改任何 application 配置或 Nacos 数据。

此变更需要重启网关，后端无需因本次响应标头修复重启。回滚到修复前的网关制品会恢复旧的重复标头行为；Nacos 配置无需回滚。
