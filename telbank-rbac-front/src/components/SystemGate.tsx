import { Alert, Card } from 'antd'
import { PageShell } from './PageShell'

interface SystemRequiredProps {
  title: string
  description?: string
}

/** 系统级页面当前系统上下文未就绪时的占位提示（避免空白页） */
export function SystemRequired({ title, description }: SystemRequiredProps) {
  return (
    <PageShell title={title} description={description}>
      <Card>
        <Alert
          type="info"
          showIcon
          title="当前系统上下文未就绪"
          description="页面数据按当前系统隔离；若当前账号没有由有效角色推导出的系统，请联系平台超管分配系统内角色。"
        />
      </Card>
    </PageShell>
  )
}
