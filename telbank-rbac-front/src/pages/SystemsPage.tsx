import { useState } from 'react'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Badge,
  Button,
  Card,
  Col,
  Descriptions,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd'
import type { TableProps, TabsProps } from 'antd'
import {
  approveSystemApplication,
  createSystem,
  disableSystem,
  listSystemApplications,
  listSystems,
  rejectSystemApplication,
  updateSystem,
} from '../api'
import type {
  SysSystemVO,
  SystemApplicationApprovePayload,
  SystemApplicationRejectPayload,
  SystemApplicationStatus,
  SystemApplicationVO,
  SystemApproveResultVO,
  SystemCreatePayload,
  SystemCreateResultVO,
  SystemUpdatePayload,
} from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { formatDateTime } from '../utils/format'
import { STATUS_OPTIONS } from '../utils/labels'
import { createPaginationConfig } from '../utils/pagination'

const { Text } = Typography

/* ------------------------------------------------------------------ */
/* 类型与常量                                                           */
/* ------------------------------------------------------------------ */

interface SystemFormValues {
  code: string
  name: string
  region: string
  /** 系统管理员账号（必填）：后端接入时自动创建该系统默认 admin 角色并授权 */
  adminExternalUserId: string
  /** 管理员姓名（可选） */
  adminName?: string
  status: number
}

interface ApproveFormValues {
  adminExternalUserId: string
  adminName?: string
}

interface RejectFormValues {
  reason: string
}

type ApplicationStatusFilter = SystemApplicationStatus | 'ALL'

const APPLICATION_FILTER_OPTIONS = [
  { value: 'PENDING', label: '待审核' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'ALL', label: '全部' },
]

const APPLICATION_STATUS_META: Record<SystemApplicationStatus, { color: string; label: string }> = {
  PENDING: { color: 'gold', label: '待审核' },
  APPROVED: { color: 'green', label: '已通过' },
  REJECTED: { color: 'red', label: '已驳回' },
}

function ApplicationStatusTag({ status }: { status: SystemApplicationStatus }) {
  const meta = APPLICATION_STATUS_META[status] ?? { color: 'default', label: status }
  if (status === 'APPROVED') {
    return <Tag color={meta.color}>已开通</Tag>
  }
  return <Tag color={meta.color}>{meta.label}</Tag>
}

/* ------------------------------------------------------------------ */
/* AppKey / AppSecret 展示（完整 AppSecret 仅创建/审核通过时返回一次）   */
/* ------------------------------------------------------------------ */

interface SecretResult {
  title: string
  systemCode: string
  systemName?: string
  appKey: string
  appSecret: string | null
}

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

function asRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null ? (value as Record<string, unknown>) : null
}

function readString(record: Record<string, unknown> | null, key: string): string | null {
  const value = record?.[key]
  return typeof value === 'string' && value.trim() ? value : null
}

/** 从 POST /api/systems 响应中提取一次性的 AppKey/AppSecret（兼容 system 包裹结构） */
function extractCreatedSecret(
  result: SystemCreateResultVO | SysSystemVO | null,
  fallbackCode: string,
  fallbackName: string,
): SecretResult {
  const record = asRecord(result)
  const systemRecord = asRecord(record?.system) ?? record
  return {
    title: '系统接入成功',
    systemCode: readString(systemRecord, 'code') ?? fallbackCode,
    systemName: readString(systemRecord, 'name') ?? fallbackName,
    appKey:
      readString(systemRecord, 'appKey') ??
      readString(record, 'appKey') ??
      '（响应未返回 AppKey，请查看业务系统列表）',
    appSecret: readString(record, 'appSecret') ?? readString(systemRecord, 'appSecret'),
  }
}

/** 从审核通过响应中提取新系统 AppKey/AppSecret（兼容 system 包裹结构） */
function extractApprovedSecret(result: SystemApproveResultVO): SecretResult {
  const record = asRecord(result)
  const systemRecord = asRecord(record?.system)
  return {
    title: '审核通过，业务系统已开通',
    systemCode: result.systemCode,
    systemName: result.name,
    appKey:
      readString(record, 'appKey') ??
      readString(systemRecord, 'appKey') ??
      '（响应未返回 AppKey，请查看业务系统列表）',
    appSecret: readString(record, 'appSecret') ?? readString(systemRecord, 'appSecret'),
  }
}

