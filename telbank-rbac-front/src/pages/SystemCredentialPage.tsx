import { useEffect, useState } from 'react'
import { EyeInvisibleOutlined, EyeOutlined, KeyOutlined, ReloadOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Descriptions,
  Flex,
  Input,
  Space,
  Tag,
  Typography,
} from 'antd'
import { getCurrentSystemCredential } from '../api'
import { PageShell } from '../components/PageShell'
import { useCurrentSystem } from '../stores/app'

const { Text } = Typography

interface ToastLike {
  success: (content: string) => void
  error: (content: string) => void
}

async function copyText(text: string, toast: ToastLike): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    toast.success('已复制到剪贴板')
  } catch {
    toast.error('复制失败，请手动选择复制')
  }
}

/** AppSecret 默认掩码展示（保留首尾各 3 位，便于核对） */
function maskSecret(secret: string): string {
  if (!secret) return '-'
  if (secret.length <= 8) return '•'.repeat(8)
  return `${secret.slice(0, 3)}${'•'.repeat(8)}${secret.slice(-3)}`
}

/**
 * 接入凭据（A18）：展示当前业务系统的 AppKey / AppSecret。
 * AppSecret 默认隐藏，点击「显示」后展示完整值并可复制；页面提示凭据属敏感信息。
 * 权限码：system:credential（admin 角色由 `*` 通配通过；普通角色不可见）。
 */
export default function SystemCredentialPage() {
  const { message } = AntApp.useApp()
  const currentSystem = useCurrentSystem()
  const [secretVisible, setSecretVisible] = useState(false)

  const credentialQuery = useQuery({
    queryKey: ['system-credential', 'current', currentSystem?.code ?? null],
    queryFn: () => getCurrentSystemCredential(),
    enabled: Boolean(currentSystem),
    retry: 1,
  })

  // 切换系统后重新隐藏 AppSecret
  useEffect(() => {
    setSecretVisible(false)
  }, [currentSystem?.code])

  const credential = credentialQuery.data

  return (
    <PageShell
      title="接入凭据"
      description="查看当前业务系统接入权限中心开放接口所需的 AppKey / AppSecret；AppSecret 属敏感信息，默认隐藏。"
      extra={
        <Button
          icon={<ReloadOutlined />}
          onClick={() => void credentialQuery.refetch()}
          disabled={!currentSystem}
        >
          刷新
        </Button>
      }
    >
      {!currentSystem ? (
        <Card>
          <Alert
            type="info"
            showIcon
            title="当前系统上下文未就绪"
            description="接入凭据按当前系统（X-System-Code）展示，请确认当前账号存在可访问的业务系统。"
          />
        </Card>
      ) : (
        <Flex vertical gap={16}>
          {credentialQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="接入凭据加载失败"
              description={credentialQuery.error.message}
              action={
                <Button size="small" onClick={() => void credentialQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Card loading={credentialQuery.isLoading}>
            {credential ? (
              <Flex vertical gap={16}>
                <Alert
                  type="warning"
                  showIcon
                  icon={<KeyOutlined />}
                  title="凭据属于敏感信息，请妥善保管"
                  description="AppSecret 用于开放接口的 HMAC-SHA256 签名，请勿泄露或在不可信环境展示；如疑似泄露请联系平台超管重置密钥。"
                />

                <Descriptions
                  bordered
                  column={2}
                  size="middle"
                  items={[
                    {
                      key: 'systemName',
                      label: '系统名称',
                      children: credential.systemName,
                    },
                    {
                      key: 'systemCode',
                      label: '系统编码',
                      children: <Tag color="blue">{credential.systemCode}</Tag>,
                    },
                    {
                      key: 'region',
                      label: '所属区域',
                      children: <Tag>{credential.region}</Tag>,
                    },
                    { key: 'systemId', label: '系统 ID', children: credential.systemId },
                  ]}
                />

                <div>
                  <Text strong>AppKey</Text>
                  <Flex gap={8} align="center" style={{ marginTop: 8 }}>
                    <Input readOnly value={credential.appKey} className="mono-text" />
                    <Button onClick={() => void copyText(credential.appKey, message)}>
                      复制
                    </Button>
                  </Flex>
                </div>

                <div>
                  <Text strong>AppSecret</Text>
                  <Flex gap={8} align="center" style={{ marginTop: 8 }} wrap>
                    <Input
                      readOnly
                      className="mono-text"
                      value={secretVisible ? credential.appSecret : maskSecret(credential.appSecret)}
                    />
                    <Button
                      icon={secretVisible ? <EyeInvisibleOutlined /> : <EyeOutlined />}
                      onClick={() => setSecretVisible((visible) => !visible)}
                    >
                      {secretVisible ? '隐藏' : '显示'}
                    </Button>
                    <Button
                      disabled={!secretVisible}
                      onClick={() => {
                        if (secretVisible) void copyText(credential.appSecret, message)
                      }}
                    >
                      复制
                    </Button>
                  </Flex>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    默认隐藏完整 AppSecret；点击「显示」后可查看并复制完整值。
                  </Text>
                </div>

                <Space size={8} wrap>
                  <Button
                    onClick={() =>
                      void copyText(
                        `appKey=${credential.appKey}\nappSecret=${credential.appSecret}`,
                        message,
                      )
                    }
                  >
                    复制 AppKey + AppSecret
                  </Button>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    提示：复制完整凭据前请确认当前环境安全。
                  </Text>
                </Space>
              </Flex>
            ) : !credentialQuery.isLoading && !credentialQuery.isError ? (
              <Alert
                type="info"
                showIcon
                title="暂未获取到凭据"
                description="当前系统可能尚未分配 AppKey/AppSecret，请联系平台超管。"
              />
            ) : null}
          </Card>
        </Flex>
      )}
    </PageShell>
  )
}
