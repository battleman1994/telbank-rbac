# 示例业务系统（JDK 21 + Boot 4 版，验收用，非交付物）

模拟 **JDK 21 + Spring Boot 4.x** 业务系统，引入 `telbank-rbac2-starter`（JDK 21 版 SDK），用于验证新 SDK 的真实拦截链路与多系统隔离。协议/配置与 JDK 8 版示例一致。

> 构建工具链：JDK 21（`mvn ... -Dmaven.repo.local` 可选）；运行 JDK 21+。

## 1. 构建

```bash
# 仓库根：先安装 JDK 21 版 SDK 到本地仓库
mvn -pl telbank-rbac2-starter install -DskipTests

# 构建示例业务系统
mvn -f samples/business-demo-jdk21/pom.xml -DskipTests package
```

## 2. 启动 CRM / ERP 两个实例

```bash
# 终端 1：CRM（system-code=crm，端口 8091）
java -jar samples/business-demo-jdk21/target/business-demo-jdk21-1.0.0.jar

# 终端 2：ERP（system-code=erp，端口 8092）
java -jar samples/business-demo-jdk21/target/business-demo-jdk21-1.0.0.jar \
  --server.port=8092 \
  --rbac-sdk.system-code=erp \
  --rbac-sdk.app-key=erp \
  --rbac-sdk.app-secret=erp-dev-secret
```

前置：权限中心 demo profile 已启动（`./scripts/start-all-macos.sh`，Linux 用 `start-all-linux.sh`）。

## 3. 验证接口拦截

```bash
TOKEN=$(./scripts/gen-demo-jwt.sh 13600136000)   # CRM 有临时 order:delete，ERP 采购员无

# CRM：有权限 → 200
curl -i -X DELETE http://localhost:8091/api/order/1 -H "Authorization: Bearer $TOKEN"

# ERP：命中映射但无权限 → 403
curl -i -X DELETE http://localhost:8092/api/order/1 -H "Authorization: Bearer $TOKEN"

# 未配置权限码的接口：映射未命中 → 直接放行
curl -i http://localhost:8091/api/health
```

预期：CRM 200、ERP 403（JDK 21 版 SDK 拦截）、`/api/health` 200；无 Redis/权限中心不可达时 SDK 自动降级，业务不中断。
