/**
 * 规则链文档模型：负责画布数据 <-> JsonChainLoader 认可的 JSON 之间互转。
 *
 * 画布节点统一为 type='rule' 的自定义节点，业务信息全部放在 data：
 *   { nodeType, title, category, description, props, relations, customRelations, extraRelations, entry }
 * 画布连线把关系名放在 data.relation 与 sourceHandle 上，导出时才转成后端 edges 结构。
 */
import { MarkerType } from '@vue-flow/core'
import { displayTitle, outputRelations, relationColor, sourceHandleFor } from './catalog.js'

export const DEFAULT_RELATION = 'Success'

export const DEFAULT_CHAIN_ID = 'fireAlarm'

export const DEFAULT_CHAIN_NAME = '消防隐患智能处置链'

const LAYOUT = { paddingX: 70, paddingY: 70, columnGap: 300, rowGap: 160 }

/** 生成不与 used 冲突的节点 id，形如 filter_1。 */
export function newNodeId(type, used = new Set()) {
  const prefix = String(type || 'node').replace(/[^\w.-]/g, '_')
  let index = 1
  while (used.has(`${prefix}_${index}`)) index += 1
  return `${prefix}_${index}`
}

/** 由节点库定义创建画布节点。 */
export function createNode(def, position = { x: LAYOUT.paddingX, y: LAYOUT.paddingY }, entry = false, used = new Set()) {
  const nodeType = String(def?.type || 'node')
  const id = newNodeId(nodeType, used)
  used.add(id)
  return {
    id,
    type: 'rule',
    position: { x: Math.round(position.x), y: Math.round(position.y) },
    data: {
      nodeType,
      title: displayTitle({ type: nodeType, title: def?.title }) || nodeType,
      category: def?.category || 'other',
      description: def?.description || '',
      props: {},
      relations: Array.isArray(def?.relations) ? [...def.relations] : [],
      customRelations: Boolean(def?.customRelations),
      extraRelations: [],
      entry
    }
  }
}

/** 创建一条带关系名、颜色与箭头的连线。 */
export function createEdge(sourceId, targetId, relation, sourceNode, seq = 0) {
  const value = String(relation || DEFAULT_RELATION)
  const color = relationColor(value)
  return {
    id: `edge-${sourceId}-${targetId}-${seq}-${Date.now().toString(36)}`,
    source: sourceId,
    target: targetId,
    sourceHandle: sourceHandleFor(sourceNode, value),
    targetHandle: 'in',
    type: 'smoothstep',
    label: value,
    data: { relation: value },
    markerEnd: MarkerType.ArrowClosed,
    style: { stroke: color, strokeWidth: 1.8 },
    labelBgStyle: { fill: '#ffffff', fillOpacity: 0.92 },
    labelBgPadding: [5, 3],
    labelBgBorderRadius: 6,
    labelStyle: { fill: color, fontWeight: '600', fontSize: '11px' }
  }
}

/** 读取连线关系名，兼容导入/手改后的多种存放位置。 */
export function relationOf(edge) {
  return String(edge?.data?.relation ?? edge?.label ?? edge?.sourceHandle ?? '').trim()
}

/** 修改连线关系，同时同步出口、颜色与标签。 */
export function applyEdgeRelation(edge, relation, sourceNode) {
  const value = String(relation || '').trim() || DEFAULT_RELATION
  const color = relationColor(value)
  edge.data = { ...(edge.data || {}), relation: value }
  edge.label = value
  edge.sourceHandle = sourceHandleFor(sourceNode, value)
  edge.style = { ...(edge.style || {}), stroke: color, strokeWidth: 1.8 }
  edge.labelStyle = { ...(edge.labelStyle || {}), fill: color }
}

/**
 * 按入口 BFS 分层布局：同层纵向排列，层间横向展开。
 * 环形链路会被层数上限截断，不可达节点统一放到最右侧。
 */
export function layoutNodes(nodes, edges, options = {}) {
  const { paddingX, paddingY, columnGap, rowGap } = { ...LAYOUT, ...options }
  if (!nodes.length) return nodes

  const ids = new Set(nodes.map((node) => node.id))
  const outgoing = new Map(nodes.map((node) => [node.id, []]))
  edges.forEach((edge) => {
    if (outgoing.has(edge.source) && ids.has(edge.target)) outgoing.get(edge.source).push(edge.target)
  })

  const roots = nodes.filter((node) => node.data?.entry).map((node) => node.id)
  const level = new Map()
  const queue = []
  const startIds = roots.length ? roots : nodes.filter((node) => !edges.some((edge) => edge.target === node.id)).map((node) => node.id)
  ;(startIds.length ? startIds : [nodes[0].id]).forEach((id) => {
    level.set(id, 0)
    queue.push(id)
  })

  const limit = nodes.length
  while (queue.length) {
    const id = queue.shift()
    const current = level.get(id) || 0
    if (current >= limit) continue
    for (const next of outgoing.get(id) || []) {
      const nextLevel = current + 1
      if (!level.has(next) || level.get(next) < nextLevel) {
        level.set(next, nextLevel)
        queue.push(next)
      }
    }
  }

  const maxLevel = level.size ? Math.max(...level.values()) : 0
  const columns = new Map()
  nodes.forEach((node) => {
    const column = level.has(node.id) ? level.get(node.id) : maxLevel + 1
    if (!columns.has(column)) columns.set(column, [])
    columns.get(column).push(node)
  })

  columns.forEach((columnNodes, column) => {
    columnNodes.forEach((node, index) => {
      node.position = {
        x: paddingX + column * columnGap,
        y: paddingY + index * rowGap
      }
    })
  })
  return nodes
}

