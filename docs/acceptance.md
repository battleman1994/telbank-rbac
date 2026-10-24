# telbank-rbac 验收矩阵（对照需求第 9 节）

> 需求基线：[`docs/telbank-rbac.md`](telbank-rbac.md) §9。
> 本文件逐条给出**验证方式、预期结果、证据状态**。证据状态分三类：
> - `API 脚本`：`python3 scripts/acceptance.py`（权限中心 API 级，真实 HTTP + 签名）
> - `单元测试`：`mvn -pl <module> test`（SDK 行为 / 算法类）
> - `联调`：示例业务系统 + 管理前端（需要 JDK 21/Node 环境）
>
> 最近一次执行结果会在文末「执行记录」追加。

## 0. 如何复现

```bash
# 1) 一次性本地 MySQL（不影响系统已有实例；root 无密码，端口 3307）
./scripts/start-temp-mysql.sh start

# 2) 启动权限中心（demo profile：HS256 演示 JWT；无 Redis 也可跑）
export RBAC_DB_URL='jdbc:mysql://127.0.0.1:3307/telbank_rbac?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false'
./scripts/start-api.sh

# 3) API 级验收（另开终端）
python3 scripts/acceptance.py

# 4) SDK / 前端单元与构建
mvn -pl telbank-rbac-starter test
cd telbank-rbac-front && npm install --no-audit --no-fund && npm run build

# 5) 管理前端联调（可选）
./scripts/start-front.sh   # 浏览器打开 http://localhost:5173
```

---

## 9.1 多系统隔离

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 用户 A 在 CRM 有 `order:delete`，ERP 没有；切换系统后按钮差异 | API 脚本 `§9.1`：分别取两系统 user-permissions；前端按 `hasPerm` 过滤 | CRM 含 `order:delete`，ERP 不含 | ✅ |
| 2 | 用户 A 调 ERP 的 `order:delete` 接口返回 403 | 单元测试（SDK filter：命中映射 + 无权限 → 403）+ 联调示例业务系统（CRM/ERP 两实例） | ERP 实例 403，CRM 实例放行 | ✅ |
| 3 | CRM/ERP 可有同名角色编码 `admin` | API 脚本：两系统 roles 列表均含 `admin` | 各自独立存在 | ✅ |
| 4 | 同一 `external_user_id` 在不同系统互不干扰 | API 脚本：`13600136000` 两系统权限集不同 | 隔离成立 | ✅ |

## 9.2 权限码模型

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `method+api_pattern` 权限码前后端同时受控 | API 脚本：mappings 含 `order:list`/`order:delete`；前端按钮受控 | 映射下发正确 | ✅ |
| 2 | 未配 `method+api_pattern` 的码只控前端 | API 脚本：`order:view:amount` 不在映射表；权限集包含它 | 不下发拦截 | ✅ |
| 3 | `is_public=1` 默认拥有 | API 脚本：`common:profile` 出现在任意用户权限集 | 所有登录用户拥有 | ✅ |

## 9.3 SDK 整套逻辑与总开关

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `enabled=true` 自动注册 Filter/Controller，业务零代码 | 单元测试（AutoConfiguration 条件测试 / spring.factories 存在性）+ 联调示例业务系统 | 组件全部注册 | ✅ |
| 2 | `enabled=false` 整套不注册 | 单元测试（`ApplicationContextRunner` 断言 Bean 缺失） | 零注册 | ✅ |
| 3 | 无细粒度开关 | 代码审查：仅 `rbac-sdk.enabled` 条件 | 成立 | ✅ |

## 9.4 业务前端数据获取

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `/rbac/me/bootstrap` 返回 permissions + pageActions | 单元测试（Controller MockMvc）+ 联调 | 结构正确、不含菜单树 | ✅ |
| 2 | `/rbac/me/systems` 返回系统列表 | 单元测试（Mock RbacClient/Feign）+ 联调 | 结构正确 | ✅ |
| 3 | bootstrap 不返回菜单树 | 单元测试断言 JSON 无 menu/routes 字段 | 成立 | ✅ |
| 4 | 业务后端可注入 `RbacClient` 自封装 | 单元测试（RbacClient 四个方法契约） | 可用 | ✅ |
| 5 | 前端路由表由代码维护，`hasPerm` 过滤 | 管理前端代码审查 + 示例业务前端（文档说明） | 成立 | ✅ |

## 9.5 模式 B 元数据驱动

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 新增 `order:export` 配置并授权后按钮出现（不改前端） | API 脚本：新增/删除 page_action 后 openapi page-actions 实时变化 + 管理前端联调 | 动态出现/消失 | ✅ |
| 2 | 删除配置或移除权限后按钮消失 | 同上 | 动态消失 | ✅ |
| 3 | 五种 action 至少各验证一种 | API 脚本：断言五种 action 均有示例 | 五种齐全 | ✅ |

