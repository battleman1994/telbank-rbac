/**
 * 权限中心管理端 API 类型定义。
 * 与 docs/impl/contracts.md §3 严格对齐：所有 /api/** 返回统一信封
 * `{ code, message, data }`，分页 data 为 `{ items, total, page, size }`，
 * 时间字段为 ISO-8601 UTC 字符串（如 2026-10-31T23:59:59Z）。
 */

/** 统一响应信封（契约 §1.2） */
export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

/** 分页响应载荷（契约 §1.3） */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface PageQuery {
  page?: number
  size?: number
}

/* ------------------------------------------------------------------ */
/* §3.1 当前用户与系统                                                  */
/* ------------------------------------------------------------------ */

export interface MeVO {
  externalUserId: string
  platformAdmin: boolean
}

export interface MySystemVO {
  id: number
  code: string
  name: string
  region: string
  isDefault: boolean
}

/**
 * GET /api/me/bootstrap —— 当前系统上下文（需带 X-System-Code: 当前系统）。
 * 新系统隔离模型：currentSystemCode 为当前系统；permissions 为「当前系统内」的
 * 短码权限集合（role:manage / permission:manage / resource:manage / page:manage /
 * user:manage / audit:view）；平台超管 permissions 返回 ["*"]，hasPerm 恒为 true。
 */
export interface AdminBootstrapVO {
  externalUserId: string
  platformAdmin: boolean
  /** 当前系统上下文编码（与请求头 X-System-Code 一致；无可访问系统时为 null） */
  currentSystemCode: string | null
  /** 当前系统内有效权限码集合；平台超管为 ["*"] */
  permissions: string[]
  /** 当前账号可访问系统（由有效角色推导；平台超管为全部启用系统） */
  systems: MySystemVO[]
}

/** 管理端会话引导结果：/api/me → /api/me/systems → /api/me/bootstrap */
export interface AdminSessionVO {
  me: MeVO
  systems: MySystemVO[]
  bootstrap: AdminBootstrapVO
  /** 选定的当前系统编码（与 bootstrap.currentSystemCode 一致，可能为 null） */
  currentSystemCode: string | null
}

/* ------------------------------------------------------------------ */
/* §3.2 系统接入（平台超管专属）                                          */
/* ------------------------------------------------------------------ */

