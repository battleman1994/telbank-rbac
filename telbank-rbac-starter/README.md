# telbank-rbac-starter（JDK 8 / Spring Boot 2.7.x 版 SDK）

供 **JDK 8 字节码 + Spring Boot 2.7.x** 业务系统接入的 RBAC SDK。协议、配置键、类名、包名与 JDK 21 版 `telbank-rbac2-starter` **完全一致**，仅实现层分别为 Feign/OkHttp + Jackson 2（本模块）与 RestClient + Jackson 3。

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

## 与 JDK 21 版的实现差异

| 项 | JDK 8 版（本模块） | JDK 21 版 |
|---|---|---|
| 自动配置 | `META-INF/spring.factories` | `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` |
| Servlet | `javax.servlet` | `jakarta.servlet` |
| HTTP 客户端 | Feign 11 + OkHttp 3 | Spring 7 `RestClient` |
| JSON | Jackson 2 | Jackson 3（`tools.jackson`） |
| 字节码 | Java 8（major=52） | Java 21（major=65） |

## 构建与测试

```bash
# 仓库根（构建工具链 JDK 17+，--release 8 产出 Java 8 字节码）
mvn -pl telbank-rbac-starter clean test
```

运行环境要求 **JDK 8+**（CI 已用真实 JDK 8 运行时验证）；构建工具链为 JDK 17+（Apple Silicon 无官方 JDK 8，见根 README §9 A7）。示例业务系统见 [`samples/business-demo`](../samples/business-demo/README.md)。