/**
 * 解析后端规则链 JSON。返回画布可直接使用的 nodes / edges。
 * 解析过程尽量容错：未知节点类型、未声明关系、重复边都降级为警告。
 */
export function parseChainJson(text, catalog = []) {
  const result = {
    chainId: DEFAULT_CHAIN_ID,
    chainName: DEFAULT_CHAIN_NAME,
    nodes: [],
    edges: [],
    errors: [],
    warnings: []
  }

  let root
  try {
    root = JSON.parse(text)
  } catch (error) {
    result.errors.push(`JSON 解析失败：${error.message}`)
    return result
  }
  if (!root || typeof root !== 'object' || Array.isArray(root)) {
    result.errors.push('规则链根节点必须是对象')
    return result
  }

  const definitions = new Map((catalog || []).map((item) => [item.type, item]))
  const rawNodes = root.nodes && typeof root.nodes === 'object' ? root.nodes : {}
  const used = new Set()

  Object.entries(rawNodes).forEach(([rawId, raw]) => {
    const id = String(rawId || '').trim()
    if (!id) {
      result.errors.push('存在空节点 id')
      return
    }
    if (!raw || typeof raw !== 'object') {
      result.errors.push(`节点 ${id} 的定义不是对象`)
      return
    }
    const nodeType = raw.type == null ? '' : String(raw.type)
    if (!nodeType) result.errors.push(`节点 ${id} 缺少 type`)
    const def = definitions.get(nodeType)
    if (nodeType && !def) result.warnings.push(`节点 ${id} 的类型「${nodeType}」不在节点库中，已按未知节点载入`)

    const props = {}
    if (raw.props && typeof raw.props === 'object' && !Array.isArray(raw.props)) {
      Object.entries(raw.props).forEach(([key, value]) => {
        if (value == null) return
        props[key] = typeof value === 'string' ? value : typeof value === 'object' ? JSON.stringify(value) : String(value)
      })
    }

    used.add(id)
    result.nodes.push({
      id,
      type: 'rule',
      position: { x: 0, y: 0 },
      data: {
        nodeType,
        title: displayTitle({ type: nodeType, title: def?.title }) || nodeType,
        category: def?.category || 'other',
        description: def?.description || '',
        props,
        relations: Array.isArray(def?.relations) ? [...def.relations] : [],
        customRelations: Boolean(def?.customRelations),
        extraRelations: [],
        entry: false
      }
    })
  })

  const nodeMap = new Map(result.nodes.map((node) => [node.id, node]))
  const rawEdges = root.edges && typeof root.edges === 'object' ? root.edges : {}
  const seen = new Set()

  Object.entries(rawEdges).forEach(([from, list]) => {
    const source = nodeMap.get(from)
    if (!source) {
      result.warnings.push(`边 ${from} → ... 的源节点不存在，已忽略`)
      return
    }
    const items = Array.isArray(list) ? list : [list]
    items.forEach((item) => {
      if (!item || typeof item !== 'object') {
        result.errors.push(`节点 ${from} 的边定义不是对象`)
        return
      }
      const to = item.to == null ? '' : String(item.to)
      if (!to) {
        result.errors.push(`节点 ${from} 存在缺少 to 的边`)
        return
      }
      const target = nodeMap.get(to)
      if (!target) {
        result.errors.push(`边 ${from} → ${to} 指向不存在的节点`)
        return
      }

      let relation = item.relation == null ? DEFAULT_RELATION : String(item.relation).trim()
      if (!relation) relation = DEFAULT_RELATION
      if (!outputRelations(source.data).includes(relation)) {
        source.data.extraRelations.push(relation)
        if (!source.data.customRelations) {
          result.warnings.push(`节点 ${from} 未声明出口「${relation}」，已补为自定义出口`)
        }
      }

      const key = `${from}\u0000${relation}\u0000${to}`
      if (seen.has(key)) {
        result.warnings.push(`重复连线 ${from} -[${relation}]-> ${to}，已忽略`)
        return
      }
      seen.add(key)
      result.edges.push(createEdge(from, to, relation, source, result.edges.length))
    })
  })

  const declaredEntry = Array.isArray(root.entry) ? root.entry.map(String) : root.entry ? [String(root.entry)] : []
  let entryIds = declaredEntry.filter((id) => nodeMap.has(id))
  if (root.entry != null && declaredEntry.length !== entryIds.length) {
    result.warnings.push('部分 entry 节点不存在，已忽略')
  }
  if (!entryIds.length && result.nodes.length) {
    entryIds = [result.nodes[0].id]
    result.warnings.push(`未声明入口，已使用 ${result.nodes[0].id} 作为入口`)
  }
  result.nodes.forEach((node) => {
    node.data.entry = entryIds.includes(node.id)
  })

  if (typeof root.id === 'string' && root.id.trim()) result.chainId = root.id.trim()
  if (typeof root.name === 'string' && root.name.trim()) result.chainName = root.name.trim()

  layoutNodes(result.nodes, result.edges)
  return result
}

