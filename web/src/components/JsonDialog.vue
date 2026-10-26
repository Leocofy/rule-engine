<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  mode: { type: String, default: 'export' },
  text: { type: String, default: '' },
  fileName: { type: String, default: 'rule-chain' },
  error: { type: String, default: '' }
})

const emit = defineEmits(['close', 'apply'])

const draft = ref('')
const copied = ref(false)

const isExport = computed(() => props.mode === 'export')

watch(
  () => [props.visible, props.text],
  () => {
    if (!props.visible) return
    draft.value = props.text
    copied.value = false
  },
  { immediate: true }
)

function close() {
  emit('close')
}

async function copy() {
  const value = draft.value
  let ok = false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
      ok = true
    }
  } catch (error) {
    ok = false
  }
  if (!ok) {
    const area = document.createElement('textarea')
    area.value = value
    area.style.position = 'fixed'
    area.style.opacity = '0'
    document.body.appendChild(area)
    area.select()
    ok = document.execCommand('copy')
    area.remove()
  }
  copied.value = ok
  if (ok) setTimeout(() => (copied.value = false), 2000)
}

function download() {
  const blob = new Blob([draft.value], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `${props.fileName || 'rule-chain'}.json`
  link.click()
  URL.revokeObjectURL(url)
}

function apply() {
  emit('apply', draft.value)
}
</script>

<template>
  <div v-if="visible" class="modal" @click.self="close">
    <div class="modal-card" role="dialog" aria-modal="true">
      <header class="modal-head">
        <h2>{{ isExport ? '导出规则链 JSON' : '导入规则链 JSON' }}</h2>
        <button class="icon" type="button" title="关闭" @click="close">×</button>
      </header>

      <p class="modal-hint">
        {{ isExport
          ? '以下内容可直接保存为 JSON 文件，或复制到配置目录由 JsonChainLoader 加载。'
          : '粘贴规则链 JSON，导入后会覆盖当前画布，画布位置由自动布局重新计算。' }}
      </p>

      <textarea
        v-model="draft"
        class="modal-text mono"
        spellcheck="false"
        :readonly="isExport"
        rows="18"
      />

      <p v-if="error" class="modal-error">{{ error }}</p>

      <footer class="modal-foot">
        <template v-if="isExport">
          <button class="btn ghost" type="button" @click="close">关闭</button>
          <button class="btn ghost" type="button" @click="download">下载文件</button>
          <button class="btn primary" type="button" @click="copy">{{ copied ? '已复制' : '复制 JSON' }}</button>
        </template>
        <template v-else>
          <button class="btn ghost" type="button" @click="close">取消</button>
          <button class="btn primary" type="button" @click="apply">导入并覆盖画布</button>
        </template>
      </footer>
    </div>
  </div>
</template>
