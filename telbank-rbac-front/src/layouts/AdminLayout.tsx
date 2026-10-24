import { useEffect, useState } from 'react'
import {
  ApartmentOutlined,
  ApiOutlined,
  AppstoreOutlined,
  AuditOutlined,
  BankOutlined,
  ClockCircleOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
  UserSwitchOutlined,
} from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Avatar,
  Button,
  Flex,
  Layout,
  Menu,
  Result,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
} from 'antd'
import type { MenuProps } from 'antd'
import type { ReactNode } from 'react'
import { Outlet, useLocation, useNavigate } from 'react-router'
import { loadAdminSession } from '../api'
import { NoPermission } from '../components/NoPermission'
import {
  MANAGEMENT_MENU_ITEMS,
  MENU_GROUP_ORDER,
  type ManagementMenuItem,
} from '../config/menu'
import { useAppStore, useBusinessSystems, useHasPerm } from '../stores/app'

const { Header, Sider, Content } = Layout
const { Text } = Typography

type ManagementMenuItemNode = NonNullable<MenuProps['items']>[number]

const MENU_ICON_BY_PATH: Record<string, ReactNode> = {
  '/systems': <BankOutlined />,
  '/roles': <TeamOutlined />,
  '/permissions': <ApartmentOutlined />,
  '/role-permissions': <SafetyCertificateOutlined />,
  '/user-roles': <UserSwitchOutlined />,
  '/user-systems': <AppstoreOutlined />,
  '/page-actions': <SettingOutlined />,
  '/sdk-endpoints': <ApiOutlined />,
  '/approval-grants': <AuditOutlined />,
  '/expiring-grants': <ClockCircleOutlined />,
}

function toMenuItem(item: ManagementMenuItem): ManagementMenuItemNode {
  return {
    key: item.path,
    icon: MENU_ICON_BY_PATH[item.path],
    label: item.label,
  }
}

/** 按 hasPerm 过滤菜单：无权限项不渲染；空分组不渲染 */
function buildMenuItems(can: (code: string) => boolean): ManagementMenuItemNode[] {
  const visibleItems = MANAGEMENT_MENU_ITEMS.filter((item) => can(item.permission))
  const items: ManagementMenuItemNode[] = visibleItems
    .filter((item) => !item.group)
    .map(toMenuItem)
  MENU_GROUP_ORDER.forEach((group) => {
    const children = visibleItems.filter((item) => item.group === group).map(toMenuItem)
    if (children.length > 0) {
      items.push({ type: 'group', label: group, children })
    }
  })
  return items
}

/**
 * 受保护布局（权限中心自身接入 RBAC）：
 * - 刷新/登录后按 /api/me → /api/me/systems → /api/me/bootstrap 串联加载管理上下文
 * - 侧边栏菜单由 bootstrap 返回的管理权限码过滤
 * - 顶部系统切换器只列出业务系统（排除管理上下文系统 rbac），业务系统选择仅用于数据筛选
 * - 无任何管理权限时展示「无管理权限」空态而非白屏
 */
