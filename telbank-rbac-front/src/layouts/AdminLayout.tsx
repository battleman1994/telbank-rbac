import { useEffect, useState } from 'react'
import {
  ApartmentOutlined,
  ApiOutlined,
  AuditOutlined,
  BankOutlined,
  FileTextOutlined,
  FormOutlined,
  LogoutOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  TeamOutlined,
  UserOutlined,
  UserSwitchOutlined,
} from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
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
  isMenuItemVisible,
  type ManagementMenuItem,
  type MenuVisibility,
} from '../config/menu'
import { useAppStore, useHasPerm } from '../stores/app'

const { Header, Sider, Content } = Layout
const { Text } = Typography

type MenuItemNode = NonNullable<MenuProps['items']>[number]

const MENU_ICON_BY_PATH: Record<string, ReactNode> = {
  '/access-applications': <FormOutlined />,
  '/systems': <BankOutlined />,
  '/system-credential': <FileTextOutlined />,
  '/roles': <TeamOutlined />,
  '/permissions': <ApartmentOutlined />,
  '/role-permissions': <SafetyCertificateOutlined />,
  '/user-roles': <UserSwitchOutlined />,
  '/sdk-endpoints': <ApiOutlined />,
  '/page-actions': <SettingOutlined />,
  '/approval-grants': <AuditOutlined />,
}

function toMenuItem(item: ManagementMenuItem): MenuItemNode {
  return {
    key: item.path,
    icon: MENU_ICON_BY_PATH[item.path],
    label: item.label,
  }
}

/** 按当前系统权限码过滤菜单：无权限项不渲染；空分组不渲染 */
function buildMenuItems(visibility: MenuVisibility): MenuItemNode[] {
  const visibleItems = MANAGEMENT_MENU_ITEMS.filter((item) =>
    isMenuItemVisible(item, visibility),
  )
  const items: MenuItemNode[] = visibleItems
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
 * 受保护布局（系统隔离模型）：
 * - 刷新/登录后按 /api/me → /api/me/systems →（选当前系统）→ /api/me/bootstrap 加载上下文
 * - X-System-Code 恒为当前系统；侧边栏菜单由当前系统内的权限码（短码）过滤
 * - 平台超管见顶级「系统接入」并可在所有系统间切换；普通系统管理员仅见自己的系统，
 *   系统数 >1 时提供切换器，=1 时显示「当前系统：xxx」标签
 * - 切换系统只更新 store/请求头并重新拉 bootstrap + 失效数据查询，不整页刷新
 * - 「我的申请」为自助入口（始终可见），无管理权限的普通用户也能进入该页；
 *   仅当当前系统无任何可见菜单时才整区展示空态提示，不白屏
 */
export default function AdminLayout() {
  const [collapsed, setCollapsed] = useState(false)
  const token = useAppStore((state) => state.token)
  const currentUser = useAppStore((state) => state.currentUser)
  const systems = useAppStore((state) => state.systems)
  const currentSystemCode = useAppStore((state) => state.currentSystemCode)
  const platformAdmin = useAppStore((state) => state.platformAdmin)
  const setCurrentUser = useAppStore((state) => state.setCurrentUser)
  const setSystems = useAppStore((state) => state.setSystems)
  const setBootstrap = useAppStore((state) => state.setBootstrap)
  const setCurrentSystemCode = useAppStore((state) => state.setCurrentSystemCode)
  const logout = useAppStore((state) => state.logout)

  const hasPerm = useHasPerm()

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
    setCurrentUser(session.me)
    const allSystems =
      session.bootstrap.systems?.length > 0 ? session.bootstrap.systems : session.systems
    setSystems(allSystems)
    setBootstrap(session.bootstrap)
  }, [sessionQuery.data, setCurrentUser, setSystems, setBootstrap])

  const visibility: MenuVisibility = { hasPerm, platformAdmin }
  const menuItems = buildMenuItems(visibility)
  const currentSystem = systems.find((system) => system.code === currentSystemCode)
  const canSwitchSystem = systems.length > 1

  if (!token) return null

  if (sessionQuery.isLoading) {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh' }}>
        <Spin size="large" description="正在加载系统上下文…">
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
          title="加载系统上下文失败"
          subTitle="请确认权限中心后端已启动、JWT 未过期（/api/me/systems 与 /api/me/bootstrap 可用），然后重试或重新登录。"
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
    if (code === currentSystemCode) return
    const system = systems.find((item) => item.code === code)
    // 先更新 store + localStorage（request() 注入 X-System-Code），只重取会话。
    // 注意：不能调用 queryClient.invalidateQueries() 全局失效——
    // 页面查询 key 里带的是旧 systemId，立即重取会变成「旧 systemId + 新系统头」，
    // 被后端 SystemScopeGuard 判定为跨系统访问并返回 403。
    // 页面数据查询的 queryKey 已包含 systemId，currentSystemCode 变更后组件会以新
    // systemId 生成新 query key 自动重取；旧的未激活缓存不再触发请求。
    setCurrentSystemCode(code)
    void sessionQuery.refetch()
    void message.success(`已切换到系统：${system?.name ?? code}`)
  }

  const header = (
    <Header className="app-header">
      <Space size={16} wrap>
        <Space size={8}>
          {canSwitchSystem ? <Text type="secondary">当前系统</Text> : null}
          {canSwitchSystem ? (
            <Select
              value={currentSystemCode ?? undefined}
              placeholder="请选择系统"
              style={{ width: 240 }}
              options={systems.map((system) => ({
                value: system.code,
                label: `${system.name}（${system.code}）`,
              }))}
              onChange={handleSystemChange}
              showSearch
              optionFilterProp="label"
              loading={sessionQuery.isFetching}
            />
          ) : (
            <Tag color="blue">
              当前系统：{currentSystem ? currentSystem.name : (currentSystemCode ?? '-')}
            </Tag>
          )}
        </Space>
        {currentSystem ? <Tag color="geekblue">region：{currentSystem.region}</Tag> : null}
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
        <div style={{ padding: '16px 20px', fontSize: 12, color: '#8c8c8c' }}>暂无可用菜单</div>
      ) : null}
    </Sider>
  )

  // 「我的申请」等 alwaysVisible 菜单对登录用户始终可见；无任何菜单时才展示整区空态
  const hasNoMenu = menuItems.length === 0

  return (
    <Layout className="app-layout">
      {sider}
      <Layout>
        {header}
        <Content className="app-content">
          <Flex vertical gap={16}>
            {!currentSystem && systems.length === 0 ? (
              <Result
                status="info"
                title="当前账号暂无可管理系统"
                subTitle="系统访问权限由有效角色推导，请联系平台超管为你分配系统内角色。"
              />
            ) : hasNoMenu ? (
              <NoPermission
                title="暂无可用功能"
                description={`当前账号（${currentUser?.externalUserId ?? '-'}）在当前系统（${currentSystem?.name ?? currentSystemCode ?? '-'}）内没有任何可用菜单，请联系平台超管授权或切换其他系统。`}
              />
            ) : (
              <Outlet />
            )}
          </Flex>
        </Content>
      </Layout>
    </Layout>
  )
}
