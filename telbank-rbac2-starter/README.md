# telbank-rbac2-starter（JDK 21 / Spring Boot 4.x 版 SDK）

供 **JDK 21 + Spring Boot 4.x** 业务系统接入的 RBAC SDK 变体。协议、配置键、类名、包名与 JDK 8 版 `telbank-rbac-starter` **完全一致**，仅实现层适配 Boot 4。

- 需求基线：[`docs/telbank-rbac.md`](../docs/telbank-rbac.md)
- 接口契约（§0/§4/§7.9）：[`docs/impl/contracts.md`](../docs/impl/contracts.md)
- 交付说明与 JDK 8 版接入示例：[`README.md`](../README.md) §5
- 文档总览：[`docs/README.md`](../docs/README.md)

## 引入

```xml
<dependency>
  <groupId>com.telbank</groupId>
  <artifactId>telbank-rbac2-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

配置与 JDK 8 版相同（`rbac-sdk.*`，见需求 §6.13 / README §5.1）。

> **注意**：两个 SDK 变体使用相同包名（`com.telbank.rbac.sdk`），同一业务系统只能引入一个，**不能同时出现在 classpath**。

## 系统列表语义（v2.0 系统隔离模型）

- `GET {base-path}/me/systems` 返回**当前用户有有效角色的业务系统**：权限中心按有效 `user_role`（`status=1` 且时效内、角色/系统启用）推导，并排除 `platform` 平台系统；响应结构仍为 `{code,name,region,isDefault}`。
- 用户**没有任何有效角色**时权限中心返回空数组，SDK **原样透传空数组**（不再降级为配置系统单例）；仅当权限中心不可用（回源失败）时才降级为本系统单例，保证业务页面可用。
- v2.0 已删除「用户-系统开通」概念，SDK 不再包含用户-系统开通/管理上下文 `rbac` 语义；`/rbac/me/systems` 不读取 `X-System-Code`。

## A 方案通配权限（`*` / `前缀:*`）

v2.0 A 方案下 admin/superadmin 角色只授一条 `*` 权限，openapi user-permissions 可能下发 `*` 或 `前缀:*`（如 `order:*`）。SDK 权限判定统一使用内部 `RbacPermissionMatcher`（Java 8 兼容实现，与后端 `@perm`、前端 `hasPerm` 口径一致）：

- **精确匹配**：权限码完全相等；
- **`*`**：匹配任意权限码（当前系统全部权限）；
- **`前缀:*`**（如 `order:*`）：匹配以 `前缀:` 开头的权限码（如 `order:delete`、`order:view:amount`）。

`/rbac/me/bootstrap` 的 `permissions` 原样透传缓存中的有效权限码：包含 `*` 时仅返回 `["*"]`（`platformAdmin=true`），前缀通配条目（如 `order:*`）原样返回，业务前端 `hasPerm` 需使用同一匹配口径。

## 与 JDK 8 版的实现差异

| 项 | JDK 8 版 | JDK 21 版（本模块） |
|---|---|---|
| 自动配置 | `META-INF/spring.factories` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Servlet | `javax.servlet` | `jakarta.servlet` |
| HTTP 客户端 | Feign 11 + OkHttp 3 | Spring 7 `RestClient` |
| JSON | Jackson 2 | Jackson 3（`tools.jackson`） |
| 字节码 | Java 8（major=52） | Java 21（major=65） |

## 构建与测试

```bash
# 仓库根（JDK 21）
mvn -pl telbank-rbac2-starter clean test
```

CI：`.github/workflows/sdk-jdk8-compat.yml` 的 `build-sdk-jdk21` job（测试 + 字节码校验 + JDK 21 示例业务系统构建）。
