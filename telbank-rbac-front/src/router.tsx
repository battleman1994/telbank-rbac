import { createBrowserRouter, Navigate } from 'react-router'
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
      { index: true, element: <Navigate to="/systems" replace /> },
      { path: 'systems', element: <SystemsPage /> },
      { path: 'roles', element: <RolesPage /> },
      { path: 'permissions', element: <PermissionsPage /> },
      { path: 'role-permissions', element: <RolePermissionsPage /> },
      { path: 'user-roles', element: <UserRolesPage /> },
      { path: 'user-systems', element: <UserSystemsPage /> },
      { path: 'page-actions', element: <PageActionsPage /> },
      { path: 'sdk-endpoints', element: <SdkEndpointsPage /> },
      { path: 'approval-grants', element: <ApprovalGrantsPage /> },
      { path: 'expiring-grants', element: <ExpiringGrantsPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
])
