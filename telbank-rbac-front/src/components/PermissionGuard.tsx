import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import { getFirstPermittedPath } from '../config/menu'
import { useAppStore, useHasPerm } from '../stores/app'
import { NoPermission } from './NoPermission'

interface PermissionGuardProps {
  /** 访问该路由所需的当前系统内权限码 */
  permission: string
  children: ReactNode
}

/** 路由级权限守卫：无对应权限码时渲染 403 无权限页（不白屏） */
export function PermissionGuard({ permission, children }: PermissionGuardProps) {
  const hasPerm = useHasPerm()
  if (!hasPerm(permission)) {
    return <NoPermission permission={permission} />
  }
  return <>{children}</>
}

/** 平台超管专属路由守卫（系统接入 /systems，无权限码） */
export function PlatformAdminGuard({ children }: { children: ReactNode }) {
  const platformAdmin = useAppStore((state) => state.platformAdmin)
  if (!platformAdmin) {
    return (
      <NoPermission
        title="无访问权限"
        description="「系统接入」仅平台超管可访问，请联系平台超管操作。"
      />
    )
  }
  return <>{children}</>
}

/**
 * 首页重定向：跳转到当前账号第一个可见菜单；
 * 无任何可见菜单时渲染 403 空态（AdminLayout 同时会整体空态兜底）。
 */
export function HomeRedirect() {
  const hasPerm = useHasPerm()
  const platformAdmin = useAppStore((state) => state.platformAdmin)
  const firstPermittedPath = getFirstPermittedPath({ hasPerm, platformAdmin })
  if (!firstPermittedPath) {
    return (
      <NoPermission
        title="无管理权限"
        description="当前账号在当前系统内没有任何管理权限，请联系平台超管授权或切换其他系统。"
      />
    )
  }
  return <Navigate to={firstPermittedPath} replace />
}
