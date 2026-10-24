import { useEffect, useMemo, useState } from 'react'
import { ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Dayjs } from 'dayjs'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Flex,
  Input,
  Popconfirm,
  Row,
  Select,
  Space,
  Table,
  Tag,
  Tree,
  Typography,
} from 'antd'
import type { TableProps, TreeDataNode } from 'antd'
import type { Key } from 'react'
import {
  getRolePermissions,
  grantRolePermissions,
  listPermissionTree,
  listRoles,
  revokeRolePermissions,
} from '../api'
import type { PermissionGrant, PermissionNode, RoleVO } from '../api/types'
import { PageShell } from '../components/PageShell'
import { StatusTag } from '../components/StatusTags'
import { SystemRequired } from '../components/SystemGate'
import { useCurrentSystemId } from '../stores/app'
import { formatValidity, toIsoString } from '../utils/format'

const { Text } = Typography

type RangeValue = [Dayjs | null, Dayjs | null] | null

function flattenTree(nodes: PermissionNode[]): PermissionNode[] {
  return nodes.flatMap((node) => [node, ...flattenTree(node.children ?? [])])
}

function buildTreeData(nodes: PermissionNode[]): TreeDataNode[] {
  return nodes.map((node) => ({
    key: String(node.id),
    title: (
      <Space size={6} wrap>
        <span>{node.name}</span>
        <Tag color="blue">{node.code}</Tag>
        {node.method && node.apiPattern ? <Tag>{node.method}</Tag> : null}
        {node.status !== 1 ? <Tag color="red">已停用</Tag> : null}
      </Space>
    ),
    disableCheckbox: node.status !== 1,
    children: node.children && node.children.length > 0 ? buildTreeData(node.children) : undefined,
  }))
}

/** 角色-权限授权（契约 §3.5）：角色选择 + 权限树勾选 + 时效 + grant/revoke */
export default function RolePermissionsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="角色-权限授权"
        description="授权关系按系统隔离，需先在顶部选择业务系统。"
      />
    )
  }
  return <RolePermissionsContent systemId={systemId} />
}

