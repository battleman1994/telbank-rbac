import { Alert, Card } from 'antd'
import { PageShell } from './PageShell'

interface SystemRequiredProps {
  title: string
  description?: string
}

/** 系统级页面未选择业务系统时的占位提示（避免空白页） */
export function SystemRequired({ title, description }: SystemRequiredProps) {
  return (
    <PageShell title={title} description={description}>
      <Card>
        <Alert
          type="info"
          showIcon
          title="请先在顶部选择业务系统"
          description="当前页面的数据按系统隔离，选择业务系统后会自动加载；若下拉框为空，说明当前账号尚未开通任何业务系统。"
        />
      </Card>
    </PageShell>
  )
}
