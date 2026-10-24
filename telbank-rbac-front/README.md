# telbank-rbac-front 权限中心管理前端

`telbank-rbac` 权限管理平台的管理端 Web 界面，对接权限中心后端 `telbank-rbac-api` 的 `/api/**` 管理接口。

- 需求基线：`docs/telbank-rbac.md` §3.3 / §5.7
- 接口契约：`docs/impl/contracts.md` §3 / §10
- 完整文档地图：[`../docs/README.md`](../docs/README.md)
- **系统隔离模型**：按「当前系统」隔离数据与权限，`X-System-Code` 恒为当前业务系统；平台超管由后端数据驱动（`platform:admin` 权限），前端只认 `/api/me` / `/api/me/bootstrap` 返回的 `platformAdmin`，不读环境变量、不硬编码账号。

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
| `VITE_API_BASE_URL` | `http://localhost:8080` | 权限中心后端地址；开发环境直接跨域请求，后端需放行 `http://localhost:5173` 与 `http://127.0.0.1:5173` |

可在 `telbank-rbac-front/.env.local` 中覆盖，例如：

```bash
VITE_API_BASE_URL=http://127.0.0.1:8080
```

> 不再需要 `VITE_MANAGEMENT_SYSTEM_CODE`：管理端没有独立「管理上下文系统」，`X-System-Code` 就是当前业务系统。

## 与权限中心后端联调

本前端默认请求 `http://localhost:8080`。本地联调步骤（详见仓库根 README）：

```bash
# 1) 启动权限中心（JDK 21+，demo profile；HS256 演示 JWT）
cd .. && ./scripts/start-all-macos.sh   # Linux 用 start-all-linux.sh

# 2) 生成演示 JWT（另开终端，演示账号如 superadmin / crm-admin-001 / erp-admin-001）
TOKEN=$(./scripts/gen-demo-jwt.sh superadmin)

# 3) 启动前端并在浏览器打开 http://localhost:5173，粘贴 TOKEN 登录
cd telbank-rbac-front && npm run dev
```

演示账号（由种子数据/后端迁移决定，前端不做特殊判断）：

| 账号 | 角色 | 说明 |
|---|---|---|
| `superadmin` | 平台超管（`platform:admin`） | 可见顶级「系统接入」；可在所有业务系统间切换，`hasPerm` 恒 true |
| `crm-admin-001` | CRM 系统管理员 | 仅可见 CRM 系统与「权限管理」菜单，无「系统接入」 |
| `erp-admin-001` | ERP 系统管理员 | 仅可见 ERP 系统与「权限管理」菜单，无「系统接入」 |
| 普通运营账号 | 无管理权限 | 无管理菜单；直接访问管理页面显示「无管理权限 / 无访问权限」 |

## 系统隔离模型与登录上下文

1. **认证外部化**：权限中心不签发 JWT，管理员在 `/login` 粘贴外部认证系统签发的 JWT，登录态存 `localStorage["rbac.token"]`。
2. **系统由角色推导**：`GET /api/me/systems` 返回当前账号可访问的业务系统（由有效 `user_role` 推导去重；平台超管为全部启用系统；不含平台系统）。
3. **当前系统上下文**：登录/刷新时按 `/api/me` → `/api/me/systems` → 选 `currentSystemCode`（`localStorage["rbac.currentSystemCode"]` 若在列表则沿用，否则第一条）→ 带 `X-System-Code` 调 `/api/me/bootstrap`。
4. **bootstrap 契约**：

   ```json
   {
     "externalUserId": "crm-admin-001",
     "platformAdmin": false,
     "currentSystemCode": "crm",
     "permissions": ["role:manage", "permission:manage", "resource:manage", "page:manage", "user:manage", "audit:view"],
     "systems": [
       { "id": 1, "code": "crm", "name": "CRM系统", "region": "region_a", "isDefault": true }
     ]
   }
   ```

   - `platformAdmin`：后端按 `platform:admin` 权限/数据判定，前端唯一依据；超管 `permissions` 为 `["*"]`。
   - `permissions`：**当前系统内**的短码权限集合（`role:manage` / `permission:manage` / `resource:manage` / `page:manage` / `user:manage` / `audit:view`）。
5. **请求头**：所有 `/api/**` 请求注入 `Authorization: Bearer <token>` 与 `X-System-Code: <currentSystemCode>`；`/api/me`、`/api/me/systems` 仅认证。
6. **切换系统**：只在顶部切换器操作，更新 store 与 `localStorage` → 重新拉 bootstrap（带新系统头）→ 失效数据查询；不整页刷新。
   - 平台超管：可在所有业务系统间切换，并显示顶级「系统接入」。
   - 普通管理员：只看到自己的系统；系统数 > 1 时显示切换器，= 1 时显示「当前系统：CRM系统」标签。
   - 平台系统不出现在切换器（后端返回的 systems 已排除）。
