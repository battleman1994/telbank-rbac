import { useState } from 'react'
import { LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { Alert, App as AntApp, Button, Card, Form, Input, Space, Typography } from 'antd'
import { Navigate, useNavigate } from 'react-router'
import { getMe, getMySystems } from '../api'
import { useAppStore } from '../stores/app'

const { Paragraph, Text, Title } = Typography

interface LoginFormValues {
  token: string
}

/**
 * 登录页（契约 §10）：权限中心不签发 JWT，管理员粘贴外部认证系统签发的 JWT。
 * 校验通过后保存 localStorage["rbac.token"]，GET /api/me + /api/me/systems，
 * 选中默认系统后进入管理台。
 */
export default function LoginPage() {
  const token = useAppStore((state) => state.token)
  const setSession = useAppStore((state) => state.setSession)
  const setCurrentUser = useAppStore((state) => state.setCurrentUser)
  const setSystems = useAppStore((state) => state.setSystems)
  const setCurrentSystemCode = useAppStore((state) => state.setCurrentSystemCode)

  const navigate = useNavigate()
  const { message } = AntApp.useApp()
  const [form] = Form.useForm<LoginFormValues>()
  const [loading, setLoading] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  if (token) {
    return <Navigate to="/systems" replace />
  }

  async function handleFinish(values: LoginFormValues) {
    const inputToken = values.token.trim()
    if (!inputToken) return
    setLoading(true)
    setErrorMessage(null)
    try {
      const me = await getMe(inputToken)
      const systems = await getMySystems(inputToken)
      setSession(inputToken)
      setCurrentUser(me)
      setSystems(systems)
      const defaultSystem = systems.find((system) => system.isDefault) ?? systems[0]
      setCurrentSystemCode(defaultSystem?.code ?? null)
      void message.success(`登录成功，欢迎 ${me.externalUserId}`)
      void navigate('/systems', { replace: true })
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
            <Text code>scripts/gen-demo-jwt.sh</Text>生成 HS256 演示 token；生产环境使用
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
