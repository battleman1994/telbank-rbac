# telbank-rbac-front 权限中心管理前端

`telbank-rbac` 权限管理平台的管理端 Web 界面，对接权限中心后端 `telbank-rbac-api` 的 `/api/**` 管理接口。

- 需求基线：`docs/telbank-rbac.md` §3.3 / §5.7
- 接口契约：`docs/impl/contracts.md` §3 / §10
- 完整文档地图：[`../docs/README.md`](../docs/README.md)
- **权限中心自身接入 RBAC（方案 A）**：管理端菜单 / 路由由 `GET /api/me/bootstrap` 返回的 `rbac:*` 管理权限码驱动；所有 `/api/**` 请求的鉴权上下文固定为管理上下文系统（`X-System-Code: rbac`），业务系统选择只作为数据筛选条件。

## 技术栈（锁定版本）

| 组件 | 版本 |
|---|---|
| Node.js | 22.22.0+（本工程验证环境 nvm Node 22.23.1） |
| React / React DOM | 19.2.9（`~19.2.0`） |
| Vite | 8.3.5 |
| @vitejs/plugin-react | 6.1.2 |
| TypeScript | 5.9.3 |
| Ant Design | 6.6.7（仅 antd 主题） |
| React Router | 8.5.0 |
| TanStack Query | 5.104.5 |
| Zustand | 5.0.16 |
| HTTP | 原生 fetch（未使用 Axios） |

## 环境要求与启动

```bash
# Node 通过 nvm 使用（React Router 8 要求 Node 22.22+）
export NVM_DIR="$HOME/.nvm"; . "$NVM_DIR/nvm.sh"; nvm use 22

cd telbank-rbac-front
npm install            # 首次安装
npm run dev            # 开发服务器 http://localhost:5173
```

构建（包含 `tsc -b` 类型检查）：

```bash
npm run build          # tsc -b && vite build，产物输出到 dist/
npm run typecheck      # 仅类型检查
npm run preview        # 预览 dist 构建产物（4173 端口）
```

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `VITE_API_BASE_URL` | `http://localhost:8080` | 权限中心后端地址；开发环境直接跨域请求，后端需放行 `http://localhost:5173` 与 `http://127.0.0.1:5173`（契约 §1.8） |
| `VITE_MANAGEMENT_SYSTEM_CODE` | `rbac` | 管理上下文系统编码：所有 `/api/**` 请求头 `X-System-Code` 的取值；业务系统选择（`rbac.systemCode`）不再进入请求头 |

可在 `telbank-rbac-front/.env.local` 中覆盖，例如：

```bash
VITE_API_BASE_URL=http://127.0.0.1:8080
VITE_MANAGEMENT_SYSTEM_CODE=rbac
```

## 与权限中心后端联调

本前端默认请求 `http://localhost:8080`。本地联调步骤（详见仓库根 README §4）：

```bash
# 1) 启动权限中心（JDK 21+，demo profile；HS256 演示 JWT）
cd .. && ./scripts/start-api.sh

# 2) 生成演示 JWT（另开终端）
TOKEN=$(./scripts/gen-demo-jwt.sh 13800138000)

# 3) 启动前端并在浏览器打开 http://localhost:5173，粘贴 TOKEN 登录
cd telbank-rbac-front && npm run dev
```

> 真实后端联调尚未纳入自动化验收（见 `docs/acceptance.md` 已知限制）；页面操作时可在浏览器 DevTools 观察 `/api/**` 请求头（`Authorization: Bearer` + `X-System-Code`）。

## 登录、管理上下文与系统选择

1. 权限中心**不签发 JWT**（认证外部化）。管理端 `/login` 页面粘贴外部认证系统签发的 JWT，通过后写入 `localStorage["rbac.token"]`。
2. 登录（以及刷新页面后）严格按 **`GET /api/me` → `GET /api/me/systems` → `GET /api/me/bootstrap`** 顺序加载管理上下文，`GET /api/me/bootstrap` 返回：

   ```json
   {
     "externalUserId": "13800138000",
     "platformAdmin": false,
     "managementSystemCode": "rbac",
     "permissions": ["rbac:system:manage", "rbac:role:manage", "..."],
     "systems": [
       { "id": 1, "code": "crm", "name": "CRM系统", "region": "region_a", "isDefault": true },
       { "id": 3, "code": "rbac", "name": "权限中心", "region": "region_a", "isDefault": false }
     ]
   }
   ```