/** 创建/审核成功后的密钥结果弹窗：完整 AppSecret 只展示这一次 */
function SecretResultModal({ result, onClose }: { result: SecretResult | null; onClose: () => void }) {
  const { message } = AntApp.useApp()
  return (
    <Modal
      title={result?.title ?? '系统接入成功'}
      open={result !== null}
      forceRender
      onCancel={onClose}
      footer={
        <Button type="primary" onClick={onClose}>
          我已知晓
        </Button>
      }
      width={640}
    >
      {result ? (
        <Flex vertical gap={16}>
          <Descriptions
            size="small"
            bordered
            column={2}
            items={[
              {
                key: 'code',
                label: '系统编码',
                children: <Tag color="blue">{result.systemCode}</Tag>,
              },
              { key: 'name', label: '系统名称', children: result.systemName || '-' },
            ]}
          />
          <Alert
            type="warning"
            showIcon
            title="完整密钥仅显示一次"
            description="请立即复制并妥善保存 AppKey/AppSecret；关闭弹窗后（或刷新页面后）只展示脱敏密钥。"
          />
          <div>
            <Text strong>AppKey</Text>
            <Flex gap={8} align="center" style={{ marginTop: 8 }}>
              <Input readOnly value={result.appKey} className="mono-text" />
              <Button onClick={() => void copyText(result.appKey, message)}>复制</Button>
            </Flex>
          </div>
          <div>
            <Text strong>AppSecret</Text>
            <Flex gap={8} align="center" style={{ marginTop: 8 }}>
              <Input
                readOnly
                value={result.appSecret ?? '（响应未返回完整密钥，请稍后重置密钥）'}
                className="mono-text"
              />
              <Button
                disabled={!result.appSecret}
                onClick={() => {
                  if (result.appSecret) void copyText(result.appSecret, message)
                }}
              >
                复制
              </Button>
            </Flex>
          </div>
        </Flex>
      ) : null}
    </Modal>
  )
}

/* ------------------------------------------------------------------ */
/* 系统接入页（平台超管专属）：业务系统 + 接入申请 两个 Tab             */
/* ------------------------------------------------------------------ */

/**
 * 系统接入（平台超管专属）：
 * - 业务系统：手动录入/维护接入系统；
 * - 接入申请：SDK 自动上报的系统接入申请审核（通过即开通、驳回需填原因）。
 */
export default function SystemsPage() {
  const [activeTab, setActiveTab] = useState('systems')
  const [secretResult, setSecretResult] = useState<SecretResult | null>(null)

  // 待审核数量 Badge：与接入申请列表共用 ['system-applications'] 缓存前缀，审核/驳回后自动刷新
  const pendingQuery = useQuery({
    queryKey: ['system-applications', 'PENDING', 1, 1],
    queryFn: () => listSystemApplications({ status: 'PENDING', page: 1, size: 1 }),
    staleTime: 30_000,
  })
  const pendingCount = pendingQuery.data?.total ?? 0

  const tabItems: TabsProps['items'] = [
    {
      key: 'systems',
      label: '业务系统',
      children: <BusinessSystemsTab onSystemCreated={setSecretResult} />,
    },
    {
      key: 'applications',
      label: (
        <Space size={6}>
          <span>接入申请</span>
          <Badge count={pendingCount} size="small" overflowCount={99} />
        </Space>
      ),
      children: <SystemApplicationsTab onSystemApproved={setSecretResult} />,
    },
  ]

  return (
    <PageShell
      title="系统接入"
      description="平台超管专属：维护业务系统接入信息；支持 SDK 自动上报的系统接入申请审核（通过即开通），也可手动录入。"
    >
      <Card>
        <Tabs activeKey={activeTab} onChange={(key) => setActiveTab(key)} items={tabItems} />
      </Card>
      <SecretResultModal result={secretResult} onClose={() => setSecretResult(null)} />
    </PageShell>
  )
}

