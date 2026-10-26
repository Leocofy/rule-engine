<script setup>
import {computed, markRaw, nextTick, onBeforeUnmount, onMounted, ref, watch} from 'vue'
import {useVueFlow, VueFlow} from '@vue-flow/core'
import {Background} from '@vue-flow/background'
import {Controls} from '@vue-flow/controls'
import {MiniMap} from '@vue-flow/minimap'
import NodePalette from '@/components/NodePalette.vue'
import PropertyPanel from '@/components/PropertyPanel.vue'
import JsonDialog from '@/components/JsonDialog.vue'
import RuleNode from '@/components/RuleNode.vue'
import {categoryColor, displayTitle, FALLBACK_CATALOG, normalizeCatalog, outputRelations} from '@/lib/catalog.js'
import {DEMO_CHAIN_TEXT} from '@/lib/demo.js'
import {
  applyEdgeRelation,
  buildChainJson,
  createEdge,
  createNode,
  DEFAULT_CHAIN_ID,
  DEFAULT_CHAIN_NAME,
  formatChainJson,
  layoutNodes,
  parseChainJson,
  relationOf,
  validateChain
} from '@/lib/chain.js'

const FLOW_ID = 'rule-designer'
const STORAGE_KEY = 'rule-engine:designer:draft'

const {
  nodes,
  edges,
  addNodes,
  addEdges,
  setNodes,
  setEdges,
  removeNodes,
  removeEdges,
  screenToFlowCoordinate,
  fitView,
  onConnect
} =
    useVueFlow(FLOW_ID)

const nodeTypes = {rule: markRaw(RuleNode)}

const chainId = ref(DEFAULT_CHAIN_ID)
const chainName = ref(DEFAULT_CHAIN_NAME)
const catalog = ref(normalizeCatalog(FALLBACK_CATALOG))
const catalogSource = ref('builtin')
const loading = ref(true)
const toastMessage = ref('')

const dialogVisible = ref(false)
const dialogMode = ref('export')
const dialogText = ref('')
const dialogError = ref('')

const fileInput = ref(null)

let ready = false
let toastTimer = null
let saveTimer = null

const documentJson = computed(() =>
    buildChainJson({chainId: chainId.value, chainName: chainName.value, nodes: nodes.value, edges: edges.value})
)

const issues = computed(() =>
    validateChain({chainId: chainId.value, nodes: nodes.value, edges: edges.value, catalog: catalog.value})
)

const selectedNode = computed(() => nodes.value.find((node) => node.selected) || null)
const selectedEdge = computed(() => edges.value.find((edge) => edge.selected) || null)
const selectedSourceNode = computed(() => {
  const edge = selectedEdge.value
  return edge ? nodes.value.find((node) => node.id === edge.source) || null : null
})

const exportFileName = computed(() => String(chainId.value || 'rule-chain').trim() || 'rule-chain')

onConnect((connection) => {
  const source = nodes.value.find((node) => node.id === connection.source)
  const target = nodes.value.find((node) => node.id === connection.target)
  if (!source || !target) return
  if (source.id === target.id) {
    notify('不能把节点连接到自身')
    return
  }
  const relation = connection.sourceHandle || 'Success'
  if (relation === '__custom__') {
    notify('该出口尚未命名，请先在右侧添加自定义出口')
    return
  }
  if (edges.value.some((edge) => edge.source === source.id && edge.target === target.id && relationOf(edge) === relation)) {
    notify('已存在相同关系与目标的连线')
    return
  }
  addEdges([createEdge(source.id, target.id, relation, source, edges.value.length)])
})

watch(
    [nodes, edges, chainId, chainName],
    () => {
      if (!ready) return
      clearTimeout(saveTimer)
      saveTimer = setTimeout(() => {
        try {
          localStorage.setItem(STORAGE_KEY, formatChainJson(documentJson.value))
        } catch (error) {
          // 本地存储不可用时静默降级，不影响编辑
        }
      }, 600)
    },
    {deep: true}
)