3. **请求头口径**：所有 `/api/**` 请求自动注入
   - `Authorization: Bearer <token>`
   - `X-System-Code: <managementSystemCode>`（来自 bootstrap，默认 `rbac`，可用 `VITE_MANAGEMENT_SYSTEM_CODE` 覆盖）

   业务系统选择（`currentSystemCode` / `systemId`）**只用于数据筛选**（查询参数 / 请求体），不再写入 `X-System-Code`；`rbac.systemCode` 只保存业务系统选择。
4. 顶部系统切换器只显示业务系统（自动排除管理上下文系统 `rbac`）；切换后写入 `localStorage["rbac.systemCode"]`，让除管理会话（`admin-session`）外的 TanStack Query 缓存失效重取，各页面查询 key 均按 `systemId` 隔离。
5. 本地联调：后端以 `demo` profile 启动（`rbac.security.jwt.mode=hs256`）时，可用仓库根目录 `scripts/gen-demo-jwt.sh` 生成的 HS256 token 粘贴登录（默认账号 `13800138000` 已具备 `rbac:*` 管理权限码）；生产使用外部认证系统 JWKS 校验。
6. 退出登录清空 `rbac.token` / `rbac.systemCode` / `rbac.managementSystemCode` 与全部查询缓存。接口返回 401 时自动清理会话并跳转 `/login`。

## 页面清单（10 个功能页面）

| 路由 | 页面 | 所需管理权限码 | 主要接口（契约 §3） |
|---|---|---|---|
| `/systems` | 系统管理 | `rbac:system:manage` | `GET/POST /api/systems`、`PUT/DELETE /api/systems/{id}` |
| `/roles` | 角色管理（按系统） | `rbac:role:manage` | `GET/POST /api/roles`、`PUT/DELETE /api/roles/{id}` |
| `/permissions` | 权限资源管理（树形） | `rbac:permission:manage` | `GET/POST /api/permissions`、`PUT/DELETE /api/permissions/{id}` |
| `/role-permissions` | 角色-权限授权（树勾选 + 时效） | `rbac:role:manage` | `GET /api/roles/{roleId}/permissions`、`POST .../grant`、`POST .../revoke` |
| `/user-roles` | 用户-角色分配（含时效） | `rbac:role:manage` | `GET/POST /api/user-roles`、`DELETE /api/user-roles/{id}` |
| `/user-systems` | 用户-系统开通 | `rbac:system:manage` | `GET/POST /api/user-systems`、`DELETE /api/user-systems/{id}` |
| `/page-actions` | 页面操作元数据（模式 B） | `rbac:page:manage` | `GET/POST /api/page-actions`、`PUT/DELETE /api/page-actions/{id}` |
| `/sdk-endpoints` | SDK 端点上报 | `rbac:permission:manage` | `GET /api/sdk-endpoints`、`PUT .../link`、`PUT .../unlink` |
| `/approval-grants` | 审批授权记录（只读） | `rbac:audit:view` | `GET /api/approval-grants` |
| `/expiring-grants` | 即将到期授权（只读） | `rbac:audit:view` | `GET /api/expiring-grants` |

交互约定：

- 列表页均支持分页 / 搜索 / 刷新；写操作均有新建或编辑 Modal 表单 + 删除（停用）二次确认。
- 权限资源树支持 `type`、`is_public`、`code`、`method`、`api_pattern`、`meta(JSON)` 编辑；`api_pattern` 前端按 Ant 风格提前校验（`*` 匹配单段、`**` 只能出现在末尾），且 `method` 与 `api_pattern` 必须同时为空或同时非空（与契约 §3.4 一致，最终以后端 400 校验为准）。
- 角色-权限授权页按系统选择角色 → 权限树勾选 → 设置 `valid_from/valid_until`（不填为永久）→ 提交 grant/revoke。
- SDK 端点上报页支持「关联已有权限码」与「新建权限码并关联」两种方式；关联后服务端把端点 `method/path` 写入权限码的 `method/api_pattern`。
- 审批授权记录、即将到期授权为只读查询页，提供筛选、分页、详情 Modal。

