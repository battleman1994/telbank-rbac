/**
 * 权限中心自身接入 RBAC 的菜单 / 路由权限配置。
 *
 * 管理上下文系统编码由 VITE_MANAGEMENT_SYSTEM_CODE（默认 rbac）决定；
 * 菜单与路由都绑定同一套管理权限码（种子数据已授予 rbac-adm 角色）：
 * - rbac:system:manage    → 系统管理、用户-系统开通
 * - rbac:role:manage      → 角色管理、角色-权限授权、用户-角色分配
 * - rbac:permission:manage→ 权限资源管理、SDK 端点上报
 * - rbac:page:manage      → 页面操作元数据
 * - rbac:audit:view       → 审批授权记录、即将到期授权
 */

/** 管理端权限码常量（与后端 @perm.has(...) 口径一致） */
export const MANAGEMENT_PERMISSIONS = {
  system: 'rbac:system:manage',
  role: 'rbac:role:manage',
  permission: 'rbac:permission:manage',
  page: 'rbac:page:manage',
  audit: 'rbac:audit:view',
} as const

export type ManagementPermissionCode =
  (typeof MANAGEMENT_PERMISSIONS)[keyof typeof MANAGEMENT_PERMISSIONS]

export interface ManagementMenuItem {
  /** 路由路径，同时作为菜单 key */
  path: string
  label: string
  permission: ManagementPermissionCode
  /** 菜单分组名；不填表示顶级菜单项 */
  group?: string
}

/** 与路由 / 菜单一一对应的管理项（顺序即菜单展示顺序） */
export const MANAGEMENT_MENU_ITEMS: readonly ManagementMenuItem[] = [
  {
    path: '/systems',
    label: '系统管理',
    permission: MANAGEMENT_PERMISSIONS.system,
  },
  {
    path: '/roles',
    label: '角色管理',
    permission: MANAGEMENT_PERMISSIONS.role,
    group: '权限配置',
  },
  {
    path: '/permissions',
    label: '权限资源管理',
    permission: MANAGEMENT_PERMISSIONS.permission,
    group: '权限配置',
  },
  {
    path: '/role-permissions',
    label: '角色-权限授权',
    permission: MANAGEMENT_PERMISSIONS.role,
    group: '权限配置',
  },
  {
    path: '/user-roles',
    label: '用户-角色分配',
    permission: MANAGEMENT_PERMISSIONS.role,
    group: '权限配置',
  },
  {
    path: '/user-systems',
    label: '用户-系统开通',
    permission: MANAGEMENT_PERMISSIONS.system,
    group: '权限配置',
  },
  {
    path: '/page-actions',
    label: '页面操作元数据',
    permission: MANAGEMENT_PERMISSIONS.page,
    group: '扩展与审计',
  },
  {
    path: '/sdk-endpoints',
    label: 'SDK 端点上报',
    permission: MANAGEMENT_PERMISSIONS.permission,
    group: '扩展与审计',
  },
  {
    path: '/approval-grants',
    label: '审批授权记录',
    permission: MANAGEMENT_PERMISSIONS.audit,
    group: '扩展与审计',
  },
  {
    path: '/expiring-grants',
    label: '即将到期授权',
    permission: MANAGEMENT_PERMISSIONS.audit,
    group: '扩展与审计',
  },
]

/** 路由路径 → 所需权限码 */
export const ROUTE_PERMISSION_BY_PATH: Record<string, ManagementPermissionCode> =
  Object.fromEntries(MANAGEMENT_MENU_ITEMS.map((item) => [item.path, item.permission])) as Record<
    string,
    ManagementPermissionCode
  >

/** 菜单分组顺序 */
export const MENU_GROUP_ORDER: readonly string[] = ['权限配置', '扩展与审计']

/**
 * 返回当前账号第一个有权限的菜单路径；无任何管理权限时返回 null。
 * 用于首页重定向与 403 页的「前往可用页面」。
 */
export function getFirstPermittedPath(hasPerm: (code: string) => boolean): string | null {
  const item = MANAGEMENT_MENU_ITEMS.find((menuItem) => hasPerm(menuItem.permission))
  return item?.path ?? null
}
