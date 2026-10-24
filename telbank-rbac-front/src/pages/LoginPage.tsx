import { useState } from 'react'
import { LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, App as AntApp, Button, Card, Form, Input, Space, Typography } from 'antd'
import { Navigate, useNavigate } from 'react-router'
import { loadAdminSession } from '../api'
import { useAppStore } from '../stores/app'

const { Paragraph, Text, Title } = Typography

interface LoginFormValues {
  token: string
}

/**
 * 登录页（契约 §10）：权限中心不签发 JWT，管理员粘贴外部认证系统签发的 JWT。
 * 校验通过后保存 localStorage["rbac.token"]，并按
 * /api/me → /api/me/systems → /api/me/bootstrap 顺序加载管理上下文
 * （管理权限码、管理上下文系统编码、可访问系统），随后进入管理台。
 */
export default function LoginPage() {
  const token = useAppStore((state) => state.token)
  const setSession = useAppStore((state) => state.setSession)
  const setCurrentUser = useAppStore((state) => state.setCurrentUser)
  const setSystems = useAppStore((state) => state.setSystems)
  const setCurrentSystemCode = useAppStore((state) => state.setCurrentSystemCode)
  const setBootstrap = useAppStore((state) => state.setBootstrap)

  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<LoginFormValues>()
  const [loading, setLoading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  if (token) {
    return <Navigate to="/" replace />
  }

  async function handleFinish(values: LoginFormValues) {
    const inputToken = values.token.trim()
    if (!inputToken) return
    setLoading(true)
    setErrorMessage(null)
    try {
      const { me, systems, bootstrap } = await loadAdminSession(inputToken)
      setSession(inputToken)
      setCurrentUser(me)
      const allSystems = systems.length > 0 ? systems : bootstrap.systems
      setSystems(allSystems)
      setBootstrap(bootstrap)
      // 业务系统选择排除管理上下文系统（默认 rbac），仅作为数据筛选条件
      const businessSystems = allSystems.filter(
        (system) => system.code !== bootstrap.managementSystemCode,
      )
      const defaultSystem =
        businessSystems.find((system) => system.isDefault) ?? businessSystems[0]
      setCurrentSystemCode(defaultSystem?.code ?? null)
      void message.success(`登录成功，欢迎 ${me.externalUserId}`)
      // 首页重定向到第一个有权限的菜单（由 HomeRedirect 计算）
      void navigate('/', { replace: true })
    } catch (error) {
      setErrorMessage(error instanceof Error ? error.message : 'JWT 校验失败，请检查后重试')
      // 失败时保持未登录状态，避免脏 token 写入 localStorage
      setCurrentUser(null)
      setSystems([])
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <Card className="login-card">
        <div className="login-brand">
          <SafetyCertificateOutlined className="login-brand-icon" />
          <div>
            <Title level={3} style={{ margin: 0 }}>
              telbank 权限中心
            </Title>
            <Text type="secondary">多系统 RBAC 权限管理平台 · 管理后台</Text>
          </div>
        </div>
        <Paragraph type="secondary" style={{ marginTop: 16 }}>
          权限中心不签发 JWT（认证外部化）。请粘贴外部认证系统签发的 JWT，系统仅校验签名与有效期。
          登录后按管理权限码（rbac:*）加载可访问菜单。
        </Paragraph>
        {errorMessage ? (
          <Alert
            type="error"
            showIcon
            title="登录失败"
            description={errorMessage}
            style={{ marginBottom: 16 }}
          />
        ) : null}
        <Form<LoginFormValues> form={form} layout="vertical" onFinish={handleFinish}>
          <Form.Item
            label="JWT Token"
            name="token"
            rules={[{ required: true, message: '请粘贴 JWT Token' }]}
          >
            <Input.TextArea
              rows={4}
              placeholder="粘贴形如 eyJhbGciOiJIUzI1NiJ9.xxx.yyy 的 JWT"
              autoComplete="off"
              spellCheck={false}
              style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace' }}
            />
          </Form.Item>
          <div className="login-tip">
            本地联调：后端以 <Text code>demo</Text> profile 启动时，可运行
            <Text code>scripts/gen-demo-jwt.sh</Text>生成 HS256 演示 token（默认账号
            <Text code>13800138000</Text> 已具备 rbac 管理权限码）；生产环境使用
            <Text code>jwk</Text> 模式校验外部认证系统签发的 JWT。
          </div>
          <Form.Item style={{ marginBottom: 0 }}>
            <Button
              type="primary"
              htmlType="submit"
              block
              size="large"
              loading={loading}
              icon={<LockOutlined />}
            >
              验证并进入管理台
            </Button>
          </Form.Item>
        </Form>
        <Space orientation="vertical" size={2} className="login-footer" style={{ width: '100%' }}>
          <Text type="secondary" style={{ fontSize: 12 }}>
            登录态保存在浏览器 localStorage，退出登录后需重新粘贴 JWT。
          </Text>
        </Space>
      </Card>
    </div>
  )
}