onMounted(async () => {
  await loadCatalog()

  let restored = false
  const draft = readDraft()
  if (draft) {
    const result = parseChainJson(draft, catalog.value)
    if (result.nodes.length && !result.errors.length) {
      applyDocument(result)
      restored = true
    }
  }

  if (!restored) {
    const example = await fetchExample()
    const result = parseChainJson(example.text, catalog.value)
    if (result.nodes.length) {
      applyDocument(result)
      if (!example.remote) notify('未连接后端，已载入内置示例链路')
    }
  }

  ready = true
  loading.value = false
  await nextTick()
  setTimeout(() => fitView({padding: 0.22, duration: 260}), 80)
})

onBeforeUnmount(() => {
  clearTimeout(toastTimer)
  clearTimeout(saveTimer)
})

async function save() {
  const response = await withTimeout(fetch(`${import.meta.env.BASE_URL}save`, {
    method: "POST",
    body: formatChainJson(documentJson.value),
    headers: {Accept: 'application/json'}
  }), 4000)
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
}

async function loadCatalog() {
  try {
    const response = await withTimeout(fetch(`${import.meta.env.BASE_URL}nodes`, {headers: {Accept: 'application/json'}}), 4000)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const list = normalizeCatalog(await response.json())
    if (!list.length) throw new Error('节点库为空')
    catalog.value = list
    catalogSource.value = 'remote'
  } catch (error) {
    catalog.value = normalizeCatalog(FALLBACK_CATALOG)
    catalogSource.value = 'builtin'
  }
}

async function fetchExample() {
  try {
    const response = await withTimeout(fetch(`${import.meta.env.BASE_URL}example`, {headers: {Accept: 'application/json'}}), 4000)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    return {text: await response.text(), remote: true}
  } catch (error) {
    return {text: DEMO_CHAIN_TEXT, remote: false}
  }
}

function withTimeout(promise, timeout) {
  return Promise.race([
    promise,
    new Promise((resolve, reject) => setTimeout(() => reject(new Error('请求超时')), timeout))
  ])
}

function readDraft() {
  try {
    return localStorage.getItem(STORAGE_KEY) || ''
  } catch (error) {
    return ''
  }
}

function applyDocument(result) {
  chainId.value = result.chainId
  chainName.value = result.chainName
  setNodes(result.nodes)
  setEdges(result.edges)
  nextTick(() => setTimeout(() => fitView({padding: 0.22, duration: 240}), 60))
}

function notify(message) {
  toastMessage.value = message
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => (toastMessage.value = ''), 2600)
}

function onDragOver(event) {
  event.preventDefault()
  event.dataTransfer.dropEffect = 'move'
}

function onDrop(event) {
  event.preventDefault()
  const type = event.dataTransfer.getData('application/rule-node') || event.dataTransfer.getData('text/plain')
  if (!type) return
  const def = catalog.value.find((item) => item.type === type)
  if (!def) {
    notify(`节点库中没有类型「${type}」`)
    return
  }
  const point = screenToFlowCoordinate({x: event.clientX, y: event.clientY})
  addNodeAt(def, {x: point.x - 105, y: point.y - 50})
}

function addNodeFromPalette(def) {
  const index = nodes.value.length
  addNodeAt(def, {x: 80 + (index % 3) * 300, y: 80 + Math.floor(index / 3) * 170})
}

function addNodeAt(def, position) {
  const used = new Set(nodes.value.map((node) => node.id))
  const node = createNode(def, position, !nodes.value.length, used)
  addNodes([node])
  notify(`已添加「${displayTitle(def)}」，id=${node.id}`)
}

function changeNodeType(type) {
  const node = selectedNode.value
  const def = catalog.value.find((item) => item.type === type)
  if (!node || !def) return
  node.data.nodeType = def.type
  node.data.title = displayTitle(def) || def.type
  node.data.category = def.category
  node.data.description = def.description
  node.data.relations = [...(def.relations || [])]
  node.data.customRelations = Boolean(def.customRelations)
  node.data.extraRelations = []
  notify(`节点类型已切换为「${displayTitle(def)}」，请检查原有连线`)
}

