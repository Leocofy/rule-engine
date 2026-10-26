/**
 * 节点目录：优先使用后端 /designer/nodes 返回的 INodeRegistry 定义，
 * 接口不可用时回退到内置目录，保证设计器离线也能编辑。
 */

export const FALLBACK_CATALOG = [
]

const TYPE_LABELS = Object.fromEntries(FALLBACK_CATALOG.map((item) => [item.type, item.title]))

const CATEGORY_LABELS = {
  debug: '调试',
  transformation: '转换',
  filter: '分流',
  validate: '校验',
  analysis: '分析',
  ai: '智能',
  external: '外部',
  storage: '存储',
  other: '其他'
}

const CATEGORY_ORDER = ['validate', 'filter', 'analysis', 'ai', 'external', 'storage', 'transformation', 'debug', 'other']

const CATEGORY_COLORS = {
  validate: '#2563eb',
  filter: '#d97706',
  analysis: '#0891b2',
  ai: '#7c3aed',
  external: '#db2777',
  storage: '#0d9488',
  transformation: '#4f46e5',
  debug: '#64748b',
  other: '#94a3b8'
}

export function normalizeCatalog(list) {
  return (Array.isArray(list) ? list : [])
    .filter((item) => item && item.type)
    .map((item) => ({
      type: String(item.type),
      title: item.title ? String(item.title) : '',
      category: item.category ? String(item.category) : 'other',
      description: item.description ? String(item.description) : '',
      relations: Array.isArray(item.relations) ? item.relations.map(String).filter(Boolean) : [],
      customRelations: Boolean(item.customRelations),
      version: item.version ? String(item.version) : '1.0'
    }))
}

export function displayTitle(def) {
  if (!def) return ''
  return def.title || TYPE_LABELS[def.type] || def.type
}

export function categoryLabel(category) {
  return CATEGORY_LABELS[category] || category || '其他'
}

export function categoryRank(category) {
  const index = CATEGORY_ORDER.indexOf(category)
  return index === -1 ? CATEGORY_ORDER.length : index
}

export function categoryColor(category) {
  return CATEGORY_COLORS[category] || CATEGORY_COLORS.other
}

/**
 * 画布上实际要渲染的输出关系：
 * - 固定关系节点：声明了什么用什么；没声明默认 Success / Failure
 * - 动态关系节点：声明关系 + 自定义出口
 * - 导入时发现的额外关系追加为独立出口，避免连线悬空
 */
export function outputRelations(data) {
  const declared = Array.isArray(data?.relations) ? data.relations.filter(Boolean) : []
  const extras = Array.isArray(data?.extraRelations) ? data.extraRelations.filter(Boolean) : []
  if (data?.customRelations) {
    return unique([...declared, ...extras, '__custom__'])
  }
  if (declared.length) {
    return unique([...declared, ...extras])
  }
  return unique(['Success', 'Failure', ...extras])
}

export function sourceHandleFor(node, relation) {
  const relations = outputRelations(node?.data)
  if (relations.includes(relation)) return relation
  return node?.data?.customRelations ? '__custom__' : relations[0] || 'Success'
}

export function relationColor(relation) {
  const value = String(relation || '')
  if (/fail|false|invalid|error|reject|失败|无效|拒绝|异常/i.test(value)) return '#dc2626'
  if (/warn|retry|observe|观察|重试|告警/i.test(value)) return '#d97706'
  if (value === 'Success' || /true|valid|通过|成功|处置/i.test(value)) return '#16a34a'
  return '#7c3aed'
}

export function relationClass(relation) {
  if (relation === '__custom__') return 'custom'
  const color = relationColor(relation)
  if (color === '#dc2626') return 'negative'
  if (color === '#d97706') return 'warn'
  return 'positive'
}

function unique(list) {
  return [...new Set(list.filter(Boolean))]
}