7. **退出登录**：清空 `rbac.token` / `rbac.currentSystemCode` 与全部查询缓存；401 同样自动清理并跳转 `/login`。

## 菜单结构（权限管理 IA）

| 菜单 | 路由 | 所需权限码（当前系统内短码） | 页面说明 |
|---|---|---|---|
| 系统接入（顶级，仅平台超管） | `/systems` | 无 | 系统接入管理：编码、名称、区域、系统管理员账号/姓名、状态 |
| 角色管理 | `/roles` | `role:manage` | 当前系统角色 CRUD |
| 权限管理 | `/permissions` | `permission:manage` | 权限码树（type/is_public/method/api_pattern） |
| 角色授权 | `/role-permissions` | `role:manage` | 角色-权限授权（树勾选 + 时效） |
| 用户授权 | `/user-roles` | `user:manage` | 用户-角色分配（含时效） |
| 资源管理 | `/sdk-endpoints` | `resource:manage` | SDK 端点上报与权限码关联；未关联端点显式标记「未生效」 |
| 页面元素 | `/page-actions` | `page:manage` | 页面元素标识：pageCode/code/text/type（语义类型） |
| 授权记录 | `/approval-grants` | `audit:view` | 审批授权记录查询 |

- 路由守卫：`src/router.tsx` 中每个页面包裹 `PermissionGuard`（短码）；不满足渲染 403 无权限页（可跳转到第一个有权限菜单），不白屏。
- `/systems` 使用 `PlatformAdminGuard`（`bootstrap.platformAdmin === true` 才可访问），非超管直接访问显示无权限页。
- **通配权限（A 方案）**：`permissions` 支持 `*` 与 `前缀:*` 两种通配。权限集合含 `*` 时 `hasPerm` 恒 true（admin / superadmin 角色只授一条 `*`，因此全部菜单可见）；含 `前缀:*`（如 `role:*`）时，请求码以 `前缀:` 开头即 true；否则精确匹配。权限树 / 角色授权页会把 `code='*'` 的权限行展示为「*（全部权限）」并正常勾选、展示（名称取后端「全部权限（通配）」）。
- 无任何管理权限：布局显示「无管理权限」空态而非白屏。
- **已移除「用户-系统开通」**：删除路由 `/user-systems`、页面、API 封装、菜单项与类型；系统访问权限完全由「用户-角色（有效角色）」推导。

## 系统接入页（平台超管专属）

- 列表列：系统编码 / 系统名称 / 区域 / 管理员账号 / 管理员姓名 / 状态 / 操作。
- 新建 / 编辑表单必填：系统编码（编辑不可改）、系统名称、所属区域、**系统管理员账号（adminExternalUserId）**；可选：管理员姓名（adminName）；另有状态（启用/停用）。
- 后端在接入时自动为管理员账号创建该系统默认 `admin` 角色并授权 `role:manage` 等管理权限码；前端只负责提交 `adminExternalUserId` / `adminName`。
- 请求模型（契约字段）：
  - `POST /api/systems`：`{code,name,region,adminExternalUserId,adminName?,status?}`
  - `PUT /api/systems/{id}`：`{name,region,adminExternalUserId?,adminName?,status?}`
  - `DELETE /api/systems/{id}`：停用（status=0）
  - `GET /api/systems?keyword=&page=&size=`：`items[] = {id,code,name,region,status,adminExternalUserId?,adminName?,...}`
- `/api/systems/**` 仅平台超管可调用（后端 403 兜底）。

## 只查当前系统数据（前端审计）

系统隔离模型下，前端所有权限相关查询都只使用「当前系统」上下文，禁止跨系统/全系统查询：

| 查询 | systemId 来源 | 页面 |
|---|---|---|
| 权限树 `GET /api/permissions?systemId=` | 当前 `currentSystemId` | 权限管理、角色授权、资源管理（关联权限弹窗） |
| 角色列表 `GET /api/roles?systemId=` | 当前 `currentSystemId` | 角色管理、角色授权、用户授权 |
| 角色授权 `GET/POST /api/roles/{roleId}/permissions[/grant|/revoke]` | 当前系统的角色 id | 角色授权 |
| 用户授权 `GET/POST /api/user-roles` | 查询参数 / 请求体 `systemId` | 用户授权 |
| 页面元素 `GET/POST /api/page-actions` | 查询参数 / 请求体 `systemId` | 页面元素 |
| 资源管理 `GET /api/sdk-endpoints`、`link/unlink` | 查询参数 `systemId` + 当前系统端点 | 资源管理 |
| 授权记录 `GET /api/approval-grants` | 查询参数 `systemId` | 授权记录（`/api/expiring-grants` 后端接口保留，前端不再展示） |

