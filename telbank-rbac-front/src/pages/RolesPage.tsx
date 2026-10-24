import { useState } from 'react'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
} from 'antd'
import type { TableProps } from 'antd'
import { createRole, disableRole, listRoles, updateRole } from '../api'
import type { RoleCreatePayload, RoleUpdatePayload, RoleVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { STATUS_OPTIONS } from '../utils/labels'
import { createPaginationConfig } from '../utils/pagination'

interface RoleFormValues {
  code: string
  name: string
  status: number
  sort: number
  remark?: string
}

/** 角色管理（契约 §3.3）：按当前系统隔离的角色 CRUD */
export default function RolesPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return <SystemRequired title="角色管理" description="当前系统上下文未就绪，无法加载角色列表。" />
  }
  return <RolesContent systemId={systemId} />
}

function RolesContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<RoleVO | null>(null)
  const [form] = Form.useForm<RoleFormValues>()

  const rolesQuery = useQuery({
    queryKey: ['roles', systemId, { keyword, page, size }],
    queryFn: () => listRoles({ systemId, keyword: keyword || undefined, page, size }),
  })

  const saveMutation = useMutation({
    mutationFn: (values: RoleFormValues) => {
      if (editing) {
        const payload: RoleUpdatePayload = {
          name: values.name.trim(),
          status: values.status,
          sort: values.sort,
          remark: values.remark,
        }
        return updateRole(editing.id, payload)
      }
      const payload: RoleCreatePayload = {
        systemId,
        code: values.code.trim(),
        name: values.name.trim(),
        status: values.status,
        sort: values.sort,
        remark: values.remark,
      }
      return createRole(payload)
    },
    onSuccess: () => {
      void message.success(editing ? '角色已更新' : '角色已创建')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['roles', systemId] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disableRole(id),
    onSuccess: () => {
      void message.success('角色已停用')
      void queryClient.invalidateQueries({ queryKey: ['roles', systemId] })
    },
  })

  function openCreateModal() {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ status: 1, sort: 0 })
    setModalOpen(true)
  }

  function openEditModal(record: RoleVO) {
    setEditing(record)
    form.resetFields()
    form.setFieldsValue({
      code: record.code,
      name: record.name,
      status: record.status,
      sort: record.sort,
      remark: record.remark ?? undefined,
    })
    setModalOpen(true)
  }

  const columns: TableProps<RoleVO>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
    {
      title: '角色编码',
      dataIndex: 'code',
      width: 160,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    { title: '角色名称', dataIndex: 'name', width: 180 },
    {
      title: '所属系统 ID',
      dataIndex: 'systemId',
      width: 120,
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    { title: '排序', dataIndex: 'sort', width: 80 },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (value: string | null) => value || '-' },
    {
      title: '操作',
      key: 'action',
      width: 160,
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用角色"
            description={`确认停用角色「${record.name}」吗？`}
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
    <PageShell
      title="角色管理"
      description={`当前系统 ID：${systemId}。角色按系统隔离，同一系统内角色编码唯一；跨系统可出现同名角色编码。`}
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void rolesQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建角色
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              placeholder="按角色编码 / 名称搜索"
              style={{ width: 280 }}
              value={keywordInput}
              onChange={(event) => setKeywordInput(event.target.value)}
              onPressEnter={() => {
                setKeyword(keywordInput.trim())
                setPage(1)
              }}
            />
            <Button
              type="primary"
              onClick={() => {
                setKeyword(keywordInput.trim())
                setPage(1)
              }}
            >
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

          {rolesQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="角色列表加载失败"
              description={rolesQuery.error.message}
              action={
                <Button size="small" onClick={() => void rolesQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Table<RoleVO>
            rowKey="id"
            columns={columns}
            dataSource={rolesQuery.data?.items ?? []}
            loading={rolesQuery.isLoading || rolesQuery.isFetching}
            pagination={createPaginationConfig(page, size, rolesQuery.data?.total ?? 0, (nextPage, nextSize) => {
              setPage(nextPage)
              setSize(nextSize)
            })}
          />
        </Flex>
      </Card>

      <Modal
        title={editing ? `编辑角色：${editing.code}` : '新建角色'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={520}
      >
        <Form<RoleFormValues> form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
          <Form.Item
            label="角色编码"
            name="code"
            rules={[
              { required: true, message: '请输入角色编码' },
              { pattern: /^[A-Za-z0-9_:-]{2,64}$/, message: '角色编码为 2-64 位字母、数字、下划线、冒号或连字符' },
            ]}
            tooltip="同一系统内唯一，创建后不可修改"
          >
            <Input placeholder="如 admin" disabled={Boolean(editing)} maxLength={64} />
          </Form.Item>
          <Form.Item label="角色名称" name="name" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input placeholder="如 管理员" maxLength={100} />
          </Form.Item>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item label="状态" name="status" rules={[{ required: true, message: '请选择状态' }]} style={{ flex: 1 }}>
              <Select options={STATUS_OPTIONS} />
            </Form.Item>
            <Form.Item label="排序" name="sort" style={{ flex: 1 }}>
              <InputNumber min={0} max={9999} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item label="备注" name="remark">
            <Input.TextArea rows={3} maxLength={255} showCount placeholder="选填" />
          </Form.Item>
        </Form>
      </Modal>
    </PageShell>
  )
}
