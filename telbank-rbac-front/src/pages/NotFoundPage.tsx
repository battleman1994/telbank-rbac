import { Button, Result } from 'antd'
import { useNavigate } from 'react-router'
import { PageShell } from '../components/PageShell'

/** 404 页面：手动输入不存在的管理端路由时展示 */
export default function NotFoundPage() {
  const navigate = useNavigate()
  return (
    <PageShell title="页面不存在">
      <Result
        status="404"
        title="404"
        subTitle="您访问的管理端页面不存在，请从左侧菜单重新进入。"
        extra={
          <Button type="primary" onClick={() => navigate('/systems', { replace: true })}>
            返回系统管理
          </Button>
        }
      />
    </PageShell>
  )
}
