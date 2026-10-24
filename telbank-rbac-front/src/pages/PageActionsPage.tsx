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
import {
  createPageAction,
  disablePageAction,
  listPageActions,
  updatePageAction,
} from '../api'
import type { PageActionPayload, PageActionVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { PageActionTag, StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { formatJson } from '../utils/format'
import { PAGE_ACTION_OPTIONS } from '../utils/labels'
import { createPaginationConfig } from '../utils/pagination'

interface PageActionFormValues {
  pageCode: string
  code: string
  text: string
  type: string
  action: PageActionPayload['action']
  configText?: string
  sort: number
}

/** 页面操作元数据（模式 B，契约 §3.8）：页面按钮配置的 CRUD */
export default function PageActionsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="页面元素"
        description="当前系统上下文未就绪，无法加载页面元素配置。"
      />
    )
  }
  return <PageActionsContent systemId={systemId} />
}

function PageActionsContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [pageCodeInput, setPageCodeInput] = useState('')
  const [pageCode, setPageCode] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PageActionVO | null>(null)
  const [form] = Form.useForm<PageActionFormValues>()

  const pageActionsQuery = useQuery({
    queryKey: ['page-actions', systemId, { pageCode, page, size }],
    queryFn: () => listPageActions({ systemId, pageCode: pageCode || undefined, page, size }),
  })

  const saveMutation = useMutation({
    mutationFn: (values: PageActionFormValues) => {
      let config: unknown = undefined
      if (values.configText?.trim()) {
        config = JSON.parse(values.configText) as unknown
      }
      const payload: PageActionPayload = {
        systemId,
        pageCode: values.pageCode.trim(),
        code: values.code.trim(),
        text: values.text.trim(),
        type: values.type.trim() || 'default',
        action: values.action,
        config,
        sort: values.sort ?? 0,
      }
      if (editing) return updatePageAction(editing.id, payload)
      return createPageAction(payload)
    },
    onSuccess: () => {
      void message.success(editing ? '页面元素已更新' : '页面元素已创建')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['page-actions', systemId] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disablePageAction(id),
    onSuccess: () => {
      void message.success('页面元素已停用')
      void queryClient.invalidateQueries({ queryKey: ['page-actions', systemId] })
    },
  })

  function openCreateModal() {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({
      pageCode: pageCode || undefined,
      type: 'default',
      action: 'callApi',
      sort: 0,
    })
    setModalOpen(true)
  }

  function openEditModal(record: PageActionVO) {
    setEditing(record)
    form.resetFields()
    form.setFieldsValue({
      pageCode: record.pageCode,
      code: record.code,
      text: record.text,
      type: record.type,
      action: record.action as PageActionPayload['action'],
      configText: record.config == null ? undefined : formatJson(record.config, ''),
      sort: record.sort,
    })
    setModalOpen(true)
  }

  const columns: TableProps<PageActionVO>['columns'] = [
    { title: 'ID', dataIndex: 'id', width: 50 },
    {
      title: '页面编码',
      dataIndex: 'pageCode',
      width: 130,
      ellipsis: true,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    {
      title: '操作码',
      dataIndex: 'code',
      width: 130,
      ellipsis: true,
      render: (value: string) => <Tag color="geekblue">{value}</Tag>,
    },
    { title: '按钮文本', dataIndex: 'text', width: 90 },
    { title: '类型', dataIndex: 'type', width: 75, render: (value: string) => <Tag>{value}</Tag> },
    { title: '动作', dataIndex: 'action', width: 100, render: (value: string) => <PageActionTag action={value} /> },
    {
      title: 'config',
      dataIndex: 'config',
      width: 160,
      ellipsis: true,
      render: (value: unknown) => <span className="mono-text">{formatJson(value)}</span>,
    },
    { title: '排序', dataIndex: 'sort', width: 60 },
    { title: '状态', dataIndex: 'status', width: 70, render: (value: number) => <StatusTag status={value} /> },
    {
      title: '操作',
      key: 'action',
      width: 110,
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用页面操作"
            description={`确认停用「${record.text}」吗？停用后业务前端将不再渲染该按钮。`}
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
      title="页面元素"
      description="模式 B：标准 CRUD 操作区按钮由当前系统的 sys_page_action 配置驱动，业务前端通过 /rbac/me/bootstrap 的 pageActions 渲染。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void pageActionsQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建页面操作
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              placeholder="按 pageCode 精确筛选，如 order:list"
              style={{ width: 300 }}
              value={pageCodeInput}
              onChange={(event) => setPageCodeInput(event.target.value)}
              onPressEnter={() => {
                setPageCode(pageCodeInput.trim())
                setPage(1)
              }}
            />
            <Button
              type="primary"
              onClick={() => {
                setPageCode(pageCodeInput.trim())
                setPage(1)
              }}
            >
              筛选
            </Button>
            <Button
              onClick={() => {
                setPageCodeInput('')
                setPageCode('')
                setPage(1)
              }}
            >
              重置
            </Button>
          </Space>

          {pageActionsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="页面元素加载失败"
              description={pageActionsQuery.error.message}
              action={
                <Button size="small" onClick={() => void pageActionsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          <Table<PageActionVO>
            rowKey="id"
            columns={columns}
            dataSource={pageActionsQuery.data?.items ?? []}
            loading={pageActionsQuery.isLoading || pageActionsQuery.isFetching}
            pagination={createPaginationConfig(page, size, pageActionsQuery.data?.total ?? 0, (nextPage, nextSize) => {
              setPage(nextPage)
              setSize(nextSize)
            })}
          />
        </Flex>
      </Card>

      <Modal
        title={editing ? `编辑页面操作：${editing.code}` : '新建页面操作'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={620}
      >
        <Form<PageActionFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => {
            if (values.configText?.trim()) {
              try {
                JSON.parse(values.configText)
              } catch {
                form.setFields([{ name: 'configText', errors: ['config 必须是合法的 JSON'] }])
                return
              }
            }
            saveMutation.mutate(values)
          }}
        >
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              label="页面编码 pageCode"
              name="pageCode"
              rules={[{ required: true, message: '请输入 pageCode' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="如 order:list" maxLength={128} />
            </Form.Item>
            <Form.Item
              label="操作码 code"
              name="code"
              rules={[{ required: true, message: '请输入操作码' }]}
              style={{ flex: 1 }}
              tooltip="通常与权限码一致，如 order:export"
            >
              <Input placeholder="如 order:export" maxLength={128} />
            </Form.Item>
          </Space>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              label="按钮文本"
              name="text"
              rules={[{ required: true, message: '请输入按钮文本' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="如 导出" maxLength={64} />
            </Form.Item>
            <Form.Item label="按钮类型 type" name="type" style={{ flex: 1 }}>
              <Input placeholder="default" maxLength={32} />
            </Form.Item>
          </Space>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              label="动作 action"
              name="action"
              rules={[{ required: true, message: '请选择动作' }]}
              style={{ flex: 2 }}
            >
              <Select options={PAGE_ACTION_OPTIONS} />
            </Form.Item>
            <Form.Item label="排序" name="sort" style={{ flex: 1 }}>
              <InputNumber min={0} max={9999} style={{ width: '100%' }} />
            </Form.Item>
          </Space>
          <Form.Item
            label="config（JSON，可选）"
            name="configText"
            tooltip="动作所需的配置，如接口路径、弹窗参数等"
          >
            <Input.TextArea rows={4} className="mono-text" placeholder='如 {"api":"/api/order/export","method":"POST"}' />
          </Form.Item>
        </Form>
      </Modal>
    </PageShell>
  )
}