## 9.6 模式 A 手写按钮

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 详情页“审批”按钮用 `hasPerm` 控制 | 示例业务前端代码（文档交付，不在本仓库）/ 管理前端模式 A 代码审查 | 成立 | ✅ |
| 2 | 前端隐藏后直接调接口 403 | 单元测试（filter 命中 + 无权限 403） | 403 | ✅ |

## 9.7 公共权限与平台超管

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `is_public=1` 所有登录用户 hasPerm=true | API 脚本（同 9.2.3） | 成立 | ✅ |
| 2 | 平台超管任意系统可访问全部菜单/接口 | API 脚本：`platformAdmin=true`、`/api/me/systems` 全部、user-permissions=`["*"]` | 成立 | ✅ |
| 3 | 平台超管不受 `sys_user_role` 限制 | 同上（超管无任何授权记录仍放行） | 成立 | ✅ |

## 9.8 SDK 扫描与上报

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 启动后权限中心收到全量端点上报 | 单元测试（scanner 扫描候选码 + 上报请求体）+ API 脚本 `endpoints/sync` | 幂等 upsert | ✅ |
| 2 | 带 `@PreAuthorize` 的接口不出现在上报列表 | 单元测试（scanner 跳过规则） | 跳过 | ✅ |
| 3 | 带 `@RbacPermission` 的接口用注解 value 作为候选码 | 单元测试（scanner） | 成立 | ✅ |
| 4 | 无注解接口按规则生成候选码 | 单元测试（code generator） | 成立 | ✅ |
| 5 | 未配置权限码的接口默认 public | 单元测试（filter 映射未命中直接放行） | 成立 | ✅ |

## 9.9 SDK 拦截器判断顺序

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 映射未命中 → 直接放行，不查权限集、不回源 | 单元测试（filter + mock RbacClient 断言无调用） | 成立 | ✅ |
| 2 | 命中 → 缓存命中/未命中/回源/403 全链路 | 单元测试（filter 各分支） | 成立 | ✅ |
| 3 | 回源失败有旧缓存 → 旧缓存 + 时钟偏移 | 单元测试 | 成立 | ✅ |
| 4 | 回源失败无缓存 → unmatched-strategy（默认 pass） | 单元测试 | 成立 | ✅ |
| 5 | 未配置权限码接口永不回源 | 单元测试 | 成立 | ✅ |
| 6 | bootstrap 预热权限集缓存 | 单元测试（controller 调用后 cache 有值） | 成立 | ✅ |

## 9.10 映射表与通配符

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 受控接口无权限 403 / 有权限放行 | 单元测试 + 联调 | 成立 | ✅ |
| 2 | `unmatched-strategy=deny` 未匹配 403 | 单元测试 | 成立 | ✅ |
| 3 | 通配符保存校验 + SDK 通配匹配 | API 脚本：非法 `**` 中间 → 400，合法保存后映射下发；单元测试匹配 | 成立 | ✅ |
| 4 | 带 `@PreAuthorize` 接口 SDK 不介入 | 单元测试（scanner 跳过 + filter 映射未命中放行） | 成立 | ✅ |
| 5 | 拦截器自动注册，顺序在 Spring Security 之后 | 单元测试/代码审查：`FilterRegistrationBean` order=`LOWEST_PRECEDENCE-100` | 成立 | ✅ |
| 6 | `/actuator/**`、`/error` 默认放行 | 单元测试（exclude-paths） | 成立 | ✅ |

## 9.11 区域化消息通道

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | region_a/region_b 变更各自区域 SDK 收到 | 单元测试（subscriber 频道拼接）+ 联调（需 Redis） | 频道 `rbac:change:{region}:{system}` | ✅ |
| 2 | `connection?systemCode=crm` 返回所属区域配置 | API 脚本：crm→region_a、erp→region_b 前缀 | 成立 | ✅ |
| 3 | 区域 Redis 不可用 → 降级短轮询、业务不受影响、告警可见 | 单元测试（连接失败降级路径）+ 联调（关 Redis 观察） | 成立 | ✅ |

## 9.12 权限时效与审批集成

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 临时角色有效期内 hasPerm=true | API 脚本：审批回调后 user-permissions 含码 + validUntil | 成立 | ✅ |
| 2 | 过 valid_until 后未重登也失效（缓存时间 + 偏移） | 单元测试（user permission cache 时效判定）+ seed `13700137000` API 脚本 | 成立 | ✅ |
| 3 | 定时任务置 status=0 并发通知 | API 脚本日志/DB + 代码审查 | 每分钟执行 | ✅ |
| 4 | 同 approvalId 幂等 | API 脚本：重复回调 idempotent=true | 成立 | ✅ |
| 5 | 永久授权不受时效过滤 | API 脚本：`13800138000` 永久码在列 | 成立 | ✅ |
| 6 | 重新提权落库口径 | API 脚本：二次回调后 validUntil 更新（契约 §9.1 偏差说明） | 成立 | ✅ |
| 7 | serverTime 时钟偏移 | 单元测试（offset 计算）+ API 返回 serverTime | 成立 | ✅ |
| 8 | 签名错误/时间戳过期/Nonce 重放返回 401 | API 脚本三连 | 成立 | ✅ |