function renameNode(nextId) {
  const node = selectedNode.value
  if (!node) return
  const id = String(nextId || '').trim()
  if (!id || id === node.id) return
  if (nodes.value.some((item) => item.id === id)) {
    notify(`节点 id「${id}」已存在`)
    return
  }
  const previous = node.id
  const nextNodes = nodes.value.map((item) => (item.id === previous ? {...item, id} : item))
  const nextEdges = edges.value.map((edge) => {
    if (edge.source !== previous && edge.target !== previous) return edge
    const source = edge.source === previous ? id : edge.source
    const target = edge.target === previous ? id : edge.target
    return {...edge, id: `edge-${source}-${target}-${Math.random().toString(36).slice(2, 7)}`, source, target}
  })
  setNodes(nextNodes)
  setEdges(nextEdges)
  notify(`节点已重命名为「${id}」`)
}

function deleteSelectedNode() {
  const node = selectedNode.value
  if (node) removeNodes([node.id])
}

function deleteSelectedEdge() {
  const edge = selectedEdge.value
  if (edge) removeEdges([edge.id])
}

function updateEdgeRelation(value) {
  const edge = selectedEdge.value
  const source = selectedSourceNode.value
  if (!edge || !source) return
  const relation = String(value || '').trim()
  if (!relation) return
  if (relation !== '__custom__' && !outputRelations(source.data).includes(relation)) {
    if (!source.data.customRelations) {
      notify(`节点 ${source.id} 未声明出口「${relation}」`)
      return
    }
    source.data.extraRelations = [...new Set([...(source.data.extraRelations || []), relation])]
  }
  applyEdgeRelation(edge, relation, source)
}

function addCustomRelation(value) {
  const node = selectedNode.value
  const relation = String(value || '').trim()
  if (!node || !relation) return
  if (outputRelations(node.data).includes(relation)) {
    notify(`出口「${relation}」已存在`)
    return
  }
  node.data.extraRelations = [...(node.data.extraRelations || []), relation]
  notify(`已添加出口「${relation}」`)
}

function removeCustomRelation(relation) {
  const node = selectedNode.value
  if (!node) return
  const used = edges.value.some((edge) => edge.source === node.id && relationOf(edge) === relation)
  if (used) {
    notify(`出口「${relation}」仍被连线使用，请先删除对应连线`)
    return
  }
  node.data.extraRelations = (node.data.extraRelations || []).filter((item) => item !== relation)
}

function autoLayout() {
  if (!nodes.value.length) return
  layoutNodes(nodes.value, edges.value)
  nextTick(() => fitView({padding: 0.22, duration: 300}))
  notify('已按入口重新分层布局')
}

async function loadExampleIntoCanvas() {
  const example = await fetchExample()
  const result = parseChainJson(example.text, catalog.value)
  if (!result.nodes.length) {
    notify('示例链路解析失败')
    return
  }
  applyDocument(result)
  notify(example.remote ? '已从后端加载示例链路' : '后端不可用，已加载内置示例链路')
}

function openExport() {
  dialogMode.value = 'export'
  dialogError.value = ''
  dialogText.value = formatChainJson(documentJson.value)
  dialogVisible.value = true
}

function openImport() {
  dialogMode.value = 'import'
  dialogError.value = ''
  dialogText.value = formatChainJson(documentJson.value)
  dialogVisible.value = true
}

function closeDialog() {
  dialogVisible.value = false
}

function triggerFileImport() {
  fileInput.value?.click()
}

async function onFileChange(event) {
  const file = event.target.files && event.target.files[0]
  event.target.value = ''
  if (!file) return
  try {
    importChainText(await file.text())
  } catch (error) {
    notify('读取文件失败')
  }
}

function importChainText(text) {
  const result = parseChainJson(text, catalog.value)
  if (result.errors.length || !result.nodes.length) {
    dialogMode.value = 'import'
    dialogText.value = text
    dialogError.value = result.errors[0] || 'JSON 中没有可用节点'
    dialogVisible.value = true
    return
  }
  dialogVisible.value = false
  dialogError.value = ''
  applyDocument(result)
  const suffix = result.warnings.length ? `，${result.warnings.length} 条警告见右侧校验` : ''
  notify(`已导入 ${result.nodes.length} 个节点、${result.edges.length} 条连线${suffix}`)
}

