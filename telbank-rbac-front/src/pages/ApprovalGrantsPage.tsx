import { useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Flex,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { listApprovalGrants } from '../api'
import type { ApprovalGrantVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { ApprovalKindTag, StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { formatDateTime } from '../utils/format'
import { createPaginationConfig } from '../utils/pagination'

const { Text } = Typography

/** 审批授权记录（契约 §3.10）：合并用户-角色与角色-权限中带审批来源的授权记录 */
export default function ApprovalGrantsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="授权记录"
        description="当前系统上下文未就绪，无法加载授权记录。"
      />
    )
  }
  return <ApprovalGrantsContent systemId={systemId} />
}

function ApprovalGrantsContent({ systemId }: { systemId: number }) {
  const [externalUserIdInput, setExternalUserIdInput] = useState('')
  const [externalUserId, setExternalUserId] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [detail, setDetail] = useState<ApprovalGrantVO | null>(null)

  const grantsQuery = useQuery({
    queryKey: ['approval-grants', systemId, { externalUserId, page, size }],
    queryFn: () =>
      listApprovalGrants({
        systemId,
        externalUserId: externalUserId || undefined,
        page,
        size,
      }),
  })

  // 表格只展示日期，避免完整时间戳在窄列中换行；详情抽屉仍展示完整时间
  const shortDate = (value?: string | null) => (value ? formatDateTime(value).slice(0, 10) : null)
  const shortValidity = (validFrom?: string | null, validUntil?: string | null) =>
    `${shortDate(validFrom) ?? '立即生效'} ~ ${shortDate(validUntil) ?? '永久有效'}`

  const columns: TableProps<ApprovalGrantVO>['columns'] = [
    {
      title: '类型',
      dataIndex: 'kind',
      width: 100,
      render: (value: ApprovalGrantVO['kind']) => <ApprovalKindTag kind={value} />,
    },
    {
      title: '审批单号',
      dataIndex: 'sourceApprovalId',
      width: 160,
      render: (value: string | null) => (value ? <Tag color="blue">{value}</Tag> : '-'),
    },
    {
      title: '用户 / 角色 / 权限',
      key: 'subject',
      width: 180,
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
      width: 190,
      render: (_, record) => shortValidity(record.validFrom, record.validUntil),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 70,
      render: (value: number) => <StatusTag status={value} />,
    },
    {
      title: '授权原因',
      dataIndex: 'grantReason',
      width: 110,
      ellipsis: true,
      render: (value: string | null) => value || '-',
    },
    {
      title: '授权时间',
      dataIndex: 'grantedAt',
      width: 140,
      render: (value: string | null) => {
        if (!value) return '-'
        const full = formatDateTime(value)
        return (
          <span title={full} className="mono-text">
            {full.length > 16 ? full.slice(0, 16) : full}
          </span>
        )
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 80,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => setDetail(record)}>
          详情
        </Button>
      ),
    },
  ]

  return (
    <PageShell
      title="授权记录"
      description="展示当前系统内由审批系统回调（POST /openapi/approval/grant）产生的授权记录，数据来源于 sys_user_role 与 sys_role_permission 中 source_approval_id 非空的记录。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void grantsQuery.refetch()}>
            刷新
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              placeholder="按 external_user_id 过滤（选填）"
              style={{ width: 320 }}
              value={externalUserIdInput}
              onChange={(event) => setExternalUserIdInput(event.target.value)}
              onPressEnter={() => {
                setExternalUserId(externalUserIdInput.trim())
                setPage(1)
              }}
            />
            <Button
              type="primary"
              onClick={() => {
                setExternalUserId(externalUserIdInput.trim())
                setPage(1)
              }}
            >
              查询
            </Button>
            <Button
              onClick={() => {
                setExternalUserIdInput('')
                setExternalUserId('')
                setPage(1)
              }}
            >
              重置
            </Button>
          </Space>

          {grantsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="授权记录加载失败"
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
                record.grantedAt ?? '',
              ].join('-')
            }
            columns={columns}
            dataSource={grantsQuery.data?.items ?? []}
            loading={grantsQuery.isLoading || grantsQuery.isFetching}
            pagination={createPaginationConfig(page, size, grantsQuery.data?.total ?? 0, (nextPage, nextSize) => {
              setPage(nextPage)
              setSize(nextSize)
            })}
          />
        </Flex>
      </Card>

      <Modal
        title="审批授权详情"
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
