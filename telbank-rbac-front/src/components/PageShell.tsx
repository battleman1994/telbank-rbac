import type { ReactNode } from 'react'
import { Flex, Typography } from 'antd'

const { Title, Text } = Typography

interface PageShellProps {
  title: string
  description?: string
  extra?: ReactNode
  children: ReactNode
}

/** 页面统一头部：标题 + 说明 + 右侧操作区，内容垂直排布 */
export function PageShell({ title, description, extra, children }: PageShellProps) {
  return (
    <Flex vertical gap={16}>
      <Flex align="flex-start" justify="space-between" gap={16} wrap>
        <div>
          <Title level={4} style={{ margin: 0 }}>
            {title}
          </Title>
          {description ? (
            <Text type="secondary" style={{ fontSize: 13 }}>
              {description}
            </Text>
          ) : null}
        </div>
        {extra ? <div>{extra}</div> : null}
      </Flex>
      {children}
    </Flex>
  )
}
