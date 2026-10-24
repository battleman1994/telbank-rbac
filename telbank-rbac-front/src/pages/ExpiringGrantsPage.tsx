import { useState } from 'react'
import { ClockCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Flex,
  InputNumber,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { listExpiringGrants } from '../api'
import type { ApprovalGrantVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { ApprovalKindTag, StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { daysUntil, formatDateTime, formatValidity } from '../utils/format'

const { Text } = Typography

/** 即将到期授权（契约 §3.10）：valid_until 在 N 天内且状态启用的授权列表 */
export default function ExpiringGrantsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="即将到期授权"
        description="即将到期授权按系统查询，需先在顶部选择业务系统。"
      />
    )
  }
  return <ExpiringGrantsContent systemId={systemId} />
}

function ExpiringGrantsContent({ systemId }: { systemId: number }) {
  const [withinDays, setWithinDays] = useState(7)
  const [detail, setDetail] = useState<ApprovalGrantVO | null>(null)

  const grantsQuery = useQuery({
    queryKey: ['expiring-grants', systemId, withinDays],
    queryFn: () => listExpiringGrants({ systemId, withinDays }),
  })

  const columns: TableProps<ApprovalGrantVO>['columns'] = [
    {
      title: '类型',
      dataIndex: 'kind',
      width: 130,
      render: (value: ApprovalGrantVO['kind']) => <ApprovalKindTag kind={value} />,
    },
    {
      title: '审批单号',
      dataIndex: 'sourceApprovalId',
      width: 200,
      render: (value: string | null) => (value ? <Tag color="blue">{value}</Tag> : '-'),
    },
    {
      title: '用户 / 角色 / 权限',
      key: 'subject',
      width: 260,
      render: (_, record) => (
        <Space orientation="vertical" size={0}>
          {record.externalUserId ? <Text>用户：{record.externalUserId}</Text> : null}
          {record.roleId != null ? <Text type="secondary">roleId：{record.roleId}</Text> : null}
          {record.permissionId != null ? <Text type="secondary">permissionId：{record.permissionId}</Text> : null}
        </Space>
      ),
    },
    {
      title: '授权时效',
      key: 'validity',
      width: 300,
      render: (_, record) => formatValidity(record.validFrom, record.validUntil),
    },
    {
      title: '剩余天数',
      key: 'remaining',
      width: 120,
      sorter: (a, b) => String(a.validUntil ?? '').localeCompare(String(b.validUntil ?? '')),
      render: (_, record) => {
        const remaining = daysUntil(record.validUntil)
        if (remaining === null) return <Tag>永久</Tag>
        const color = remaining <= 1 ? 'red' : remaining <= 3 ? 'orange' : 'gold'
        return <Tag color={color}>{remaining} 天</Tag>
      },
    },
    {
      title: '失效时间',
      dataIndex: 'validUntil',
      width: 190,
      render: (value: string | null) => formatDateTime(value, '永久有效'),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: number) => <StatusTag status={value} />,
    },
    {
      title: '授权原因',
      dataIndex: 'grantReason',
      ellipsis: true,
      render: (value: string | null) => value || '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      fixed: 'right',
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => setDetail(record)}>
          详情
        </Button>
      ),
    },
  ]

  return (
    <PageShell
      title="即将到期授权"
      description="按失效时间升序展示 N 天内即将到期的授权；到期后权限查询即视为无权限，并由定时任务标记 status=0。"
      extra={
        <Space>
          <Text type="secondary">到期窗口</Text>
          <InputNumber
            min={1}
            max={90}
            value={withinDays}
            onChange={(value) => setWithinDays(value ?? 7)}
            suffix="天"
            style={{ width: 130 }}
          />
          <Button icon={<ReloadOutlined />} onClick={() => void grantsQuery.refetch()}>
            刷新
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Alert
            type="info"
            showIcon
            icon={<ClockCircleOutlined />}
            title={`当前查询 ${withinDays} 天内到期的授权（valid_until BETWEEN NOW() AND NOW()+${withinDays}天，且 status=1）`}
          />

          {grantsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="即将到期授权加载失败"
              description={grantsQuery.error.message}
              action={
                <Button size="small" onClick={() => void grantsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Table<ApprovalGrantVO>
            rowKey={(record) =>
              [
                record.kind,
                record.systemId,
                record.externalUserId ?? '',
                record.roleId ?? '',
                record.permissionId ?? '',
                record.sourceApprovalId ?? '',
              ].join('-')
            }
            columns={columns}
            dataSource={grantsQuery.data ?? []}
            loading={grantsQuery.isLoading || grantsQuery.isFetching}
            scroll={{ x: 1200 }}
            pagination={false}
            locale={{ emptyText: `${withinDays} 天内暂无即将到期的授权` }}
          />
        </Flex>
      </Card>

      <Modal
        title="即将到期授权详情"
        open={detail !== null}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>关闭</Button>}
        width={640}
      >
        {detail ? (
          <Descriptions
            column={1}
            bordered
            size="small"
            items={[
              { key: 'kind', label: '授权类型', children: <ApprovalKindTag kind={detail.kind} /> },
              { key: 'systemId', label: '系统 ID', children: detail.systemId },
              { key: 'sourceApprovalId', label: '审批单号', children: detail.sourceApprovalId ?? '-' },
              { key: 'externalUserId', label: '用户', children: detail.externalUserId ?? '-' },
              { key: 'roleId', label: '角色 ID', children: detail.roleId ?? '-' },
              { key: 'permissionId', label: '权限 ID', children: detail.permissionId ?? '-' },
              { key: 'validFrom', label: '生效时间', children: formatDateTime(detail.validFrom, '立即生效') },
              { key: 'validUntil', label: '失效时间', children: formatDateTime(detail.validUntil, '永久有效') },
              {
                key: 'remaining',
                label: '剩余天数',
                children: daysUntil(detail.validUntil) ?? '永久',
              },
              { key: 'status', label: '状态', children: <StatusTag status={detail.status} /> },
              { key: 'grantReason', label: '授权原因', children: detail.grantReason ?? '-' },
              { key: 'grantedAt', label: '授权时间', children: formatDateTime(detail.grantedAt) },
            ]}
          />
        ) : null}
      </Modal>
    </PageShell>
  )
}