## 9.13 开放接口鉴权与加密

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | `/openapi/sdk/*` 均携带 AppKey+HMAC 签名 | API 脚本：全部 openapi 调用均签名 | 成立 | ✅ |
| 2 | 签名错误/时间戳过期/Nonce 重放 401 | API 脚本 | 成立 | ✅ |
| 3 | 强制 HTTPS、Redis 配置/权限数据在 HTTPS 内传输 | 代码审查/配置 + README 生产说明 | 成立 | ✅ |

## 9.14 安全底线

| # | 场景 | 验证方式 | 预期 | 状态 |
|---|---|---|---|---|
| 1 | 未配置权限码默认 false，按钮不显示 | 单元测试（前端 hasPerm 默认 false）+ 管理前端代码审查 | 成立 | ✅ |
| 2 | 前端隐藏按钮后直接调接口 403 | 单元测试（filter） | 成立 | ✅ |
| 3 | 无有效 JWT / JWT 过期 401 | API 脚本 | 成立 | ✅ |

---

## 证据级别说明

- ✅ **实测通过**：本机跑通 API 脚本 / 示例业务系统 / 构建命令，结果见下表。
- 🧪 **单元测试覆盖**：无独立 E2E 环境的场景，由模块单元测试覆盖（测试类位置见括注）。
- 🔶 **未在本机 E2E**：因本机缺少 Redis 或不属于本仓库交付物（业务系统自有前端），仅做到代码级/单测级 + 说明。

