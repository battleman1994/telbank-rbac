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
import { Outlet, useLocation, useNavigate } from 'react-router'
import { getMe, getMySystems } from '../api'
import { useAppStore } from '../stores/app'

const { Header, Sider, Content } = Layout
const { Text } = Typography

const menuItems: MenuProps['items'] = [
  {
    key: '/systems',
    icon: <BankOutlined />,
    label: '系统管理',
  },
  {
    type: 'group',
    label: '权限配置',
    children: [
      { key: '/roles', icon: <TeamOutlined />, label: '角色管理' },
      { key: '/permissions', icon: <ApartmentOutlined />, label: '权限资源管理' },
      { key: '/role-permissions', icon: <SafetyCertificateOutlined />, label: '角色-权限授权' },
      { key: '/user-roles', icon: <UserSwitchOutlined />, label: '用户-角色分配' },
      { key: '/user-systems', icon: <AppstoreOutlined />, label: '用户-系统开通' },
    ],
  },
  {
    type: 'group',
    label: '扩展与审计',
    children: [
      { key: '/page-actions', icon: <SettingOutlined />, label: '页面操作元数据' },
      { key: '/sdk-endpoints', icon: <ApiOutlined />, label: 'SDK 端点上报' },
      { key: '/approval-grants', icon: <AuditOutlined />, label: '审批授权记录' },
      { key: '/expiring-grants', icon: <ClockCircleOutlined />, label: '即将到期授权' },
    ],
  },
]

/**
 * 受保护布局：登录态校验 + 当前用户/可访问系统引导 + 系统切换器 + 侧边栏菜单。
 * 切换系统写入 localStorage["rbac.systemCode"] 并让所有查询失效重取（契约 §10）。
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
  const setCurrentSystemCode = useAppStore((state) => state.setCurrentSystemCode)
  const logout = useAppStore((state) => state.logout)

  const navigate = useNavigate()
  const location = useLocation()
  const queryClient = useQueryClient()
  const { message } = AntApp.useApp()

  const meQuery = useQuery({
    queryKey: ['me'],
    queryFn: () => getMe(),
    enabled: Boolean(token),
    retry: false,
    staleTime: 5 * 60 * 1000,
  })

  const systemsQuery = useQuery({
    queryKey: ['me', 'systems'],
    queryFn: () => getMySystems(),
    enabled: Boolean(token),
    retry: 1,
    staleTime: 5 * 60 * 1000,
  })

  useEffect(() => {
    if (!token) {
      void navigate('/login', { replace: true })
    }
  }, [token, navigate])

  useEffect(() => {
    if (meQuery.data) setCurrentUser(meQuery.data)
  }, [meQuery.data, setCurrentUser])

  useEffect(() => {
    if (!systemsQuery.data) return
    setSystems(systemsQuery.data)
    const storedCode = useAppStore.getState().currentSystemCode
    const hasValidStoredCode =
      Boolean(storedCode) && systemsQuery.data.some((system) => system.code === storedCode)
    if (systemsQuery.data.length > 0 && !hasValidStoredCode) {
      const defaultSystem =
        systemsQuery.data.find((system) => system.isDefault) ?? systemsQuery.data[0]
      setCurrentSystemCode(defaultSystem.code)
    }
  }, [systemsQuery.data, setSystems, setCurrentSystemCode])

  if (!token) return null

  if (meQuery.isLoading || systemsQuery.isLoading) {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh' }}>
        <Spin size="large" description="正在加载登录信息…">
          <div style={{ width: 240, height: 120 }} />
        </Spin>
      </Flex>
    )
  }

  if (meQuery.isError || systemsQuery.isError) {
    return (
      <Flex align="center" justify="center" style={{ minHeight: '100vh' }}>
        <Result
          status="warning"
          title="加载登录信息失败"
          subTitle="请确认权限中心后端已启动、JWT 未过期，然后重试或重新登录。"
          extra={[
            <Button
              key="retry"
              type="primary"
              onClick={() => {
                void meQuery.refetch()
                void systemsQuery.refetch()
              }}
            >
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
    void queryClient.invalidateQueries()
    const system = systems.find((item) => item.code === code)
    void message.success(`已切换到系统：${system?.name ?? code}`)
  }

  const currentSystem = systems.find((system) => system.code === currentSystemCode)

  return (
    <Layout className="app-layout">
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
      </Sider>
      <Layout>
        <Header className="app-header">
          <Space size={16} wrap>
            <Space size={8}>
              <Text type="secondary">当前系统</Text>
              <Select
                value={currentSystemCode ?? undefined}
                placeholder={systems.length > 0 ? '请选择业务系统' : '暂无可访问系统'}
                style={{ width: 260 }}
                options={systems.map((system) => ({
                  value: system.code,
                  label: `${system.name}（${system.code}）`,
                }))}
                onChange={handleSystemChange}
                disabled={systems.length === 0}
                showSearch
                optionFilterProp="label"
              />
            </Space>
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
        <Content className="app-content">
          <Flex vertical gap={16}>
            {systems.length === 0 ? (
              <Alert
                type="warning"
                showIcon
                title="当前账号未开通任何业务系统"
                description="系统级页面需要先在「用户-系统开通」中为账号开通业务系统，或由平台超管直接操作。"
              />
            ) : null}
            <Outlet />
          </Flex>
        </Content>
      </Layout>
    </Layout>
  )
}
