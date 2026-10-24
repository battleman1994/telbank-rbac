import { createBrowserRouter } from 'react-router'
import { HomeRedirect, PermissionGuard, PlatformAdminGuard } from './components/PermissionGuard'
import { MANAGEMENT_PERMISSIONS } from './config/menu'
import AdminLayout from './layouts/AdminLayout'
import ApprovalGrantsPage from './pages/ApprovalGrantsPage'
import ExpiringGrantsPage from './pages/ExpiringGrantsPage'
import LoginPage from './pages/LoginPage'
import NotFoundPage from './pages/NotFoundPage'
import PageActionsPage from './pages/PageActionsPage'
import PermissionsPage from './pages/PermissionsPage'
import RolePermissionsPage from './pages/RolePermissionsPage'
import RolesPage from './pages/RolesPage'
import SdkEndpointsPage from './pages/SdkEndpointsPage'
import SystemsPage from './pages/SystemsPage'
import UserRolesPage from './pages/UserRolesPage'

/**
 * 管理端路由（系统隔离模型）：
 * - 登录页 + 受保护布局 + 8 个权限管理页面 + 平台超管专属「系统接入」
 * - 权限管理页面绑定当前系统内的短码权限（role:manage / permission:manage /
 *   resource:manage / page:manage / user:manage / audit:view），
 *   由 PermissionGuard 做路由级权限守卫（无权限渲染 403 页）
 * - /systems（系统接入）仅平台超管可访问（PlatformAdminGuard）
 */
export const router = createBrowserRouter([
  {
    path: '/login',
    element: <LoginPage />,
  },
  {
    path: '/',
    element: <AdminLayout />,
    children: [
      { index: true, element: <HomeRedirect /> },
      {
        path: 'systems',
        element: (
          <PlatformAdminGuard>
            <SystemsPage />
          </PlatformAdminGuard>
        ),
      },
      {
        path: 'roles',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.role}>
            <RolesPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'permissions',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.permission}>
            <PermissionsPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'role-permissions',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.role}>
            <RolePermissionsPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'user-roles',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.user}>
            <UserRolesPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'page-actions',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.page}>
            <PageActionsPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'sdk-endpoints',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.resource}>
            <SdkEndpointsPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'approval-grants',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.audit}>
            <ApprovalGrantsPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'expiring-grants',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.audit}>
            <ExpiringGrantsPage />
          </PermissionGuard>
        ),
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
