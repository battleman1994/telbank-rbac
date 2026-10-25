import { createBrowserRouter } from 'react-router'
import { HomeRedirect, PermissionGuard, PlatformAdminGuard } from './components/PermissionGuard'
import { MANAGEMENT_PERMISSIONS } from './config/menu'
import AdminLayout from './layouts/AdminLayout'
import AccessApplicationsPage from './pages/AccessApplicationsPage'
import ApprovalGrantsPage from './pages/ApprovalGrantsPage'
import LoginPage from './pages/LoginPage'
import NotFoundPage from './pages/NotFoundPage'
import PageActionsPage from './pages/PageActionsPage'
import PermissionsPage from './pages/PermissionsPage'
import RolePermissionsPage from './pages/RolePermissionsPage'
import RolesPage from './pages/RolesPage'
import SdkEndpointsPage from './pages/SdkEndpointsPage'
import SystemCredentialPage from './pages/SystemCredentialPage'
import SystemsPage from './pages/SystemsPage'
import UserRolesPage from './pages/UserRolesPage'

/**
 * 管理端路由（系统隔离模型）：
 * - 登录页 + 受保护布局 + 7 个权限管理页面 + 平台超管专属「系统接入」
 * - 权限管理页面绑定当前系统内的短码权限（role:manage / permission:manage /
 *   resource:manage / page:manage / user:manage / audit:view），
 *   由 PermissionGuard 做路由级权限守卫（无权限渲染 403 页）
 * - /systems（系统接入）仅平台超管可访问（PlatformAdminGuard）
 * - /system-credential（接入凭据）需 system:credential（admin 角色由 `*` 通配通过）
 * - /access-applications（我的申请）为自助入口，不包管理权限守卫，所有登录用户可访问
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
        // 自助申请入口：当前系统内申请临时角色权限，审批在外部审批平台完成
        path: 'access-applications',
        element: <AccessApplicationsPage />,
      },
      {
        path: 'systems',
        element: (
          <PlatformAdminGuard>
            <SystemsPage />
          </PlatformAdminGuard>
        ),
      },
      {
        path: 'system-credential',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.credential}>
            <SystemCredentialPage />
          </PermissionGuard>
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
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