export default function AdminLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const token = useAppStore((state) => state.token)
  const currentUser = useAppStore((state) => state.currentUser)
  const currentSystemCode = useAppStore((state) => state.currentSystemCode)
  const platformAdmin = useAppStore((state) => state.platformAdmin)
  const managementSystemCode = useAppStore((state) => state.managementSystemCode)
  const setCurrentUser = useAppStore((state) => state.setCurrentUser)
  const setSystems = useAppStore((state) => state.setSystems)
  const setCurrentSystemCode = useAppStore((state) => state.setCurrentSystemCode)
  const setBootstrap = useAppStore((state) => state.setBootstrap)
  const logout = useAppStore((state) => state.logout)

  const hasPerm = useHasPerm()
  const businessSystems = useBusinessSystems()

  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { message } = AntApp.useApp()

  const sessionQuery = useQuery({
    queryKey: ['admin-session'],
    queryFn: () => loadAdminSession(),
    enabled: Boolean(token),
    retry: 1,
    staleTime: 5 * 60 * 1000,
    refetchOnWindowFocus: false,
  })

  useEffect(() => {
    if (!token) {
      void navigate('/login', { replace: true })
    }
  }, [token, navigate])

  useEffect(() => {
    const session = sessionQuery.data
    if (!session) return
    const { me, systems, bootstrap } = session
    setCurrentUser(me)
    const allSystems = systems.length > 0 ? systems : bootstrap.systems
    setSystems(allSystems)
    setBootstrap(bootstrap)

    // 业务系统选择只允许非管理上下文系统；无有效选择时回落到默认业务系统
    const business = allSystems.filter((system) => system.code !== bootstrap.managementSystemCode)
    const storedCode = useAppStore.getState().currentSystemCode
    const hasValidStoredCode =
      Boolean(storedCode) && business.some((system) => system.code === storedCode)
    if (!hasValidStoredCode) {
      const defaultSystem = business.find((system) => system.isDefault) ?? business[0]
      setCurrentSystemCode(defaultSystem?.code ?? null)
    }
  }, [sessionQuery.data, setCurrentUser, setSystems, setBootstrap, setCurrentSystemCode])

  const menuItems = buildMenuItems(hasPerm)
  const hasManagementAccess = MANAGEMENT_MENU_ITEMS.some((item) => hasPerm(item.permission))
  const currentSystem = businessSystems.find((system) => system.code === currentSystemCode)

  if (!token) return null

  if (sessionQuery.isLoading) {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh' }}>
        <Spin size="large" description="正在加载管理权限…">
          <div style={{ width: 260, height: 120 }} />
        </Spin>
      </Flex>
    )
  }

  if (sessionQuery.isError) {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh' }}>
        <Result
          status="warning"
          title="加载管理上下文失败"
          subTitle="请确认权限中心后端已启动、JWT 未过期（/api/me/bootstrap 可用），然后重试或重新登录。"
          extra={[
            <Button key="retry" type="primary" onClick={() => void sessionQuery.refetch()}>
              重试
            </Button>,
            <Button key="logout" onClick={handleLogout}>
              重新登录
            </Button>,
          ]}
        />
      </Flex>
    )
  }

  function handleLogout() {
    logout()
    queryClient.clear()
    void message.success('已退出登录')
    void navigate('/login', { replace: true })
  }

  function handleSystemChange(code: string) {
    setCurrentSystemCode(code)
    // 业务系统切换只影响数据筛选，不重取管理上下文会话（admin-session）
    void queryClient.invalidateQueries({
      predicate: (query) => query.queryKey[0] !== 'admin-session',
    })
    const system = businessSystems.find((item) => item.code === code)
    void message.success(`已切换业务系统：${system?.name ?? code}（仅用于数据筛选）`)
  }

  const header = (
    <Header className="app-header">
      <Space size={16} wrap>
        <Space size={8}>
          <Text type="secondary">业务系统</Text>
          <Select
            value={currentSystemCode ?? undefined}
            placeholder={businessSystems.length > 0 ? '请选择业务系统' : '暂无业务系统'}
            style={{ width: 260 }}
            options={businessSystems.map((system) => ({
              value: system.code,
              label: `${system.name}（${system.code}）`,
            }))}
            onChange={handleSystemChange}
            disabled={businessSystems.length === 0}
            showSearch
            optionFilterProp="label"
          />
        </Space>
        <Tag color="purple">管理上下文：{managementSystemCode}</Tag>
        {currentSystem ? <Tag color="blue">region：{currentSystem.region}</Tag> : null}
      </Space>
      <Space size={12}>
        {platformAdmin ? <Tag color="gold">平台超管</Tag> : null}
        <Space size={8}>
          <Avatar size="small" icon={<UserOutlined />} />
          <Text>{currentUser?.externalUserId ?? '-'}</Text>
        </Space>
        <Button icon={<LogoutOutlined />} onClick={handleLogout}>
          退出登录
        </Button>
      </Space>
    </Header>
  )

  const sider = (
    <Sider
      width={232}
      theme="light"
      collapsible
      collapsed={collapsed}
      onCollapse={setCollapsed}
      className="app-sider"
    >
      <div className="app-logo">
        <SafetyCertificateOutlined className="app-logo-icon" />
        <span>权限中心管理台</span>
      </div>
      <Menu
        mode="inline"
        items={menuItems}
        selectedKeys={[location.pathname]}
        onClick={({ key }) => void navigate(key)}
        style={{ borderInlineEnd: 'none' }}
      />
      {menuItems.length === 0 ? (
        <div style={{ padding: '16px 20px', fontSize: 12, color: '#8c8c8c' }}>
          暂无可用菜单
        </div>
      ) : null}
    </Sider>
  )

  // 无任何管理权限：保留布局框架，内容区展示空态，不白屏
  if (!hasManagementAccess) {
    return (
      <Layout className="app-layout">
        {sider}
        <Layout>
          {header}
          <Content className="app-content">
            <NoPermission
              title="无管理权限"
              description={`当前账号（${currentUser?.externalUserId ?? '-'}）未被授予权限中心任何管理权限（rbac:*），请联系平台超管授权或使用其他账号登录。`}
            />
          </Content>
        </Layout>
      </Layout>
    )
  }

  return (
    <Layout className="app-layout">
      {sider}
      <Layout>
        {header}
        <Content className="app-content">
          <Flex vertical gap={16}>
            {businessSystems.length === 0 ? (
              <Alert
                type="warning"
                showIcon
                title="当前账号未开通任何业务系统"
                description="系统级页面需要先在「用户-系统开通」中为账号开通业务系统；管理类页面（系统/角色/权限等）不受影响。"
              />
            ) : null}
            <Outlet />
          </Flex>
        </Content>
      </Layout>
    </Layout>
  )
}
