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
import { createSystem, disableSystem, listSystems, updateSystem } from '../api'
import type { SysSystemVO, SystemCreatePayload, SystemUpdatePayload } from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { formatDateTime } from '../utils/format'
import { STATUS_OPTIONS } from '../utils/labels'
import { createPaginationConfig } from '../utils/pagination'

interface SystemFormValues {
  code: string
  name: string
  region: string
  status: number
  sort: number
  remark?: string
}

/** 系统管理（契约 §3.2）：分页查询、新建、编辑、停用 */
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
          name: values.name,
          region: values.region,
          status: values.status,
          sort: values.sort,
          remark: values.remark,
        }
        return updateSystem(editing.id, payload)
      }
      const payload: SystemCreatePayload = {
        code: values.code.trim(),
        name: values.name.trim(),
        region: values.region.trim(),
        status: values.status,
        sort: values.sort,
        remark: values.remark,
      }
      return createSystem(payload)
    },
    onSuccess: () => {
      void message.success(editing ? '系统信息已更新' : '系统已创建')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['systems'] })
      void queryClient.invalidateQueries({ queryKey: ['me', 'systems'] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disableSystem(id),
    onSuccess: () => {
      void message.success('系统已停用')
      void queryClient.invalidateQueries({ queryKey: ['systems'] })
      void queryClient.invalidateQueries({ queryKey: ['me', 'systems'] })
    },
  })

  function openCreateModal() {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ status: 1, sort: 0 })
    setModalOpen(true)
  }

  function openEditModal(record: SysSystemVO) {
    setEditing(record)
    form.resetFields()
    form.setFieldsValue({
      code: record.code,
      name: record.name,
      region: record.region,
      status: record.status,
      sort: record.sort,
      remark: record.remark ?? undefined,
    })
    setModalOpen(true)
  }

  function handleSearch() {
    setKeyword(keywordInput.trim())
    setPage(1)
  }

  const columns: TableProps<SysSystemVO>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 80 },
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
      width: 120,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    { title: '排序', dataIndex: 'sort', width: 80 },
    { title: '备注', dataIndex: 'remark', ellipsis: true, render: (value: string | null) => value || '-' },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 180,
      render: (value: string | null) => formatDateTime(value),
    },
    {
      title: '操作',
      key: 'action',
      width: 160,
      fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用系统"
            description={`确认停用系统「${record.name}」吗？停用后 SDK 与用户将无法继续使用该系统。`}
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
      title="系统管理"
      description="维护业务系统及其所属区域（region），系统编码全局唯一；停用仅置 status=0，不物理删除。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void systemsQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建系统
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
              title="系统列表加载失败"
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
            scroll={{ x: 1120 }}
            pagination={createPaginationConfig(page, size, systemsQuery.data?.total ?? 0, (nextPage, nextSize) => {
              setPage(nextPage)
              setSize(nextSize)
            })}
          />
        </Flex>
      </Card>

      <Modal
        title={editing ? `编辑系统：${editing.code}` : '新建系统'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={560}
      >
        <Form<SystemFormValues> form={form} layout="vertical" onFinish={(values) => saveMutation.mutate(values)}>
          <Form.Item
            label="系统编码"
            name="code"
            rules={[
              { required: true, message: '请输入系统编码' },
              { pattern: /^[A-Za-z0-9_-]{2,64}$/, message: '系统编码为 2-64 位字母、数字、下划线或连字符' },
            ]}
            tooltip="系统编码全局唯一，创建后不可修改；SDK 的 system-code 与此一致"
          >
            <Input placeholder="如 crm" disabled={Boolean(editing)} maxLength={64} />
          </Form.Item>
          <Form.Item label="系统名称" name="name" rules={[{ required: true, message: '请输入系统名称' }]}>
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
