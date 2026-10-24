import { useEffect, useMemo, useState } from 'react'
import type { Key } from 'react'
import { DownOutlined, PlusOutlined, ReloadOutlined, UpOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  TreeSelect,
} from 'antd'
import type { TableProps } from 'antd'
import {
  createPermission,
  disablePermission,
  listPermissionTree,
  updatePermission,
} from '../api'
import type { PermissionNode, PermissionSavePayload, PermissionType } from '../api/types'
import { PageShell } from '../components/PageShell'
import { PermissionTypeTag, StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { validateAntPattern, validateMethodAndPattern } from '../utils/antPattern'
import { formatJson } from '../utils/format'
import {
  HTTP_METHOD_OPTIONS,
  PERMISSION_TYPE_OPTIONS,
  STATUS_OPTIONS,
  YES_NO_OPTIONS,
} from '../utils/labels'

interface PermissionFormValues {
  parentId?: number
  type: PermissionType
  code: string
  name: string
  method?: string
  apiPattern?: string
  sort: number
  status: number
  isPublic: number
  metaText?: string
}

/** 权限资源管理（契约 §3.4）：树形表格 + 新建/编辑（type/isPublic/method/apiPattern） */
export default function PermissionsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return <SystemRequired title="权限管理" description="当前系统上下文未就绪，无法加载权限码。" />
  }
  return <PermissionsContent systemId={systemId} />
}

interface ParentTreeOption {
  title: string
  value: number
  children?: ParentTreeOption[]
}

function flattenTree(nodes: PermissionNode[]): PermissionNode[] {
  return nodes.flatMap((node) => [node, ...flattenTree(node.children ?? [])])
}

function collectDescendantIds(node: PermissionNode): number[] {
  const children = node.children ?? []
  return children.flatMap((child) => [child.id, ...collectDescendantIds(child)])
}

const WILDCARD_PERMISSION_NAME = '全部权限（通配）'

function permissionDisplayName(node: PermissionNode): string {
  if (node.code === '*') return node.name || WILDCARD_PERMISSION_NAME
  return node.name
}

function PermissionsContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<PermissionNode | null>(null)
  const [form] = Form.useForm<PermissionFormValues>()
  const [expandedRowKeys, setExpandedRowKeys] = useState<Key[]>([])
  const [expandVersion, setExpandVersion] = useState(0)

  const permissionsQuery = useQuery({
    queryKey: ['permissions', systemId],
    queryFn: () => listPermissionTree(systemId),
  })

  const treeData = permissionsQuery.data ?? []
  const flatNodes = useMemo(() => flattenTree(treeData), [treeData])

  useEffect(() => {
    if (!permissionsQuery.data) return
    setExpandedRowKeys(flattenTree(permissionsQuery.data).map((node) => node.id))
    setExpandVersion((version) => version + 1)
  }, [permissionsQuery.data])

  const saveMutation = useMutation({
    mutationFn: (payload: PermissionSavePayload) => {
      if (editing) return updatePermission(editing.id, payload)
      return createPermission(payload)
    },
    onSuccess: () => {
      void message.success(editing ? '权限码已更新' : '权限码已创建')
      setModalOpen(false)
      void queryClient.invalidateQueries({ queryKey: ['permissions', systemId] })
    },
  })

  const disableMutation = useMutation({
    mutationFn: (id: number) => disablePermission(id),
    onSuccess: () => {
      void message.success('权限码已停用')
      void queryClient.invalidateQueries({ queryKey: ['permissions', systemId] })
    },
  })

  const parentTreeData = useMemo(() => {
    const excludedIds = new Set(editing ? [editing.id, ...collectDescendantIds(editing)] : [])
    function build(nodes: PermissionNode[]): ParentTreeOption[] {
      return nodes
        .filter((node) => !excludedIds.has(node.id))
        .filter((node) => node.code !== '*')
        .map((node) => {
          const children = build(node.children ?? [])
          return {
            title: `${node.name}（${node.code}）`,
            value: node.id,
            children: children.length > 0 ? children : undefined,
          }
        })
    }
    return build(treeData)
  }, [treeData, editing])

  function openCreateModal(parent?: PermissionNode) {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({
      parentId: parent?.id ?? undefined,
      type: parent ? 2 : 1,
      sort: 0,
      status: 1,
      isPublic: 0,
    })
    setModalOpen(true)
  }

  function openEditModal(record: PermissionNode) {
    setEditing(record)
    form.resetFields()
    form.setFieldsValue({
      parentId: record.parentId > 0 ? record.parentId : undefined,
      type: record.type,
      code: record.code,
      name: record.name,
      method: record.method ?? undefined,
      apiPattern: record.apiPattern ?? undefined,
      sort: record.sort,
      status: record.status,
      isPublic: record.isPublic,
      metaText: record.meta == null ? undefined : formatJson(record.meta, ''),
    })
    setModalOpen(true)
  }

  function handleFinish(values: PermissionFormValues) {
    const method = values.method?.trim() ? values.method.trim() : null
    const apiPattern = values.apiPattern?.trim() ? values.apiPattern.trim() : null
    const patternError = validateMethodAndPattern(method, apiPattern)
    if (patternError) {
      form.setFields([{ name: 'apiPattern', errors: [patternError] }])
      return
    }
    let meta: unknown = undefined
    if (values.metaText?.trim()) {
      try {
        meta = JSON.parse(values.metaText)
      } catch {
        form.setFields([{ name: 'metaText', errors: ['meta 必须是合法的 JSON 字符串'] }])
        return
      }
    }
    const payload: PermissionSavePayload = {
      systemId,
      parentId: values.parentId ?? 0,
      type: values.type,
      code: values.code.trim(),
      name: values.name.trim(),
      method,
      apiPattern,
      sort: values.sort ?? 0,
      status: values.status,
      isPublic: values.isPublic,
      meta,
    }
    saveMutation.mutate(payload)
  }

  const columns: TableProps<PermissionNode>['columns'] = [
    {
      title: '名称',
      dataIndex: 'name',
      width: 240,
      render: (_, record) => (
        <Space size={6}>
          <span>{permissionDisplayName(record)}</span>
          {record.isPublic === 1 ? <Tag color="green">公开</Tag> : null}
          {record.status === 0 ? <Tag color="red">已停用</Tag> : null}
        </Space>
      ),
    },
    {
      title: '权限码',
      dataIndex: 'code',
      width: 210,
      render: (value: string) =>
        value === '*' ? (
          <Tag color="gold">*（全部权限）</Tag>
        ) : (
          <Tag color="blue">{value}</Tag>
        ),
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 110,
      render: (value: PermissionType) => <PermissionTypeTag type={value} />,
    },
    {
      title: '方法',
      dataIndex: 'method',
      width: 90,
      render: (value: string | null) => (value ? <Tag>{value}</Tag> : '-'),
    },
    {
      title: 'API 路径（Ant 风格）',
      dataIndex: 'apiPattern',
      width: 230,
      render: (value: string | null) =>
        value ? <span className="mono-text">{value}</span> : '-',
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    { title: '排序', dataIndex: 'sort', width: 80 },
    {
      title: '操作',
      key: 'action',
      width: 210,
      render: (_, record) => (
        <Space size={2} wrap>
          <Button type="link" size="small" onClick={() => openCreateModal(record)}>
            新增子级
          </Button>
          <Button type="link" size="small" onClick={() => openEditModal(record)}>
            编辑
          </Button>
          <Popconfirm
            title="停用权限资源"
            description={`确认停用「${record.name}」吗？存在子节点时后端会拒绝停用。`}
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
      title="权限管理"
      description="维护当前系统内的权限码（菜单/按钮/接口共用唯一能力标识）；method 与 api_pattern 同时填写后才会进入 SDK 映射表做后端拦截。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void permissionsQuery.refetch()}>
            刷新
          </Button>
          <Button
            icon={<DownOutlined />}
            onClick={() => setExpandedRowKeys(flatNodes.map((node) => node.id))}
          >
            展开全部
          </Button>
          <Button icon={<UpOutlined />} onClick={() => setExpandedRowKeys([])}>
            收起全部
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreateModal()}>
            新建根权限
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex vertical gap={16}>
          {permissionsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="权限树加载失败"
              description={permissionsQuery.error.message}
              action={
                <Button size="small" onClick={() => void permissionsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}
          <Table<PermissionNode>
            key={`permission-table-${expandVersion}`}
            rowKey="id"
            columns={columns}
            dataSource={treeData}
            loading={permissionsQuery.isLoading || permissionsQuery.isFetching}
            pagination={false}
            expandable={{
              expandedRowKeys,
              onExpandedRowsChange: (keys) => setExpandedRowKeys([...keys]),
            }}
          />
        </Flex>
      </Card>

      <Modal
        title={editing ? `编辑权限资源：${editing.code}` : '新建权限资源'}
        open={modalOpen}
        forceRender
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={saveMutation.isPending}
        okText="保存"
        cancelText="取消"
        width={680}
      >
        <Form<PermissionFormValues> form={form} layout="vertical" onFinish={handleFinish}>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item label="上级节点" name="parentId" tooltip="不选择表示根节点">
                <TreeSelect
                  allowClear
                  placeholder="不选表示根节点"
                  treeData={parentTreeData}
                  treeDefaultExpandAll
                  showSearch
                  treeNodeFilterProp="title"
                />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="资源类型"
                name="type"
                rules={[{ required: true, message: '请选择资源类型' }]}
              >
                <Select options={PERMISSION_TYPE_OPTIONS} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={12}>
              <Form.Item
                label="权限码"
                name="code"
                rules={[
                  { required: true, message: '请输入权限码' },
                  {
                    validator: (_, value: string | undefined) => {
                      const trimmed = value?.trim()
                      // 通配权限行允许固定值 `*`
                      if (trimmed === '*') return Promise.resolve()
                      if (/^[A-Za-z0-9_:\-.]{1,128}$/.test(trimmed ?? '')) {
                        return Promise.resolve()
                      }
                      return Promise.reject(
                        new Error(
                          '权限码为 1-128 位字母、数字、下划线、冒号、连字符或点（如 order:delete）',
                        ),
                      )
                    },
                  },
                ]}
                tooltip="系统内唯一，建议采用 模块:资源:操作 格式"
              >
                <Input placeholder="如 order:delete" maxLength={128} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item
                label="名称"
                name="name"
                rules={[{ required: true, message: '请输入名称' }]}
              >
                <Input placeholder="如 删除订单" maxLength={100} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="HTTP 方法" name="method" tooltip="与 API 路径必须同时填写或同时留空">
                <Select allowClear placeholder="不填" options={HTTP_METHOD_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={16}>
              <Form.Item
                label="API 路径（Ant 风格）"
                name="apiPattern"
                rules={[
                  {
                    validator: (_, value: string | undefined) => {
                      if (!value?.trim()) return Promise.resolve()
                      const error = validateAntPattern(value)
                      return error ? Promise.reject(new Error(error)) : Promise.resolve()
                    },
                  },
                ]}
                tooltip="* 匹配单段，** 只能出现在末尾；如 /api/order/{id}、/api/order/**"
              >
                <Input className="mono-text" placeholder="如 /api/order/{id} 或 /api/order/**" maxLength={255} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={16}>
            <Col span={8}>
              <Form.Item label="是否公开" name="isPublic" tooltip="is_public=1 时所有登录用户默认拥有该权限">
                <Select options={YES_NO_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="状态" name="status" rules={[{ required: true, message: '请选择状态' }]}>
                <Select options={STATUS_OPTIONS} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item label="排序" name="sort">
                <InputNumber min={0} max={9999} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item
            label="meta（JSON，可选）"
            name="metaText"
            tooltip="附加元数据，保存时按 JSON 校验"
          >
            <Input.TextArea rows={4} className="mono-text" placeholder='如 {"level":"page"}' />
          </Form.Item>
        </Form>
      </Modal>
    </PageShell>
  )
}
