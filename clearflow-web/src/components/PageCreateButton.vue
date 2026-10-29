<script setup>
import { computed } from 'vue'
import { useUiStore } from '../stores/ui'
import { usePermStore } from '../stores/perm'

const props = defineProps({
  type: { type: String, required: true }
})
const ui = useUiStore()
const perm = usePermStore()

// 与后端 @RequireProject / @RequireAdmin 对齐：模块写权限；邀请成员仅系统管理员
const TYPES = {
  task: { label: '新建任务', module: '任务看板' },
  requirement: { label: '新建需求', module: '需求池' },
  iteration: { label: '新建迭代', module: '迭代规划' },
  defect: { label: '提缺陷', module: '缺陷追踪' },
  release: { label: '新建发版计划', module: '发版计划' },
  document: { label: '新建文档', module: '文档管理' },
  member: { label: '邀请成员' }
}
const meta = computed(() => TYPES[props.type])
const canCreate = computed(() => (props.type === 'member' ? perm.isAdmin : perm.canWrite(meta.value.module)))
</script>

<template>
  <button v-if="canCreate" class="btn" @click="ui.openCreate(type)">
    <svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14" /></svg>{{ meta.label }}
  </button>
</template>
