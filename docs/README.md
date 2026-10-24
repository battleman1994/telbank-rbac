# telbank-rbac 文档总览（Documentation Map）

本文件是仓库文档的**权威性索引与维护规则**。所有 Markdown 的职责边界、更新顺序与最近修订记录如下。

## 1. 文档分层与权威性

| 文档 | 职责 | 权威性 | 何时更新 |
|---|---|---|---|
| [`docs/telbank-rbac.md`](telbank-rbac.md) | **需求基线**：业务范围、技术栈、数据模型、验收场景（第 9 节） | 需求以本文为准；实现期偏差见文末「附录 A 实现修订记录」 | 需求变更时先改基线并追加附录 A |
| [`docs/impl/contracts.md`](impl/contracts.md) | **跨端契约**：管理端 API、SDK 开放接口与签名、审批回调、Redis 消息、SDK 行为口径、偏差决策（§9） | 三端接口/协议以本文为准（与需求冲突处见 §9） | 接口或协议变化时同步更新，并通知三端 |
| [`docs/acceptance.md`](acceptance.md) | **验收矩阵与执行记录**：需求第 9 节逐条证据级别、执行命令、结果、已知限制 | 验收状态以本文「执行记录」为准 | 每次验收/补验后追加记录 |
| [`README.md`](../README.md) | **交付说明**：模块组成、环境要求、快速开始、SDK 接入、审批回调、配置、偏差清单 | 使用方式以本文为准 | 启动方式/配置/依赖变化时更新 |
| [`telbank-rbac2-starter/README.md`](../telbank-rbac2-starter/README.md) | JDK 21 版 SDK 说明：坐标、Boot 4 差异、构建测试 | JDK 21 版 SDK 以本文为准 | SDK 实现/协议变化时更新 |
| [`telbank-rbac-front/README.md`](../telbank-rbac-front/README.md) | 管理前端说明：技术栈、页面、状态管理、自接入、本地验证 | 前端细节以本文为准 | 前端页面/接口变化时更新 |
| [`samples/business-demo/README.md`](../samples/business-demo/README.md) | 验收用示例业务系统（非交付物）的构建与启动、SDK 拦截验证 | 示例系统以本文为准 | 示例或 SDK 接入方式变化时更新 |
| [`.github/workflows/sdk-jdk8-compat.yml`](../.github/workflows/sdk-jdk8-compat.yml) | CI：SDK 用 JDK 17 构建、JDK 8 运行时可加载（兼容性证据） | 兼容性验证以 CI 结果为准 | 构建口径/依赖变化时更新 |

## 2. 维护规则

1. **需求 → 契约 → 实现 → 验收**：需求变化先改基线（或追加附录 A），再改契约，最后补验收记录；不要只改代码不改文档。
2. **文档与代码冲突时以代码 + 验收记录为准**，并在当日修正文档；不允许长期保留过期描述。
3. 新增/修改接口时，三处必须同时更新：`docs/impl/contracts.md` 对应章节、两端（api / starter 或 front）的 README 相关命令或说明、`docs/acceptance.md` 中受影响的验收项。
4. 验收记录只追加不删除；已完成的历史限制项移入「已完成的补验」，不要从记录里抹掉。
5. 版本/数量类表述（表数量、接口数量、测试数量、构件版本）必须来自实际构建或代码，禁止手写估计值。

## 3. 当前文档状态（2026-10-24）

- 需求基线：`docs/telbank-rbac.md`（含附录 A 实现修订记录，13 项偏差/新增口径）
- 接口契约：`docs/impl/contracts.md` v2.2
- 验收状态：第 9 节条目均有证据（实测 / 单元测试 / 代码级说明）；SDK JDK 8 运行时兼容由 GitHub Actions 实测；真实 Redis Pub/Sub 已本机联调；**v2.0 系统隔离重构（A11）**：数据驱动超管（`superadmin`）、系统由有效角色推导、系统接入仅超管、用户-系统开通已移除
- 已验证：权限中心 **112 单测**（MyBatis-Plus 持久层 + A 方案通配/严格隔离）、SDK（JDK 8）**113** 单测、SDK（JDK 21）**114** 单测、API 验收 **88/88**（v2.0，含两个 SDK 端到端 4/4+4/4）、前端构建、JDK 8 运行时 CI、**手动 SQL 初始化（A10，已移除 Flyway）**、权限时效演示（1 分钟过期）
- 待补（可选）：管理前端 ↔ 真实后端浏览器人工联调（前后端均已就绪，当前前端为 build + mock 冒烟）；生产 HTTPS 链路的真机验证（本机验收为 HTTP demo）

## 4. 修订记录

| 日期 | 修订 |
|---|---|
| 2026-10-24 | 建立文档总览；统一三端文档口径；需求基线补附录 A；契约升级 v1.1（SDK 构建工具链 JDK 17 / api_pattern `{var}` / `user-systems` 接口 / 真实 Redis 与 JDK 8 CI 证据）；修正过期表述（Redis 未安装、构件版本、验收命令等） |
| 2026-10-24 | **v2.2 持久层替换为 MyBatis-Plus（A13）**：移除 JPA/Hibernate，实体/Mapper/注解 SQL/MP 分页；REST 契约不变 |
| 2026-10-24 | **v2.1 A 方案通配权限 + 严格上下文（A12）**：admin/超管一条 `*`、前端/SDK 通配匹配、超管也按上下文查询 |
| 2026-10-24 | **v2.0 系统隔离重构（A11）**：删除用户-系统开通；数据驱动超管；管理上下文改为当前业务系统；管理权限短码；系统接入仅超管并录入管理员；契约升级 v2.0、菜单 IA 与验收矩阵同步 |
