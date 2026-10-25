import { useEffect, useMemo, useState } from 'react'
import { ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { Dayjs } from 'dayjs'
import {
  Alert,
  App as AntApp,
  Button,
  Card,
  DatePicker,
  Divider,
  Empty,
  Flex,
  Input,
  Popconfirm,
  Select,
  Skeleton,
  Space,
  Tag,
  Tree,
  Typography,
} from 'antd'
import type { TreeDataNode } from 'antd'
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
import { formatValidity, toDayjs, toIsoString } from '../utils/format'

const { Text } = Typography

type ValidityRange = [Dayjs | null, Dayjs | null] | null

/**
 * 递归移除通配权限 `*` 节点（含其子树）：通配由系统默认规则管理，不参与逐项授权，
 * 也不出现在可授权树中；已存在的通配授权仍在右侧「已授权列表」只读展示。
 */
function filterWildcardTree(nodes: PermissionNode[]): PermissionNode[] {
  return nodes
    .filter((node) => node.code !== '*')
    .map((node) => ({
      ...node,
      children: node.children ? filterWildcardTree(node.children) : undefined,
    }))
}

function flattenTree(nodes: PermissionNode[]): PermissionNode[] {
  return nodes.flatMap((node) => [node, ...flattenTree(node.children ?? [])])
}

/** 通配权限行名称兜底（后端种子名称为「全部权限（通配）」） */
const WILDCARD_PERMISSION_NAME = '全部权限（通配）'

function permissionDisplayName(node: PermissionNode): string {
  if (node.code === '*') return node.name || WILDCARD_PERMISSION_NAME
  return node.name
}

/** 已授权记录 → 权限节点（通配 `*` 不在过滤后的权限树中，用授权记录构造只读展示节点） */
function resolveGrantPermission(
  grant: PermissionGrant,
  permissions: PermissionNode[],
  systemId: number,
): PermissionNode {
  return (
    permissions.find((node) => node.id === grant.permissionId) ?? {
      id: grant.permissionId,
      systemId,
      parentId: 0,
      type: 3,
      code: grant.code,
      name: grant.code === '*' ? WILDCARD_PERMISSION_NAME : grant.code,
      sort: 0,
      status: grant.status,
      isPublic: 0,
    }
  )
}

function buildTreeData(nodes: PermissionNode[]): TreeDataNode[] {
  return nodes.map((node) => ({
    key: String(node.id),
    title: (
      <Space size={6} wrap>
        <span>{permissionDisplayName(node)}</span>
        <Tag color={node.code === '*' ? 'gold' : 'blue'}>{node.code}</Tag>
        {node.method && node.apiPattern ? <Tag>{node.method}</Tag> : null}
        {node.status !== 1 ? <Tag color="red">已停用</Tag> : null}
      </Space>
    ),
    disableCheckbox: node.status !== 1,
    children: node.children && node.children.length > 0 ? buildTreeData(node.children) : undefined,
  }))
}

/** 角色-权限授权（契约 §3.5）：左侧操作（角色 + 权限树 + 逐项时效），右侧只读列表 */
export default function RolePermissionsPage() {
  const systemId = useCurrentSystemId()
  if (!systemId) {
    return (
      <SystemRequired
        title="角色授权"
        description="当前系统上下文未就绪，无法加载授权关系。"
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
  const [validityByPermission, setValidityByPermission] = useState<
    Record<number, ValidityRange>
  >({})
  const [grantReason, setGrantReason] = useState('')
  const [expandedKeys, setExpandedKeys] = useState<Key[]>([])

  // 超管/多系统管理员切换系统后，清空上一系统的角色选择、勾选与时效草稿，避免串系统
  useEffect(() => {
    setSelectedRoleId(null)
    setCheckedKeys([])
    setValidityByPermission({})
    setGrantReason('')
  }, [systemId])

  const rolesQuery = useQuery({
    queryKey: ['roles', systemId, { keyword: '', page: 1, size: 200 }],
    queryFn: () => listRoles({ systemId, page: 1, size: 200 }),
  })

  const permissionsQuery = useQuery({
    queryKey: ['permissions', systemId],
    queryFn: () => listPermissionTree(systemId),
  })

  // 仅当所选角色属于「当前系统」（出现在本系统角色列表中）时才允许查询授权关系，
  // 防止切换系统瞬间用上一系统的 roleId 发起跨系统请求。
  const selectedRoleBelongsToSystem = useMemo(
    () =>
      selectedRoleId !== null &&
      (rolesQuery.data?.items ?? []).some((role) => role.id === selectedRoleId),
    [selectedRoleId, rolesQuery.data],
  )

  const grantsQuery = useQuery({
    queryKey: ['role-permissions', systemId, selectedRoleId],
    queryFn: () => getRolePermissions(selectedRoleId as number),
    enabled: selectedRoleBelongsToSystem,
  })

  // 角色下拉过滤内置 admin 角色：admin 默认拥有全部权限（`*`），无需也不允许在此授权
  const roleOptions = useMemo(
    () =>
      (rolesQuery.data?.items ?? [])
        .filter((role) => role.status === 1 && role.code !== 'admin')
        .map((role: RoleVO) => ({
          value: role.id,
          label: `${role.name}（${role.code}）`,
        })),
    [rolesQuery.data],
  )

  // 兜底：若当前选中角色是 admin（历史状态/接口返回），清空选择并提示改用角色信息编辑
  useEffect(() => {
    if (selectedRoleId === null) return
    const selected = (rolesQuery.data?.items ?? []).find((role) => role.id === selectedRoleId)
    if (selected?.code === 'admin') {
      setSelectedRoleId(null)
      setCheckedKeys([])
      setValidityByPermission({})
      setGrantReason('')
    }
  }, [rolesQuery.data, selectedRoleId])

  // 可授权权限树：递归移除通配 `*` 节点；勾选/全选/展开均基于过滤后的树
  const permissionTree = useMemo(
    () => filterWildcardTree(permissionsQuery.data ?? []),
    [permissionsQuery.data],
  )

  const flatPermissions = useMemo(() => flattenTree(permissionTree), [permissionTree])

  useEffect(() => {
    if (!permissionsQuery.data) return
    setExpandedKeys(flattenTree(permissionTree).map((node) => String(node.id)))
  }, [permissionsQuery.data, permissionTree])

  // 勾选态 = 已授权态；时效草稿从每条授权记录分别回填，避免「统一时效」覆盖单条时效。
  // 通配 `*` 不在过滤后的授权树中，其授权记录只读展示，不进入勾选态。
  useEffect(() => {
    const grants = grantsQuery.data?.grants ?? []
    const active = grants.filter((grant) => grant.status === 1)
    const grantableIds = new Set(flatPermissions.map((node) => String(node.id)))
    const checked = active.filter((grant) => grantableIds.has(String(grant.permissionId)))
    setCheckedKeys(checked.map((grant) => String(grant.permissionId)))
    const next: Record<number, ValidityRange> = {}
    for (const grant of checked) {
      const from = toDayjs(grant.validFrom)
      const until = toDayjs(grant.validUntil)
      next[grant.permissionId] = from || until ? [from, until] : null
    }
    setValidityByPermission(next)
  }, [grantsQuery.data, flatPermissions])

  const checkedPermissions = useMemo(() => {
    const selected = new Set(checkedKeys.map((key) => String(key)))
    return flatPermissions.filter((node) => selected.has(String(node.id)))
  }, [checkedKeys, flatPermissions])

  const grants = grantsQuery.data?.grants ?? []
  const activeGrants = grants.filter((grant) => grant.status === 1)
  const hasWildcardGrant = activeGrants.some((grant) => grant.code === '*')
  const grantedIds = useMemo(() => new Set(activeGrants.map((grant) => grant.permissionId)), [activeGrants])

  function updateValidity(permissionId: number, range: ValidityRange) {
    setValidityByPermission((previous) => ({ ...previous, [permissionId]: range }))
  }

  function removeChecked(permissionId: number) {
    setCheckedKeys((keys) => keys.filter((key) => String(key) !== String(permissionId)))
    setValidityByPermission((previous) => {
      const next = { ...previous }
      delete next[permissionId]
      return next
    })
  }

  function handleCheck(checked: Key[] | { checked: Key[]; halfChecked: Key[] }) {
    const keys = Array.isArray(checked) ? checked : checked.checked
    setCheckedKeys(keys)
    setValidityByPermission((previous) => {
      const next = { ...previous }
      const selected = new Set(keys.map((key) => String(key)))
      for (const key of Object.keys(next)) {
        if (!selected.has(String(key))) delete next[Number(key)]
      }
      for (const key of keys) {
        const permissionId = Number(key)
        if (Number.isFinite(permissionId) && next[permissionId] === undefined) {
          next[permissionId] = null
        }
      }
      return next
    })
  }

  function selectAll() {
    const keys = flatPermissions
      .filter((node) => node.status === 1)
      .map((node) => String(node.id))
    setCheckedKeys(keys)
    setValidityByPermission((previous) => {
      const next = { ...previous }
      for (const key of keys) {
        const permissionId = Number(key)
        if (next[permissionId] === undefined) next[permissionId] = null
      }
      return next
    })
  }

  function clearAll() {
    setCheckedKeys([])
    setValidityByPermission({})
  }

  function handleRoleChange(roleId: number | undefined) {
    setSelectedRoleId(roleId ?? null)
    setCheckedKeys([])
    setValidityByPermission({})
    setGrantReason('')
  }

  const grantMutation = useMutation<number, Error, void>({
    mutationFn: async () => {
      if (!selectedRoleId) throw new Error('请先选择角色')
      const permissionIds = checkedKeys
        .map((key) => Number(key))
        .filter((id) => Number.isFinite(id))
      if (permissionIds.length === 0) throw new Error('请至少勾选一个权限')

      // 后端 grant 接口一次请求只带一个时效；按「相同生效/失效时间」分组提交，
      // 这样每个权限仍然保留自己的时效，同时减少请求数。
      const groups = new Map<
        string,
        { permissionIds: number[]; validFrom: string | null; validUntil: string | null }
      >()
      for (const permissionId of permissionIds) {
        const range = validityByPermission[permissionId] ?? null
        const validFrom = toIsoString(range?.[0] ?? null)
        const validUntil = toIsoString(range?.[1] ?? null)
        if (validFrom && validUntil && new Date(validUntil).getTime() < new Date(validFrom).getTime()) {
          throw new Error(`权限 #${permissionId} 的失效时间不能早于生效时间`)
        }
        const key = `${validFrom ?? ''}|${validUntil ?? ''}`
        const group = groups.get(key) ?? { permissionIds: [], validFrom, validUntil }
        group.permissionIds.push(permissionId)
        groups.set(key, group)
      }

      for (const group of groups.values()) {
        await grantRolePermissions(selectedRoleId, {
          permissionIds: group.permissionIds,
          validFrom: group.validFrom,
          validUntil: group.validUntil,
          grantReason: grantReason.trim() || undefined,
        })
      }
      return groups.size
    },
    onSuccess: (groupCount) => {
      void message.success(`授权已提交（${groupCount} 组时效）`)
      setGrantReason('')
      void queryClient.invalidateQueries({
        queryKey: ['role-permissions', systemId, selectedRoleId],
      })
    },
    onError: (error) => {
      void message.error(error.message)
    },
  })

  const revokeMutation = useMutation<void, Error, number[]>({
    mutationFn: async (permissionIds) => {
      if (!selectedRoleId) throw new Error('请先选择角色')
      if (permissionIds.length === 0) throw new Error('请至少勾选一个权限')
      await revokeRolePermissions(selectedRoleId, { permissionIds })
    },
    onSuccess: () => {
      void message.success('撤销授权已提交')
      setCheckedKeys([])
      void queryClient.invalidateQueries({
        queryKey: ['role-permissions', systemId, selectedRoleId],
      })
    },
    onError: (error) => {
      void message.error(error.message)
    },
  })

  return (
    <PageShell
      title="角色授权"
      description="左侧选择角色、勾选权限并逐项设置生效/失效时间；右侧查看该角色已授权项。提交为整体提交，但每条权限可独立设置时效。"
      extra={
        <Space>
          <Button
            icon={<ReloadOutlined />}
            onClick={() => void grantsQuery.refetch()}
            disabled={!selectedRoleId}
          >
            刷新授权
          </Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        title="系统管理员角色（admin）默认拥有全部权限，无需授权"
        description="系统管理员角色（admin）默认拥有全部权限，无需授权；如需调整请编辑角色信息（backend 会拒绝对 admin 角色的 grant/revoke 操作）。"
      />

      {hasWildcardGrant ? (
        <Alert
          type="info"
          showIcon
          title="该角色已拥有通配权限「*」（全部权限）"
          description="通配授权由系统默认规则管理，不在下方授权树中逐项展示；勾选/撤销其他权限码不会影响该通配授权。"
        />
      ) : null}

      <div className="grant-workspace">
        <Card
          title="授权操作"
          extra={<Text type="secondary">已勾选 {checkedKeys.length} 项</Text>}
        >
          <Flex vertical gap={12}>
            <div>
              <Text strong>选择角色</Text>
              <Select
                style={{ width: '100%', marginTop: 8 }}
                placeholder={rolesQuery.isLoading ? '角色加载中…' : '请选择角色'}
                value={selectedRoleId ?? undefined}
                options={roleOptions}
                onChange={handleRoleChange}
                showSearch
                optionFilterProp="label"
                loading={rolesQuery.isLoading}
                notFoundContent={
                  rolesQuery.isLoading
                    ? '加载中'
                    : '当前系统暂无可在此授权的启用角色（admin 角色默认全权限，已排除）'
                }
              />
              <Text type="secondary" style={{ fontSize: 12 }}>
                角色列表按当前系统隔离；admin 角色默认全权限，不在本页授权。
              </Text>
            </div>

            {rolesQuery.isError ? (
              <Alert
                type="error"
                showIcon
                title="角色列表加载失败"
                description={rolesQuery.error.message}
              />
            ) : null}

            {selectedRoleId === null ? (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="请先选择一个角色，再配置其权限授权"
              />
            ) : (
              <>
                <div>
                  <Flex justify="space-between" align="center" style={{ marginBottom: 8 }}>
                    <Space size={6}>
                      <Text strong>权限树</Text>
                      <Tag>{checkedKeys.length} 项已选</Tag>
                    </Space>
                    <Space size={4}>
                      <Button size="small" onClick={selectAll}>
                        全选
                      </Button>
                      <Button size="small" onClick={clearAll}>
                        清空
                      </Button>
                    </Space>
                  </Flex>
                  <div className="grant-tree-panel">
                    <Tree
                      checkable
                      checkStrictly
                      selectable={false}
                      treeData={buildTreeData(permissionTree)}
                      checkedKeys={checkedKeys}
                      expandedKeys={expandedKeys}
                      onExpand={(keys) => setExpandedKeys(keys)}
                      onCheck={handleCheck}
                    />
                  </div>
                  {permissionsQuery.isError ? (
                    <Alert
                      style={{ marginTop: 8 }}
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
                </div>

                <Divider style={{ margin: '4px 0' }}>本次授权清单</Divider>

                <div className="grant-selected-list">
                  {checkedPermissions.length === 0 ? (
                    <Text type="secondary">勾选权限树中的节点后，会在这里逐项设置时效</Text>
                  ) : (
                    checkedPermissions.map((node) => (
                      <div className="grant-selected-row" key={node.id}>
                        <Space size={4} wrap>
                          <Text strong={node.code === '*'}>{permissionDisplayName(node)}</Text>
                          <Tag
                            color={node.code === '*' ? 'gold' : grantedIds.has(node.id) ? 'green' : 'blue'}
                            title={grantedIds.has(node.id) ? '已授权' : undefined}
                          >
                            {node.code}
                          </Tag>
                        </Space>
                        <Space size={2} align="center">
                          <DatePicker.RangePicker
                            size="small"
                            showTime={{ format: 'HH:mm' }}
                            format="MM-DD HH:mm"
                            style={{ width: 248 }}
                            value={validityByPermission[node.id] ?? null}
                            onChange={(dates) =>
                              updateValidity(
                                node.id,
                                (dates as [Dayjs | null, Dayjs | null] | null) ?? null,
                              )
                            }
                            placeholder={['生效时间', '失效时间']}
                            allowEmpty={[true, true]}
                          />
                          <Button
                            type="text"
                            size="small"
                            danger
                            title="移出本次授权"
                            onClick={() => removeChecked(node.id)}
                          >
                            移出
                          </Button>
                        </Space>
                      </div>
                    ))
                  )}
                </div>

                <Flex gap={8} align="flex-end" wrap>
                  <div style={{ flex: '1 1 220px', minWidth: 220 }}>
                    <Text strong>授权原因</Text>
                    <Input
                      style={{ marginTop: 6 }}
                      placeholder="选填，所有本次提交的权限共用"
                      value={grantReason}
                      onChange={(event) => setGrantReason(event.target.value)}
                      maxLength={255}
                    />
                  </div>
                  <Button
                    type="primary"
                    loading={grantMutation.isPending}
                    disabled={checkedKeys.length === 0}
                    onClick={() => grantMutation.mutate()}
                  >
                    提交授权（{checkedKeys.length}）
                  </Button>
                  <Popconfirm
                    title="撤销勾选的权限授权"
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
                      撤销勾选（{checkedKeys.length}）
                    </Button>
                  </Popconfirm>
                </Flex>
              </>
            )}
          </Flex>
        </Card>

        <div className="grant-side-column">
        <Card
          title="已授权列表"
          extra={
            <Text type="secondary">
              共 {grants.length} 条 · 生效中 {activeGrants.length} 条
            </Text>
          }
        >
          {selectedRoleId === null ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="选择左侧角色后查看已授权项"
            />
          ) : grantsQuery.isLoading ? (
            <Skeleton active paragraph={{ rows: 5 }} />
          ) : grantsQuery.isError ? (
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
          ) : grants.length === 0 ? (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description="该角色暂无任何权限授权，可从左侧勾选后提交"
            />
          ) : (
            <div className="grant-grant-list">
              {grants.map((grant: PermissionGrant) => {
                const permission = resolveGrantPermission(grant, flatPermissions, systemId)
                return (
                  <div className="grant-grant-row" key={`${grant.permissionId}-${grant.status}`}>
                    <div className="grant-grant-main">
                      <Space size={6} wrap>
                        <Text strong={grant.code === '*'}>
                          {permission ? permissionDisplayName(permission) : grant.code}
                        </Text>
                        <Tag color={grant.code === '*' ? 'gold' : 'blue'}>
                          {grant.code === '*' ? '*（全部权限）' : grant.code}
                        </Tag>
                      </Space>
                      <div className="grant-grant-meta">
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          {formatValidity(grant.validFrom, grant.validUntil)}
                        </Text>
                      </div>
                    </div>
                    <Space size={12} align="center">
                      <StatusTag status={grant.status} />
                      <Popconfirm
                        title="撤销该权限授权"
                        description={`确认撤销权限「${grant.code}」吗？`}
                        okText="确认撤销"
                        cancelText="取消"
                        onConfirm={() => revokeMutation.mutate([grant.permissionId])}
                      >
                        <Button type="link" size="small" danger disabled={grant.status !== 1}>
                          撤销
                        </Button>
                      </Popconfirm>
                    </Space>
                  </div>
                )
              })}
            </div>
          )}
        </Card>
        <Card size="small" title="授权规则">
          <ul className="grant-rule-list">
            <li>勾选权限树节点后点击「提交授权」；未设置时间的权限为永久授权。</li>
            <li>每条权限可在左侧单独设置生效/失效时间，互不影响。</li>
            <li>取消勾选只移出本次提交，不会自动撤销；撤销请点右侧「撤销」或底部「撤销勾选」。</li>
            <li>右侧「撤销」单条即时生效；到期后权限查询与 SDK 会即时判定无权限。</li>
          </ul>
        </Card>
        </div>
      </div>
    </PageShell>
  )
}
