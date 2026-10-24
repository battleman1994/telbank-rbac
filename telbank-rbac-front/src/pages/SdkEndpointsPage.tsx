import { useEffect, useMemo, useState } from 'react'
import { LinkOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Descriptions,
  Flex,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Col,
  Popconfirm,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  TreeSelect,
  Typography,
} from 'antd'
import type { TableProps } from 'antd'
import {
  createPermission,
  linkSdkEndpoint,
  listPermissionTree,
  listSdkEndpoints,
  unlinkSdkEndpoint,
} from '../api'
import type { PermissionNode, PermissionSavePayload, SdkEndpointVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { formatDateTime } from '../utils/format'
import { PERMISSION_TYPE_OPTIONS, YES_NO_OPTIONS } from '../utils/labels'
import { createPaginationConfig } from '../utils/pagination'

interface LinkFormValues {
  permissionId: number
}

interface CreatePermissionFormValues {
  code: string
  name: string
  type: PermissionSavePayload['type']
  isPublic: number
  sort: number
}

interface PermissionSelectNode {
  title: string
  value: number
  children?: PermissionSelectNode[]
}

function buildPermissionSelectTree(nodes: PermissionNode[]): PermissionSelectNode[] {
  return nodes.map((node) => {
    const children = buildPermissionSelectTree(node.children ?? [])
    return {
      title: `${node.name}（${node.code}）`,
      value: node.id,
      children: children.length > 0 ? children : undefined,
    }
  })
}

/** SDK 端点上报列表（契约 §3.9）：查看上报端点、关联/新建权限码、解除关联 */
export default function SdkEndpointsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="资源管理"
        description="当前系统上下文未就绪，无法加载接口资源。"
      />
    )
  }
  return <SdkEndpointsContent systemId={systemId} />
}

function SdkEndpointsContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(20)
  const [linkTarget, setLinkTarget] = useState<SdkEndpointVO | null>(null)
  const [linkForm] = Form.useForm<LinkFormValues>()
  const [createForm] = Form.useForm<CreatePermissionFormValues>()

  // 系统切换后关闭关联弹窗并清空表单，避免旧系统的端点/权限码跨系统操作
  useEffect(() => {
    setLinkTarget(null)
    linkForm.resetFields()
    createForm.resetFields()
  }, [systemId, linkForm, createForm])

  const endpointsQuery = useQuery({
    queryKey: ['sdk-endpoints', systemId, { keyword, page, size }],
    queryFn: () => listSdkEndpoints({ systemId, keyword: keyword || undefined, page, size }),
  })

  const permissionsQuery = useQuery({
    queryKey: ['permissions', systemId],
    queryFn: () => listPermissionTree(systemId),
    enabled: linkTarget !== null,
  })

  const permissionTreeData = useMemo(
    () => buildPermissionSelectTree(permissionsQuery.data ?? []),
    [permissionsQuery.data],
  )

  const linkMutation = useMutation({
    mutationFn: (values: LinkFormValues) => {
      if (!linkTarget) return Promise.reject(new Error('未选择端点'))
      return linkSdkEndpoint(linkTarget.id, values.permissionId)
    },
    onSuccess: () => {
      void message.success('端点已关联权限码')
      setLinkTarget(null)
      linkForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['sdk-endpoints', systemId] })
      void queryClient.invalidateQueries({ queryKey: ['permissions', systemId] })
    },
  })

  const createAndLinkMutation = useMutation({
    mutationFn: async (values: CreatePermissionFormValues) => {
      if (!linkTarget) throw new Error('未选择端点')
      const payload: PermissionSavePayload = {
        systemId,
        parentId: 0,
        type: values.type,
        code: values.code.trim(),
        name: values.name.trim(),
        method: linkTarget.method,
        apiPattern: linkTarget.path,
        sort: values.sort ?? 0,
        status: 1,
        isPublic: values.isPublic,
      }
      const permission = await createPermission(payload)
      await linkSdkEndpoint(linkTarget.id, permission.id)
      return permission
    },
    onSuccess: () => {
      void message.success('权限码已创建并关联到端点')
      setLinkTarget(null)
      createForm.resetFields()
      void queryClient.invalidateQueries({ queryKey: ['sdk-endpoints', systemId] })
      void queryClient.invalidateQueries({ queryKey: ['permissions', systemId] })
    },
  })

  const unlinkMutation = useMutation({
    mutationFn: (id: number) => unlinkSdkEndpoint(id),
    onSuccess: () => {
      void message.success('端点已解除关联')
      void queryClient.invalidateQueries({ queryKey: ['sdk-endpoints', systemId] })
      void queryClient.invalidateQueries({ queryKey: ['permissions', systemId] })
    },
  })

  function openLinkModal(record: SdkEndpointVO) {
    setLinkTarget(record)
    linkForm.resetFields()
    createForm.resetFields()
    createForm.setFieldsValue({
      code: record.candidateCode ?? '',
      name: record.candidateCode ?? '',
      type: 3,
      isPublic: 0,
      sort: 0,
    })
  }

  const columns: TableProps<SdkEndpointVO>['columns'] = [
    {
      title: '方法',
      dataIndex: 'method',
      width: 85,
      render: (value: string) => <Tag color="purple">{value}</Tag>,
    },
    {
      title: '路径 path',
      dataIndex: 'path',
      width: 140,
      ellipsis: true,
      render: (value: string) => (
        <span className="mono-text" title={value}>
          {value}
        </span>
      ),
    },
    {
      title: '权限码',
      dataIndex: 'permissionCode',
      width: 125,
      render: (value: string | null) =>
        value ? <Tag color="green">{value}</Tag> : <Tag color="default">未关联</Tag>,
    },
    {
      title: '来源',
      dataIndex: 'source',
      width: 95,
      render: (value: string) => (
        <Tag color={value === 'ANNOTATION' ? 'gold' : 'cyan'} title={value}>
          {value === 'ANNOTATION' ? '注解' : '自动'}
        </Tag>
      ),
    },
    {
      title: '生效状态',
      key: 'effective',
      width: 90,
      render: (_, record) =>
        record.permissionId ? (
          <Tag color="green" title="已确认权限码并写入 mappings；若该权限后续停用，映射会停止下发">
            已生效
          </Tag>
        ) : (
          <Tag color="default" title="未关联权限码，不会写入 mappings；SDK 对未命中映射的请求默认放行">
            未生效
          </Tag>
        ),
    },
    {
      title: '提示',
      key: 'hint',
      width: 280,
      ellipsis: true,
      render: (_, record) =>
        record.permissionId ? (
          <Typography.Text type="secondary">已确认，已生效</Typography.Text>
        ) : (
          <span>
            <Typography.Text type="secondary">确认后生效，候选：</Typography.Text>
            {record.candidateCode ? (
              <Tag color="blue">{record.candidateCode}</Tag>
            ) : (
              <Typography.Text type="secondary">无</Typography.Text>
            )}
          </span>
        ),
    },
    {
      title: '上报时间',
      dataIndex: 'reportedAt',
      width: 155,
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
      width: 135,
      render: (_, record) => (
        <Space size={4}>
          <Button type="link" size="small" onClick={() => openLinkModal(record)}>
            {record.permissionId ? '改关联' : '关联'}
          </Button>
          <Popconfirm
            title="解除端点关联"
            description="解除后该端点不再关联权限码、不会进入 SDK mappings，默认放行；权限码本身的 method/api_pattern 不会被修改。"
            okText="确认解除"
            cancelText="取消"
            disabled={!record.permissionId}
            onConfirm={() => unlinkMutation.mutate(record.id)}
          >
            <Button type="link" size="small" danger disabled={!record.permissionId}>
              解除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ]

  return (
    <PageShell
      title="资源管理"
      description="接口资源管理：SDK 启动时全量上报受控端点。未关联权限码的端点标记为「未生效」，不会写入 SDK mappings、默认放行；关联或新建权限码后才按对应权限拦截。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void endpointsQuery.refetch()}>
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
              placeholder="按路径 / 候选权限码 / 方法搜索"
              style={{ width: 320 }}
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

          {endpointsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="资源列表加载失败"
              description={endpointsQuery.error.message}
              action={
                <Button size="small" onClick={() => void endpointsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}

          {(() => {
            const unlinkCount = (endpointsQuery.data?.items ?? []).filter((item) => !item.permissionId).length
            if (unlinkCount === 0) return null
            return (
              <Alert
                type="warning"
                showIcon
                title={
                  <span>
                    当前页有{' '}
                    <strong style={{ color: '#d46b08', fontSize: '1.35em', lineHeight: 1 }}>
                      {unlinkCount}
                    </strong>{' '}
                    个端点未生效
                  </span>
                }
                description="未关联权限码的端点不会写入 SDK mappings，SDK 对未命中映射的请求默认放行。点击行尾「关联权限码」后才会按权限拦截。"
              />
            )
          })()}

          <Table<SdkEndpointVO>
            rowKey="id"
            columns={columns}
            rowClassName={(record) => (record.permissionId ? '' : 'endpoint-row-unlinked')}
            dataSource={endpointsQuery.data?.items ?? []}
            loading={endpointsQuery.isLoading || endpointsQuery.isFetching}
            pagination={createPaginationConfig(page, size, endpointsQuery.data?.total ?? 0, (nextPage, nextSize) => {
              setPage(nextPage)
              setSize(nextSize)
            })}
          />
        </Flex>
      </Card>

      <Modal
        title={linkTarget ? `关联权限码：${linkTarget.method} ${linkTarget.path}` : '关联权限码'}
        open={linkTarget !== null}
        forceRender
        onCancel={() => setLinkTarget(null)}
        footer={null}
        width={720}
      >
        {linkTarget ? (
          <Flex vertical gap={16}>
            <Descriptions
              size="small"
              column={1}
              items={[
                { key: 'method', label: 'HTTP 方法', children: <Tag color="purple">{linkTarget.method}</Tag> },
                { key: 'path', label: '路径', children: <span className="mono-text">{linkTarget.path}</span> },
                {
                  key: 'candidateCode',
                  label: '候选权限码',
                  children: linkTarget.candidateCode ? <Tag color="blue">{linkTarget.candidateCode}</Tag> : '无',
                },
              ]}
            />
            <Tabs
              items={[
                {
                  key: 'existing',
                  label: '关联已有权限码',
                  forceRender: true,
                  children: (
                    <Form<LinkFormValues>
                      form={linkForm}
                      layout="vertical"
                      onFinish={(values) => linkMutation.mutate(values)}
                    >
                      <Form.Item
                        label="选择权限码"
                        name="permissionId"
                        rules={[{ required: true, message: '请选择要关联的权限码' }]}
                        tooltip="关联后服务端会把该端点的 method/path 写入所选权限码"
                      >
                        <TreeSelect
                          treeData={permissionTreeData}
                          treeDefaultExpandAll
                          showSearch
                          treeNodeFilterProp="title"
                          placeholder={permissionsQuery.isLoading ? '权限树加载中…' : '请选择权限码'}
                          loading={permissionsQuery.isLoading}
                        />
                      </Form.Item>
                      <Button
                        type="primary"
                        htmlType="submit"
                        icon={<LinkOutlined />}
                        loading={linkMutation.isPending}
                      >
                        确认关联
                      </Button>
                    </Form>
                  ),
                },
                {
                  key: 'create',
                  label: '新建权限码并关联',
                  forceRender: true,
                  children: (
                    <Form<CreatePermissionFormValues>
                      form={createForm}
                      layout="vertical"
                      onFinish={(values) => createAndLinkMutation.mutate(values)}
                    >
                      <Form.Item
                        label="权限码"
                        name="code"
                        rules={[
                          { required: true, message: '请输入权限码' },
                          {
                            pattern: /^[A-Za-z0-9_:\-.]{1,128}$/,
                            message: '权限码为 1-128 位字母、数字、下划线、冒号、连字符或点',
                          },
                        ]}
                      >
                        <Input placeholder="如 order:delete" maxLength={128} />
                      </Form.Item>
                      <Form.Item label="名称" name="name" rules={[{ required: true, message: '请输入名称' }]}>
                        <Input placeholder="如 删除订单" maxLength={100} />
                      </Form.Item>
                      <Row gutter={16}>
                        <Col span={8}>
                          <Form.Item label="资源类型" name="type">
                            <Select options={PERMISSION_TYPE_OPTIONS} />
                          </Form.Item>
                        </Col>
                        <Col span={8}>
                          <Form.Item label="是否公开" name="isPublic">
                            <Select options={YES_NO_OPTIONS} />
                          </Form.Item>
                        </Col>
                        <Col span={8}>
                          <Form.Item label="排序" name="sort">
                            <InputNumber min={0} max={9999} style={{ width: '100%' }} />
                          </Form.Item>
                        </Col>
                      </Row>
                      <Alert
                        type="info"
                        showIcon
                        style={{ marginBottom: 16 }}
                        title={`创建后自动写入 method=${linkTarget.method}、api_pattern=${linkTarget.path} 并关联当前端点`}
                      />
                      <Button type="primary" htmlType="submit" loading={createAndLinkMutation.isPending}>
                        创建并关联
                      </Button>
                    </Form>
                  ),
                },
              ]}
            />
          </Flex>
        ) : null}
      </Modal>
    </PageShell>
  )
}
