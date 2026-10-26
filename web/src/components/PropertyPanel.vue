<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { displayTitle, outputRelations } from '@/lib/catalog.js'
import { DEFAULT_RELATION, relationOf } from '@/lib/chain.js'

const props = defineProps({
  node: { type: Object, default: null },
  edge: { type: Object, default: null },
  sourceNode: { type: Object, default: null },
  catalog: { type: Array, default: () => [] },
  issues: { type: Object, default: () => ({ errors: [], warnings: [] }) }
})

const emit = defineEmits([
  'notify',
  'delete-node',
  'rename-node',
  'change-type',
  'update-relation',
  'delete-edge',
  'add-custom-relation',
  'remove-custom-relation'
])

const idDraft = ref('')
const newRelation = ref('')

watch(
  () => props.node?.id,
  (value) => {
    idDraft.value = value || ''
    newRelation.value = ''
  },
  { immediate: true }
)

const isEdge = computed(() => Boolean(props.edge))
const relation = computed(() => relationOf(props.edge))
const isCustomNode = computed(() => Boolean(props.node?.data?.customRelations))

const typeOptions = computed(() => {
  const list = props.catalog.map((def) => ({ value: def.type, label: displayTitle(def) }))
  const current = props.node?.data?.nodeType
  if (current && !list.some((item) => item.value === current)) {
    list.unshift({ value: current, label: `${current}（未知类型）` })
  }
  return list
})

const relationOptions = computed(() => {
  if (!props.sourceNode) return []
  return outputRelations(props.sourceNode.data).filter((item) => item !== '__custom__')
})

const relationHint = computed(() => {
  if (relation.value === DEFAULT_RELATION) return 'Success 为默认关系，导出时可省略 relation 字段。'
  if (relation.value === '__custom__') return '自定义出口尚未命名，请在上方输入框填写关系名。'
  return '关系名需要与源节点声明的出口一致。'
})

function commitId(event) {
  const next = String(event.target.value || '').trim()
  if (!next || next === props.node?.id) {
    event.target.value = props.node?.id || ''
    idDraft.value = props.node?.id || ''
    return
  }
  emit('rename-node', next)
  nextTick(() => {
    if (props.node && props.node.id !== next) {
      idDraft.value = props.node.id
      event.target.value = props.node.id
    }
  })
}

function renameProp(node, oldKey, event) {
  const next = String(event.target.value || '').trim()
  if (!next || next === oldKey) {
    event.target.value = oldKey
    return
  }
  if (Object.prototype.hasOwnProperty.call(node.data.props, next)) {
    event.target.value = oldKey
    emit('notify', `参数「${next}」已存在`)
    return
  }
  const value = node.data.props[oldKey]
  delete node.data.props[oldKey]
  node.data.props[next] = value
}

function addProp(node) {
  const current = node.data.props
  let index = Object.keys(current).length + 1
  let key = `param${index}`
  while (Object.prototype.hasOwnProperty.call(current, key)) {
    index += 1
    key = `param${index}`
  }
  current[key] = ''
}

function removeProp(node, key) {
  delete node.data.props[key]
}

function submitRelation(event) {
  const value = String(event.target.value || '').trim()
  if (!value) {
    event.target.value = relation.value
    return
  }
  emit('update-relation', value)
}

function addCustomRelation() {
  const value = newRelation.value.trim()
  if (!value) return
  if (outputRelations(props.node?.data).includes(value)) {
    emit('notify', `出口「${value}」已存在`)
    return
  }
  emit('add-custom-relation', value)
  newRelation.value = ''
}
</script>

