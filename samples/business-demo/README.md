# 示例业务系统（验收用，非交付物）

模拟业务系统 **JDK 8 运行时 + Spring Boot 2.7.x** 环境，引入 `telbank-rbac-starter`，用于验证 SDK 的真实拦截链路与多系统隔离。

> 构建口径：**构建工具链 JDK 17+**（`--release 8`），产物为 Java 8 字节码；运行环境 **JDK 8+**（CI 已用真实 JDK 8 运行时验证，见 `.github/workflows/sdk-jdk8-compat.yml`）。

> 说明：真实业务系统的登录/JWT 校验由业务系统自己负责；本示例不做认证，SDK 按契约 §7.6 从 `Authorization: Bearer <JWT>` 的 payload 解析 `sub` 作为 `external_user_id`（这是 SDK 的兜底路径，用来演示）。

## 1. 构建

```bash
# 仓库根：先安装 SDK 到本地仓库（JDK 17+ 工具链；可用 ./scripts/build-sdk.sh 先做单测+字节码校验）
mvn -pl telbank-rbac-starter install -DskipTests

# 构建示例业务系统（JDK 17+ 编译，产物为 Java 8 字节码）
mvn -f samples/business-demo/pom.xml -DskipTests package
```

## 2. 启动 CRM / ERP 两个实例

```bash
# 终端 1：CRM（system-code=crm，端口 8081）
java -jar samples/business-demo/target/business-demo-1.0.0.jar

# 终端 2：ERP（system-code=erp，端口 8082）
java -jar samples/business-demo/target/business-demo-1.0.0.jar \
  --server.port=8082 \
  --rbac-sdk.system-code=erp \
  --rbac-sdk.app-key=erp \
  --rbac-sdk.app-secret=erp-dev-secret
```

前置：权限中心 demo profile 已启动（`./scripts/start-api.sh`）。运行 JDK 可用 JDK 8+（CI 环境用 JDK 8 验证；本机 JDK 17 亦可）。demo profile 默认关闭 Redis；如需验证真实 Pub/Sub，启动权限中心时追加 `--rbac.redis.enabled=true` 并确保本机 Redis 可用。

## 3. 验证接口拦截

```bash
# 生成用户 JWT（13600136000：CRM 有临时 order:delete，ERP 采购员无 order:delete）
TOKEN=$(./scripts/gen-demo-jwt.sh 13600136000)

# CRM：有权限 → 200
curl -i -X DELETE http://localhost:8081/api/order/1 -H "Authorization: Bearer $TOKEN"

# ERP：命中映射但无权限 → 403
curl -i -X DELETE http://localhost:8082/api/order/1 -H "Authorization: Bearer $TOKEN"

# 未配置权限码的接口：映射未命中 → 直接放行（无需 token）
curl -i http://localhost:8081/api/health
```

预期：CRM 返回 200，ERP 返回 403（SDK 拦截），`/api/health` 返回 200。若 SDK 连不上权限中心或 Redis，会降级为短轮询/本地缓存，业务请求不中断。
