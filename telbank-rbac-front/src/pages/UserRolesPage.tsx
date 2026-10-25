import { useState } from 'react'
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Flex,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import { createUserRole, listRoles, listUserRoles, revokeUserRole } from '../api'
import type { RoleVO, UserRoleCreatePayload, UserRoleVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { TimeRangeLines } from '../components/TimeRangeLines'
import { useCurrentSystemId } from '../stores/app'
import { formatDateTime } from '../utils/format'

const { Text } = Typography

interface UserRoleFormValues {
  externalUserId: string
  roleId: number
  grantReason?: string
}

/** 用户-角色分配（契约 §3.6）：external_user_id 精确查询 + 新增（含时效）+ 撤销 */
export default function UserRolesPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="用户角色"
        description="当前系统上下文未就绪，无法加载用户角色关系。"
      />
    )
  }
  return <UserRolesContent systemId={systemId} />
}

function UserRolesContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [searchInput, setSearchInput] = useState('')
  const [externalUserId, setExternalUserId] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm<UserRoleFormValues>()
  const watchedRoleId = Form.useWatch('roleId', form)

  const userRolesQuery = useQuery({
    queryKey: ['user-roles', systemId, externalUserId],
    queryFn: () => listUserRoles({ systemId, externalUserId }),
    enabled: externalUserId.trim().length > 0,
  })

  const rolesQuery = useQuery({
    queryKey: ['roles', systemId, { keyword: '', page: 1, size: 200 }],
    queryFn: () => listRoles({ systemId, page: 1, size: 200 }),
    enabled: systemId > 0,
  })

  const createMutation = useMutation({
    mutationFn: (values: UserRoleFormValues) => {
      // A21：管理端不再手工选择时间，窗口由所选角色的时间模板在分配时计算
      const payload: UserRoleCreatePayload = {
        systemId,
        externalUserId: values.externalUserId.trim(),
        roleId: values.roleId,
        grantReason: values.grantReason?.trim() || undefined,
      }
      return createUserRole(payload)
    },
    onSuccess: () => {
      void message.success('用户角色已添加')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['user-roles', systemId] })
    },
  })

  const revokeMutation = useMutation({
    mutationFn: (id: number) => revokeUserRole(id),
    onSuccess: () => {
      void message.success('用户角色已撤销')
      void queryClient.invalidateQueries({ queryKey: ['user-roles', systemId] })
    },
  })

  function handleSearch() {
    const value = searchInput.trim()
    if (!value) {
      void message.warning('请输入 external_user_id')
      return
    }
    setExternalUserId(value)
  }

  function openCreateModal() {
    form.resetFields()
    form.setFieldsValue({
      externalUserId: externalUserId || searchInput.trim(),
    })
    setModalOpen(true)
  }

  const roleOptions = (rolesQuery.data?.items ?? [])
    .filter((role: RoleVO) => role.status === 1)
    .map((role: RoleVO) => ({
      value: role.id,
      label: `${role.name}（${role.code}）· ${role.timeTemplateName || '永久角色'}`,
    }))

  const selectedRole = (rolesQuery.data?.items ?? []).find((role) => role.id === watchedRoleId)

  const columns: TableProps<UserRoleVO>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: 'external_user_id',
      dataIndex: 'externalUserId',
      width: 200,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    {
      title: '角色',
      key: 'role',
      width: 200,
      render: (_, record) => (
        <Space size={4}>
          <Tag color="geekblue">{record.roleCode}</Tag>
          <span>{record.roleName}</span>
        </Space>
      ),
    },
    {
      title: '时效',
      key: 'validity',
      render: (_, record) => (
        <TimeRangeLines
          start={record.validFrom ? formatDateTime(record.validFrom) : '立即生效'}
          end={record.validUntil ? formatDateTime(record.validUntil) : '永久有效'}
        />
      ),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    { title: '授权原因', dataIndex: 'grantReason', ellipsis: true, render: (value: string | null) => value || '-' },
    {
      title: '审批来源',
      dataIndex: 'sourceApprovalId',
      width: 180,
      render: (value: string | null) => (value ? <Tag>{value}</Tag> : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Popconfirm
          title="撤销用户角色"
          description={`确认撤销用户「${record.externalUserId}」的角色「${record.roleName}」吗？`}
          okText="确认撤销"
          cancelText="取消"
          onConfirm={() => revokeMutation.mutate(record.id)}
        >
          <Button type="link" size="small" danger disabled={record.status === 0}>
            撤销
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <PageShell
      title="用户角色"
      description="在当前系统内为外部用户（无用户主数据）分配角色：通过 external_user_id 精确查询；分配后按角色绑定的时间模板计算授权窗口（未绑定模板为永久角色）。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void userRolesQuery.refetch()} disabled={!externalUserId}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新增分配
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="输入 external_user_id 精确查询，如 13800138000"
              style={{ width: 360 }}
              value={searchInput}
              onChange={(event) => setSearchInput(event.target.value)}
              onPressEnter={handleSearch}
            />
            <Button type="primary" onClick={handleSearch}>
              查询
            </Button>
            <Text type="secondary">查询结果按当前系统过滤</Text>
          </Space>

          {userRolesQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="用户角色列表加载失败"
              description={userRolesQuery.error.message}
              action={
                <Button size="small" onClick={() => void userRolesQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          {!externalUserId ? (
            <Alert
              type="info"
              showIcon
              title="请输入 external_user_id 后查询"
              description="用户-角色接口按用户维度查询：GET /api/user-roles?systemId=…&externalUserId=…"
            />
          ) : (
            <Table<UserRoleVO>
              rowKey="id"
              columns={columns}
              dataSource={userRolesQuery.data ?? []}
              loading={userRolesQuery.isLoading || userRolesQuery.isFetching}
              pagination={false}
              locale={{ emptyText: `用户 ${externalUserId} 在当前系统下暂无角色分配` }}
            />
          )}
        </Flex>
      </Card>

      <Modal
        title="新增用户角色"
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={createMutation.isPending}
        okText="确认分配"
        cancelText="取消"
        width={560}
      >
        <Form<UserRoleFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => createMutation.mutate(values)}
        >
          <Form.Item
            label="external_user_id"
            name="externalUserId"
            rules={[{ required: true, message: '请输入 external_user_id' }]}
            tooltip="外部认证系统 JWT 的 sub，全局不建用户表"
          >
            <Input placeholder="如 13800138000" maxLength={255} />
          </Form.Item>
          <Form.Item
            label="角色"
            name="roleId"
            rules={[{ required: true, message: '请选择角色' }]}
            tooltip="仅列出当前系统下启用的角色"
          >
            <Select
              placeholder={rolesQuery.isLoading ? '角色加载中…' : '请选择角色'}
              options={roleOptions}
              loading={rolesQuery.isLoading}
              showSearch
              optionFilterProp="label"
              notFoundContent="当前系统暂无启用角色"
            />
          </Form.Item>
          {selectedRole ? (
            <Alert
              showIcon
              style={{ marginBottom: 16 }}
              type={selectedRole.timeTemplateName ? 'info' : 'success'}
              title={
                selectedRole.timeTemplateName
                  ? `时间模板：${selectedRole.timeTemplateName}`
                  : '永久角色（窗口为空）'
              }
              description={
                selectedRole.timeTemplateName
                  ? '实际生效/失效时间在分配时按模板规则计算（锚点为分配时刻），到期后权限查询与 SDK 自动判定无权限。'
                  : '该角色未绑定时间模板，分配后权限永久有效，不再需要手工设置有效期。'
              }
            />
          ) : null}
          <Form.Item label="授权原因" name="grantReason">
            <Input.TextArea rows={3} maxLength={255} placeholder="选填" />
          </Form.Item>
        </Form>
      </Modal>
    </PageShell>
  )
}
