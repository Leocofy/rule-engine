<script setup>
import { computed, ref } from 'vue'
import { categoryColor, categoryLabel, categoryRank, displayTitle } from '@/lib/catalog.js'

const props = defineProps({
  catalog: { type: Array, default: () => [] }
})

const emit = defineEmits(['add'])

const keyword = ref('')

const filtered = computed(() => {
  const value = keyword.value.trim().toLowerCase()
  if (!value) return props.catalog
  return props.catalog.filter((def) => {
    return [def.type, def.title, def.description, categoryLabel(def.category)]
      .filter(Boolean)
      .some((text) => String(text).toLowerCase().includes(value))
  })
})

const groups = computed(() => {
  const map = new Map()
  filtered.value.forEach((def) => {
    if (!map.has(def.category)) map.set(def.category, [])
    map.get(def.category).push(def)
  })
  return [...map.entries()]
    .sort((a, b) => categoryRank(a[0]) - categoryRank(b[0]))
    .map(([category, items]) => ({
      category,
      label: categoryLabel(category),
      items: [...items].sort((a, b) => displayTitle(a).localeCompare(displayTitle(b), 'zh-CN'))
    }))
})

function onDragStart(event, def) {
  event.dataTransfer.setData('application/rule-node', def.type)
  event.dataTransfer.setData('text/plain', def.type)
  event.dataTransfer.effectAllowed = 'move'
}
</script>

<template>
  <aside class="palette">
    <div class="panel-head">
      <h2>节点库</h2>
      <span class="panel-count">{{ filtered.length }}</span>
    </div>

    <div class="palette-search">
      <input v-model="keyword" type="search" placeholder="搜索节点 / 类型" aria-label="搜索节点" />
    </div>

    <div class="palette-body">
      <section v-for="group in groups" :key="group.category" class="palette-group">
        <h3>
          <span class="dot" :style="{ background: categoryColor(group.category) }" />
          {{ group.label }}
        </h3>
        <button
          v-for="def in group.items"
          :key="def.type"
          class="palette-item"
          type="button"
          draggable="true"
          :title="def.description || def.type"
          @dragstart="onDragStart($event, def)"
          @click="emit('add', def)"
        >
          <span class="palette-accent" :style="{ background: categoryColor(def.category) }" />
          <span class="palette-meta">
            <span class="palette-title">{{ displayTitle(def) }}</span>
            <span class="palette-type">{{ def.type }}</span>
          </span>
          <span class="palette-add">+</span>
        </button>
      </section>

      <p v-if="!filtered.length" class="palette-empty">没有匹配的节点</p>
    </div>

    <p class="palette-tip">拖拽节点到画布，或点击直接添加</p>
  </aside>
</template>