- `/api/me`、`/api/me/systems` 仅要求认证，不带 `X-System-Code`；其余 `/api/**` 均带 `X-System-Code: <currentSystemCode>`。
- 切换系统（含平台超管）：更新 `currentSystemCode` + `localStorage` → 失效并重拉 `admin-session`（bootstrap 带新系统头）→ 所有数据查询按新 `systemId` 重取；角色授权页清空上一系统所选角色、资源管理页关闭关联弹窗，且授权关系查询仅在「所选角色属于当前系统角色列表」时才发起，杜绝切换瞬间用旧 roleId 跨系统查询。
- mock 冒烟日志审计：CRM 阶段所有业务查询均为 `systemId=1` + `X-System-Code=crm`，无 `systemId=2`；superadmin 切到 ERP 后仅出现 `systemId=2` + `erp` 请求，权限树/角色/授权记录均为 ERP 数据，无 CRM 残留。

## 弹窗表单规范

- 统一使用 `Form layout="vertical"`；同一行字段用 `Row gutter={16} + Col`，不使用 `Space` 拼 flex；
- 弹窗宽度按字段量统一：普通 2 列 600–680、权限/资源关联 720；
- `.ant-modal` 内 `Select/TreeSelect/DatePicker/InputNumber` 统一 `width:100%`，避免控件过短；
- 字段间距 18px，textarea 不使用 `showCount`，避免与 footer 重叠。

## 状态管理

- **TanStack Query** 管理服务端状态：
  - `['admin-session']`：`/api/me` → `/api/me/systems` →（选当前系统）→ `/api/me/bootstrap` 串联结果；切换系统时失效重取
  - `['permissions', systemId]`、`['roles', systemId, {...}]`、`['role-permissions', systemId, roleId]`、`['user-roles', systemId, externalUserId]`、`['page-actions', systemId, {...}]`、`['sdk-endpoints', systemId, {...}]`、`['approval-grants', systemId, {...}]`
  - 切换系统时**只 refetch `admin-session`**，不全局 invalidate 旧 systemId 查询；页面 queryKey 含 systemId，系统上下文变化后自动按新 key 重取（避免「旧 systemId + 新系统头」的跨系统 403）
- **Zustand**（`src/stores/app.ts`）：`token`、`currentUser`、`systems`、`currentSystemCode`、`platformAdmin`、`permissions: Set<string>`、`hasPerm(code)`（平台超管恒 true）、`setBootstrap`、`logout`
  - 持久化：`rbac.token`（JWT）、`rbac.currentSystemCode`（当前业务系统上下文）

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
    ├── router.tsx               # 登录页 + 受保护布局 + 8 个权限管理页 + 系统接入（超管守卫）
    ├── config/menu.ts           # 菜单/路由/权限短码映射（role:manage 等）+ 系统接入（仅超管）
    ├── api/
    │   ├── request.ts           # 原生 fetch：JWT / X-System-Code=当前系统 / 信封解析 / 401
    │   ├── types.ts             # 契约类型（含 bootstrap / 系统接入管理员字段）
    │   ├── me.ts                # getMe / getMySystems / getBootstrap / loadAdminSession
    │   ├── systems.ts roles.ts permissions.ts rolePermissions.ts
    │   ├── userRoles.ts pageActions.ts sdkEndpoints.ts grants.ts
    │   └── index.ts
    ├── stores/app.ts            # Zustand：token/systems/currentSystemCode/permissions/hasPerm
    ├── layouts/AdminLayout.tsx  # 权限菜单 + 系统切换器 + 会话引导 + 无权限空态
    ├── components/              # PageShell/SystemGate/StatusTags/AppNotifier/错误边界/PermissionGuard/NoPermission
    ├── pages/                   # LoginPage + 9 个管理页面 + NotFoundPage
    ├── utils/                   # 时间格式、Ant 路径校验、分页配置、文案常量
    └── styles/global.css