export interface SysSystemVO {
  id: number
  code: string
  name: string
  region: string
  status: number
  /** 系统管理员账号（接入时自动创建默认 admin 角色并授权） */
  adminExternalUserId?: string | null
  /** 系统管理员姓名（可选） */
  adminName?: string | null
  /** SDK 接入 AppKey（等宽展示，可复制） */
  appKey?: string | null
  /** 已脱敏的 AppSecret（完整密钥仅在创建/审核通过响应中返回一次） */
  appSecretMasked?: string | null
  sort?: number
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SystemCreatePayload {
  code: string
  name: string
  region: string
  /** 系统管理员账号（必填） */
  adminExternalUserId: string
  /** 管理员姓名（可选） */
  adminName?: string
  status?: number
}

export interface SystemUpdatePayload {
  name: string
  region: string
  adminExternalUserId?: string
  adminName?: string
  status?: number
}

/* ------------------------------------------------------------------ */
/* §3.2c 系统接入凭据（A18：当前系统 / 超管按 systemId 查看完整密钥）    */
/* ------------------------------------------------------------------ */

export interface SystemCredentialVO {
  systemId: number
  systemCode: string
  systemName: string
  region: string
  appKey: string
  /** 完整 AppSecret（敏感信息，仅授权页面/超管展示） */
  appSecret: string
}

/* ------------------------------------------------------------------ */
/* §3.2b 系统接入申请（A16：SDK 自动上报 + 平台超管审核）                */
/* ------------------------------------------------------------------ */

export type SystemApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

export interface SystemApplicationVO {
  id: number
  systemCode: string
  name: string
  region: string
  /** 来源：SDK / MANUAL */
  source: string
  appKey?: string | null
  sdkVersion?: string | null
  remark?: string | null
  status: SystemApplicationStatus
  rejectReason?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  /** 审核通过后创建的业务系统 id */
  systemId?: number | null
  createdAt?: string | null
}

export interface SystemApplicationListQuery extends PageQuery {
  status?: SystemApplicationStatus
}

export interface SystemApplicationApprovePayload {
  adminExternalUserId: string
  adminName?: string
}

export interface SystemApplicationRejectPayload {
  reason: string
}

/** POST /api/systems 响应：系统 VO + 仅本次返回的完整 AppSecret（后续只返回掩码） */
export interface SystemCreateResultVO extends SysSystemVO {
  appSecret?: string | null
}

/** POST /api/system-applications/{id}/approve 响应：申请 VO + 新系统 AppKey/完整 AppSecret（仅本次返回） */
export interface SystemApproveResultVO extends SystemApplicationVO {
  appKey?: string | null
  appSecret?: string | null
}

/* ------------------------------------------------------------------ */
/* §3.2d 时间模板（A21，全局，不按系统隔离）                             */
/* ------------------------------------------------------------------ */

/**
 * 时间模板：只在「用户角色」写入时计算一个绝对授权窗口，本身不按系统隔离。
 * 角色绑定模板后为临时角色；未绑定模板为永久角色（窗口为空）。
 */
export interface TimeTemplateVO {
  id: number
  name: string
  /** IANA 时区，默认 Asia/Shanghai */
  timezone: string
  /** 相对锚点日期的开始天数偏移（0–3650） */
  startDayOffset: number
  /** 本地开始时间 HH:mm */
  startTime: string
  /** 相对锚点日期的结束天数偏移（0–3650） */
  endDayOffset: number
  /** 本地结束时间 HH:mm */
  endTime: string
  status: number
  remark?: string | null
}

export interface TimeTemplateCreatePayload {
  name: string
  timezone?: string
  startDayOffset: number
  startTime: string
  endDayOffset: number
  endTime: string
  status?: number
  remark?: string
}

export type TimeTemplateUpdatePayload = TimeTemplateCreatePayload

export interface TimeTemplateListQuery {
  /** 1 启用 / 0 停用；不传返回全部 */
  status?: number
}

/* ------------------------------------------------------------------ */
/* §3.3 角色管理                                                        */
/* ------------------------------------------------------------------ */

export interface RoleVO {
  id: number
  systemId: number
  code: string
  name: string
  status: number
  sort: number
  remark?: string | null
  /** 绑定的时间模板 ID；null 表示永久角色 */
  timeTemplateId?: number | null
  /** 绑定的时间模板名称；null/空表示永久角色 */
  timeTemplateName?: string | null
}

export interface RoleCreatePayload {
  systemId: number
  code: string
  name: string
  status?: number
  sort?: number
  remark?: string
  /** 可空；非空时模板必须存在且启用，admin 角色必须为空 */
  timeTemplateId?: number | null
}

export interface RoleUpdatePayload {
  name: string
  status?: number
  sort?: number
  remark?: string
  /** 可空；null 表示改为永久角色 */
  timeTemplateId?: number | null
}

/* ------------------------------------------------------------------ */
/* §3.4 权限资源管理（树形）                                             */
/* ------------------------------------------------------------------ */

/** 权限资源类型：1 目录/页面级、2 操作级、3 纯接口 */
export type PermissionType = 1 | 2 | 3

export interface PermissionNode {
  id: number
  systemId: number
  parentId: number
  type: PermissionType
  code: string
  name: string
  method?: string | null
  apiPattern?: string | null
  sort: number
  status: number
  isPublic: number
  meta?: unknown
  children?: PermissionNode[]
}

export interface PermissionSavePayload {
  systemId: number
  parentId?: number
  type: PermissionType
  code: string
  name: string
  method?: string | null
  apiPattern?: string | null
  sort?: number
  status?: number
  isPublic?: number
  meta?: unknown
}

/* ------------------------------------------------------------------ */
/* §3.5 角色-权限授权                                                   */
/* ------------------------------------------------------------------ */

/**
 * 角色已授权项（A21）：角色-权限为静态授权，授权/撤销即时生效，不再带时效。
 * 时间语义上移到「用户-角色」（validFrom/validUntil 属于 UserRoleVO）。
 */
export interface PermissionGrant {
  permissionId: number
  code: string
  status: number
}

export interface RolePermissionResult {
  roleId: number
  grants: PermissionGrant[]
}

export interface GrantPayload {
  permissionIds: number[]
  grantReason?: string
}

export interface RevokePayload {
  permissionIds: number[]
}

/* ------------------------------------------------------------------ */
/* §3.6 用户-角色分配                                                   */
/* ------------------------------------------------------------------ */

export interface UserRoleVO {
  id: number
  systemId: number
  externalUserId: string
  roleId: number
  roleCode: string
  roleName: string
  validFrom: string | null
  validUntil: string | null
  status: number
  grantReason: string | null
  sourceApprovalId: string | null
}

export interface UserRoleCreatePayload {
  systemId: number
  externalUserId: string
  roleId: number
  grantReason?: string
}

/* ------------------------------------------------------------------ */
/* §3.8 页面操作元数据（模式 B）                                        */
/* ------------------------------------------------------------------ */

export const PAGE_ACTION_KINDS = [
  'openDialog',
  'callApi',
  'download',
  'navigate',
  'confirmThenCallApi',
] as const

export type PageActionKind = (typeof PAGE_ACTION_KINDS)[number]

export interface PageActionVO {
  id: number
  systemId: number
  pageCode: string
  code: string
  text: string
  type: string
  action: PageActionKind | string
  config: unknown
  sort: number
  status: number
}

export interface PageActionPayload {
  systemId: number
  pageCode: string
  code: string
  text: string
  type?: string
  action: PageActionKind
  config?: unknown
  sort?: number
}

/* ------------------------------------------------------------------ */
/* §3.9 SDK 端点上报列表                                                */
/* ------------------------------------------------------------------ */

export interface SdkEndpointVO {
  id: number
  systemId: number
  method: string
  path: string
  candidateCode: string | null
  source: string
  permissionId: number | null
  permissionCode: string | null
  reportedAt: string | null
}

/* ------------------------------------------------------------------ */
/* §3.10 审批授权记录 / 即将到期                                         */
/* ------------------------------------------------------------------ */

export type ApprovalGrantKind = 'USER_ROLE' | 'ROLE_PERMISSION'

export interface ApprovalGrantVO {
  kind: ApprovalGrantKind
  systemId: number
  externalUserId?: string | null
  roleId?: number | null
  permissionId?: number | null
  sourceApprovalId: string | null
  grantReason: string | null
  validFrom: string | null
  validUntil: string | null
  status: number
  grantedAt: string | null
}

/* ------------------------------------------------------------------ */
/* §5.1 业务用户临时权限申请（申请人侧，自助申请）                      */
/* ------------------------------------------------------------------ */

/** 申请单状态：待审批 / 已通过 / 已驳回（审批在外部审批平台完成） */
export type AccessApplicationStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** 可申请角色中的权限预览项 */
export interface AccessRolePermissionVO {
  id: number
  code: string
  name: string
  type: PermissionType | number
}

/** 可申请角色（A21）：只返回「启用 + 非 admin + 已绑定启用时间模板」的角色 */
export interface AccessRoleOptionVO {
  roleId: number
  roleCode: string
  roleName: string
  owned: boolean
  /** 申请角色的时间模板 ID */
  timeTemplateId?: number | null
  /** 时间模板名称 */
  timeTemplateName?: string | null
  /** 时间模板摘要（如「审批后当天 19:00 ~ 次日 07:00」） */
  timeTemplateText?: string | null
  permissions: AccessRolePermissionVO[]
}

/** GET /api/access-applications/options —— 当前系统可申请角色与审批链接模板 */
export interface AccessApplicationOptionsVO {
  systemId: number
  systemCode: string
  systemName: string
  approvalUrlTemplate: string
  roles: AccessRoleOptionVO[]
}

/**
 * 申请单中的角色快照（A21）：记录申请时的 roleCode/roleName 与角色时间模板，
 * 审批通过后写入该角色自己的授权窗口（多角色窗口可以不同）。
 */
export interface AccessApplicationRoleVO {
  roleId: number
  roleCode: string
  roleName: string
  /** 角色绑定的时间模板名称（申请时快照） */
  timeTemplateName?: string | null
  /** 该角色审批通过后的生效时间（ISO-8601 UTC，仅 APPROVED 后存在） */
  approvedValidFrom?: string | null
  /** 该角色审批通过后的失效时间（ISO-8601 UTC，仅 APPROVED 后存在） */
  approvedValidUntil?: string | null
}

/**
 * 申请单视图：时间字段为 ISO-8601 UTC 字符串；
 * approvedValidFrom/approvedValidUntil 为所有角色窗口的包络（最早开始、最晚结束），
 * 仅在审批通过后存在；逐角色窗口见 roles[].approvedValidFrom/Until。
 */
export interface AccessApplicationVO {
  id: number
  applicationNo: string
  systemId: number
  systemCode: string
  status: AccessApplicationStatus
  roleCount: number
  roles: AccessApplicationRoleVO[]
  reason: string
  approvedValidFrom: string | null
  approvedValidUntil: string | null
  decisionComment: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  createdAt: string
  approvalUrl: string | null
}

/** POST /api/access-applications —— roleIds 非空且不超过 20 个，reason 1–200 字 */
export interface AccessApplicationCreatePayload {
  roleIds: number[]
  reason: string
}

/* ------------------------------------------------------------------ */
/* 查询参数                                                             */
/* ------------------------------------------------------------------ */

export interface KeywordPageQuery extends PageQuery {
  keyword?: string
}

export interface AccessApplicationListQuery extends PageQuery {
  status?: AccessApplicationStatus
}

export interface RoleListQuery extends KeywordPageQuery {
  systemId: number
}

export interface PageActionListQuery extends PageQuery {
  systemId: number
  pageCode?: string
}

export interface SdkEndpointListQuery extends KeywordPageQuery {
  systemId: number
}

export interface UserRoleListQuery {
  systemId: number
  externalUserId: string
}

export interface ApprovalGrantListQuery extends PageQuery {
  systemId?: number
  externalUserId?: string
}