function applyImport(text) {
  importChainText(text)
}

function miniMapColor(node) {
  return categoryColor(node?.data?.category)
}
</script>

<template>
  <div class="app">
    <header class="topbar">
      <div class="brand">
        <span class="brand-logo">RE</span>
        <span class="brand-text">
          <strong>规则链设计器</strong>
          <small>DAG 编排 · Vue Flow</small>
        </span>
      </div>

      <div class="chain-meta">
        <label>
          <span>链 ID</span>
          <input v-model="chainId" class="mono" spellcheck="false"/>
        </label>
        <label>
          <span>名称</span>
          <input v-model="chainName"/>
        </label>
      </div>

      <div class="topbar-actions">
        <span class="source-chip" :class="catalogSource">
          {{ catalogSource === 'remote' ? '节点库：后端' : '节点库：内置' }}
        </span>
        <button class="btn ghost" type="button" @click="loadExampleIntoCanvas">加载示例</button>
        <button class="btn ghost" type="button" @click="triggerFileImport">导入文件</button>
        <button class="btn ghost" type="button" @click="openImport">导入 JSON</button>
        <button class="btn ghost" type="button" @click="autoLayout">自动布局</button>
        <button class="btn primary" type="button" @click="openExport">导出 JSON</button>
        <button class="btn ghost" type="button" @click="save">保存</button>
        <input ref="fileInput" type="file" accept="application/json,.json" class="hidden-input" @change="onFileChange"/>
      </div>
    </header>

    <main class="workspace">
      <NodePalette :catalog="catalog" @add="addNodeFromPalette"/>

      <section class="canvas" @dragover="onDragOver" @drop="onDrop">
        <VueFlow
            :id="FLOW_ID"
            class="flow"
            :node-types="nodeTypes"
            :delete-key-code="['Delete', 'Backspace']"
            :min-zoom="0.2"
            :max-zoom="2.2"
        >
          <Background :gap="20" :size="1.4" pattern-color="#cbd5e1"/>
          <Controls position="bottom-left"/>
          <MiniMap pannable zoomable :node-color="miniMapColor"/>
        </VueFlow>

        <div class="canvas-hints">
          <span>拖拽左侧节点到画布</span>
          <span>从节点右侧出口圆点拖出连线</span>
          <span>Delete 删除选中元素</span>
        </div>

        <div v-if="loading" class="canvas-loading">正在载入节点库与示例链路…</div>
      </section>

      <PropertyPanel
          :node="selectedEdge ? null : selectedNode"
          :edge="selectedEdge"
          :source-node="selectedSourceNode"
          :catalog="catalog"
          :issues="issues"
          @notify="notify"
          @delete-node="deleteSelectedNode"
          @delete-edge="deleteSelectedEdge"
          @rename-node="renameNode"
          @change-type="changeNodeType"
          @update-relation="updateEdgeRelation"
          @add-custom-relation="addCustomRelation"
          @remove-custom-relation="removeCustomRelation"
      />
    </main>

    <footer class="statusbar">
      <span>节点 {{ nodes.length }}</span>
      <span>连线 {{ edges.length }}</span>
      <span :class="{ 'status-error': issues.errors.length }">错误 {{ issues.errors.length }}</span>
      <span :class="{ 'status-warn': issues.warnings.length }">警告 {{ issues.warnings.length }}</span>
      <span class="status-spacer"/>
      <span>导出 JSON 可直接作为 rule.engine.chain-config 加载</span>
    </footer>

    <transition name="toast">
      <div v-if="toastMessage" class="toast">{{ toastMessage }}</div>
    </transition>

    <JsonDialog
        :visible="dialogVisible"
        :mode="dialogMode"
        :text="dialogText"
        :file-name="exportFileName"
        :error="dialogError"
        @close="closeDialog"
        @apply="applyImport"
    />
  </div>
</template>
