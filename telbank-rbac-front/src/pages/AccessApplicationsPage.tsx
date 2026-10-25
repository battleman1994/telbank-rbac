import { useMemo, useState } from 'react'
import { LinkOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Flex,
  Form,
  Input,
  Modal,
  Select,
  Skeleton,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import {
  createAccessApplication,
  getAccessApplication,
  getAccessApplicationOptions,
  getAccessApplications,
} from '../api'
import type {
  AccessApplicationStatus,
  AccessApplicationVO,
  AccessRoleOptionVO,
} from '../api/types'
import { PageShell } from '../components/PageShell'
import { SystemRequired } from '../components/SystemGate'
import { TimeRangeLines } from '../components/TimeRangeLines'
import { useCurrentSystem, useCurrentSystemId } from '../stores/app'
import { formatDateTime, formatDateTimeMinute } from '../utils/format'
import { createPaginationConfig } from '../utils/pagination'

const { Text } = Typography

type StatusFilter = 'ALL' | AccessApplicationStatus

const STATUS_FILTER_OPTIONS: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  { value: 'PENDING', label: '待审批' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
]

const STATUS_META: Record<AccessApplicationStatus, { text: string; color: string }> = {
  PENDING: { text: '待审批', color: 'warning' },
  APPROVED: { text: '已通过', color: 'success' },
  REJECTED: { text: '已驳回', color: 'error' },
}

/** 申请状态标签：待审批 / 已通过 / 已驳回 */
function AccessApplicationStatusTag({ status }: { status: AccessApplicationStatus }) {
  const meta = STATUS_META[status]
  return <Tag color={meta?.color}>{meta?.text ?? status}</Tag>
}

/** 统一授权窗口展示：开始/结束分两行 */
function ApprovedWindowLines({ record }: { record: AccessApplicationVO }) {
  return (
    <TimeRangeLines
      start={formatDateTimeMinute(record.approvedValidFrom)}
      end={formatDateTimeMinute(record.approvedValidUntil)}
    />
  )
}

const APPROVED_PHASE_META = {
  PENDING: { text: '待生效', color: 'gold' },
  ACTIVE: { text: '生效中', color: 'green' },
  EXPIRED: { text: '已过期', color: 'default' },
} as const

type ApprovedPhase = keyof typeof APPROVED_PHASE_META

/** 按当前时间判断已通过窗口所处的阶段（仅展示文案，不改变状态字段） */
function resolveApprovedPhase(
  validFrom?: string | null,
  validUntil?: string | null,
): ApprovedPhase | null {
  if (!validFrom && !validUntil) return null
  const now = Date.now()
  const from = validFrom ? new Date(validFrom).getTime() : null
  const until = validUntil ? new Date(validUntil).getTime() : null
  if (from !== null && now < from) return 'PENDING'
  if (until !== null && now > until) return 'EXPIRED'
  return 'ACTIVE'
}

/** 已通过窗口的阶段标签：待生效 / 生效中 / 已过期 */
function ApprovedPhaseTag({ validFrom, validUntil }: { validFrom?: string | null; validUntil?: string | null }) {
  const phase = resolveApprovedPhase(validFrom, validUntil)
  if (!phase) return null
  const meta = APPROVED_PHASE_META[phase]
  return <Tag color={meta.color}>{meta.text}</Tag>
}

/** 详情中的逐角色模板与授权窗口（A21：多角色窗口可以不同） */
function RoleSnapshotDetails({ record }: { record: AccessApplicationVO }) {
  const roles = record.roles ?? []
  if (roles.length === 0) return <Text type="secondary">-</Text>
  return (
    <Flex vertical gap={8}>
      {roles.map((role) => (
        <div key={role.roleId} className="access-role-window">
          <Space size={6} wrap>
            <Tag color="geekblue">{role.roleCode}</Tag>
            <Text strong>{role.roleName}</Text>
            <Tag color="purple">{role.timeTemplateName || '永久角色'}</Tag>
            {record.status === 'APPROVED' ? (
              <ApprovedPhaseTag
                validFrom={role.approvedValidFrom}
                validUntil={role.approvedValidUntil}
              />
            ) : null}
          </Space>
          <div className="access-role-window__time">
            {record.status === 'APPROVED' ? (
              <TimeRangeLines
                start={formatDateTime(role.approvedValidFrom)}
                end={formatDateTime(role.approvedValidUntil)}
              />
            ) : (
              <Text type="secondary" style={{ fontSize: 12 }}>
                授权窗口：待审批通过后按模板计算
              </Text>
            )}
          </div>
        </div>
      ))}
    </Flex>
  )
}

/** 申请角色快照标签；超过 max 个折叠为 +N（悬停展示全部） */
function RoleSnapshotTags({ record, max = 3 }: { record: AccessApplicationVO; max?: number }) {
  const roles = record.roles ?? []
  if (roles.length === 0) return <Text type="secondary">-</Text>
  const visible = roles.slice(0, max)
  const rest = roles.slice(max)
  return (
    <Space size={4} wrap>
      {visible.map((role) => (
        <Tag key={role.roleId} color="geekblue">
          {role.roleName}
        </Tag>
      ))}
      {rest.length > 0 ? (
        <Tooltip title={rest.map((role) => role.roleName).join('、')}>
          <Tag>+{rest.length}</Tag>
        </Tooltip>
      ) : null}
    </Space>
  )
}

interface CreateFormValues {
  roleIds: number[]
  reason: string
}

/**
 * 「我的申请」：业务用户自助申请当前系统的临时角色权限（申请人侧，A20）。
 *
 * - 路由 /access-applications 不包管理权限守卫，所有登录用户可见；
 * - 只读当前系统（X-System-Code）；列表/选项按 systemId 隔离；
 * - 审批在外部审批平台完成，本页不提供审批操作，仅展示状态与审批链接/详情。
 */
export default function AccessApplicationsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="我的申请"
        description="当前系统上下文未就绪，无法加载可申请角色与申请记录。"
      />
    )
  }
  return <AccessApplicationsContent systemId={systemId} />
}

function AccessApplicationsContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const currentSystem = useCurrentSystem()

  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [createOpen, setCreateOpen] = useState(false)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [detailNo, setDetailNo] = useState<string | null>(null)
  const [detailFallback, setDetailFallback] = useState<AccessApplicationVO | null>(null)
  const [form] = Form.useForm<CreateFormValues>()
  const selectedRoleIds = Form.useWatch('roleIds', form)

  const status = statusFilter === 'ALL' ? undefined : statusFilter

  const listQuery = useQuery({
    queryKey: ['access-applications', systemId, statusFilter, page, size],
    queryFn: () => getAccessApplications({ status, page, size }),
  })

  const optionsQuery = useQuery({
    queryKey: ['access-application-options', systemId],
    queryFn: getAccessApplicationOptions,
  })

  const detailQuery = useQuery({
    queryKey: ['access-application', systemId, detailNo],
    queryFn: () => getAccessApplication(detailNo as string),
    enabled: Boolean(detailNo),
    retry: false,
  })

  const detail = detailQuery.data ?? detailFallback

  const roleOptions = optionsQuery.data?.roles ?? []

  // 已选角色的权限预览（按角色分组）
  const selectedRoles = useMemo(() => {
    const selected = new Set(selectedRoleIds ?? [])
    return roleOptions.filter((role) => selected.has(role.roleId))
  }, [roleOptions, selectedRoleIds])

  const createMutation = useMutation({
    mutationFn: (values: CreateFormValues) =>
      createAccessApplication({
        roleIds: values.roleIds,
        reason: values.reason.trim(),
      }),
    onSuccess: (application) => {
      void message.success(`申请已提交：${application.applicationNo}`)
      setCreateOpen(false)
      setSubmitError(null)
      form.resetFields()
      setStatusFilter('ALL')
      setPage(1)
      void queryClient.invalidateQueries({ queryKey: ['access-applications', systemId] })
      void queryClient.invalidateQueries({ queryKey: ['access-application-options', systemId] })
    },
    onError: (error: Error) => {
      // 后端错误逐字展示（尤其 409「已有待审批申请」/「已拥有该角色」）
      setSubmitError(error.message)
    },
  })

  function openCreateModal() {
    setSubmitError(null)
    form.resetFields()
    setCreateOpen(true)
  }

  function openDetail(record: AccessApplicationVO) {
    setDetailFallback(record)
    setDetailNo(record.applicationNo)
  }

  const columns: TableProps<AccessApplicationVO>['columns'] = [
    {
      title: '申请单号',
      dataIndex: 'applicationNo',
      width: 185,
      render: (value: string, record) =>
        record.approvalUrl ? (
          <Tooltip title="前往审批平台查看/处理">
            <a
              href={record.approvalUrl}
              target="_blank"
              rel="noreferrer"
              className="mono-text"
            >
              {value}
            </a>
          </Tooltip>
        ) : (
          <span className="mono-text">{value}</span>
        ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 80,
      render: (value: AccessApplicationStatus) => <AccessApplicationStatusTag status={value} />,
    },
    {
      title: '申请角色',
      key: 'roles',
      width: 145,
      render: (_, record) => <RoleSnapshotTags record={record} />,
    },
    {
      title: '授权有效期',
      key: 'approvedWindow',
      width: 200,
      render: (_, record) =>
        record.status === 'APPROVED' ? (
          <ApprovedWindowLines record={record} />
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    {
      title: '生效状态',
      key: 'approvedPhase',
      width: 100,
      render: (_, record) =>
        record.status === 'APPROVED' ? (
          <ApprovedPhaseTag
            validFrom={record.approvedValidFrom}
            validUntil={record.approvedValidUntil}
          />
        ) : (
          <Text type="secondary">-</Text>
        ),
    },
    {
      title: '申请原因',
      dataIndex: 'reason',
    },
    {
      title: '申请时间',
      dataIndex: 'createdAt',
      width: 150,
      render: (value: string) => formatDateTimeMinute(value),
    },
    {
      title: '操作',
      key: 'action',
      width: 70,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => openDetail(record)}>
          详情
        </Button>
      ),
    },
  ]

  return (
    <PageShell
      title="我的申请"
      description={`当前系统：${currentSystem?.name ?? '-'}（${currentSystem?.code ?? '-'}）。审批通过后按各角色绑定的时间模板计算授权窗口（多角色窗口可以不同），到期自动失效；本页不提供审批操作。`}
      extra={
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => void listQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建申请
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Select<StatusFilter>
              style={{ width: 160 }}
              value={statusFilter}
              options={STATUS_FILTER_OPTIONS}
              onChange={(value) => {
                setStatusFilter(value)
                setPage(1)
              }}
            />
            <Tag color="blue">
              当前系统：{currentSystem?.name ?? '-'}（{currentSystem?.code ?? '-'}）
            </Tag>
          </Space>

          {listQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="申请记录加载失败"
              description={listQuery.error.message}
              action={
                <Button size="small" onClick={() => void listQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Table<AccessApplicationVO>
            rowKey="id"
            columns={columns}
            dataSource={listQuery.data?.items ?? []}
            loading={listQuery.isLoading || listQuery.isFetching}
            pagination={createPaginationConfig(
              page,
              size,
              listQuery.data?.total ?? 0,
              (nextPage, nextSize) => {
                setPage(nextPage)
                setSize(nextSize)
              },
            )}
            locale={{
              emptyText: (
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="暂无申请记录，点击右上角「新建申请」提交临时权限申请"
                />
              ),
            }}
          />
        </Flex>
      </Card>

      <Modal
        title="新建权限申请"
        open={createOpen}
        forceRender
        width={760}
        okText="提交申请"
        cancelText="取消"
        confirmLoading={createMutation.isPending}
        onCancel={() => setCreateOpen(false)}
        onOk={() => form.submit()}
      >
        <Flex vertical gap={16}>
          <Alert
            type="info"
            showIcon
            title="审批通过后自动授予临时权限"
            description="申请提交后由外部审批平台审批；通过后按每个角色绑定的时间模板计算生效窗口（如「审批后当天 19:00 ~ 次日 07:00」），到期自动失效。请勿在申请原因中填写密码、密钥等敏感信息。"
          />

          {submitError ? (
            <Alert
              type="error"
              showIcon
              closable
              title="申请提交失败"
              description={submitError}
              onClose={() => setSubmitError(null)}
            />
          ) : null}

          {optionsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="可申请角色加载失败"
              description={optionsQuery.error.message}
              action={
                <Button size="small" onClick={() => void optionsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Form<CreateFormValues>
            form={form}
            layout="vertical"
            onFinish={(values) => createMutation.mutate(values)}
          >
            <Form.Item label="申请系统">
              <Space size={8} wrap>
                <Tag color="blue">
                  {optionsQuery.data?.systemName ?? currentSystem?.name ?? '-'}（
                  {optionsQuery.data?.systemCode ?? currentSystem?.code ?? '-'}）
                </Tag>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  只能申请当前系统内的临时角色权限
                </Text>
              </Space>
            </Form.Item>

            <Form.Item
              label="申请角色"
              name="roleIds"
              rules={[{ required: true, message: '请至少选择一个角色' }]}
              extra="已拥有的角色不可重复申请；admin（系统管理员）角色不可自助申请。"
            >
              <Select
                mode="multiple"
                placeholder={optionsQuery.isLoading ? '可申请角色加载中…' : '请选择需要临时授予的角色'}
                loading={optionsQuery.isLoading}
                showSearch
                optionFilterProp="label"
                disabled={optionsQuery.isLoading || optionsQuery.isError}
                options={roleOptions.map((role: AccessRoleOptionVO) => ({
                  value: role.roleId,
                  label: `${role.roleName}（${role.roleCode}）${role.timeTemplateText ? ` · ${role.timeTemplateText}` : ''}${role.owned ? ' · 已拥有' : ''}`,
                  disabled: role.owned,
                }))}
              />
            </Form.Item>

            <Form.Item label="角色权限预览">
              {selectedRoles.length === 0 ? (
                <Text type="secondary">选择角色后预览该角色当前可授予的权限</Text>
              ) : (
                <Flex vertical gap={12}>
                  {selectedRoles.map((role) => (
                    <div key={role.roleId} className="access-role-preview">
                      <Space size={6} wrap style={{ marginBottom: 6 }}>
                        <Text strong>{role.roleName}</Text>
                        <Tag color="geekblue">{role.roleCode}</Tag>
                        {role.timeTemplateText ? (
                          <Tag color="purple">{role.timeTemplateText}</Tag>
                        ) : null}
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {role.permissions.length} 项权限
                        </Text>
                      </Space>
                      {role.permissions.length === 0 ? (
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          该角色暂无有效权限
                        </Text>
                      ) : (
                        <Space size={4} wrap>
                          {role.permissions.map((permission) => (
                            <Tooltip key={permission.id} title={permission.name}>
                              <Tag color="blue">{permission.code}</Tag>
                            </Tooltip>
                          ))}
                        </Space>
                      )}
                    </div>
                  ))}
                </Flex>
              )}
            </Form.Item>

            <Form.Item
              label="申请原因"
              name="reason"
              rules={[
                { required: true, whitespace: true, message: '请填写申请原因' },
                { max: 200, message: '申请原因不能超过 200 字' },
              ]}
            >
              <Input.TextArea
                rows={3}
                maxLength={200}
                placeholder="请说明申请原因，如：季度末需要批量导出订单"
              />
            </Form.Item>
          </Form>
        </Flex>
      </Modal>

      <Drawer
        title="申请详情"
        size={560}
        open={Boolean(detailNo)}
        onClose={() => setDetailNo(null)}
        extra={
          detail?.status === 'PENDING' && detail.approvalUrl ? (
            <Button
              type="primary"
              icon={<LinkOutlined />}
              href={detail.approvalUrl}
              target="_blank"
              rel="noreferrer"
            >
              前往审批平台
            </Button>
          ) : null
        }
      >
        {detailQuery.isLoading && !detail ? (
          <Skeleton active paragraph={{ rows: 8 }} />
        ) : detailQuery.isError && !detail ? (
          <Alert
            type="error"
            showIcon
            title="申请详情加载失败"
            description={detailQuery.error.message}
            action={
              <Button size="small" onClick={() => void detailQuery.refetch()}>
                重试
              </Button>
            }
          />
        ) : detail ? (
          <Descriptions
            bordered
            column={1}
            size="middle"
            items={[
              {
                key: 'applicationNo',
                label: '申请单号',
                children: <span className="mono-text">{detail.applicationNo}</span>,
              },
              {
                key: 'status',
                label: '状态',
                children: <AccessApplicationStatusTag status={detail.status} />,
              },
              {
                key: 'systemCode',
                label: '所属系统',
                children: <Tag color="blue">{detail.systemCode}</Tag>,
              },
              {
                key: 'roles',
                label: '申请角色（模板与窗口）',
                children: <RoleSnapshotDetails record={detail} />,
              },
              {
                key: 'reason',
                label: '申请原因',
                children: detail.reason,
              },
              {
                key: 'createdAt',
                label: '提交时间',
                children: formatDateTime(detail.createdAt),
              },
              {
                key: 'approvedWindow',
                label: '授权有效期',
                children:
                  detail.status === 'APPROVED' ? <ApprovedWindowLines record={detail} /> : '-',
              },
              {
                key: 'approvedPhase',
                label: '生效状态',
                children:
                  detail.status === 'APPROVED' ? (
                    <ApprovedPhaseTag
                      validFrom={detail.approvedValidFrom}
                      validUntil={detail.approvedValidUntil}
                    />
                  ) : (
                    '-'
                  ),
              },
              {
                key: 'reviewedBy',
                label: '审批人',
                children: detail.reviewedBy ?? '-',
              },
              {
                key: 'reviewedAt',
                label: '审批时间',
                children: formatDateTime(detail.reviewedAt),
              },
              {
                key: 'decisionComment',
                label: '审批意见',
                children: detail.decisionComment ?? '-',
              },
            ]}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无申请详情" />
        )}
      </Drawer>
    </PageShell>
  )
}
