# telbank-rbac-starter（JDK 8 / Spring Boot 2.7.x 版 SDK）

供 **JDK 8 字节码 + Spring Boot 2.7.x** 业务系统接入的 RBAC SDK。协议、配置键、类名、包名与 JDK 21 版 `telbank-rbac2-starter` **完全一致**，仅实现层分别为 Feign 11.10 + OkHttp 3.14.9 + Jackson 2（本模块）与 Feign 11.10 + OkHttp 4.12.0 + Jackson 3 编解码器；开放接口以声明式 Feign `interface` 声明——**一组开放接口一个 `interface` 类**（`RbacOpenApiFeignApi` / `RbacOnboardingFeignApi`），签名头由 `RbacSigningClient` 统一注入，业务层只收发 `RbacEnvelope<T>` POJO（A23）。

- 需求基线：[`docs/telbank-rbac.md`](../docs/telbank-rbac.md)
- 接口契约（§0/§4/§7）：[`docs/impl/contracts.md`](../docs/impl/contracts.md)
- 交付说明与接入示例：[`README.md`](../README.md) §5
- 文档总览：[`docs/README.md`](../docs/README.md)

## 引入

```xml
<dependency>
  <groupId>com.telbank</groupId>
  <artifactId>telbank-rbac-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

配置项 `rbac-sdk.*` 见需求 §6.13 / 根 README §5.1（两个 SDK 变体配置键与语义完全一致）：

```yaml
rbac-sdk:
  enabled: true
  system-code: crm
  platform-base-url: https://rbac.example.com
  app-key: crm
  app-secret: ${RBAC_SDK_APP_SECRET}
```

> **注意**：两个 SDK 变体使用相同包名（`com.telbank.rbac.sdk`），同一业务系统只能引入一个，**不能同时出现在 classpath**。

## 自动注册的接口与组件

- `GET {base-path}/me/systems`：当前用户**有有效角色的业务系统**列表（v2.0 系统隔离模型，由权限中心按有效 `user_role` 推导、排除 `platform` 平台系统；无有效角色时返回空数组；仅回源失败时降级为本系统单例）。
- `GET {base-path}/me/bootstrap`：`permissions` + `pageActions` + `platformAdmin`，并预热用户权限集缓存。
- `RbacPermissionFilter`：按契约 §7.2 顺序做接口级权限校验（映射未命中默认放行）。
- `RbacEndpointScanner`：启动后全量上报端点（跳过 `@PreAuthorize`/`@Secured`）。
- 区域 Redis Pub/Sub 变更订阅 + 兜底短轮询；权限中心/Redis 不可用时自动降级，不阻塞业务。
- `RbacClient`：业务后端可选的编程式查询 API（SDK 唯一允许被同类型 Bean 覆盖的扩展点）。

> v2.0 系统隔离重构后已删除「用户-系统开通」概念：业务系统访问权限由有效角色推导，SDK 不再有任何用户-系统开通/管理上下文 `rbac` 语义；`/rbac/me/systems` 不再使用 `X-System-Code`。

## 系统接入自动上报（A16，可选）

开启后 SDK 在应用启动完成（`ApplicationReadyEvent`）时，用 **onboarding 专用 AppKey/AppSecret** 对 `POST /openapi/onboarding/systems` 做 AppKey + HMAC-SHA256 签名上报（timestamp/nonce/canonical 规则与其它开放接口一致），body 为 `{systemCode,name,region,sdkVersion?,remark?}`，响应 `{applicationId,status}`：

```yaml
rbac-sdk:
  system-name: CRM系统          # 默认空 -> 使用 system-code
  region: region_a              # 默认空字符串
  onboarding:
    enabled: false              # 默认关闭
    app-key: onboarding-app
    app-secret: ${RBAC_SDK_ONBOARDING_SECRET}
    report-on-startup: true     # 默认 true
```

- 上报**只创建待审核申请（PENDING），不直接开通系统**；平台超管在管理端审核通过后，页面显示分配的 appKey/appSecret，人工配置回业务系统 SDK（本轮 SDK 不自动拉取凭据）。
- 上报在守护线程中执行：`enabled=false`/`report-on-startup=false`/凭据缺失时不发请求；任何失败（网络、401、5xx 等）只打 WARNING，**绝不影响业务系统启动**。
- 与端点上报（`/openapi/sdk/endpoints/sync`）完全独立，互不影响。

## A 方案通配权限（`*` / `前缀:*`）

v2.0 A 方案下 admin/superadmin 角色只授一条 `*` 权限，openapi user-permissions 可能下发 `*` 或 `前缀:*`（如 `order:*`）。SDK 权限判定统一使用内部 `RbacPermissionMatcher`（Java 8 兼容实现，与后端 `@perm`、前端 `hasPerm` 口径一致）：

- **精确匹配**：权限码完全相等；
- **`*`**：匹配任意权限码（当前系统全部权限）；
- **`前缀:*`**（如 `order:*`）：匹配以 `前缀:` 开头的权限码（如 `order:delete`、`order:view:amount`）。

`/rbac/me/bootstrap` 的 `permissions` 原样透传缓存中的有效权限码：包含 `*` 时仅返回 `["*"]`（`platformAdmin=true`），前缀通配条目（如 `order:*`）原样返回，业务前端 `hasPerm` 需使用同一匹配口径。

## 与 JDK 21 版的实现差异

| 项 | JDK 8 版（本模块） | JDK 21 版 |
|---|---|---|
| 自动配置 | `META-INF/spring.factories` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Servlet | `javax.servlet` | `jakarta.servlet` |
| HTTP 客户端（声明式 `interface` 接口） | Feign 11.10 + OkHttp 3.14.9 + Jackson 2 | Feign 11.10 + OkHttp 4.12.0 + Jackson 3 编解码器 |
| JSON | Jackson 2 | Jackson 3（`tools.jackson`） |
| 字节码 | Java 8（major=52） | Java 21（major=65） |

## 构建与测试

```bash
# 仓库根（构建工具链 JDK 17+，--release 8 产出 Java 8 字节码）
mvn -pl telbank-rbac-starter clean test
```

运行环境要求 **JDK 8+**（CI 已用真实 JDK 8 运行时验证）；构建工具链为 JDK 17+（Apple Silicon 无官方 JDK 8，见根 README §9 A7）。示例业务系统见 [`samples/business-demo`](../samples/business-demo/README.md)。
