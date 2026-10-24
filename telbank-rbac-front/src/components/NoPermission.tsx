import { Button, Result, Space } from 'antd'
import { useNavigate } from 'react-router'
import { getFirstPermittedPath } from '../config/menu'
import { useHasPerm } from '../stores/app'
import { PageShell } from './PageShell'

interface NoPermissionProps {
  /** 缺少的权限码；不传表示当前账号没有任何管理权限 */
  permission?: string
  title?: string
  description?: string
  /** 是否包裹 PageShell（路由级 403 用 true；布局内的空态可传 false） */
  withShell?: boolean
}

/**
 * 无权限提示（403）：不白屏，并提供「前往第一个有权限的菜单」与刷新入口。
 * 路由守卫与「无任何管理权限」布局空态共用。
 */
export function NoPermission({
  permission,
  title = '无访问权限',
  description,
  withShell = true,
}: NoPermissionProps) {
  const navigate = useNavigate()
  const hasPerm = useHasPerm()
  const firstPermittedPath = getFirstPermittedPath(hasPerm)

  const subTitle =
    description ??
    (permission
      ? `当前账号缺少管理权限「${permission}」，请联系平台超管授权后重试。`
      : '当前账号没有任何权限中心管理权限（rbac:*），请联系平台超管授权后重试。')

  const result = (
    <Result
      status="403"
      title={title}
      subTitle={subTitle}
      extra={
        <Space wrap>
          {firstPermittedPath ? (
            <Button type="primary" onClick={() => void navigate(firstPermittedPath, { replace: true })}>
              前往可用页面
            </Button>
          ) : null}
          <Button onClick={() => window.location.reload()}>刷新页面</Button>
        </Space>
      }
    />
  )

  if (!withShell) return result
  return <PageShell title="访问受限">{result}</PageShell>
}
