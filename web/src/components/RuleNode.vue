<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { categoryColor, categoryLabel, outputRelations, relationClass } from '@/lib/catalog.js'

const props = defineProps({
  id: { type: String, required: true },
  data: { type: Object, required: true },
  selected: { type: Boolean, default: false }
})

const relations = computed(() => outputRelations(props.data))
const accent = computed(() => categoryColor(props.data.category))
const propCount = computed(() => Object.keys(props.data.props || {}).length)

function handleTop(index) {
  return `${((index + 1) / (relations.value.length + 1)) * 100}%`
}

function relationText(relation) {
  return relation === '__custom__' ? '自定义' : relation
}
</script>

<template>
  <div class="rule-node" :class="{ 'is-selected': selected, 'is-entry': data.entry }" :style="{ '--accent': accent }">
    <Handle id="in" type="target" :position="Position.Left" class="rule-handle rule-handle-in" />

    <div class="rule-node-bar" />

    <header class="rule-node-head">
      <span class="rule-node-cat">{{ categoryLabel(data.category) }}</span>
      <span v-if="data.entry" class="rule-node-entry">入口</span>
    </header>

    <div class="rule-node-title">{{ data.title || data.nodeType }}</div>
    <div class="rule-node-type">{{ data.nodeType || 'unknown' }}</div>
    <p v-if="data.description" class="rule-node-desc">{{ data.description }}</p>

    <footer class="rule-node-foot">
      <span>{{ propCount }} 项配置</span>
      <span>{{ relations.length }} 个出口</span>
    </footer>

    <template v-for="(relation, index) in relations" :key="relation">
      <Handle
        :id="relation"
        type="source"
        :position="Position.Right"
        class="rule-handle rule-handle-out"
        :class="`rel-${relationClass(relation)}`"
        :style="{ top: handleTop(index) }"
      />
      <span class="rule-node-out" :class="`rel-${relationClass(relation)}`" :style="{ top: handleTop(index) }">
        {{ relationText(relation) }}
      </span>
    </template>
  </div>
</template>