## 权限中心自身接入 RBAC（方案 A）

权限中心自身作为编号 `rbac` 的管理系统接入 RBAC，管理端功能由管理权限码驱动：

| 管理权限码 | 菜单 / 路由 |
|---|---|
| `rbac:system:manage` | 系统管理、用户-系统开通 |
| `rbac:role:manage` | 角色管理、角色-权限授权、用户-角色分配 |
| `rbac:permission:manage` | 权限资源管理、SDK 端点上报 |
| `rbac:page:manage` | 页面操作元数据 |
| `rbac:audit:view` | 审批授权记录、即将到期授权 |

实现要点：

1. **权限码来源**：`GET /api/me/bootstrap`（仅要求已认证）→ `permissions: string[]`；平台超管返回 `["*"]`。
2. **hasPerm**：`src/stores/app.ts` 中 `hasPerm(code)`——平台超管或 `permissions` 含 `"*"` 时恒 `true`，否则判断 `permissions.has(code)`；React 组件通过 `useHasPerm()` 订阅权限集合变化。
3. **菜单过滤**：`src/config/menu.ts` 统一维护「菜单 / 路由 / 权限码」映射；`AdminLayout` 按 `hasPerm` 过滤菜单项，空分组不渲染；系统切换器排除管理上下文系统（`rbac`），只列出业务系统。
4. **路由守卫**：`router.tsx` 中每个页面包裹 `<PermissionGuard permission="rbac:*">`；无权限时渲染 `403 无权限页`（`NoPermission`），提供「前往可用页面」（第一个有权限菜单）与刷新按钮，不白屏。平台超管全部放行。
5. **无任何管理权限**：布局保留框架并展示「无管理权限」空态，提示联系平台超管；首页 `/` 自动重定向到第一个有权限的菜单，无任何权限时展示 403 空态。
6. **请求头**：管理端所有 `/api/**` 请求的 `X-System-Code` 固定为 `managementSystemCode`（bootstrap 返回，默认 `rbac`）；业务系统选择只影响查询参数 `systemId`。

## 状态管理

- **TanStack Query** 管理所有服务端状态：权限树、角色、授权、映射、审批记录等。查询 key 均显式包含 `systemId`，例如：
  - `['permissions', systemId]`
  - `['roles', systemId, { keyword, page, size }]`
  - `['role-permissions', systemId, roleId]`
  - `['expiring-grants', systemId, withinDays]`
  - `['admin-session']`：`/api/me` → `/api/me/systems` → `/api/me/bootstrap` 串联结果（切换业务系统时不重取）
  - 切换业务系统后 `queryClient.invalidateQueries()` 让除 `admin-session` 外的查询失效重取。
- **Zustand**（`src/stores/app.ts`）管理客户端上下文：`token`、`currentUser`、`systems`（含管理系统 rbac）、`currentSystemCode`（业务系统选择）、`platformAdmin`、`permissions: Set<string>`、`managementSystemCode`、`hasPerm(code)`。
  - 持久化：`rbac.token`（JWT）、`rbac.systemCode`（业务系统选择）、`rbac.managementSystemCode`（管理上下文系统编码）。

## 目录结构

```text
telbank-rbac-front/
├── index.html
├── vite.config.ts
├── tsconfig.json / tsconfig.app.json / tsconfig.node.json
├── .env.example
├── public/favicon.svg
└── src/
    ├── main.tsx                 # Provider 装配：ConfigProvider + antd App + QueryClient + Router
    ├── router.tsx               # 登录页 + 受保护布局 + 10 个页面路由（均包裹 PermissionGuard）
    ├── api/
    │   ├── request.ts           # 原生 fetch 封装：JWT / X-System-Code / 信封解析 / 401 处理
    │   ├── types.ts             # 契约 §3 全部 VO / 请求 / 响应类型
    │   ├── me.ts systems.ts roles.ts permissions.ts
    │   ├── rolePermissions.ts userRoles.ts userSystems.ts
    │   ├── pageActions.ts sdkEndpoints.ts grants.ts
    │   └── index.ts
    ├── config/menu.ts           # 菜单/路由/管理权限码映射（rbac:system|role|permission|page|audit）
    ├── stores/app.ts            # Zustand 全局状态（token/systems/permissions/managementSystemCode/hasPerm）
    ├── layouts/AdminLayout.tsx  # 权限过滤菜单 + 业务系统切换器 + 管理会话引导 + 无权限空态
    ├── components/              # PageShell/SystemGate/StatusTags/AppNotifier/错误边界/PermissionGuard/NoPermission
    ├── pages/                   # LoginPage + 10 个管理页面 + NotFoundPage
    ├── utils/                   # 时间格式、Ant 路径校验、分页配置、文案常量
    └── styles/global.css
```