/* ------------------------------------------------------------------ */
/* Tab1：业务系统（原有列表 + 手动新建/编辑/停用）                      */
/* ------------------------------------------------------------------ */

function BusinessSystemsTab({
  onSystemCreated,
}: {
  onSystemCreated: (result: SecretResult) => void
}) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<SysSystemVO | null>(null)
  const [viewingKeys, setViewingKeys] = useState<SysSystemVO | null>(null)
  const [form] = Form.useForm<SystemFormValues>()

  const systemsQuery = useQuery({
    queryKey: ['systems', { keyword, page, size }],
    queryFn: () => listSystems({ keyword: keyword || undefined, page, size }),
  })

  const saveMutation = useMutation<SystemCreateResultVO | SysSystemVO, Error, SystemFormValues>({
    mutationFn: (values: SystemFormValues) => {
      if (editing) {
        const payload: SystemUpdatePayload = {
          name: values.name.trim(),
          region: values.region.trim(),
          adminExternalUserId: values.adminExternalUserId.trim(),
          adminName: values.adminName?.trim() || undefined,
          status: values.status,
        }
        return updateSystem(editing.id, payload)
      }
      const payload: SystemCreatePayload = {
        code: values.code.trim(),
        name: values.name.trim(),
        region: values.region.trim(),
        adminExternalUserId: values.adminExternalUserId.trim(),
        adminName: values.adminName?.trim() || undefined,
        status: values.status,
      }
      return createSystem(payload)
    },
    onSuccess: (result, values) => {
      setModalOpen(false)
      if (editing) {
        void message.success('系统接入信息已更新')
      } else {
        void message.success('系统接入已创建，请保存本次返回的 AppKey/AppSecret')
        // 新建成功后弹结果弹窗，展示一次性的完整 AppSecret
        onSystemCreated(
          extractCreatedSecret(result, values.code.trim(), values.name.trim()),
        )
      }
      void queryClient.invalidateQueries({ queryKey: ['systems'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-session'] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disableSystem(id),
    onSuccess: () => {
      void message.success('系统已停用')
      void queryClient.invalidateQueries({ queryKey: ['systems'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-session'] })
    },
  })

  function openCreateModal() {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ status: 1 })
    setModalOpen(true)
  }

  function openEditModal(record: SysSystemVO) {
    setEditing(record)
    form.resetFields()
    form.setFieldsValue({
      code: record.code,
      name: record.name,
      region: record.region,
      adminExternalUserId: record.adminExternalUserId ?? '',
      adminName: record.adminName ?? undefined,
      status: record.status,
    })
    setModalOpen(true)
  }

  function handleSearch() {
    setKeyword(keywordInput.trim())
    setPage(1)
  }

  const columns: TableProps<SysSystemVO>['columns'] = [
    {
      title: '系统编码',
      dataIndex: 'code',
      width: 100,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    { title: '系统名称', dataIndex: 'name', width: 130, ellipsis: true },
    {
      title: '区域',
      dataIndex: 'region',
      width: 80,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: '管理员账号',
      dataIndex: 'adminExternalUserId',
      width: 140,
      ellipsis: true,
      render: (value: string | null | undefined) =>
        value ? <Tag color="geekblue">{value}</Tag> : '-',
    },
    {
      title: '管理员姓名',
      dataIndex: 'adminName',
      width: 90,
      ellipsis: true,
      render: (value: string | null | undefined) => value || '-',
    },
    {
      title: 'AppKey',
      dataIndex: 'appKey',
      width: 120,
      ellipsis: true,
      render: (value: string | null | undefined) =>
        value ? (
          <Text copyable={{ text: value }} className="mono-text">
            {value}
          </Text>
        ) : (
          '-'
        ),
    },
    {
      title: 'AppSecret',
      dataIndex: 'appSecretMasked',
      width: 110,
      ellipsis: true,
      render: (value: string | null | undefined) =>
        value ? (
          <Text className="mono-text" type="secondary">
            {value}
          </Text>
        ) : (
          '-'
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 70,
      render: (value: number) => <StatusTag status={value} />,
    },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_, record) => (
        <Space size={2}>
          <Button type="link" size="small" onClick={() => setViewingKeys(record)}>
            查看密钥
          </Button>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用系统"
            description={`确认停用系统「${record.name}」吗？停用后该系统的角色与用户将无法继续使用。`}
            okText="确认停用"
            cancelText="取消"
            onConfirm={() => disableMutation.mutate(record.id)}
          >
            <Button type="link" size="small" danger disabled={record.status === 0}>
              停用
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <Flex vertical gap={16}>
      <Flex justify="space-between" align="center" gap={12} wrap>
        <Space wrap>
          <Input
            allowClear
            placeholder="按系统编码 / 名称搜索"
            style={{ width: 260 }}
            value={keywordInput}
            onChange={(event) => setKeywordInput(event.target.value)}
            onPressEnter={handleSearch}
          />
          <Button type="primary" onClick={handleSearch}>
            搜索
          </Button>
          <Button
            onClick={() => {
              setKeywordInput('')
              setKeyword('')
              setPage(1)
            }}
          >
            重置
          </Button>
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => void systemsQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建系统接入
          </Button>
        </Space>
      </Flex>

      {systemsQuery.isError ? (
        <Alert
          type="error"
          showIcon
          title="系统接入列表加载失败"
          description={systemsQuery.error.message}
          action={
            <Button size="small" onClick={() => void systemsQuery.refetch()}>
              重试
            </Button>
          }
        />
      ) : null}

      <Table<SysSystemVO>
        rowKey="id"
        columns={columns}
        dataSource={systemsQuery.data?.items ?? []}
        loading={systemsQuery.isLoading || systemsQuery.isFetching}
        pagination={createPaginationConfig(page, size, systemsQuery.data?.total ?? 0, (nextPage, nextSize) => {
          setPage(nextPage)
          setSize(nextSize)
        })}
        locale={{ emptyText: '暂无接入的业务系统，可点击「新建系统接入」手动录入' }}
      />

      <Modal
        title={editing ? `编辑系统接入：${editing.code}` : '新建系统接入'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={640}
      >
        <Form<SystemFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="系统编码"
                name="code"
                rules={[
                  { required: true, message: '请输入系统编码' },
                  {
                    pattern: /^[A-Za-z0-9_-]{2,64}$/,
                    message: '系统编码为 2-64 位字母、数字、下划线或连字符',
                  },
                ]}
                tooltip="系统编码全局唯一，创建后不可修改；SDK 的 system-code 与此一致"
              >
                <Input placeholder="如 crm" disabled={Boolean(editing)} maxLength={64} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="系统名称"
                name="name"
                rules={[{ required: true, message: '请输入系统名称' }]}
              >
                <Input placeholder="如 CRM系统" maxLength={100} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="所属区域"
                name="region"
                rules={[{ required: true, message: '请输入所属区域' }]}
                tooltip="区域用于路由 Redis 变更通知频道：rbac:change:{region}:{systemCode}"
              >
                <Input placeholder="如 region_a" maxLength={32} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="状态"
                name="status"
                rules={[{ required: true, message: '请选择状态' }]}
              >
                <Select options={STATUS_OPTIONS} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="系统管理员账号"
                name="adminExternalUserId"
                rules={[{ required: true, message: '请输入系统管理员账号' }]}
                tooltip="接入时后端会为该账号自动创建本系统默认 admin 角色并授权，账号来自外部认证系统（external_user_id）"
              >
                <Input placeholder="如 crm-admin-001" maxLength={255} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item label="管理员姓名（可选）" name="adminName">
                <Input placeholder="如 张三" maxLength={100} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>

      <Modal
        title={viewingKeys ? `密钥信息：${viewingKeys.name}` : '密钥信息'}
        open={viewingKeys !== null}
        forceRender
        onCancel={() => setViewingKeys(null)}
        footer={
          <Space>
            <Button disabled title="密钥重置功能暂未开放">
              重置密钥（暂未开放）
            </Button>
            <Button type="primary" onClick={() => setViewingKeys(null)}>
              关闭
            </Button>
          </Space>
        }
        width={600}
      >
        {viewingKeys ? (
          <Flex vertical gap={12}>
            <Alert
              type="info"
              showIcon
              title="AppSecret 已脱敏展示"
              description="完整 AppSecret 仅在创建系统或审核通过时返回一次；如需重置密钥，请等待后续版本开放。"
            />
            <div>
              <Text strong>AppKey</Text>
              <Input
                readOnly
                value={viewingKeys.appKey ?? '-'}
                className="mono-text"
                style={{ marginTop: 8 }}
              />
            </div>
            <div>
              <Text strong>AppSecret（脱敏）</Text>
              <Input
                readOnly
                value={viewingKeys.appSecretMasked ?? '-'}
                className="mono-text"
                style={{ marginTop: 8 }}
              />
            </div>
          </Flex>
        ) : null}
      </Modal>
    </Flex>
  )
}

/* ------------------------------------------------------------------ */
/* Tab2：接入申请（SDK 上报审核）                                       */
/* ------------------------------------------------------------------ */

function SystemApplicationsTab({
  onSystemApproved,
}: {
  onSystemApproved: (result: SecretResult) => void
}) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<ApplicationStatusFilter>('PENDING')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [approveTarget, setApproveTarget] = useState<SystemApplicationVO | null>(null)
  const [rejectTarget, setRejectTarget] = useState<SystemApplicationVO | null>(null)
  const [approveForm] = Form.useForm<ApproveFormValues>()
  const [rejectForm] = Form.useForm<RejectFormValues>()

  const queryStatus = statusFilter === 'ALL' ? undefined : statusFilter
  const applicationsQuery = useQuery({
    queryKey: ['system-applications', statusFilter, page, size],
    queryFn: () => listSystemApplications({ status: queryStatus, page, size }),
  })

  const approveMutation = useMutation({
    mutationFn: (input: { id: number; payload: SystemApplicationApprovePayload }) =>
      approveSystemApplication(input.id, input.payload),
    onSuccess: (result) => {
      void message.success('审核通过，业务系统已开通，请保存本次返回的 AppKey/AppSecret')
      setApproveTarget(null)
      approveForm.resetFields()
      // 审核通过同样只返回一次完整 AppSecret，弹结果弹窗供复制
      onSystemApproved(extractApprovedSecret(result))
      void queryClient.invalidateQueries({ queryKey: ['system-applications'] })
      void queryClient.invalidateQueries({ queryKey: ['systems'] })
      void queryClient.invalidateQueries({ queryKey: ['admin-session'] })
    },
  })

  const rejectMutation = useMutation({
    mutationFn: (input: { id: number; payload: SystemApplicationRejectPayload }) =>
      rejectSystemApplication(input.id, input.payload),
    onSuccess: () => {
      void message.success('接入申请已驳回')
      setRejectTarget(null)
      rejectForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['system-applications'] })
    },
  })

  function openApproveModal(record: SystemApplicationVO) {
    setApproveTarget(record)
    approveForm.resetFields()
  }

  function openRejectModal(record: SystemApplicationVO) {
    setRejectTarget(record)
    rejectForm.resetFields()
  }

  function handleStatusChange(value: ApplicationStatusFilter) {
    setStatusFilter(value)
    setPage(1)
  }

  const emptyText =
    statusFilter === 'PENDING'
      ? '暂无待审核的接入申请'
      : statusFilter === 'APPROVED'
        ? '暂无已通过的接入申请'
        : statusFilter === 'REJECTED'
          ? '暂无已驳回的接入申请'
          : '暂无接入申请'

  const columns: TableProps<SystemApplicationVO>['columns'] = [
    {
      title: '系统编码',
      dataIndex: 'systemCode',
      width: 110,
      ellipsis: true,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    { title: '系统名称', dataIndex: 'name', width: 130, ellipsis: true },
    {
      title: '区域',
      dataIndex: 'region',
      width: 110,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 80,
      render: (value: string) => (
        <Tag color={value === 'SDK' ? 'geekblue' : 'default'}>{value}</Tag>
      ),
    },
    {
      title: 'SDK版本',
      dataIndex: 'sdkVersion',
      width: 90,
      ellipsis: true,
      render: (value: string | null | undefined) => value || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: SystemApplicationStatus) => <ApplicationStatusTag status={value} />,
    },
    {
      title: '申请时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (value: string | null | undefined) => formatDateTime(value),
    },
    {
      title: '备注',
      dataIndex: 'remark',
      ellipsis: true,
      render: (value: string | null | undefined) => value || '-',
    },
    {
      title: '审核信息',
      key: 'review',
      width: 160,
      ellipsis: true,
      render: (_, record) => {
        if (record.status === 'APPROVED') {
          return <Tag color="green">已开通（systemId={record.systemId ?? '-'}）</Tag>
        }
        if (record.status === 'REJECTED') {
          return (
            <Text type="danger" ellipsis={{ tooltip: record.rejectReason ?? '-' }}>
              驳回原因：{record.rejectReason || '-'}
            </Text>
          )
        }
        return <Text type="secondary">待审核</Text>
      },
    },
    {
      title: '操作',
      key: 'action',
      width: 110,
      render: (_, record) =>
        record.status === 'PENDING' ? (
          <Space size={2}>
            <Button type="link" size="small" onClick={() => openApproveModal(record)}>
              审核
            </Button>
            <Button type="link" size="small" danger onClick={() => openRejectModal(record)}>
              驳回
            </Button>
          </Space>
        ) : (
          <Text type="secondary">已处理</Text>
        ),
    },
  ]

  return (
    <Flex vertical gap={16}>
      <Flex justify="space-between" align="center" gap={12} wrap>
        <Space wrap>
          <Text type="secondary">状态</Text>
          <Select<ApplicationStatusFilter>
            value={statusFilter}
            options={APPLICATION_FILTER_OPTIONS}
            style={{ width: 160 }}
            onChange={handleStatusChange}
          />
        </Space>
        <Button icon={<ReloadOutlined />} onClick={() => void applicationsQuery.refetch()}>
          刷新
        </Button>
      </Flex>

      {applicationsQuery.isError ? (
        <Alert
          type="error"
          showIcon
          title="接入申请列表加载失败"
          description={applicationsQuery.error.message}
          action={
            <Button size="small" onClick={() => void applicationsQuery.refetch()}>
              重试
            </Button>
          }
        />
      ) : null}

      <Table<SystemApplicationVO>
        rowKey="id"
        columns={columns}
        dataSource={applicationsQuery.data?.items ?? []}
        loading={applicationsQuery.isLoading || applicationsQuery.isFetching}
        pagination={createPaginationConfig(page, size, applicationsQuery.data?.total ?? 0, (nextPage, nextSize) => {
          setPage(nextPage)
          setSize(nextSize)
        })}
        locale={{ emptyText }}
      />

      <Modal
        title="审核系统接入申请"
        open={approveTarget !== null}
        forceRender
        onCancel={() => setApproveTarget(null)}
        onOk={() => approveForm.submit()}
        confirmLoading={approveMutation.isPending}
        okText="确认开通"
        cancelText="取消"
        width={640}
      >
        {approveTarget ? (
          <>
            <Descriptions
              size="small"
              bordered
              column={2}
              style={{ marginBottom: 16 }}
              items={[
                {
                  key: 'code',
                  label: '系统编码',
                  children: <Tag color="blue">{approveTarget.systemCode}</Tag>,
                },
                { key: 'name', label: '系统名称', children: approveTarget.name },
                {
                  key: 'region',
                  label: '区域',
                  children: <Tag>{approveTarget.region}</Tag>,
                },
                { key: 'source', label: '来源', children: approveTarget.source },
                {
                  key: 'sdkVersion',
                  label: 'SDK 版本',
                  children: approveTarget.sdkVersion || '-',
                },
                {
                  key: 'createdAt',
                  label: '申请时间',
                  children: formatDateTime(approveTarget.createdAt),
                },
                {
                  key: 'appKey',
                  label: '上报 AppKey',
                  children: approveTarget.appKey || '-',
                },
                {
                  key: 'remark',
                  label: '申请备注',
                  span: 2,
                  children: approveTarget.remark || '-',
                },
              ]}
            />
            <Form<ApproveFormValues>
              form={approveForm}
              layout="vertical"
              onFinish={(values) => {
                if (!approveTarget) return
                approveMutation.mutate({
                  id: approveTarget.id,
                  payload: {
                    adminExternalUserId: values.adminExternalUserId.trim(),
                    adminName: values.adminName?.trim() || undefined,
                  },
                })
              }}
            >
              <Row gutter={16}>
                <Col span={12}>
                  <Form.Item
                    label="系统管理员账号"
                    name="adminExternalUserId"
                    rules={[{ required: true, message: '请输入系统管理员账号' }]}
                    tooltip="审核通过后为该账号创建本系统默认 admin 角色（通配权限 *）并授权"
                  >
                    <Input
                      placeholder={`如 ${approveTarget.systemCode}-admin-001`}
                      maxLength={255}
                      style={{ width: '100%' }}
                    />
                  </Form.Item>
                </Col>
                <Col span={12}>
                  <Form.Item label="管理员姓名（可选）" name="adminName">
                    <Input placeholder="如 张三" maxLength={100} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
              </Row>
              <Alert
                type="info"
                showIcon
                title="审核通过后系统立即开通"
                description="将创建业务系统、默认 admin 角色（通配权限 *）并完成管理员授权，申请状态更新为「已通过」。"
              />
            </Form>
          </>
        ) : null}
      </Modal>

      <Modal
        title="驳回系统接入申请"
        open={rejectTarget !== null}
        forceRender
        onCancel={() => setRejectTarget(null)}
        onOk={() => rejectForm.submit()}
        confirmLoading={rejectMutation.isPending}
        okText="确认驳回"
        okButtonProps={{ danger: true }}
        cancelText="取消"
        width={600}
      >
        {rejectTarget ? (
          <>
            <Descriptions
              size="small"
              bordered
              column={2}
              style={{ marginBottom: 16 }}
              items={[
                {
                  key: 'code',
                  label: '系统编码',
                  children: <Tag color="blue">{rejectTarget.systemCode}</Tag>,
                },
                { key: 'name', label: '系统名称', children: rejectTarget.name },
                {
                  key: 'region',
                  label: '区域',
                  children: <Tag>{rejectTarget.region}</Tag>,
                },
                {
                  key: 'sdkVersion',
                  label: 'SDK 版本',
                  children: rejectTarget.sdkVersion || '-',
                },
                {
                  key: 'appKey',
                  label: '上报 AppKey',
                  children: rejectTarget.appKey || '-',
                },
                {
                  key: 'remark',
                  label: '申请备注',
                  span: 2,
                  children: rejectTarget.remark || '-',
                },
              ]}
            />
            <Form<RejectFormValues>
              form={rejectForm}
              layout="vertical"
              onFinish={(values) => {
                if (!rejectTarget) return
                rejectMutation.mutate({
                  id: rejectTarget.id,
                  payload: { reason: values.reason.trim() },
                })
              }}
            >
              <Form.Item
                label="驳回原因"
                name="reason"
                rules={[
                  { required: true, message: '请输入驳回原因' },
                  { max: 255, message: '驳回原因不能超过 255 个字符' },
                ]}
              >
                <Input.TextArea
                  rows={4}
                  maxLength={255}
                  showCount
                  style={{ width: '100%' }}
                  placeholder="请说明驳回原因，SDK 侧可修改后重新上报"
                />
              </Form.Item>
            </Form>
          </>
        ) : null}
      </Modal>
    </Flex>
  )
}
