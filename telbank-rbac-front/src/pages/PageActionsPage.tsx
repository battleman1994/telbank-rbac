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
import {
  createPageAction,
  disablePageAction,
  listPageActions,
  updatePageAction,
} from '../api'
import type { PageActionPayload, PageActionVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { createPaginationConfig } from '../utils/pagination'

/** 页面元素类型：只表达元素类别，不表达样式（primary/danger 等样式不再维护） */
const ELEMENT_TYPE_OPTIONS = [
  { value: 'button', label: '按钮' },
  { value: 'link', label: '链接' },
  { value: 'menu', label: '菜单' },
  { value: 'icon', label: '图标' },
  { value: 'input', label: '输入框' },
]

const ELEMENT_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  ELEMENT_TYPE_OPTIONS.map((option) => [option.value, option.label]),
)

const LEGACY_TYPE_MAP: Record<string, string> = {
  primary: 'button',
  danger: 'button',
  default: 'button',
  link: 'link',
}

function normalizeType(type: string | null | undefined): string {
  if (!type) return 'button'
  return LEGACY_TYPE_MAP[type] ?? type
}

interface PageActionFormValues {
  pageCode: string
  code: string
  text: string
  type: string
}

/** 页面元素元数据：只维护“哪个页面、哪个元素、名称、类型” */
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
      // action/config/sort 不再在页面维护，但接口与 SDK 契约保持兼容：
      // 新建给安全默认值，编辑保留原值，避免历史数据被清空。
      const payload: PageActionPayload = {
        systemId,
        pageCode: values.pageCode.trim(),
        code: values.code.trim(),
        text: values.text.trim(),
        type: values.type.trim() || 'button',
        action: (editing?.action ?? 'callApi') as PageActionPayload['action'],
        config: editing?.config ?? undefined,
        sort: editing?.sort ?? 0,
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
      type: 'button',
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
      type: normalizeType(record.type),
    })
    setModalOpen(true)
  }

  const columns: TableProps<PageActionVO>['columns'] = [
    {
      title: '页面编码',
      dataIndex: 'pageCode',
      width: 160,
      ellipsis: true,
      render: (value: string) => <Tag color="blue">{value}</Tag>,
    },
    {
      title: '元素标识',
      dataIndex: 'code',
      width: 180,
      ellipsis: true,
      render: (value: string) => <Tag color="geekblue">{value}</Tag>,
    },
    { title: '元素名称', dataIndex: 'text', width: 140 },
    {
      title: '元素类型',
      dataIndex: 'type',
      width: 100,
      render: (value: string) => (
        <Tag>{ELEMENT_TYPE_LABELS[normalizeType(value)] ?? value}</Tag>
      ),
    },
    { title: '状态', dataIndex: 'status', width: 80, render: (value: number) => <StatusTag status={value} /> },
    {
      title: '操作',
      key: 'action',
      width: 100,
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用页面元素"
            description={`确认停用「${record.text}」吗？`}
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
      description="维护页面元素标识：页面编码、元素标识、元素名称、类型（按钮/链接/菜单/图标/输入框）。不在这里维护样式、动作与 config；业务前端按需渲染。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void pageActionsQuery.refetch()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
            新建页面元素
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          <Space wrap>
            <Input
              allowClear
              placeholder="按页面编码精确筛选，如 order:list"
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
        title={editing ? `编辑页面元素：${editing.code}` : '新建页面元素'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={560}
      >
        <Form<PageActionFormValues>
          form={form}
          layout="vertical"
          onFinish={(values) => saveMutation.mutate(values)}
        >
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              label="页面编码"
              name="pageCode"
              rules={[{ required: true, message: '请输入页面编码' }]}
              style={{ flex: 1 }}
              tooltip="元素所属页面，如 order:list"
            >
              <Input placeholder="如 order:list" maxLength={128} />
            </Form.Item>
            <Form.Item
              label="元素标识"
              name="code"
              rules={[{ required: true, message: '请输入元素标识' }]}
              style={{ flex: 1 }}
              tooltip="页面内唯一，如 order:export"
            >
              <Input placeholder="如 order:export" maxLength={128} />
            </Form.Item>
          </Space>
          <Space size={16} style={{ display: 'flex' }}>
            <Form.Item
              label="元素名称"
              name="text"
              rules={[{ required: true, message: '请输入元素名称' }]}
              style={{ flex: 1 }}
            >
              <Input placeholder="如 导出" maxLength={64} />
            </Form.Item>
            <Form.Item label="元素类型" name="type" style={{ flex: 1 }}>
              <Select options={ELEMENT_TYPE_OPTIONS} placeholder="按钮" />
            </Form.Item>
          </Space>
        </Form>
      </Modal>
    </PageShell>
  )
}
