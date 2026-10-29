<script setup>
import { computed } from 'vue'

// ===== 统一分页条（全站列表共用）=====
// 父组件持有 page / pageSize 状态：监听 update:page、update:pageSize 即可
const props = defineProps({
  total: { type: Number, default: 0 },     // 筛选后的总条数
  page: { type: Number, default: 1 },      // 当前页（从 1 开始）
  pageSize: { type: Number, default: 10 }  // 每页条数
})
const emit = defineEmits(['update:page', 'update:pageSize'])

const totalPages = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))
// 页码窗口化：始终保留首尾页与当前页邻域，其余折叠为省略号
const pages = computed(() => {
  const total = totalPages.value
  const cur = props.page
  if (total <= 7) return Array.from({ length: total }, (_, i) => i + 1)
  const out = [1]
  let start = Math.max(2, cur - 1)
  let end = Math.min(total - 1, cur + 1)
  if (cur <= 4) { start = 2; end = 5 }
  if (cur >= total - 3) { start = total - 4; end = total - 1 }
  if (start > 2) out.push('…')
  for (let p = start; p <= end; p++) out.push(p)
  if (end < total - 1) out.push('…')
  out.push(total)
  return out
})
function goto(p) { emit('update:page', Math.min(Math.max(1, p), totalPages.value)) }
function onSize(e) {
  emit('update:pageSize', Number(e.target.value))
  emit('update:page', 1) // 切每页条数时回到第一页
}
</script>

<template>
  <div v-if="total > 0" class="pager">
    <span class="pg-total">共 {{ total }} 条</span>
    <select class="pg-size" :value="pageSize" @change="onSize">
      <option :value="10">10 条/页</option>
      <option :value="20">20 条/页</option>
      <option :value="50">50 条/页</option>
    </select>
    <button class="pg-btn" :disabled="page <= 1" @click="goto(page - 1)">‹</button>
    <template v-for="(p, i) in pages" :key="p === '…' ? 'e' + i : p">
      <span v-if="p === '…'" class="pg-ellipsis">…</span>
      <button v-else class="pg-btn num" :class="{ on: p === page }" @click="goto(p)">{{ p }}</button>
    </template>
    <button class="pg-btn" :disabled="page >= totalPages" @click="goto(page + 1)">›</button>
  </div>
</template>

<style scoped>
.pager{display:flex;align-items:center;gap:8px;justify-content:flex-end;padding:12px 4px 2px;border-top:1px solid var(--border)}
.pg-total{font-size:12px;color:var(--t3);margin-right:auto}
.pg-size{height:28px;padding:0 6px;font-size:12px;border:1px solid var(--border);border-radius:7px;
  background:var(--card);color:var(--t2);outline:none;font-family:inherit;cursor:pointer;
  transition:border-color .15s var(--ease)}
.pg-size:hover{border-color:var(--border-strong)}
.pg-btn{min-width:28px;height:28px;padding:0 6px;border:1px solid var(--border);border-radius:7px;
  background:var(--card);color:var(--t2);font-size:13px;cursor:pointer;font-family:inherit;
  transition:border-color .15s var(--ease),color .15s var(--ease),background .15s var(--ease),box-shadow .2s var(--ease)}
.pg-btn:hover:not(:disabled){border-color:var(--primary);color:var(--primary)}
.pg-btn:disabled{opacity:.45;cursor:default}
.pg-btn.on{background:var(--grad-primary);border-color:transparent;color:#fff;font-weight:700;box-shadow:var(--shadow-primary)}
.pg-ellipsis{min-width:20px;text-align:center;font-size:12px;color:var(--t3);user-select:none}
</style>