```

## 错误与空态处理

- 统一响应信封 `{code,message,data}`：HTTP 非 2xx 或 `code !== 0` 抛出 `ApiError`，并通过 antd `message.error` 提示后端 `message`。
- 每个页面在查询失败时展示 `Alert`（含错误信息与「重试」按钮），不会白屏；列表查询为空时展示表格空态文案。
- 顶层 `AppErrorBoundary` 捕获渲染异常，展示可刷新的错误页。
- 未登录访问受保护路由自动跳转 `/login`；JWT 失效（401）自动清理会话并回到登录页。
- 权限不足：路由级 `PermissionGuard` 展示 403 无权限页（含「前往可用页面」按钮）；`/systems` 非超管访问展示「系统接入仅平台超管可访问」；无任何管理权限时布局展示「无管理权限」空态。
- 后端未启动时，页面提示「无法连接权限中心后端」，构建与开发服务器不依赖后端。

## 构建产物（本工程最近一次构建）

```text
> telbank-rbac-front@1.0.0 build
> tsc -b && vite build

vite v8.3.5 building client environment for production...
✓ 3255 modules transformed.
dist/index.html                     0.60 kB │ gzip:   0.40 kB
dist/assets/index-*.css             1.80 kB │ gzip:   0.84 kB
dist/assets/index-*.js          1,399.10 kB │ gzip: 442.35 kB
✓ built in ~0.5s
```

`npm run build` 总耗时约 3 秒（含 `tsc -b` 类型检查），`dist/` 总大小约 1.4 MB（JS gzip 约 442 kB）。

## 已知限制 / 后续

- 后端新契约（系统隔离、`platform:admin` 数据驱动超管、系统接入管理员字段）由 api-dev 并行改造；联调阶段若字段名有差异需要对齐。
- 未做路由级 code splitting：生产包单 chunk 约 1.4 MB（gzip 约 442 kB），后续可用 `React.lazy` + `manualChunks` 优化首屏。
- 授权记录按契约仅提供查询（只读）；「到期提醒」菜单/路由已移除（后端 `/api/expiring-grants` 接口保留）。
- 资源管理：未关联权限码的端点显示「未生效/未关联」并有当前页提示，行背景高亮；关联后才进入 SDK mappings 生效。
- 全局 `.ant-message*` 设置 `pointer-events:none`，避免切换系统 toast 覆盖顶部系统下拉框、导致下拉框点不开。

## 本地验证记录

- `npm run build`：`tsc -b` 类型检查 0 error，Vite 构建成功。
- `npm run dev`（5175 端口冒烟）：`curl http://localhost:5175/` 返回 HTTP 200。
- mock 后端 + headless Chrome（CDP）冒烟（系统隔离模型）：
  - `crm-admin-001`：仅见「权限管理」组 8 个菜单，无「系统接入」；顶部「当前系统：CRM系统」标签（单系统）；直接访问 `/systems` 显示「系统接入仅平台超管可访问」403；请求头 `X-System-Code=crm`、数据查询 `systemId=1`。
  - `erp-admin-001`：仅见 ERP（`X-System-Code=erp`、`systemId=2`），无「系统接入」。
  - `superadmin`（数据驱动超管）：见顶级「系统接入」+「权限管理」组；切换器可切 CRM/ERP，切换后 `localStorage["rbac.currentSystemCode"]` 与请求头同步变化、bootstrap 重新拉取；系统接入列表含管理员账号/姓名列，新建表单含必填「系统管理员账号」与可选「管理员姓名」，提交载荷含 `adminExternalUserId/adminName`。
  - 非超管但拥有两个系统的管理员：可见切换器、无「系统接入」，直接访问 `/systems` 403。
  - 普通运营账号：无管理菜单，首页「无管理权限」空态；直接访问 `/roles` 显示无权限。
  - 退出登录后清空 `rbac.token` / `rbac.currentSystemCode` 并回到 `/login`。
- 通配权限与严格系统隔离冒烟（mock 后端 + headless Chrome，零 console 错误）：
  - `permissions=["*"]` 的 CRM admin：8 个「权限管理」菜单全部可见（无系统接入）；权限树显示 `*（全部权限）` 行；角色授权页选择 admin 后通配权限行已勾选、已授权列表展示「*（全部权限）」并提示通配授权。
  - `permissions=["role:*"]` 前缀通配账号：仅「角色管理 / 角色授权」可见；直接访问 `/permissions` 显示缺少 `permission:manage` 的 403。
  - 平台超管 `superadmin`：从 CRM 切到 ERP 后，权限树仅 ERP 权限（含 ERP 通配行）、角色列表为 ERP 角色、授权记录 roleId 为 ERP 角色；请求日志 100% 为 `systemId=2` + `X-System-Code=erp`，无 CRM 数据残留。
  - 请求审计：CRM 阶段所有权限相关查询均为 `systemId=1` + `X-System-Code=crm`；全程无跨系统 `systemId`、无空 systemId 的全系统查询（`/api/me`、`/api/me/systems` 为仅认证接口，按契约不带系统头）。