/** 导出为 JsonChainLoader 可直接加载的 JSON 对象。 */
export function buildChainJson({ chainId, chainName, nodes, edges }) {
  const id = String(chainId || '').trim() || DEFAULT_CHAIN_ID
  const ordered = [...nodes].sort((a, b) => a.position.x - b.position.x || a.position.y - b.position.y)
  const ids = new Set(ordered.map((node) => node.id))
  const doc = { id, name: String(chainName || '').trim() || id, nodes: {}, edges: {}, entry: [] }

  ordered.forEach((node) => {
    const def = { type: node.data?.nodeType || '' }
    const props = {}
    Object.entries(node.data?.props || {}).forEach(([key, value]) => {
      const name = String(key).trim()
      if (!name) return
      props[name] = value == null ? '' : String(value)
    })
    if (Object.keys(props).length) def.props = props
    doc.nodes[node.id] = def
  })

  edges.forEach((edge) => {
    if (!ids.has(edge.source) || !ids.has(edge.target)) return
    const relation = relationOf(edge) || DEFAULT_RELATION
    const item = { to: edge.target }
    if (relation !== DEFAULT_RELATION) item.relation = relation
    if (!doc.edges[edge.source]) doc.edges[edge.source] = []
    doc.edges[edge.source].push(item)
  })

  doc.entry = ordered.filter((node) => node.data?.entry).map((node) => node.id)
  if (!doc.entry.length && ordered.length) doc.entry = [ordered[0].id]
  return doc
}

/** 导出文本，便于复制与下载。 */
export function formatChainJson(document) {
  return JSON.stringify(document, null, 2)
}

/** 文档校验：errors 阻断导出加载，warnings 提示风险。 */
export function validateChain({ chainId, nodes, edges, catalog = [] }) {
  const errors = []
  const warnings = []
  const definitions = new Map((catalog || []).map((item) => [item.type, item]))
  const ids = new Set()

  if (!String(chainId || '').trim()) errors.push('规则链 id 不能为空')
  if (!nodes.length) errors.push('画布至少需要一个节点')

  nodes.forEach((node) => {
    const id = String(node.id || '').trim()
    if (!id) {
      errors.push('存在缺少 id 的节点')
      return
    }
    if (ids.has(id)) errors.push(`节点 id 重复：${id}`)
    ids.add(id)
    const type = node.data?.nodeType
    if (!type) errors.push(`节点 ${id} 缺少 type`)
    else if (definitions.size && !definitions.has(type)) warnings.push(`节点 ${id} 的类型「${type}」不在节点库中`)
  })

  if (!nodes.some((node) => node.data?.entry)) errors.push('缺少入口节点，加载后不会触发任何流程')

  const nodeMap = new Map(nodes.map((node) => [node.id, node]))
  const seen = new Set()
  edges.forEach((edge) => {
    const relation = relationOf(edge)
    if (!ids.has(edge.source)) errors.push(`连线指向不存在的源节点：${edge.source}`)
    if (!ids.has(edge.target)) errors.push(`连线指向不存在的目标节点：${edge.target}`)
    if (edge.source === edge.target) errors.push(`节点 ${edge.source} 不能连接自身`)
    if (!relation) {
      errors.push(`连线 ${edge.source} → ${edge.target} 缺少关系名`)
      return
    }
    if (relation === '__custom__') {
      errors.push(`连线 ${edge.source} → ${edge.target} 使用了未命名的自定义出口`)
    }
    const source = nodeMap.get(edge.source)
    if (source && relation !== '__custom__' && !outputRelations(source.data).includes(relation)) {
      errors.push(`节点 ${edge.source} 没有出口「${relation}」`)
    }
    const key = `${edge.source}\u0000${relation}\u0000${edge.target}`
    if (seen.has(key)) warnings.push(`存在重复连线：${edge.source} -[${relation}]-> ${edge.target}`)
    seen.add(key)
  })

  const entries = nodes.filter((node) => node.data?.entry)
  if (entries.length && nodes.length) {
    const outgoing = new Map(nodes.map((node) => [node.id, []]))
    edges.forEach((edge) => {
      if (outgoing.has(edge.source)) outgoing.get(edge.source).push(edge.target)
    })
    const reached = new Set()
    const stack = entries.map((node) => node.id)
    while (stack.length) {
      const id = stack.pop()
      if (reached.has(id)) continue
      reached.add(id)
      ;(outgoing.get(id) || []).forEach((next) => {
        if (!reached.has(next)) stack.push(next)
      })
    }
    nodes.forEach((node) => {
      if (!reached.has(node.id)) warnings.push(`节点 ${node.id} 无法从入口到达`)
    })
  }

  return { errors, warnings }
}