function RolePermissionsContent({ systemId }: { systemId: number }) {
  const { message } = AntApp.useApp()
  const queryClient = useQueryClient()
  const [selectedRoleId, setSelectedRoleId] = useState<number | null>(null)
  const [checkedKeys, setCheckedKeys] = useState<Key[]>([])
  const [validRange, setValidRange] = useState<RangeValue>(null)
  const [grantReason, setGrantReason] = useState('')
  const [expandedKeys, setExpandedKeys] = useState<Key[]>([])

  const rolesQuery = useQuery({
    queryKey: ['roles', systemId, { keyword: '', page: 1, size: 200 }],
    queryFn: () => listRoles({ systemId, page: 1, size: 200 }),
  })

  const permissionsQuery = useQuery({
    queryKey: ['permissions', systemId],
    queryFn: () => listPermissionTree(systemId),
  })

  const grantsQuery = useQuery({
    queryKey: ['role-permissions', systemId, selectedRoleId],
    queryFn: () => getRolePermissions(selectedRoleId as number),
    enabled: selectedRoleId !== null,
  })

  const roleOptions = useMemo(
    () =>
      (rolesQuery.data?.items ?? [])
        .filter((role) => role.status === 1)
        .map((role: RoleVO) => ({
          value: role.id,
          label: `${role.name}（${role.code}）`,
        })),
    [rolesQuery.data],
  )

  const flatPermissions = useMemo(
    () => flattenTree(permissionsQuery.data ?? []),
    [permissionsQuery.data],
  )

  useEffect(() => {
    if (!permissionsQuery.data) return
    setExpandedKeys(flattenTree(permissionsQuery.data).map((node) => String(node.id)))
  }, [permissionsQuery.data])

  useEffect(() => {
    const grants = grantsQuery.data?.grants ?? []
    setCheckedKeys(grants.filter((grant) => grant.status === 1).map((grant) => String(grant.permissionId)))
  }, [grantsQuery.data])

  const grantMutation = useMutation({
    mutationFn: () => {
      if (!selectedRoleId) return Promise.reject(new Error('请先选择角色'))
      if (checkedKeys.length === 0) return Promise.reject(new Error('请至少勾选一个权限'))
      const permissionIds = checkedKeys
        .map((key) => Number(key))
        .filter((id) => Number.isFinite(id))
      return grantRolePermissions(selectedRoleId, {
        permissionIds,
        validFrom: validRange ? toIsoString(validRange[0]) : null,
        validUntil: validRange ? toIsoString(validRange[1]) : null,
        grantReason: grantReason.trim() || undefined,
      })
    },
    onSuccess: () => {
      void message.success('授权已提交')
      setValidRange(null)
      setGrantReason('')
      void queryClient.invalidateQueries({ queryKey: ['role-permissions', systemId, selectedRoleId] })
    },
    onError: (error: Error) => {
      if (error.message === '请至少勾选一个权限' || error.message === '请先选择角色') {
        void message.warning(error.message)
      }
    },
  })

  const revokeMutation = useMutation({
    mutationFn: (permissionIds: number[]) => {
      if (!selectedRoleId) return Promise.reject(new Error('请先选择角色'))
      if (permissionIds.length === 0) return Promise.reject(new Error('请至少勾选一个权限'))
      return revokeRolePermissions(selectedRoleId, { permissionIds })
    },
    onSuccess: () => {
      void message.success('撤销授权已提交')
      setCheckedKeys([])
      void queryClient.invalidateQueries({ queryKey: ['role-permissions', systemId, selectedRoleId] })
    },
    onError: (error: Error) => {
      if (error.message === '请至少勾选一个权限' || error.message === '请先选择角色') {
        void message.warning(error.message)
      }
    },
  })

  function handleRoleChange(roleId: number | undefined) {
    setSelectedRoleId(roleId ?? null)
    setCheckedKeys([])
    setValidRange(null)
    setGrantReason('')
  }

  const grants = grantsQuery.data?.grants ?? []
  const activeGrants = grants.filter((grant) => grant.status === 1)

  const grantColumns: TableProps<PermissionGrant>['columns'] = [
    { title: '权限 ID', dataIndex: 'permissionId', width: 100 },
    { title: '权限码', dataIndex: 'code', width: 220, render: (value: string) => <Tag color="blue">{value}</Tag> },
    {
      title: '时效',
      key: 'validity',
      render: (_, record) => formatValidity(record.validFrom, record.validUntil),
    },
    { title: '状态', dataIndex: 'status', width: 90, render: (value: number) => <StatusTag status={value} /> },
    {
      title: '操作',
      key: 'action',
      width: 110,
      render: (_, record) => (
        <Popconfirm
          title="撤销该权限授权"
          description={`确认撤销权限「${record.code}」吗？`}
          okText="确认撤销"
          cancelText="取消"
          onConfirm={() => revokeMutation.mutate([record.permissionId])}
        >
          <Button type="link" size="small" danger disabled={record.status !== 1}>
            撤销
          </Button>
        </Popconfirm>
      ),
    },
  ]

  return (
    <PageShell
      title="角色-权限授权"
      description="按系统选择角色后勾选权限树节点，可设置生效/失效时间（不设置则为永久授权）；提交后服务端发布 Redis 权限变更通知。"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void grantsQuery.refetch()} disabled={!selectedRoleId}>
            刷新授权
          </Button>
          <Button icon={<SafetyCertificateOutlined />} disabled>
            已授权 {activeGrants.length} 项
          </Button>
        </Space>
      }
    >
      <Card>
        <Flex align="center" gap={16} wrap>
          <Text strong>选择角色</Text>
          <Select
            style={{ width: 320 }}
            placeholder={rolesQuery.isLoading ? '角色加载中…' : '请选择角色'}
            value={selectedRoleId ?? undefined}
            options={roleOptions}
            onChange={handleRoleChange}
            showSearch
            optionFilterProp="label"
            loading={rolesQuery.isLoading}
            notFoundContent={rolesQuery.isLoading ? '加载中' : '当前系统暂无启用角色'}
          />
          <Text type="secondary">
            角色列表按当前系统隔离；授权变更会失效 SDK 的用户权限集缓存。
          </Text>
        </Flex>
        {rolesQuery.isError ? (
          <Alert
            style={{ marginTop: 16 }}
            type="error"
            showIcon
            title="角色列表加载失败"
            description={rolesQuery.error.message}
          />
        ) : null}
      </Card>

      {selectedRoleId === null ? (
        <Card>
          <Empty description="请先选择一个角色，再配置其权限授权" />
        </Card>
      ) : (
        <>
          {permissionsQuery.isError ? (
            <Alert
              type="error"
              showIcon
              title="权限资源树加载失败"
              description={permissionsQuery.error.message}
              action={
                <Button size="small" onClick={() => void permissionsQuery.refetch()}>
                  重试
                </Button>
              }
            />
          ) : null}
          <Row gutter={16}>
            <Col xs={24} xl={14}>
              <Card
                title={`权限树（已勾选 ${checkedKeys.length} 项）`}
                extra={
                  <Space>
                    <Button size="small" onClick={() => setCheckedKeys(flatPermissions.filter((node) => node.status === 1).map((node) => String(node.id)))}>
                      全选
                    </Button>
                    <Button size="small" onClick={() => setCheckedKeys([])}>
                      清空
                    </Button>
                  </Space>
                }
              >
                <div className="grant-tree-panel">
                  <Tree
                    checkable
                    checkStrictly
                    selectable={false}
                    treeData={buildTreeData(permissionsQuery.data ?? [])}
                    checkedKeys={checkedKeys}
                    expandedKeys={expandedKeys}
                    onExpand={(keys) => setExpandedKeys(keys)}
                    onCheck={(checked) => {
                      const keys = Array.isArray(checked) ? checked : checked.checked
                      setCheckedKeys(keys)
                    }}
                  />
                </div>
              </Card>
            </Col>
            <Col xs={24} xl={10}>
              <Card title="授权设置">
                <Flex vertical gap={16}>
                  <div>
                    <Text strong>授权时效</Text>
                    <div style={{ marginTop: 8 }}>
                      <DatePicker.RangePicker
                        showTime
                        style={{ width: '100%' }}
                        value={validRange}
                        onChange={(dates) => setValidRange(dates as RangeValue)}
                        placeholder={['生效时间（空=立即生效）', '失效时间（空=永久有效）']}
                      />
                    </div>
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      不设置时间表示永久授权；到期后 SDK 与权限查询即时视为无权限。
                    </Text>
                  </div>
                  <div>
                    <Text strong>授权原因</Text>
                    <Input
                      style={{ marginTop: 8 }}
                      placeholder="选填，如：季度末批量清理订单"
                      value={grantReason}
                      onChange={(event) => setGrantReason(event.target.value)}
                      maxLength={255}
                    />
                  </div>
                  <Space>
                    <Button
                      type="primary"
                      loading={grantMutation.isPending}
                      onClick={() => grantMutation.mutate()}
                    >
                      提交授权
                    </Button>
                    <Popconfirm
                      title="撤销所选权限授权"
                      description={`确认撤销当前勾选的 ${checkedKeys.length} 项权限授权吗？`}
                      okText="确认撤销"
                      cancelText="取消"
                      onConfirm={() =>
                        revokeMutation.mutate(
                          checkedKeys.map((key) => Number(key)).filter((id) => Number.isFinite(id)),
                        )
                      }
                      disabled={checkedKeys.length === 0}
                    >
                      <Button danger disabled={checkedKeys.length === 0} loading={revokeMutation.isPending}>
                        撤销授权
                      </Button>
                    </Popconfirm>
                  </Space>
                </Flex>
              </Card>
            </Col>
          </Row>

          <Card
            title="当前角色已授权列表"
            extra={
              <Text type="secondary">
                共 {grants.length} 条，生效中 {activeGrants.length} 条
              </Text>
            }
          >
            {grantsQuery.isError ? (
              <Alert
                type="error"
                showIcon
                title="授权列表加载失败"
                description={grantsQuery.error.message}
                action={
                  <Button size="small" onClick={() => void grantsQuery.refetch()}>
                    重试
                  </Button>
                }
              />
            ) : (
              <Table<PermissionGrant>
                rowKey={(record) => `${record.permissionId}-${record.status}`}
                columns={grantColumns}
                dataSource={grants}
                loading={grantsQuery.isLoading || grantsQuery.isFetching}
                pagination={false}
                size="small"
                scroll={{ x: 800 }}
                locale={{ emptyText: '该角色暂无任何权限授权' }}
              />
            )}
          </Card>
        </>
      )}
    </PageShell>
  )
}
