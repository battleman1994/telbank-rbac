# telbank-rbac-starter-jdk21（JDK 21 / Spring Boot 4.x 版 SDK）

供 **JDK 21 + Spring Boot 4.x** 业务系统接入的 RBAC SDK 变体。协议、配置键、类名、包名与 JDK 8 版 `telbank-rbac-starter` **完全一致**，仅实现层适配 Boot 4。

- 需求基线：[`docs/telbank-rbac.md`](../docs/telbank-rbac.md)
- 接口契约（§0/§4/§7.9）：[`docs/impl/contracts.md`](../docs/impl/contracts.md)
- 交付说明与 JDK 8 版接入示例：[`README.md`](../README.md) §5
- 文档总览：[`docs/README.md`](../docs/README.md)

## 引入

```xml
<dependency>
  <groupId>com.telbank</groupId>
  <artifactId>telbank-rbac-starter-jdk21</artifactId>
  <version>1.0.0</version>
</dependency>
```

配置与 JDK 8 版相同（`rbac-sdk.*`，见需求 §6.13 / README §5.1）。

> **注意**：两个 SDK 变体使用相同包名（`com.telbank.rbac.sdk`），同一业务系统只能引入一个，**不能同时出现在 classpath**。

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
mvn -pl telbank-rbac-starter-jdk21 clean test
```

CI：`.github/workflows/sdk-jdk8-compat.yml` 的 `build-sdk-jdk21` job（测试 + 字节码校验 + JDK 21 示例业务系统构建）。