## 错误与空态处理

- 统一响应信封 `{code,message,data}`：HTTP 非 2xx 或 `code !== 0` 抛出 `ApiError`，并通过 antd `message.error` 提示后端 `message`。
- 每个页面在查询失败时展示 `Alert`（含错误信息与「重试」按钮），不会白屏；列表查询为空时展示表格空态文案。
- 顶层 `AppErrorBoundary` 捕获渲染异常，展示可刷新的错误页。
- 未登录访问受保护路由自动跳转 `/login`；JWT 失效（401）自动清理会话并回到登录页。
- 管理权限不足时：路由级 `PermissionGuard` 展示 403 无权限页（含「前往可用页面」按钮），菜单中不展示无权限项；无任何管理权限时布局展示「无管理权限」空态，均不白屏。
- 后端未启动时，页面提示「无法连接权限中心后端」，构建与开发服务器不依赖后端。

## 构建产物（本工程最近一次构建）

```text
> telbank-rbac-front@1.0.0 build
> tsc -b && vite build

vite v8.3.5 building client environment for production...
transforming...
✓ 3257 modules transformed.
rendering chunks...
computing gzip size...
dist/index.html                     0.60 kB │ gzip:   0.40 kB
dist/assets/index-*.css             1.80 kB │ gzip:   0.84 kB
dist/assets/index-*.js          1,410.82 kB │ gzip: 444.74 kB
✓ built in ~1s
```

`npm run build` 总耗时约 3~15 秒（含 `tsc -b` 类型检查，视机器负载），`dist/` 总大小约 1.4 MB（JS gzip 约 445 kB）。

## 已知限制 / 后续

- 自接入依赖后端已实现 `GET /api/me/bootstrap` 与管理系统 `rbac` 的权限码种子；若后端尚未重启/升级，登录会提示「加载管理上下文失败」（非白屏），需先完成后端升级。
- 未做路由级 code splitting：生产包单 chunk 1.4 MB（gzip 约 445 kB），后续可用 `React.lazy` + `manualChunks` 优化首屏。
- 真实权限中心后端的浏览器人工联调待补（当前验证为类型检查 / 构建 / mock 冒烟）。
- 审批授权记录、即将到期授权按契约仅提供查询（只读）。

## 本地验证记录

- `npm run build`：`tsc -b` 类型检查 0 error，Vite 构建成功。
- `npm run dev`：`vite` 启动于 5173，`curl http://localhost:5173/` 返回 HTTP 200。
- 使用 mock 后端 + headless Chrome（CDP）做页面级冒烟：10 个页面全部渲染无异常、无控制台错误；验证了新建/编辑弹窗、角色选择 + 权限树勾选、external_user_id 查询、系统切换（查询 `systemId=2` 隔离生效）、退出登录回到 `/login`。
- **自接入权限冒烟（mock 后端按 token 返回不同 bootstrap）**：
  - 平台超管（`permissions=["*"]`）：10 个菜单全部可见；系统切换器只含 CRM / ERP（排除 `rbac`）；请求头 `X-System-Code` 恒为 `rbac`，业务系统切换只改查询参数 `systemId`；刷新后会话自动恢复。
  - 仅有 `rbac:audit:view`：菜单仅「审批授权记录 / 即将到期授权」，首页重定向到 `/approval-grants`；直接访问 `/systems` 显示 403 无权限页并可「前往可用页面」。
  - 无任何管理权限：布局显示「无管理权限」空态（空菜单、不白屏）。
  - 全管理权限非超管账号：10 个菜单可见（无平台超管标签）；切换业务系统 ERP 后请求 `systemId=2` 且请求头仍为 `rbac`。
  - 退出登录清空 `rbac.token` / `rbac.systemCode` / `rbac.managementSystemCode` 并回到 `/login`。
