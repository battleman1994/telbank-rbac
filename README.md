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
| **权限中心后端** | `telbank-rbac-api/` | 权限码/角色/授权/页面元数据/接口映射的唯一数据源；管理端 `/api/**` + SDK 开放接口 `/openapi/**` + 审批回调 | JDK 21、Spring Boot 4.1.2、Spring Security 7.1、MySQL 8（手动 SQL 初始化，无 Flyway）、MyBatis-Plus 3.5.17（Boot4 starter）、Caffeine + Redis |
| **业务系统 SDK** | `telbank-rbac-starter/` | 业务系统后端引入即用：接口权限拦截、`/rbac/me/*`、端点扫描上报、映射/权限集缓存（带时效 + 时钟偏移）、Redis Pub/Sub + 兜底轮询、Feign + OkHttp、AppKey 签名 | JDK 8 字节码（构建工具链 JDK 17+）、Spring Boot 2.7.18 |
| **JDK 21 业务系统 SDK** | `telbank-rbac2-starter/` | 与 JDK 8 版同协议/同配置键/同类名，供 JDK 21 + Boot 4 业务系统接入（同一系统只能二选一） | JDK 21、Spring Boot 4.x、Spring 7 RestClient、Jackson 3 |
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
├── telbank-rbac2-starter/         # SDK（JDK 21 + Boot 4.x，协议一致）
├── telbank-rbac-front/            # 管理前端（React 19 + Vite 8）
└── pom.xml                        # Maven 聚合工程
```

> 说明：SDK 与权限中心运行环境完全隔离。SDK 以 Maven 依赖交付，业务系统是 JDK 8 + Spring Boot 2.x，权限中心是 JDK 21 + Boot 4.1.x，两者只通过 HTTPS + 区域化 Redis Pub/Sub 通信。**SDK 的构建工具链为 JDK 17+（推荐 JDK 17），使用 `--release 8` 产出 Java 8 字节码，因此可直接运行在 JDK 8 业务系统；不需要用 JDK 8 来构建 SDK。**

### 1.1 系统隔离模型与数据驱动超管（v2.0）

- **系统是隔离边界**：角色、权限码、角色授权、用户授权、页面元素、资源映射都归属具体业务系统；系统管理员登录后只看到/只能管理自己系统内的数据，完全看不到其他系统，也看不到「系统接入」。
- **平台超管是数据驱动的**：平台系统 `platform`（`sys_system.is_platform=1`）内的 `super-admin` 角色持有 `platform:admin` 权限；种子超管账号 **`superadmin`**。后端不再读取 `rbac.platform-admin-ids` 等配置文件。
- **管理上下文**：所有管理端 `/api/**` 带 `X-System-Code=<当前业务系统>`；普通用户必须在该系统有有效角色，且查询/写入的 `systemId` 必须等于上下文；平台超管可切换任意业务系统、可跨系统查询。
- **用户可访问系统由有效角色推导**：不再有「用户-系统开通」表和接口；用户在某系统有有效角色（`sys_user_role` status=1 且时效内）即视为可访问。
- **系统接入（仅超管，双通道）**：支持手动录入，也支持 SDK 启动自动上报（onboarding AppKey/HMAC）生成待审核申请；审核通过后自动创建系统、默认 `admin` 角色 + **一条 `*` 通配权限**并授权给管理员。
- **系统凭据**：每个业务系统接入时自动分配 `appKey/appSecret`；系统接入页展示 AppKey/掩码，创建/审批通过时一次性返回完整 Secret；系统管理员可在「接入凭据」页查看本系统完整密钥，平台超管可查看任意系统。
- **业务用户临时权限申请**：无管理权限的业务用户可从顶级「我的申请」选择当前系统角色并查看权限预览，提交后生成带 `RA-...` 单号与状态的申请；点击单号跳转外部审批平台，审批通过后按统一连续有效期写 `sys_user_role`，过期自动失效；权限中心不实现审批流。
- **角色授权**：`admin` 角色默认拥有全部权限，角色授权页不再列出 admin，后端也拒绝对其 grant/revoke。
- **通配权限（A 方案）**：管理员只授一条 `*`；匹配规则 `*` > `前缀:*` > 精确，后端 `@perm`、前端 `hasPerm`、SDK 拦截器一致；角色授权页可授权树不展示 `*`，非 admin 角色手工授 `*` 由后端拒绝。
- **严格系统隔离**：每次请求只查/只写 `X-System-Code` 当前系统数据；**超管也必须切换系统上下文**，禁止单请求跨系统（否则 403）。
- **菜单 IA**：`权限管理` 组（角色管理、角色授权、权限管理、资源管理、页面元素（元素标识/名称/类型）、用户授权、授权记录）+ 顶级「系统接入」（仅超管）+ 顶级「我的申请」（所有登录用户）；已移除「用户-系统开通」与「到期提醒」菜单（`/api/expiring-grants` 接口保留）。

---

## 2. 功能特性

- **多系统隔离**：角色、权限码、授权关系、页面操作元数据全部按 `system_id` 隔离；系统管理员只能管理自己系统，平台超管（数据驱动 `platform:admin`）可跨系统；用户可访问系统由有效角色推导。
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

> **一键启动（推荐，按平台）**：
>
> | 平台 | 命令（每平台仅一个自包含脚本） |
> |---|---|
> | macOS | `./scripts/start-all-macos.sh --temp-db` |
> | Linux | `./scripts/start-all-linux.sh --temp-db` |
> | Windows | `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\start-all-windows.ps1 -TempDb` |
>
> - `--temp-db`：**零依赖**——自动启动一次性临时 MySQL（默认 3307）并创建 `telbank_rbac`、导入 `db/manual/*.sql`（表结构 + 示例数据），后端就绪后再启动前端；`Ctrl+C` 同时停止前后端并清理临时库。
> - 已有 MySQL：去掉 `--temp-db`；首次建库用 `MYSQL_USER=root MYSQL_PASSWORD='***' ./scripts/start-all-macos.sh --init-db`（Linux 换成 `start-all-linux.sh`；Windows 用 `-InitDb` + 环境变量）；脚本会自动把 `MYSQL_*` 映射为后端 `RBAC_DB_*`。
> - 端口：Unix 用 `API_PORT=18080 FRONT_PORT=5174`；Windows 用 `-ApiPort 18080 -FrontPort 5174`。日志在 `logs/`。以下 4.1–4.5 为分步说明。

### 4.1 初始化数据库

```bash
export MYSQL_USER=root MYSQL_PASSWORD='你的密码'
./scripts/init-db.sh
```

> `init-db.sh` 会创建库，并按文件名顺序导入 `telbank-rbac-api/src/main/resources/db/manual/` 下的手动 SQL：
> `01_schema.sql`（8 张表，v2.0 经 `04_system_isolation.sql` 删除 `sys_user_system` 后为 7 张）、`02_seed_data.sql`（CRM/ERP 示例数据）、`03_rbac_self_onboarding.sql`（历史自接入，已被 04 清理）、`04_system_isolation.sql`（v2.0 系统隔离迁移）。
> **本项目不使用 Flyway**；已有表结构的库会自动跳过导入，`./scripts/init-db.sh --drop` 可删库重建。
> 用平台一键脚本时无需再单独导出：脚本会把 `MYSQL_USER/MYSQL_PASSWORD/MYSQL_HOST/MYSQL_PORT/RBAC_DB_NAME` 自动映射为后端的 `RBAC_DB_*`（含 `RBAC_DB_URL`）；`--temp-db` 会自动执行上述导入。
> 如果本机已有 MySQL 且 root 密码未知，直接用平台一键脚本的 `--temp-db`（自动起一次性 MySQL 3307，root 无密码并在退出时清理）。

### 4.2 启动权限中心后端（demo profile）

```bash
# 推荐直接用平台一键脚本（自动探测 JDK21，会连同前端一起启动）：
./scripts/start-all-macos.sh        # Linux 换成 start-all-linux.sh

# 只启动后端（手动）：
JAVA_HOME=<JDK21> mvn -pl telbank-rbac-api -am spring-boot:run -Dspring-boot.run.profiles=demo
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
# 前端由平台一键脚本自动启动；也可手动：
cd telbank-rbac-front && npm run dev
```

示例账号（`external_user_id`，对应 Seed 数据）：

| external_user_id | 身份 | 说明 |
|---|---|---|
| `superadmin` | **平台超管** | 平台系统 `platform` 的 `super-admin`（`platform:admin`），可切换所有业务系统 + 系统接入 |
| `crm-admin-001` | CRM 系统管理员 | 仅 CRM；持有通配 `*`（一条记录覆盖全部管理权限），看不到 ERP/系统接入 |
| `erp-admin-001` | ERP 系统管理员 | 仅 ERP；同上 |
| `13800138000` | CRM 管理员 + ERP 管理员 | 两个系统都有 admin 角色（非平台超管），用于验证双系统切换与隔离 |
| `13900139000` | CRM 运营 | 临时授权（`order:delete`/`order:export` 到 2027-06-30），仅 CRM |
| `13600136000` | CRM 运营 + ERP 采购员 | 跨系统隔离验收：CRM 有临时 `order:delete`，ERP 没有 |
| `13700137000` | 已过期用户 | 角色授权已过期 → 无可访问系统、无有效权限 |

### 4.4 时间权限演示（1 分钟过期）

`demo-temp-permission.sh` 给指定账号授予一条 N 分钟（默认 1 分钟）的临时权限，用于验证「刷新后权限消失、点击提示无权限」：

```bash
# 默认：demo-1min 账号 + 通配 * + CRM 系统 + 1 分钟
MYSQL_USER=root MYSQL_PASSWORD='***' ./scripts/demo-temp-permission.sh

# 自定义：账号 / 权限码 / 系统 / 分钟数
MYSQL_USER=root MYSQL_PASSWORD='***' ./scripts/demo-temp-permission.sh 13700137000 order:delete crm 1
```

- **1 分钟内**：按脚本提示生成 `TOKEN=$(./scripts/gen-demo-jwt.sh demo-1min)` 登录管理前端，可见「授权记录」，对应接口 200；
- **1 分钟后**：刷新页面菜单消失，直接访问 `/approval-grants` 显示无权限页（后端返回 403）；
- 重新计时：再执行一次脚本；清理演示角色：`./scripts/demo-temp-permission.sh --clean`。

### 4.5 生产 profile

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
  <artifactId>telbank-rbac2-starter</artifactId>
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
mvn -pl telbank-rbac2-starter test

# 管理前端（nvm node 22）
export NVM_DIR="$HOME/.nvm" && . "$NVM_DIR/nvm.sh" && nvm use 22
cd telbank-rbac-front && npm install --no-audit --no-fund && npm run build

# 示例业务系统（SDK 端到端验收，非交付物）
mvn -pl telbank-rbac-starter install -DskipTests
mvn -f samples/business-demo/pom.xml -DskipTests package
# JDK 21 版 SDK 的示例业务系统（JDK 21 构建）
mvn -pl telbank-rbac2-starter install -DskipTests
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
8. **A8 · 权限中心自身接入方式（v2.0 已被 A11 取代）**：v1 实现为 `rbac` 系统自接入；v2.0 系统隔离重构后删除，改为平台系统 + 数据驱动超管（见 A11）。
9. **A9 · SDK 的 JDK 版本覆盖**：需求只定义 JDK 8 版 SDK；实现新增 JDK 21 + Spring Boot 4 变体（协议/配置一致、同包名不可共存），供 JDK 21 业务系统接入。
10. **A10 · 移除 Flyway**：按用户要求去掉 `flyway-core/flyway-mysql` 依赖与 `FlywayConfig`；数据库改由 `telbank-rbac-api/src/main/resources/db/manual/*.sql` 手动初始化（`scripts/init-db.sh` 建库并按序导入，`--temp-db` 自动执行），后端启动前库表须已存在。
11. **A11 · 系统隔离重构（v2.0）**：删除用户-系统开通（表/接口/页面），系统访问由有效角色推导；平台超管改为数据驱动（platform 系统 + super-admin + platform:admin，删除 `rbac.platform-admin-ids` 配置）；管理上下文改为 `X-System-Code=当前业务系统`，非超管跨系统 403；管理权限改为系统内短码（role:manage 等）；系统接入仅超管且必须录入系统管理员，自动创建默认 admin 角色并授权；菜单统一收在「权限管理」组，新增「系统接入」。
12. **A12 · A 方案通配权限 + 严格上下文**：admin/超管角色只授一条 `*`（支持 `*`/`前缀:*` 匹配），不再写 6 条短码；`06_cleanup_admin_redundant_grants.sql` 清理 admin 角色残留业务授权，使角色授权只剩一条 `*`（`common:profile` 等 `is_public=1` 公共权限仍对所有登录用户生效，不计入角色授权）；所有权限/角色/资源查询严格限定当前 `X-System-Code` 系统，列表缺省 `systemId` 收敛上下文、by-id 校验实体 systemId，**超管也不能单请求跨系统**。
13. **A13 · 持久层替换为 MyBatis-Plus**：移除 JPA/Hibernate 与 Spring Data Repository；改为 MyBatis-Plus 3.5.17（Boot4 starter + jsqlparser）实体注解/BaseMapper/注解 SQL/MP 分页；REST 契约与数据库表结构不变。
16. **A16 · 系统接入双通道（SDK 上报 + 手动录入）**：SDK 启动用 onboarding AppKey/HMAC 上报 `systemCode/name/region`，只生成 `sys_system_application` 待审核申请（单条最新状态）；平台超管审核通过后自动创建系统与管理员授权，驳回可重报；手动录入保留；审核 API 可复用给外部审批系统。
18. **A18 · 凭据查看 + admin 授权保护**：新增「接入凭据」页（`system:credential`，admin `*`/超管可见），系统管理员可查看本系统完整 AppKey/AppSecret，超管可查看任意系统；角色授权页排除 admin 角色，后端拒绝对 admin 的 grant/revoke（400）。
19. **A19 · 表格内容优先展示**：表格改为 auto 布局，宽度足够不换行、不够才换行完整展示，不再用省略号；时间列缩短为 `YYYY-MM-DD HH:mm`；资源管理/授权记录等列宽重新分配。
20. **A20 · 业务用户临时权限申请**：新增顶级「我的申请」（所有登录用户可用，不占管理权限），业务用户只能在已有有效角色的当前系统内申请非 `admin` 角色；申请单 `RA-...` + `PENDING/APPROVED/REJECTED`，点击单号跳外部审批模板 URL；新增 `/api/access-applications*` 自助接口与 `/openapi/approval/access-applications/decision` 审批回调（审批 AppKey，原子多角色，幂等）；批准后写 `sys_user_role` 统一连续有效期，过期由既有定时任务收回；权限中心仍不建审批流，提供 `scripts/mock-access-approval.py` 模拟外部审批；同时角色授权页可授权树隐藏 `*`，后端拒绝非 admin 手工授 `*`；迁移 `10_access_application.sql`。
17. **A17 · 系统接入凭据**：`sys_system` 新增 `app_key/app_secret`，手动/审核创建时自动分配（appKey=system code、secret 随机 32 位）；页面展示 AppKey 与掩码，创建/审批响应一次性返回完整 secret；开放接口验签 DB 优先，YAML 仅兼容回退；`09` 回填 crm/erp。
15. **A15 · 页面元素管理简化**：页面元素只维护 `pageCode/code/text/type`（类型为 button/link/menu/icon/input 语义类型），不再在管理页维护样式/action/config；`action/config` 仍保留在 API 与 SDK 契约中；`07` 迁移把历史样式 type 归一为语义类型。
14. **A14 · 前端菜单/布局优化**：移除「到期提醒」菜单、路由与页面（后端 `/api/expiring-grants` 接口保留）；管理台布局改为占满 100% 视口、内容区独立滚动，表格宽度自适应，去除页面/表格横向滚动条。

其他实现说明（非偏差，需求本身已明确）：

- **缓存口径**：管理端高频查询用 Caffeine 一级缓存；Redis 用于 Pub/Sub、Nonce 防重放与通知，不实现复杂跨节点缓存协议。
- **不容器化**：按需求只交付源码 + 启动脚本，不提供 Docker/K8s 清单。
- **不存储菜单**：权限中心不管理菜单/路由结构（需求 §2），由业务系统前端自行维护。

> 版本说明：需求锁定 React 19.2.x / Vite 8 / antd 6 / React Router 8 / TanStack Query v5 / Zustand v5；`telbank-rbac-front/package.json` 按此约束（React `~19.2.0`），lockfile 实际解析为 React 19.2.9 / Vite 8.3.5 / antd 6.6.7 / React Router 8.5.0 / TanStack Query 5.104.5 / Zustand 5.0.16。

---

## 10. 常见问题

- **启动报 `Access denied ... (using password: NO)` 或数据库连接/方言类错误**：通常是「连不上库」的表象。确认 MySQL 已启动、`telbank_rbac` 已创建，并且把密码传给后端：`MYSQL_USER=root MYSQL_PASSWORD='***' ./scripts/start-all-macos.sh --init-db`（Linux 用 `start-all-linux.sh`；脚本会自动映射 `MYSQL_*` → `RBAC_DB_*`）；也可显式设置 `RBAC_DB_URL/RBAC_DB_USER/RBAC_DB_PASSWORD`。
- **JDK 版本不对**：权限中心需要 **JDK 21+**（`java -version` 校验；平台启动脚本会自动寻找 JDK 21+/SDKMAN/Homebrew 路径）；SDK 用 **JDK 17+ 构建**（产物 Java 8 字节码），业务系统运行时 **JDK 8+** 即可。
- **没有 Redis**：demo profile 已关闭 Redis（`RBAC_REDIS_ENABLED` 也可控制）；SDK 侧在真实环境会自动降级为短轮询。
- **前端 401**：JWT 过期（默认 24h）或 `iss`/签名不匹配，重新生成 `TOKEN`；demo 密钥需与 `application-demo.yml` 一致。
- **切系统后接口 403**：用户在该系统没有有效角色（`sys_user_role`），或调用了与 `X-System-Code` 不一致的 `systemId`；系统管理员只能操作自己系统；到「用户授权」给用户分配系统内角色即可。
- **管理端接口 403**：确认当前用户在 `X-System-Code` 对应系统内拥有管理权限（admin 角色一条 `*` 即可），且 `systemId` 与上下文一致；**超管同样不能单请求跨系统**（先切系统）；平台超管由 `platform` 系统内的 `*` 判定（种子账号 `superadmin`）。
- **SDK 接口未被拦截**：确认权限中心该系统的「权限码」已填写 `method + api_pattern`（SDK 只下发非空记录），且业务系统 `system-code` 与 `rbac.clients` 一致。

---

## 11. 本次交付验证记录（摘要）

- 权限中心后端：`clean test` **192/192 PASS**（JDK 21.0.13，含 A16/A17/A18/A20）。
- **A16/A17/A18/A20**：SDK 上报→待审核→审批创建系统/分配 appKey/secret、凭据查看、admin 授权保护；业务用户「我的申请」→外部审批 decision→临时角色→过期回收全链路；真实 SDK 启动上报验证通过；全量验收 **124/124 PASS**（含两版 SDK e2e 8/8、A20 §9.17 22/22）。
- **持久层（A13）**：JPA/Hibernate 全部移除，改用 MyBatis-Plus 3.5.17（Boot4 starter + `mybatis-plus-jsqlparser`）；实体 `@TableName/@TableId`、7 个 `BaseMapper`、复杂时效 SQL 注解化、MP 分页；真库 v4 验收 **88/88 PASS**（含两版 SDK e2e 8/8）。
- **系统隔离重构（A11）**：当前业务表 7 张（含平台系统共 3 个系统：crm/erp/platform）；全量 API 验收 **81/81 PASS**（含 JDK8/JDK21 两个 SDK e2e 各 4/4）；覆盖系统由有效角色推导、数据驱动超管、bootstrap `currentSystemCode`、跨系统 403/超管跨系统、系统接入自动创建管理员角色、用户-系统开通接口 404。
- **数据库初始化（A10：已移除 Flyway，历史记录）**：`scripts/init-db.sh` 按序导入 `db/manual/01_schema.sql → 02_seed_data.sql → 03_rbac_self_onboarding.sql`；全新库验证 **8 张表**（无 `flyway_schema_history`）、API 验收 **59/59 PASS**（跳过示例业务系统）；`--temp-db` 自动导入并启动成功。
- SDK（JDK 8 版）：`./scripts/build-sdk.sh`（**JDK 17 工具链**）→ **121/121 PASS**，产物字节码 **major=52（Java 8）**。
- SDK（JDK 21 版）：`mvn -pl telbank-rbac2-starter clean test`（JDK 21）→ **122/122 PASS**，字节码 **major=65**；JDK 21 示例业务系统端到端 **4/4 PASS**；真实 Redis Pub/Sub 联调通过。
- **一键验证**：`./scripts/verify-all.sh` → **16 项检查全过**（api 192、SDK v1 121、SDK v2 122、前端构建、MyBatis-Plus/无 JPA、08/09/10 迁移、凭据/admin 保护、A20 申请产物、3 平台启动脚本）。
- **GitHub Actions（6 job 全绿）**：权限中心（JDK21 88 单测）/ SDK v1（JDK17 97 单测）/ SDK v2（JDK21 98 单测）/ 前端（Node22）/ Windows 启动脚本语法 / JDK 8 运行时冒烟全部 success（[run 40094016169](https://github.com/battleman1994/telbank-rbac/actions/runs/40094016169)）。
- **v1 自接入（已被 A11 取代）**：旧的 `rbac` 系统自接入已移除；现由平台系统 `platform` + `superadmin` 数据驱动超管。
- **真实 Redis Pub/Sub（本机实测）**：Redis 8.10.2 + 两区域频道（`rbac:change:region_a:crm` / `region_b:erp`）；变更后 API 发布日志 `receivers=1`，CRM/ERP SDK 各自收到 `PAGE_ACTION_CHANGED` / `PERMISSION_CHANGED` 并刷新缓存；过期收回任务同样经 Redis 发布。
- 第 9 节全量验收（当前）：**124/124 PASS**（含 JDK8/JDK21 两个 SDK 端到端各 4/4、A20 §9.17 22/22；v10 全新库 01→10）。
- **权限时效演示（本机验证，A12 后）**：`demo-temp-permission.sh` 给 `demo-1min` 在 CRM 临时授予通配 `*`（1 分钟），有效期内可访问管理接口，过期后 `permissions=[]`、接口 403。
- 管理前端：`tsc -b && vite build` 通过（3259 modules），dist 1.4 MB（JS 1,456.05 kB / gzip 459.13 kB）；headless Chrome 对真实后端联调通过（136 仅见「我的申请」、admin 角色权限预览、角色授权树无 `*`），见验收文档。
- 一键验证：`./scripts/verify-all.sh` → **14 项检查全过**；已知限制见 [`docs/acceptance.md`](docs/acceptance.md)。

---

## 12. 许可

内部交付工程（示例数据仅用于演示）。
