/**
 * 权限管理菜单 / 路由权限配置（系统隔离模型）。
 *
 * 管理权限码为「当前系统内」的短码，菜单 / 路由守卫共用：
 * - role:manage        → 角色管理、角色授权
 * - permission:manage  → 权限管理
 * - resource:manage    → 资源管理（SDK 端点资源）
 * - page:manage        → 页面元素
 * - user:manage        → 用户授权
 * - audit:view         → 授权记录、到期提醒
 * 顶级「系统接入」（/systems）仅平台超管可见，无权限码。
 */

/** 当前系统内的管理权限码常量（与后端 @perm.has(...) 口径一致） */
export const MANAGEMENT_PERMISSIONS = {
  role: 'role:manage',
  permission: 'permission:manage',
  resource: 'resource:manage',
  page: 'page:manage',
  user: 'user:manage',
  audit: 'audit:view',
} as const

export type ManagementPermissionCode =
  (typeof MANAGEMENT_PERMISSIONS)[keyof typeof MANAGEMENT_PERMISSIONS]

/** 菜单可见性上下文：hasPerm 已含平台超管旁路，platformAdmin 用于无权限码菜单 */
export interface MenuVisibility {
  hasPerm: (code: string) => boolean
  platformAdmin: boolean
}

export interface ManagementMenuItem {
  /** 路由路径，同时作为菜单 key */
  path: string
  label: string
  /** 所需权限码；不填表示仅平台超管可见 */
  permission?: ManagementPermissionCode
  /** 仅平台超管可见（如系统接入） */
  platformAdminOnly?: boolean
  /** 菜单分组名；不填表示顶级菜单项 */
  group?: string
}

export const SYSTEM_ACCESS_PATH = '/systems'
export const MENU_GROUP_NAME = '权限管理'

/** 与路由 / 菜单一一对应的管理项（顺序即菜单展示顺序） */
export const MANAGEMENT_MENU_ITEMS: readonly ManagementMenuItem[] = [
  {
    path: SYSTEM_ACCESS_PATH,
    label: '系统接入',
    platformAdminOnly: true,
  },
  {
    path: '/roles',
    label: '角色管理',
    permission: MANAGEMENT_PERMISSIONS.role,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/permissions',
    label: '权限管理',
    permission: MANAGEMENT_PERMISSIONS.permission,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/role-permissions',
    label: '角色授权',
    permission: MANAGEMENT_PERMISSIONS.role,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/user-roles',
    label: '用户授权',
    permission: MANAGEMENT_PERMISSIONS.user,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/sdk-endpoints',
    label: '资源管理',
    permission: MANAGEMENT_PERMISSIONS.resource,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/page-actions',
    label: '页面元素',
    permission: MANAGEMENT_PERMISSIONS.page,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/approval-grants',
    label: '授权记录',
    permission: MANAGEMENT_PERMISSIONS.audit,
    group: MENU_GROUP_NAME,
  },
  {
    path: '/expiring-grants',
    label: '到期提醒',
    permission: MANAGEMENT_PERMISSIONS.audit,
    group: MENU_GROUP_NAME,
  },
]

/** 菜单分组顺序 */
export const MENU_GROUP_ORDER: readonly string[] = [MENU_GROUP_NAME]

/** 菜单项是否对当前账号可见 */
export function isMenuItemVisible(item: ManagementMenuItem, visibility: MenuVisibility): boolean {
  if (item.platformAdminOnly) return visibility.platformAdmin
  if (!item.permission) return true
  return visibility.hasPerm(item.permission)
}

/** 当前账号可见菜单项（保持定义顺序） */
export function getVisibleMenuItems(visibility: MenuVisibility): ManagementMenuItem[] {
  return MANAGEMENT_MENU_ITEMS.filter((item) => isMenuItemVisible(item, visibility))
}

/** 路由路径 → 所需权限码；系统接入返回 undefined（仅平台超管守卫） */
export function getRoutePermission(path: string): ManagementPermissionCode | undefined {
  const item = MANAGEMENT_MENU_ITEMS.find((menuItem) => menuItem.path === path)
  return item?.permission
}

/**
 * 返回当前账号第一个有权限 / 可见的菜单路径；无任何可见菜单时返回 null。
 * 用于首页重定向与 403 页的「前往可用页面」。
 */
export function getFirstPermittedPath(visibility: MenuVisibility): string | null {
  const item = getVisibleMenuItems(visibility)[0]
  return item?.path ?? null
}
