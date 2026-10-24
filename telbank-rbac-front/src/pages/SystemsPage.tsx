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
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
} from 'antd'
import type { TableProps } from 'antd'
import { createSystem, disableSystem, listSystems, updateSystem } from '../api'
import type { SysSystemVO, SystemCreatePayload, SystemUpdatePayload } from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { STATUS_OPTIONS } from '../utils/labels'
import { createPaginationConfig } from '../utils/pagination'

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

/**
 * 系统接入（平台超管专属）：分页查询、新建、编辑、停用系统。
 * 新建/编辑表单必须录入「系统管理员账号」，后端会为该账号自动创建系统默认 admin 角色并授权。
 */
export default function SystemsPage() {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<SysSystemVO | null>(null)
  const [form] = Form.useForm<SystemFormValues>()

  const systemsQuery = useQuery({
    queryKey: ['systems', { keyword, page, size }],
    queryFn: () => listSystems({ keyword: keyword || undefined, page, size }),
  })

  const saveMutation = useMutation({
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
    onSuccess: () => {
      void message.success(editing ? '系统接入信息已更新' : '系统接入已创建')
      setModalOpen(false)
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
      width: 140,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    { title: '系统名称', dataIndex: 'name', width: 180 },
    {
      title: '区域',
      dataIndex: 'region',
      width: 130,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: '管理员账号',
      dataIndex: 'adminExternalUserId',
      width: 200,
      render: (value: string | null | undefined) =>
        value ? <Tag color="geekblue">{value}</Tag> : '-',
    },
    {
      title: '管理员姓名',
      dataIndex: 'adminName',
      width: 140,
      render: (value: string | null | undefined) => value || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: number) => <StatusTag status={value} />,
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_, record) => (
        <Space size={4}>
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
    <PageShell
      title="系统接入"
      description="平台超管专属：维护接入的业务系统及其所属区域（region），并为每个系统指定管理员账号（接入时自动创建系统默认 admin 角色并授权）；系统编码全局唯一，停用仅置 status=0。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void systemsQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建系统接入
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              placeholder="按系统编码 / 名称搜索"
              style={{ width: 280 }}
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
          />
        </Flex>
      </Card>

      <Modal
        title={editing ? `编辑系统接入：${editing.code}` : '新建系统接入'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={600}
      >
        <Form<SystemFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
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
            <Input placeholder="如 crm" disabled={Boolean(editing)} maxLength={64} />
          </Form.Item>
          <Form.Item
            label="系统名称"
            name="name"
            rules={[{ required: true, message: '请输入系统名称' }]}
          >
            <Input placeholder="如 CRM系统" maxLength={100} />
          </Form.Item>
          <Form.Item
            label="所属区域"
            name="region"
            rules={[{ required: true, message: '请输入所属区域' }]}
            tooltip="区域用于路由 Redis 变更通知频道：rbac:change:{region}:{systemCode}"
          >
            <Input placeholder="如 region_a" maxLength={32} />
          </Form.Item>
          <Form.Item
            label="系统管理员账号"
            name="adminExternalUserId"
            rules={[{ required: true, message: '请输入系统管理员账号' }]}
            tooltip="接入时后端会为该账号自动创建本系统默认 admin 角色并授权，账号来自外部认证系统（external_user_id）"
          >
            <Input placeholder="如 crm-admin-001" maxLength={255} />
          </Form.Item>
          <Form.Item label="管理员姓名（可选）" name="adminName">
            <Input placeholder="如 张三" maxLength={100} />
          </Form.Item>
          <Form.Item label="状态" name="status" rules={[{ required: true, message: '请选择状态' }]}>
            <Select options={STATUS_OPTIONS} />
          </Form.Item>
        </Form>
      </Modal>
    </PageShell>
  )
}