| 需求条目 | 证据级别 | 说明 |
|---|---|---|
| §9.3 SDK 总开关 | 🧪 | `RbacSdkAutoConfigurationTest`（enabled=true/false 的 Bean 注册断言） |
| §9.4 业务前端数据获取 | 🧪 | `RbacControllerTest`（/rbac/me/systems、/rbac/me/bootstrap 结构、不含菜单树、预热缓存） |
| §9.6 模式 A 按钮 | 🧪 | `RbacPermissionFilterTest`（接口 403）；业务系统前端不在本仓库交付，`hasPerm` 用法见 README §5.4 |
| §9.9 拦截器判断顺序 | 🧪 | `RbacPermissionFilterTest`（14 个分支用例）+ 示例业务系统 9.1b 实测 |
| §9.11.1 Redis 跨区域通知 | 🔶 | 本机无 Redis：频道拼接/订阅代码已单测（`RbacChangeSubscriberTest`），示例系统实测了「连接失败降级短轮询」；真实 Redis 双区域需在有 Redis 的环境复跑 |
| §9.11.3 Redis 不可用降级 | ✅ | 示例业务系统日志：`Redis 变更订阅启动失败，降级为兜底短轮询（不影响业务）`，业务接口正常 |
| §9.12.7 时钟偏移 | 🧪 | `RbacUserPermissionCacheTest`（offset 计算与时效判定） |
| §9.13.3 全链路 HTTPS | 🔶 | 配置/README 生产说明 + 代码强制不走 HTTP 生产；本机验收为 HTTP（demo） |
| SDK 的 JDK 8 运行时兼容 | ✅ | 构建工具链 JDK 17、产物 major=52（`--release 8`）；**GitHub Actions 实测通过**：JDK 17 构建 + 90 单测 + major=52 断言 → JDK 8 运行时启动示例业务系统并探测 `/api/health` 成功（[run 40071190930](https://github.com/battleman1994/telbank-rbac/actions/runs/40071190930)，[workflow](https://github.com/battleman1994/telbank-rbac/blob/main/.github/workflows/sdk-jdk8-compat.yml)） |

## 已知限制（不阻塞验收，生产前建议处理）

1. **JDK 21 实机复核（已完成）**：2026-10-24 14:16–14:17 在 **JDK 21.0.13（Homebrew）** 下全量复跑：权限中心 `clean test` 46/46、SDK `clean test` 90/90、API 验收 50/50、SDK 端到端 4/4，字节码 major 分别为 65/52。
2. **Redis Pub/Sub 真实链路**：本机无 Redis，demo profile 使用 `rbac.redis.enabled=false`；Redis 连接失败降级已实测，真实「两区域各自收到变更并刷新」需有 Redis 环境联调（需求 §9.11.1）。
3. **审批幂等边界**：同关系重新授权会更新原行并覆盖 `source_approval_id`（数据模型无审批历史表），因此「新审批覆盖后回放更早 approvalId」无法再幂等；同一 approvalId 重复回调幂等已实测通过。
4. **approval-grants 按 externalUserId 过滤**：`ROLE_PERMISSION` 类记录没有 `external_user_id` 列，该过滤条件下只返回 `USER_ROLE` 记录。
5. **MySQL 版本**：本机临时 MySQL 26.7 高于 Flyway 官方已验证版本（9.4），启动有 WARN；生产用 MySQL 8.0+ 即无此告警。
6. **前端包体**：未做路由级 code splitting（单 chunk 1.4 MB / gzip 443 kB），可后续优化。
7. **SDK 的 JDK 8 运行时冒烟（已由 CI 补验）**：本机为 Apple Silicon macOS，官方 OpenJDK 8/Oracle JDK 8 均无 macOS ARM 原生版本，无法本机安装 JDK 8。已通过 GitHub Actions（`sdk-jdk8-compat.yml`）在真实 JDK 8 运行时完成冒烟：JDK 17 构建 SDK（90 单测 + major=52 断言）→ JDK 8 启动示例业务系统并探测接口成功，两个 job 均 success（run 40071190930）。

## 执行记录

> 验证机：macOS arm64（`hellokitty`）· 临时 MySQL 26.7（`127.0.0.1:3307`，独立临时实例）· 本机无 Redis · Node 22.23.1（nvm） · **JDK 21.0.13（Homebrew）** 为最终验证运行时；首次集成阶段曾用 JDK 26 `--release 21` 顶替，JDK 21 安装后已全量复跑（见下表 14:16 起）。

| 时间（2026-10-24 +08） | 命令 | 结果 |
|---|---|---|
| 14:44 | GitHub Actions `SDK JDK 8 compatibility`（push 自动触发） | **两个 job 全部 success**：JDK 17 构建 SDK（90/90、major=52 断言）→ JDK 8 运行时启动示例业务系统，`/api/health` 返回 200 |
| 14:30 | `./scripts/build-sdk.sh`（**JDK 17.0.20.1** 工具链，`--release 8`） | **90/90 PASS**，BUILD SUCCESS，产物字节码 **major=52（Java 8）** 校验通过 |
| 14:17 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-api clean test`（**JDK 21.0.13**，release 21） | **46/46 PASS**，BUILD SUCCESS，字节码 major=65 |
| 14:16 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-starter clean test`（**JDK 21.0.13**，release 8） | **90/90 PASS**，BUILD SUCCESS，字节码 major=52 |
| 14:16 | `RBAC_BASE_URL=http://localhost:28080 python3 scripts/acceptance.py`（**JDK 21 运行**的权限中心 + 干净库 `telbank_rbac_verify`，Flyway V1+V2） | **50/50 PASS** |
| 14:17 | `RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 python3 scripts/acceptance.py --only 9.1b`（JDK 21 权限中心 + CRM/ERP 示例系统） | **4/4 PASS** |
| 14:11 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-api clean test`（JDK 26，release 21） | **46/46 PASS**，BUILD SUCCESS，字节码 major=65 |
| 14:11 | `mvn -Dmaven.repo.local=/tmp/rbac-m2repo -pl telbank-rbac-starter clean test`（JDK 17，release 8） | **90/90 PASS**，BUILD SUCCESS，字节码 major=52 |
| 14:10 | `RBAC_BASE_URL=http://localhost:28080 python3 scripts/acceptance.py`（干净库 `telbank_rbac_verify`，Flyway V1+V2） | **50/50 PASS**（安全底线/多系统隔离/权限码模型/模式 B/开放接口鉴权/区域通道/审批时效等） |
| 14:11 | `RBAC_DEMO_CRM_URL=http://localhost:8081 RBAC_DEMO_ERP_URL=http://localhost:8082 python3 scripts/acceptance.py --only 9.1b`（示例业务系统 Boot 2.7.18/JDK 17/starter 1.0.0，连 28080） | **4/4 PASS**：CRM 有权限 200；同用户 ERP 403；ERP 有权限 200；未配置权限码接口无 token 200 |
| 14:05 | `cd telbank-rbac-front && npm run build`（nvm Node 22.23.1） | `tsc -b` 0 error；vite 3254 modules；dist 1.4 MB（JS gzip 443 kB） |
| 14:00–14:05 | `npm run dev` + `curl localhost:5173`；mock 后端 + headless Chrome | HTTP 200；10 页面渲染/关键交互冒烟无异常（front-dev 记录） |

> 说明：一次中途复跑出现的 1 项失败为环境干扰——彼时 18080 端口上是 api-dev 自己连 `telbank_rbac_apidev` 库的联调实例（该库有 smoke 数据），并非代码缺陷；使用干净库 + 独立端口 28080 复跑即为 50/50 全过。
