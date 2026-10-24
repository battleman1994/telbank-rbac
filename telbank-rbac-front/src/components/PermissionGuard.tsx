import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import { getFirstPermittedPath } from '../config/menu'
import { useHasPerm } from '../stores/app'
import { NoPermission } from './NoPermission'

interface PermissionGuardProps {
  /** 访问该路由所需的管理权限码 */
  permission: string
  children: ReactNode
}

/**
 * 路由级权限守卫：无对应权限码时渲染 403 无权限页（不白屏），
 * 平台超管 / permissions 含 "*" 时全部放行。
 */
export function PermissionGuard({ permission, children }: PermissionGuardProps) {
  const hasPerm = useHasPerm()
  if (!hasPerm(permission)) {
    return <NoPermission permission={permission} />
  }
  return <>{children}</>
}

/**
 * 首页重定向：跳转到当前账号第一个有权限的菜单；
 * 无任何管理权限时渲染 403 空态（AdminLayout 同时会有整体空态兜底）。
 */
export function HomeRedirect() {
  const hasPerm = useHasPerm()
  const firstPermittedPath = getFirstPermittedPath(hasPerm)
  if (!firstPermittedPath) {
    return (
      <NoPermission
        title="无管理权限"
        description="当前账号没有被授予权限中心任何管理权限（rbac:*），请联系平台超管授权或使用其他账号登录。"
      />
    )
  }
  return <Navigate to={firstPermittedPath} replace />
}
