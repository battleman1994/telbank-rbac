import { createBrowserRouter } from 'react-router'
import { HomeRedirect, PermissionGuard } from './components/PermissionGuard'
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
import UserSystemsPage from './pages/UserSystemsPage'

/**
 * 管理端路由（需求 §5.7）：登录页 + 受保护布局 + 10 个功能页面。
 * 每个页面绑定管理权限码（rbac:*），由 PermissionGuard 做路由级权限守卫：
 * 无权限渲染 403 无权限页（可跳转到第一个有权限菜单），平台超管全部放行。
 * 菜单结构由前端代码维护，权限中心不存储菜单（需求 §2）。
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
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.system}>
            <SystemsPage />
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
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.role}>
            <UserRolesPage />
          </PermissionGuard>
        ),
      },
      {
        path: 'user-systems',
        element: (
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.system}>
            <UserSystemsPage />
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
          <PermissionGuard permission={MANAGEMENT_PERMISSIONS.permission}>
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
