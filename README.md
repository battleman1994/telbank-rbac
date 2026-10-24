# telbank-rbac 权限管理系统

统一权限中心 + 多业务系统权限隔离 + 零侵入 SDK 的 RBAC 权限管理平台。权限中心只做授权、不做认证、不管理菜单；业务系统前端不直连权限中心；SDK 以 JDK 8 字节码运行在业务系统后端，自动注册接口拦截器与 `/rbac/me/*` 接口。

[![SDK JDK 8 compatibility](https://github.com/battleman1994/telbank-rbac/actions/workflows/sdk-jdk8-compat.yml/badge.svg)](https://github.com/battleman1994/telbank-rbac/actions/workflows/sdk-jdk8-compat.yml)
[![Repo](https://img.shields.io/badge/GitHub-battleman1994%2Ftelbank--rbac-blue)](https://github.com/battleman1994/telbank-rbac)

**文档地图**：[`docs/README.md`](docs/README.md) —— 需求基线 / 接口契约 / 验收矩阵 / 各端说明与文档维护规则。

需求基线：[`docs/telbank-rbac.md`](docs/telbank-rbac.md)　接口/实现契约：[`docs/impl/contracts.md`](docs/impl/contracts.md)　验收矩阵：[`docs/acceptance.md`](docs/acceptance.md)

---

## 1. 工程组成

| 工程 | 目录 | 说明 | 技术栈 |
|---|---|---|---|
| 聚合工程 | 仓库根 `pom.xml` | Maven reactor 聚合三个后端模块（api + 两个 SDK 变体） | Maven 3.6.3+ |
| **权限中心后端** | `telbank-rbac-api/` | 权限码/角色/授权/页面元数据/接口映射的唯一数据源；管理端 `/api/**` + SDK 开放接口 `/openapi/**` + 审批回调 | JDK 21、Spring Boot 4.1.2、Spring Security 7.1、MySQL 8、Flyway、Caffeine + Redis |
| **业务系统 SDK** | `telbank-rbac-starter/` | 业务系统后端引入即用：接口权限拦截、`/rbac/me/*`、端点扫描上报、映射/权限集缓存（带时效 + 时钟偏移）、Redis Pub/Sub + 兜底轮询、Feign + OkHttp、AppKey 签名 | JDK 8 字节码（构建工具链 JDK 17+）、Spring Boot 2.7.18 |
| **JDK 21 业务系统 SDK** | `telbank-rbac-starter-jdk21/` | 与 JDK 8 版同协议/同配置键/同类名，供 JDK 21 + Boot 4 业务系统接入（同一系统只能二选一） | JDK 21、Spring Boot 4.x、Spring 7 RestClient、Jackson 3 |
| **权限中心管理前端** | `telbank-rbac-front/` | 权限中心自己的 Web 管理界面 | React 19.2、Vite 8、TypeScript 5、Ant Design 6、TanStack Query v5、Zustand v5、原生 fetch |

```text
telbank-rbac/
├── docs/
│   ├── README.md                  # 文档总览（权威性/维护规则/修订记录）
│   ├── telbank-rbac.md            # 需求基线（含附录 A 实现修订记录）
│   ├── acceptance.md              # 第 9 节验收矩阵与证据
│   └── impl/contracts.md          # 三端接口契约 + 偏差口径
├── scripts/                       # 数据库初始化、启动、演示 JWT、签名、构建、一键验收
├── .github/workflows/             # CI（三端构建 + JDK 8 运行时兼容性）
├── samples/business-demo/         # 示例业务系统（JDK 8 + Boot 2.7，验收用）
├── samples/business-demo-jdk21/   # 示例业务系统（JDK 21 + Boot 4，验收用）
├── telbank-rbac-api/              # 权限中心后端（JDK 21 + Boot 4.1.x）
├── telbank-rbac-starter/          # SDK（JDK 8 字节码 + Boot 2.7.x）
├── telbank-rbac-starter-jdk21/    # SDK（JDK 21 + Boot 4.x，协议一致）
├── telbank-rbac-front/            # 管理前端（React 19 + Vite 8）
└── pom.xml                        # Maven 聚合工程
```

> 说明：SDK 与权限中心运行环境完全隔离。SDK 以 Maven 依赖交付，业务系统是 JDK 8 + Spring Boot 2.x，权限中心是 JDK 21 + Boot 4.1.x，两者只通过 HTTPS + 区域化 Redis Pub/Sub 通信。**SDK 的构建工具链为 JDK 17+（推荐 JDK 17），使用 `--release 8` 产出 Java 8 字节码，因此可直接运行在 JDK 8 业务系统；不需要用 JDK 8 来构建 SDK。**

### 1.1 权限中心自身接入（自举）

权限中心自己也作为系统 `rbac` 接入同一套权限模型（**不引入 SDK**，避免自调用循环）：

- `sys_system` 注册 `rbac`（权限中心）系统；管理权限码 `rbac:*` 与 `rbac-admin` 角色归属该系统；
- 管理端 `/api/**` 的鉴权上下文固定为 `X-System-Code: rbac`，业务系统选择仅作为查询参数 `systemId`；
- 管理前端登录后调用 `GET /api/me/bootstrap` 获取管理权限码，按 `hasPerm` 过滤菜单/按钮；
- 演示管理员 `13800138000` 已接入 `rbac` 系统（`rbac-admin` 角色）；平台超管仍由 `rbac.platform-admin-ids` 配置短路。

---

## 2. 功能特性

- **多系统隔离**：角色、权限码、授权关系、页面操作元数据全部按 `system_id` 隔离；同一权限码在不同系统可重复（如 CRM 与 ERP 都有 `order:delete`）。
- **认证外部化**：外部系统签发 JWT（`sub` = `external_user_id`），权限中心作为 OAuth2 Resource Server 只校验、不签发、无登录页；不建用户表。
- **一个权限码前后端共用**：`sys_permission.method/api_pattern` 非空才进入 SDK 接口映射表做后端拦截；为空只做前端展示控制。
- **页面操作元数据（模式 B）**：标准 CRUD 操作区按钮由 `sys_page_action` 驱动，增删授权即可控制按钮，无需改前端代码；复杂页面特殊按钮用 `hasPerm(code)`（模式 A）。
- **权限时效**：授权支持 `valid_from`/`valid_until`，审批通过后授予，到期即视为无权限（查询层过滤 + 定时任务收回 + 通知刷新）。
- **SDK 零侵入**：加依赖 + 两行配置；`rbac-sdk.enabled` 总开关整套启停；自动注册拦截器与 `/rbac/me/*`；全量端点扫描上报；默认 public、映射未命中直接放行，可用 `unmatched-strategy: deny` 收紧。
- **安全链路**：管理端 JWT + `X-System-Code`；SDK/审批系统 → 权限中心用 AppKey + HMAC-SHA256 签名（时间戳窗口 + Nonce 防重放）；强制 HTTPS（生产）。

---

## 3. 环境要求

| 组件 | 版本 | 用途 |
|---|---|---|
| JDK | **21+**（推荐 21 LTS） | 构建/运行权限中心后端 |
| JDK（SDK） | JDK 8 版：构建 17+ → 产物 Java 8 / 运行 8+；JDK 21 版：JDK 21 → 产物 Java 21 / 运行 21+ | 构建 SDK；按业务系统 JDK/Boot 版本选择变体 |
| Maven | 3.6.3+ | 构建两个后端模块 |
| Node.js | **22.22+**（推荐用 nvm 管理） | 构建/运行管理前端 |
| MySQL | 8.0+ | 权限中心数据存储 |
| Redis | 6+（按区域各一套） | 权限变更 Pub/Sub（本地验收可临时关闭，见 §5.2） |
| 浏览器 | Chrome/Edge/Safari 最新版 | 管理前端 |

本仓库在 macOS 上使用如下方式管理工具链（其他平台等价即可）：

```bash
# Node（nvm）
export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use 22

# JDK 21（示例：Homebrew 或任意发行版；确保 java -version 为 21+）
java -version
```

---

## 4. 快速开始（本地 demo）

### 4.1 初始化数据库

```bash
export MYSQL_USER=root MYSQL_PASSWORD='你的密码'
./scripts/init-db.sh
```

> 只需建空库 `telbank_rbac`；8 张表与 CRM/ERP 示例数据由权限中心启动时 Flyway 自动迁移（`telbank-rbac-api/src/main/resources/db/migration/`）。
> 如果本机已有 MySQL 且 root 密码未知，可起一个一次性实例：`./scripts/start-temp-mysql.sh start`（127.0.0.1:3307，root 无密码），启动后端时用 `RBAC_DB_URL` 指向它（脚本会打印完整命令）。

### 4.2 启动权限中心后端（demo profile）

```bash
./scripts/start-api.sh
```

demo profile 特点：`rbac.security.jwt.mode=hs256`（演示 JWT）、`rbac.redis.enabled=false`（无 Redis 也能跑；变更发布降级为 no-op，nonce 用本地缓存），默认端口 `8080`。

### 4.3 生成演示 JWT 并登录管理前端

```bash
# 生成 token（外部认证系统在生产环境负责签发；demo 用 HS256 脚本模拟）
TOKEN=$(./scripts/gen-demo-jwt.sh 13800138000)

# 验证后端
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/me
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/me/systems
```

启动前端并在浏览器打开 <http://localhost:5173>，在登录页粘贴上面的 `TOKEN`：

```bash
./scripts/start-front.sh
```

示例账号（`external_user_id`，对应 Seed 数据）：

| external_user_id | 身份 | 说明 |
|---|---|---|
| `13800138000` | CRM 管理员 + ERP 管理员 + 权限中心管理员 | 三个系统均有角色（`rbac` 系统为 `rbac-admin`），管理前端用它登录 |
| `13900139000` | CRM 运营 | 临时授权（`order:delete`/`order:export` 到 2027-06-30），未开通 ERP |
| `13600136000` | CRM 运营 + ERP 采购员 | 跨系统隔离验收：CRM 有临时 `order:delete`，ERP 没有 |
| `13700137000` | 已过期用户 | 授权已过期，用于验证自动收回与时效过滤 |
| `platform-admin-001` | 平台超管 | 命中 `rbac.platform-admin-ids`，跳过权限校验 |

### 4.4 生产 profile

```bash
export RBAC_JWK_SET_URI=https://auth.example.com/.well-known/jwks.json
export RBAC_JWT_ISSUER=https://auth.example.com
export RBAC_DB_USER=rbac RBAC_DB_PASSWORD='***'
export RBAC_CRM_SECRET='***' RBAC_ERP_SECRET='***' RBAC_APPROVAL_SECRET='***'
export RBAC_REDIS_REGION_A_HOST=redis-a.internal RBAC_REDIS_REGION_A_PASSWORD='***'
java -jar telbank-rbac-api/target/telbank-rbac-api-1.0.0.jar --spring.profiles.active=prod
```

---

## 5. SDK 接入指南（业务系统后端）

### 5.1 引入依赖与配置（业务系统：JDK 8 + Spring Boot 2.7.x）

> 构建口径：SDK 由 **JDK 17 工具链**构建（`--release 8`），产物为 Java 8 字节码，业务系统 JDK 8 直接引入即可；详见 §8 的 `./scripts/build-sdk.sh`。

```xml
<dependency>
  <groupId>com.telbank</groupId>
  <artifactId>telbank-rbac-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
rbac-sdk:
  enabled: true                  # 总开关：true 整套逻辑生效，false 完全不注册
  system-code: crm
  platform-base-url: https://rbac.example.com
  app-key: crm
  app-secret: ${RBAC_SDK_APP_SECRET}
  endpoint:
    base-path: /rbac
  scan:
    base-packages: com.telbank.crm
    report-on-startup: true
  interceptor:
    unmatched-strategy: pass      # pass / deny
    exclude-paths:
      - /actuator/**
      - /error
      - /favicon.ico
  cache:
    fallback-poll-interval: 300s
  code-generator:
    pattern: "{systemCode}:{resource}:{action}"
    resource-from: controller-class
    action-from: http-method
```

接入后业务系统**零代码**即获得（`rbac-sdk.enabled=true` 时 SDK 自动注册）：

- `RbacPermissionFilter`：按「HTTP Method + Path → 权限码」映射校验业务接口；
- `GET /rbac/me/systems`、`GET /rbac/me/bootstrap`：给业务前端取系统列表 / 权限码 + 页面操作元数据；
- 启动时全量端点扫描上报；映射/权限集/页面元数据缓存；Redis Pub/Sub 刷新 + 短轮询兜底。

### 5.2 自定义候选权限码（可选）

```java
@RbacPermission(value = "order:delete", name = "删除订单")
@DeleteMapping("/api/order/{id}")
public void deleteOrder(@PathVariable Long id) { ... }
```

未加注解的接口按 `code-generator` 规则生成候选码（如 `OrderController#deleteOrder + DELETE → crm:order:delete`）。扫描到的候选码会全量上报权限中心，由管理员在「SDK 端点上报」页关联到权限码并配置 `method/api_pattern` 后，接口拦截才生效。带 `@PreAuthorize`/`@Secured` 的接口 SDK 完全跳过。

### 5.3 编程式查询（可选）

```java
@Autowired
private RbacClient rbacClient;

Set<String> codes = rbacClient.getPermissions(externalUserId, "crm");
List<PageAction> actions = rbacClient.getPageActions("crm", "order:list");
boolean platformAdmin = rbacClient.isPlatformAdmin(externalUserId);
```

### 5.4 业务前端（不在本仓库）

业务系统前端路由/菜单由前端代码维护，通过 `hasPerm(code)` 过滤；数据从业务系统后端的 `/rbac/me/bootstrap`（SDK 自动注册）或业务后端自封装接口获取。返回：`permissions`、`pageActions`、`platformAdmin`。

### 5.5 JDK 21 + Spring Boot 4 业务系统接入

JDK 21 + Spring Boot 4.x 业务系统使用 **JDK 21 版 SDK**（协议、配置键、类名与 JDK 8 版完全一致，仅实现层适配 Boot 4）：

```xml
<dependency>
  <groupId>com.telbank</groupId>
  <artifactId>telbank-rbac-starter-jdk21</artifactId>
  <version>1.0.0</version>
</dependency>
```

- `application.yml` 配置与 §5.1 完全相同（`rbac-sdk.*`），认证/缓存/通知/拦截行为一致；
- 实现差异：`jakarta.servlet`、Spring 7 `RestClient`、Jackson 3、Boot 4 自动配置（`AutoConfiguration.imports`）；
- 两个 SDK 变体使用相同包名，**同一业务系统只能引入一个**，不要同时出现在 classpath。

---

## 6. 审批系统接入

审批系统在审批通过后回调权限中心（AppKey + HMAC-SHA256 签名，与 SDK 同一套算法，见契约 §4.1）：

```bash
# 生成签名并发起回调（scripts/sign-request.py 可打印可直接执行的 curl）
python3 scripts/sign-request.py \
  --app-key approval-system --app-secret "${RBAC_APPROVAL_SECRET}" \
  --method POST --path /openapi/approval/grant \
  --body '{"systemCode":"crm","externalUserId":"13900139000","grantType":"role","roleId":2,"validFrom":"2026-10-24T00:00:00Z","validUntil":"2027-06-30T23:59:59Z","approvalId":"APV-20261024-100","reason":"临时运营权限申请"}'
```

响应：`{"code":0,"data":{"success":true,"grantId":...,"idempotent":false}}`。相同 `approvalId` 重复回调幂等返回（`idempotent=true`）。授权写入后权限中心按系统 `region` 发布 Redis 通知，SDK 刷新缓存；到期后查询层立即失效，定时任务再标记 `status=0` 并通知。

---

## 7. 配置与安全说明

### 7.1 权限中心关键配置（`application.yml` / 环境变量）

| 配置 | 说明 |
|---|---|
| `rbac.platform-admin-ids` | 平台超管 `external_user_id` 列表，命中即跳过权限校验 |
| `rbac.management-system-code` | 管理端鉴权使用的系统 code，默认 `rbac`（权限中心自身系统） |
| `rbac.security.jwt.mode` | `jwk`（生产，JWKS 校验）或 `hs256`（demo） |
| `rbac.security.jwt.jwk-set-uri` / `issuer` | OAuth2 Resource Server 校验参数 |
| `rbac.clients[]` | 各业务系统的 AppKey / system-code / AppSecret（secret 用环境变量注入） |
| `rbac.approval.app-key/app-secret` | 审批系统回调凭据 |
| `rbac.regions.{region}` | 各区域 Redis 连接（host/port/password/channel-prefix），SDK 通过 `/openapi/sdk/connection` 获取 |
| `rbac.redis.enabled` | `false` 时用于无 Redis 的本地联调（变更发布 no-op、nonce 本地缓存） |
| `rbac.expiry-scan-cron` | 过期授权自动收回任务 cron，默认每分钟 |

### 7.2 安全约束

- 生产必须 HTTPS（SDK ↔ 权限中心、审批系统 ↔ 权限中心），只在 HTTPS 通道内传输 Redis 连接配置与权限数据。
- `AppSecret`、Redis 密码、数据库密码不在仓库/配置中明文出现，一律环境变量注入（见契约 §9.4）。
- 签名校验：时间戳窗口 5 分钟 + Nonce 防重放 + HMAC-SHA256 常量时间比较。
- 前端隐藏按钮只是体验优化；**后端接口拦截才是安全边界**。
- demo profile 的 HS256 密钥仅用于本地，禁止用于任何共享/生产环境。

---

## 8. 构建与测试

```bash
# 一键验证（不需要数据库/Redis：后端单测 + SDK 单测 + 前端类型检查/构建 + 产物核对）
./scripts/verify-all.sh

# 权限中心后端（JDK 21）编译 + 单元测试
mvn -pl telbank-rbac-api -am test

# SDK（JDK 8 版：推荐用脚本，JDK 17 工具链 → Java 8 字节码，并校验 major=52）
./scripts/build-sdk.sh
# 等价手动命令（JDK 17）：
#   JAVA_HOME=<jdk17> mvn -pl telbank-rbac-starter clean test

# SDK（JDK 21 版：JDK 21 + Boot 4，协议一致）
mvn -pl telbank-rbac-starter-jdk21 test

# 管理前端（nvm node 22）
export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use 22
cd telbank-rbac-front && npm install --no-audit --no-fund && npm run build

# 示例业务系统（SDK 端到端验收，非交付物）
mvn -pl telbank-rbac-starter install -DskipTests
mvn -f samples/business-demo/pom.xml -DskipTests package
# JDK 21 版 SDK 的示例业务系统（JDK 21 构建）
mvn -pl telbank-rbac-starter-jdk21 install -DskipTests
mvn -f samples/business-demo-jdk21/pom.xml -DskipTests package
```

验收矩阵与逐条证据见 [`docs/acceptance.md`](docs/acceptance.md)；本地冒烟脚本：

```bash
# 需先启动权限中心后端（demo profile）
python3 scripts/acceptance.py

# 额外跑 SDK 端到端（先按 samples/business-demo/README.md 启动 CRM:8081 / ERP:8082）
RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 \
  python3 scripts/acceptance.py --only 9.1b

# JDK 21 版 SDK 端到端（samples/business-demo-jdk21，CRM:8091 / ERP:8092）
RBAC_DEMO_JDK21_CRM_URL=http://localhost:8091 RBAC_DEMO_JDK21_ERP_URL=http://localhost:8092 \
  python3 scripts/acceptance.py --only 9.1c
```

---

## 9. 与需求文档的偏差（已拍板，实现按此执行）

需求基线 `docs/telbank-rbac.md` 中有几处内部冲突/未定义，实现口径如下。编号与需求文档「附录 A 实现修订记录」一一对应，详细论证见 `docs/impl/contracts.md` §9：

1. **A1 · SDK 端点上报缺少持久化表**：需求 7 张表无法承载 §5.7 的端点上报列表，实现新增第 8 张表 `sys_sdk_endpoint`（其余 7 张严格按需求）。
2. **A2 · “重新提权旧记录置 status=0、新记录插入”与唯一键冲突**：`sys_user_role`/`sys_role_permission` 的唯一键不允许同一关系存在两条记录，两者不可同时满足。实现为**同关系更新原行**（`valid_from/valid_until/source_approval_id/grant_reason/status`），不新增行。
3. **A3 · 审批回调 `grantType=permission` 无法落地**：无 user_permission 表，实现仅支持 `grantType=role`，`permission` 返回 400 并说明；`permissionCodes` 仅用于校验审批内容与角色授权一致。
4. **A4 · AppSecret / 区域 Redis 密码“数据库加密存储”无对应表**：实现为环境变量注入（配置中仅 `${ENV:本地默认值}`），未新增密钥表。
5. **A5 · `api_pattern` 仅写“Ant 风格”**：实现同时接受 Spring 模板 `{id}`（单段，与 `*` 等价）与 Ant `*`、末尾 `**`；`**` 非末尾视为非法。
6. **A6 · `/rbac/me/systems` 数据来源未定义**：新增开放接口 `GET /openapi/sdk/user-systems` 供 SDK 使用（管理端 `/api/user-systems` 需 JWT，SDK 不能直接调用）。
7. **A7 · SDK「JDK 8 字节码」的构建方式**：字节码目标保持 Java 8（major=52），但构建工具链为 **JDK 17+**（`--release 8`）——Apple Silicon 无官方 JDK 8；运行时仍兼容 JDK 8+（GitHub Actions 已用真实 JDK 8 运行时验证）。
8. **A8 · 权限中心自身接入方式**：需求未定义。实现为权限中心作为 `rbac` 系统接入自身权限模型（管理端固定管理上下文 + `/api/me/bootstrap` + 前端按权限渲染菜单）；**不引入 SDK**（避免自调用循环）。
9. **A9 · SDK 的 JDK 版本覆盖**：需求只定义 JDK 8 版 SDK；实现新增 JDK 21 + Spring Boot 4 变体（协议/配置一致、同包名不可共存），供 JDK 21 业务系统接入。

其他实现说明（非偏差，需求本身已明确）：

- **缓存口径**：管理端高频查询用 Caffeine 一级缓存；Redis 用于 Pub/Sub、Nonce 防重放与通知，不实现复杂跨节点缓存协议。
- **不容器化**：按需求只交付源码 + 启动脚本，不提供 Docker/K8s 清单。
- **不存储菜单**：权限中心不管理菜单/路由结构（需求 §2），由业务系统前端自行维护。

> 版本说明：需求锁定 React 19.2.x / Vite 8 / antd 6 / React Router 8 / TanStack Query v5 / Zustand v5；`telbank-rbac-front/package.json` 按此约束（React `~19.2.0`），lockfile 实际解析为 React 19.2.9 / Vite 8.3.5 / antd 6.6.7 / React Router 8.5.0 / TanStack Query 5.104.5 / Zustand 5.0.16。

---

## 10. 常见问题

- **启动报 `Access denied for user 'root'@'localhost'`**：检查 `MYSQL_USER/MYSQL_PASSWORD`，或先 `./scripts/init-db.sh`。
- **JDK 版本不对**：权限中心需要 **JDK 21+**（`java -version` 校验；`./scripts/start-api.sh` 会自动寻找 JDK 21+/SDKMAN/Homebrew 路径）；SDK 用 **JDK 17+ 构建**（产物 Java 8 字节码），业务系统运行时 **JDK 8+** 即可。
- **没有 Redis**：demo profile 已关闭 Redis（`RBAC_REDIS_ENABLED` 也可控制）；SDK 侧在真实环境会自动降级为短轮询。
- **前端 401**：JWT 过期（默认 24h）或 `iss`/签名不匹配，重新生成 `TOKEN`；demo 密钥需与 `application-demo.yml` 一致。
- **切系统后接口 403**：该用户未被开通目标系统（`sys_user_system`）或没有对应角色/权限码，到管理端「用户-系统开通」「用户-角色分配」处理。
- **管理端接口 403**：管理端鉴权走 `rbac` 管理系统上下文；确认当前用户在 `rbac` 系统有 `rbac-admin`（或等价 `rbac:*`）角色，或把 `external_user_id` 加入 `rbac.platform-admin-ids`。
- **SDK 接口未被拦截**：确认权限中心该系统的「权限码」已填写 `method + api_pattern`（SDK 只下发非空记录），且业务系统 `system-code` 与 `rbac.clients` 一致。

---

## 11. 本次交付验证记录（摘要）

- 权限中心后端：`clean test` **53/53 PASS**（JDK 21.0.13，字节码 major=65）。
- SDK（JDK 8 版）：`./scripts/build-sdk.sh`（**JDK 17 工具链**）→ **90/90 PASS**，产物字节码 **major=52（Java 8）**；JDK 21 下同样 90/90。
- SDK（JDK 21 版）：`mvn -pl telbank-rbac-starter-jdk21 clean test`（JDK 21）→ **91/91 PASS**，字节码 **major=65**；JDK 21 示例业务系统端到端 **4/4 PASS**。
- **SDK 的 JDK 8 兼容性（GitHub Actions 实测）**：workflow 通过 —— JDK 17 构建 SDK（90 单测 + 断言 major=52）→ 在 **JDK 8 运行时**启动示例业务系统并成功探测 `/api/health`（[Actions](https://github.com/battleman1994/telbank-rbac/actions/workflows/sdk-jdk8-compat.yml)）。
- **权限中心自接入（rbac 系统）**：V1+V2+V3 迁移；管理员 bootstrap 返回 5 个 `rbac:*`、operator 空集、平台超管 `["*"]`；管理端 `X-System-Code: rbac` 200 / 业务系统上下文 403；管理前端按权限码过滤菜单与路由、无权限 403 页。
- **真实 Redis Pub/Sub（本机实测）**：Redis 8.10.2 + 两区域频道（`rbac:change:region_a:crm` / `region_b:erp`）；变更后 API 发布日志 `receivers=1`，CRM/ERP SDK 各自收到 `PAGE_ACTION_CHANGED` / `PERMISSION_CHANGED` 并刷新缓存；过期收回任务同样经 Redis 发布。
- 第 9 节全量验收：**65/65 PASS**（JDK 21 运行 + 干净库 + JDK 8/JDK 21 两个 SDK 端到端各 4/4）。
- 管理前端：`tsc -b && vite build` 通过（3257 modules），dist 1.4 MB（JS 1,410.82 kB / gzip 444.74 kB）；与真实后端的浏览器人工联调待补（当前为 build + mock 冒烟，见验收文档）。
- 一键验证：`./scripts/verify-all.sh` → **全部通过（8 项检查）**；已知限制见 [`docs/acceptance.md`](docs/acceptance.md)。

---

## 12. 许可

内部交付工程（示例数据仅用于演示）。