<template>
  <aside class="inspector">
    <div class="panel-head">
      <h2>{{ isEdge ? '连线属性' : node ? '节点属性' : '属性面板' }}</h2>
      <span v-if="isEdge" class="pill">edge</span>
      <span v-else-if="node" class="pill">{{ node.data.nodeType }}</span>
    </div>

    <div v-if="isEdge" class="inspector-body">
      <section class="field">
        <label>连接</label>
        <div class="edge-path">
          <code>{{ edge.source }}</code>
          <span class="arrow">→</span>
          <code>{{ edge.target }}</code>
        </div>
      </section>

      <section class="field">
        <label>关系（relation）</label>
        <select v-if="!sourceNode?.data?.customRelations" :value="relation" @change="submitRelation">
          <option v-for="item in relationOptions" :key="item" :value="item">{{ item }}</option>
          <option v-if="!relationOptions.includes(relation)" :value="relation">{{ relation }}（未声明）</option>
        </select>
        <input v-else :value="relation" list="edge-relation-options" @change="submitRelation" />
        <datalist id="edge-relation-options">
          <option v-for="item in relationOptions" :key="item" :value="item" />
        </datalist>
        <p class="hint">{{ relationHint }}</p>
      </section>

      <section class="field">
        <label>源节点可用出口</label>
        <div class="tag-list">
          <span v-for="item in relationOptions" :key="item" class="tag" :class="{ active: item === relation }">{{ item }}</span>
          <span v-if="!relationOptions.length" class="muted">无</span>
        </div>
      </section>

      <button class="btn danger block" type="button" @click="emit('delete-edge')">删除连线</button>
    </div>

    <div v-else-if="node" class="inspector-body">
      <section class="field">
        <label>节点 ID（写入 JSON 的 key）</label>
        <input v-model="idDraft" class="mono" spellcheck="false" @change="commitId" />
      </section>

      <section class="field">
        <label>节点类型（type）</label>
        <select :value="node.data.nodeType" @change="emit('change-type', $event.target.value)">
          <option v-for="item in typeOptions" :key="item.value" :value="item.value">{{ item.label }} · {{ item.value }}</option>
        </select>
      </section>

      <section class="field">
        <label>显示名称</label>
        <input v-model="node.data.title" placeholder="画布上展示的名称" />
      </section>

      <section class="field">
        <label>说明</label>
        <textarea v-model="node.data.description" rows="2" placeholder="节点用途说明（不写入 JSON）" />
      </section>

      <section class="field">
        <label class="switch">
          <input v-model="node.data.entry" type="checkbox" />
          <span>作为规则链入口（entry）</span>
        </label>
        <p class="hint">入口可以有多个，引擎会把同一消息投递给全部入口。</p>
      </section>

      <section class="field">
        <label>节点参数（props）</label>
        <div v-for="(value, key) in node.data.props" :key="key" class="prop-row">
          <input class="prop-key mono" :value="key" spellcheck="false" @change="renameProp(node, key, $event)" />
          <input class="prop-value" :value="value" placeholder="值" @input="node.data.props[key] = $event.target.value" />
          <button class="icon" type="button" title="删除参数" @click="removeProp(node, key)">×</button>
        </div>
        <p v-if="!Object.keys(node.data.props || {}).length" class="muted">暂无参数</p>
        <button class="btn ghost small" type="button" @click="addProp(node)">+ 添加参数</button>
        <p class="hint">props 值统一按字符串写入 JSON，节点 init 时再解析。</p>
      </section>

      <section v-if="isCustomNode" class="field">
        <label>自定义出口</label>
        <div class="tag-list">
          <span v-for="item in node.data.extraRelations" :key="item" class="tag removable">
            {{ item }}
            <button class="tag-close" type="button" title="删除出口" @click="emit('remove-custom-relation', item)">×</button>
          </span>
          <span v-if="!node.data.extraRelations?.length" class="muted">尚未添加命名出口</span>
        </div>
        <form class="inline-form" @submit.prevent="addCustomRelation">
          <input v-model="newRelation" placeholder="例如 立即处置" />
          <button class="btn ghost small" type="submit">添加</button>
        </form>
        <p class="hint">该节点按运行时结论动态选择出口，关系名即 tellNext 的取值。</p>
      </section>

      <section class="field">
        <label>可用出口</label>
        <div class="tag-list">
          <span v-for="item in outputRelations(node.data)" :key="item" class="tag">{{ item === '__custom__' ? '自定义（未命名）' : item }}</span>
        </div>
      </section>

      <button class="btn danger block" type="button" @click="emit('delete-node')">删除节点</button>
    </div>

    <div v-else class="inspector-body empty">
      <p>选中画布中的节点或连线，在这里编辑属性。</p>
      <ul class="tips">
        <li>从左侧拖拽节点到画布</li>
        <li>拖动节点右侧圆点连线，圆点即出口关系</li>
        <li>Delete / Backspace 删除选中元素</li>
      </ul>
    </div>

    <section class="issues">
      <div class="issues-head">
        <span>校验结果</span>
        <span class="issues-badges">
          <span class="badge error">{{ issues.errors.length }} 错误</span>
          <span class="badge warn">{{ issues.warnings.length }} 警告</span>
        </span>
      </div>
      <ul v-if="issues.errors.length || issues.warnings.length" class="issues-list">
        <li v-for="(item, index) in issues.errors" :key="`e-${index}`" class="error">{{ item }}</li>
        <li v-for="(item, index) in issues.warnings" :key="`w-${index}`" class="warn">{{ item }}</li>
      </ul>
      <p v-else class="issues-ok">校验通过，可以导出 JSON 交给引擎加载。</p>
    </section>
  </aside>
</template>
